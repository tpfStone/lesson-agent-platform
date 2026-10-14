from __future__ import annotations

import json
import hashlib
import os
import queue
import threading
import time
import uuid
from dataclasses import dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable

from app.analysis.issue_agent import IssueAnalysisAgent
from app.analysis.report_builder import (
    ROLE_LABELS,
    build_statistics,
    build_summary,
    render_action_items_markdown,
    render_summary_markdown,
)
from app.classroom.classroom import Classroom
from app.classroom.events import (
    ClassroomEvent,
    TRIGGER_EMPTY_ENTER,
    TRIGGER_INITIALIZATION,
    TRIGGER_TIMEOUT,
    TRIGGER_USER_INPUT,
)
from app.contracts.analysis import AnalysisResult
from app.contracts.event import (
    ClassroomEventRecord,
    ManagerDecisionRecord,
    MaterialRef,
    TriggerRecord,
)
from app.contracts.session import (
    CreateSessionRequest,
    ModelMode,
    SessionLimits,
    SessionStatus,
    SettingsPatch,
)
from app.llm.mock import MockProvider
from app.llm.provider import create_llm
from app.storage.event_store import EventStore
from app.teaching_plan import ParsedTeachingSection, parse_teaching_script


_TERMINAL = {
    SessionStatus.COMPLETED,
    SessionStatus.INTERRUPTED,
    SessionStatus.FAILED,
}
_STOP = object()


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def atomic_text(path: Path, content: str) -> None:
    temporary = path.with_name(path.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temporary.write_text(content, encoding='utf-8')
        temporary.replace(path)
    finally:
        temporary.unlink(missing_ok=True)


@dataclass
class SessionRuntime:
    session_id: str
    lesson_id: str
    version_id: str
    request_key: str
    model_mode: ModelMode
    model_provider: str
    model_name: str
    input_materials: list[dict[str, Any]]
    materials: list[dict[str, Any]]
    input_mode: str
    limits: SessionLimits
    source: dict[str, Any] | None
    classroom: Classroom
    event_store: EventStore
    status: SessionStatus = SessionStatus.READY
    started_at: str | None = None
    finished_at: str | None = None
    stop_reason: str | None = None
    pause_reason: str | None = None
    created_at: str = field(default_factory=utc_now)
    message_queue: queue.Queue = field(default_factory=lambda: queue.Queue(maxsize=int(os.getenv('F3_MAX_PENDING_MESSAGES', '100'))))
    lock: threading.RLock = field(default_factory=threading.RLock)
    analysis_lock: threading.RLock = field(default_factory=threading.RLock)
    condition: threading.Condition | None = None
    thread: threading.Thread | None = None
    stop_requested: bool = False
    manual_paused: bool = False
    last_error: str | None = None
    analysis: AnalysisResult | None = None
    materials_complete_sequence: int | None = None
    request_fingerprint: str | None = None

    def __post_init__(self):
        self.condition = threading.Condition(self.lock)


class ClassroomService:
    """Formal multi-session F3 service.

    Each session owns an independent Classroom/ClassState/EventStore and a single
    worker thread. Manager/Agent execution is serialized by that worker, while API
    calls only enqueue events or mutate control state under a lock.
    """

    def __init__(
        self,
        *,
        storage_root: str | Path = "runtime/f3_sessions",
        llm_factory: Callable[[ModelMode], Any] | None = None,
        auto_start: bool = True,
    ):
        self.storage_root = Path(storage_root)
        self.storage_root.mkdir(parents=True, exist_ok=True)
        self.llm_factory = llm_factory or self._default_llm_factory
        self.auto_start = auto_start
        self.max_active_sessions = int(os.getenv('F3_MAX_ACTIVE_SESSIONS', '4'))
        self.max_retained_terminal = max(0, int(os.getenv('F3_MAX_RETAINED_TERMINAL_SESSIONS', '16')))
        self._sessions: dict[str, SessionRuntime] = {}
        self._request_keys: dict[str, str] = {}
        self._archived: dict[str, dict[str, Any]] = {}
        self._lock = threading.RLock()
        self._archive_analysis_locks: dict[str, threading.Lock] = {}
        self._recover_interrupted_sessions()

    # ---------- lifecycle / creation ----------

    def create_session(self, request: CreateSessionRequest) -> tuple[dict[str, Any], bool]:
        with self._lock:
            self._prune_terminal_runtimes()
            if self._cancellation_path(request.request_key).exists():
                raise ValueError('REQUEST_CANCELLED_BEFORE_CREATION')
            fingerprint = hashlib.sha256(json.dumps(request.model_dump(mode='json', by_alias=True),
                sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
            existing_id = self._request_keys.get(request.request_key)
            if existing_id:
                existing = self.get_session(existing_id)
                if existing.get('requestFingerprint') != fingerprint:
                    raise ValueError('REQUEST_KEY_CONFLICT: request key belongs to a different or unverifiable payload')
                return existing, True
            if sum(r.status not in _TERMINAL for r in self._sessions.values()) >= self.max_active_sessions:
                raise RuntimeError('SESSION_CAPACITY_EXCEEDED')

            mode = self._resolve_model_mode(request.model_mode)
            llm = self.llm_factory(mode)
            actual_mode = self._actual_mode(mode, llm)
            if mode == ModelMode.REAL and actual_mode != ModelMode.REAL:
                raise RuntimeError(
                    "modelMode=REAL requested, but the configured provider resolved to MOCK"
                )

            session_id = str(uuid.uuid4())
            input_materials = [material.to_classroom_material() for material in request.materials]
            materials, teaching_plan, input_mode = self._prepare_materials(input_materials)
            limits = request.limits()
            session_dir = self.storage_root / session_id
            classroom = Classroom(
                llm=llm,
                materials=materials,
                teaching_plan=teaching_plan,
                active_roles=request.active_roles,
                log_dir=str(session_dir / "legacy_logs"),
                timeout_seconds=limits.timeout_seconds,
                max_consecutive_timeouts=limits.max_consecutive_timeouts,
            )
            if input_mode == "TEACHING_PROCESS_SPLIT" and input_materials:
                source = input_materials[0]
                classroom.plan_meta = {
                    "materialId": source.get("materialId", source.get("id")),
                    "title": source.get("title"),
                    "sourceSectionId": source.get("sourceSectionId"),
                    "keyPoints": list(source.get("keyPoints", source.get("key_points", []))),
                    "metadata": dict(source.get("metadata", {})),
                }
            runtime = SessionRuntime(
                session_id=session_id,
                lesson_id=request.lesson_id,
                version_id=request.version_id,
                request_key=request.request_key,
                model_mode=actual_mode,
                model_provider=getattr(llm, "name", "unknown"),
                model_name=getattr(llm, "model", getattr(llm, "name", "unknown")),
                input_materials=input_materials,
                materials=materials,
                input_mode=input_mode,
                limits=limits,
                source=request.source,
                classroom=classroom,
                event_store=EventStore(self.storage_root, session_id),
                request_fingerprint=fingerprint,
            )
            self._sessions[session_id] = runtime
            self._archive_analysis_locks[session_id] = runtime.analysis_lock
            self._request_keys[request.request_key] = session_id
            self._persist_session(runtime)

        if self.auto_start:
            self.start_session(session_id)
        return self.get_session(session_id), False

    def start_session(self, session_id: str) -> dict[str, Any]:
        runtime = self._require_runtime(session_id)
        with runtime.lock:
            if runtime.status in _TERMINAL:
                return self._snapshot(runtime)
            if runtime.thread and runtime.thread.is_alive():
                return self._snapshot(runtime)
            runtime.status = SessionStatus.RUNNING
            runtime.started_at = runtime.started_at or utc_now()
            runtime.thread = threading.Thread(
                target=self._run_session,
                args=(runtime,),
                daemon=True,
                name=f"f3-session-{session_id[:8]}",
            )
            self._persist_session(runtime)
            runtime.thread.start()
            return self._snapshot(runtime)

    # ---------- controls ----------

    def _prune_terminal_runtimes(self) -> None:
        # Called with the service lock. Never retire a running worker or an active analysis.
        candidates = [r for r in self._sessions.values() if r.status in _TERMINAL
                      and not (r.thread and r.thread.is_alive())]
        for runtime in candidates[:max(0, len(candidates) - self.max_retained_terminal)]:
            if not runtime.analysis_lock.acquire(blocking=False): continue
            try:
                with runtime.lock:
                    # A model result may exist only in memory after an artifact
                    # write failed. Publish it before retiring that memory.
                    if runtime.analysis is not None:
                        self._write_analysis_artifacts(runtime)
                    self._write_result_artifact(runtime)
                    self._persist_session(runtime)
                    self._archived[runtime.session_id] = self._snapshot(runtime)
                    del self._sessions[runtime.session_id]
            except OSError:
                # Keep the completed result available for a later publish retry.
                continue
            finally:
                runtime.analysis_lock.release()

    def send_message(self, session_id: str, message: str) -> dict[str, Any]:
        runtime = self._require_runtime(session_id)
        text = (message or "").strip()
        if not text:
            raise ValueError("message must not be empty")
        with runtime.lock:
            self._ensure_accepts_input(runtime)
            try:
                runtime.message_queue.put_nowait(ClassroomEvent(TRIGGER_USER_INPUT, user_message=text))
            except queue.Full as error:
                raise RuntimeError('MESSAGE_CAPACITY_EXCEEDED') from error
            # Timeout safety pause is cleared by _process_event(user_input). Manual
            # pause intentionally stays paused until /resume.
            if runtime.pause_reason == "MAX_CONSECUTIVE_TIMEOUTS" and not runtime.manual_paused:
                runtime.status = SessionStatus.RUNNING
                runtime.pause_reason = None
                runtime.condition.notify_all()
            self._persist_session(runtime)
        return self._snapshot(runtime)

    def pause(self, session_id: str) -> dict[str, Any]:
        runtime = self._require_runtime(session_id)
        with runtime.lock:
            self._ensure_not_terminal(runtime)
            runtime.manual_paused = True
            runtime.pause_reason = "MANUAL"
            runtime.status = SessionStatus.PAUSED
            self._persist_session(runtime)
            return self._snapshot(runtime)

    def resume(self, session_id: str) -> dict[str, Any]:
        runtime = self._require_runtime(session_id)
        with runtime.lock:
            self._ensure_not_terminal(runtime)
            previous_reason = runtime.pause_reason
            runtime.manual_paused = False
            runtime.status = SessionStatus.RUNNING
            runtime.pause_reason = None
            # Internal timeout pause requires an empty-enter semantic event to reset
            # the consecutive timeout counter and restore autonomous_mode correctly.
            if previous_reason == "MAX_CONSECUTIVE_TIMEOUTS":
                try:
                    runtime.message_queue.put_nowait(ClassroomEvent(TRIGGER_EMPTY_ENTER))
                except queue.Full:
                    runtime.manual_paused = True
                    runtime.status = SessionStatus.PAUSED
                    runtime.pause_reason = previous_reason
                    raise RuntimeError('SESSION_MESSAGE_CAPACITY')
            runtime.condition.notify_all()
            self._persist_session(runtime)
            return self._snapshot(runtime)

    def end(self, session_id: str) -> dict[str, Any]:
        runtime = self._require_runtime(session_id)
        with runtime.lock:
            if runtime.status in _TERMINAL:
                return self._snapshot(runtime)
            runtime.status = SessionStatus.STOPPING
            runtime.stop_reason = "MANUAL_END"
            runtime.stop_requested = True
            try: runtime.message_queue.put_nowait(_STOP)
            except queue.Full: pass  # stop_requested is authoritative even when the input queue is full.
            runtime.condition.notify_all()
            self._persist_session(runtime)
            return self._snapshot(runtime)

    def patch_settings(self, session_id: str, patch: SettingsPatch) -> dict[str, Any]:
        runtime = self._require_runtime(session_id)
        with runtime.lock:
            self._ensure_not_terminal(runtime)
            data = patch.model_dump(exclude_none=True)
            if "timeout_seconds" in data:
                runtime.limits.timeout_seconds = data["timeout_seconds"]
                runtime.classroom.timeout_seconds = data["timeout_seconds"]
            if "max_consecutive_timeouts" in data:
                runtime.limits.max_consecutive_timeouts = data["max_consecutive_timeouts"]
                runtime.classroom.max_consecutive_timeouts = data["max_consecutive_timeouts"]
            if "max_events" in data:
                runtime.limits.max_events = data["max_events"]
            if "max_wall_clock_seconds" in data:
                runtime.limits.max_wall_clock_seconds = data["max_wall_clock_seconds"]
            self._persist_session(runtime)
            return self._snapshot(runtime)

    # ---------- reads ----------

    def _cancellation_path(self, request_key: str) -> Path:
        return self.storage_root / '_request_cancellations' / (hashlib.sha256(request_key.encode()).hexdigest() + '.json')

    def cancel_request_if_absent(self, request_key: str) -> dict[str, Any]:
        with self._lock:
            session_id = self._request_keys.get(request_key)
            if session_id is None:
                path = self._cancellation_path(request_key)
                path.parent.mkdir(parents=True, exist_ok=True)
                temporary = path.with_suffix('.tmp')
                temporary.write_text(json.dumps({'requestKey': request_key, 'status': 'NOT_CREATED'}), encoding='utf-8')
                temporary.replace(path)
                return {'requestKey': request_key, 'status': 'NOT_CREATED'}
        return self.get_session(session_id)

    def session_for_request(self, request_key: str) -> dict[str, Any]:
        with self._lock:
            session_id = self._request_keys.get(request_key)
        if session_id is None: raise KeyError(request_key)
        return self.get_session(session_id)

    def get_session(self, session_id: str) -> dict[str, Any]:
        runtime = self._sessions.get(session_id)
        if runtime is not None:
            with runtime.lock:
                return self._snapshot(runtime)
        archived = self._archived.get(session_id)
        if archived is not None:
            return json.loads(json.dumps(archived, ensure_ascii=False))
        raise KeyError(session_id)

    def get_events(self, session_id: str) -> list[dict[str, Any]]:
        runtime = self._sessions.get(session_id)
        if runtime is not None:
            return [e.model_dump(by_alias=True) for e in runtime.event_store.all()]
        path = self.storage_root / session_id / "events.json"
        if path.exists():
            return json.loads(path.read_text(encoding="utf-8"))
        if session_id in self._archived:
            return []
        raise KeyError(session_id)

    def analyze(self, session_id: str) -> dict[str, Any]:
        # The same lock survives runtime eviction so an in-flight request cannot
        # race a disk-backed retry and repeat model analysis.
        with self._lock:
            if session_id not in self._sessions and session_id not in self._archived:
                raise KeyError(session_id)
            lock = self._archive_analysis_locks.setdefault(session_id, threading.RLock())
        with lock:
            return self._analyze_current(session_id)

    def _analyze_current(self, session_id: str) -> dict[str, Any]:
        if session_id in self._archived:
            return self._analyze_archived(session_id)
        runtime = self._require_runtime(session_id)
        with runtime.lock:
            if runtime.status not in _TERMINAL:
                raise RuntimeError(
                    "IssueAnalysisAgent is only available after the classroom reaches a terminal status"
                )
        # Issue analysis can make several real-model calls.  Do not keep the
        # session lock while waiting for them: the Web UI and F2 runner must
        # remain able to read the terminal session and its events.
        with runtime.analysis_lock:
            with runtime.lock:
                if runtime.analysis is not None and runtime.analysis.analysis_status == 'COMPLETED':
                    self._write_analysis_artifacts(runtime)
                    self._write_result_artifact(runtime)
                    return runtime.analysis.model_dump(by_alias=True)
                events = runtime.event_store.all()
                materials = list(runtime.materials)
                session_status = runtime.status.value
                stop_reason = runtime.stop_reason
                model_mode = runtime.model_mode.value
            analysis = IssueAnalysisAgent(runtime.classroom.llm).analyze(
                session_id=runtime.session_id,
                version_id=runtime.version_id,
                events=events,
                materials=materials,
                session_status=session_status,
                stop_reason=stop_reason,
                model_mode=model_mode,
            )
            with runtime.lock:
                runtime.analysis = analysis
                self._write_analysis_artifacts(runtime)
                self._write_result_artifact(runtime)
                return runtime.analysis.model_dump(by_alias=True)

    def _analyze_archived(self, session_id: str) -> dict[str, Any]:
        with self._lock:
            lock = self._archive_analysis_locks.setdefault(session_id, threading.Lock())
        with lock:
            directory = self.storage_root / session_id
            analysis_path = directory / 'issue_analysis.json'
            cached = AnalysisResult.model_validate_json(analysis_path.read_text(encoding='utf-8')) if analysis_path.exists() else None
            session = self.get_session(session_id)
            if session['status'] not in {x.value for x in _TERMINAL}:
                raise RuntimeError('Archived execution is not terminal')
            events = [ClassroomEventRecord.model_validate(e) for e in self.get_events(session_id)]
            from app.contracts.session import MaterialInput
            materials = [MaterialInput.model_validate(m).to_classroom_material() for m in session.get('materials', [])]
            analysis = cached if cached and cached.analysis_status == 'COMPLETED' else IssueAnalysisAgent(self.llm_factory(ModelMode(session['modelMode']))).analyze(
                session_id=session_id, version_id=session['versionId'], events=events, materials=materials,
                session_status=session['status'], stop_reason=session.get('stopReason'), model_mode=session['modelMode'])
            statistics = build_statistics(events, materials)
            result = {'schemaVersion': '2.1', 'session': session, 'events': [e.model_dump(by_alias=True) for e in events],
                'statistics': statistics, 'summary': build_summary(session=session, events=events, statistics=statistics),
                'issues': [i.model_dump(by_alias=True) for i in analysis.issues], 'actionItems': analysis.action_items,
                'analysisWarnings': analysis.warnings, 'executionStatus': session['status'], 'analysisStatus': analysis.analysis_status,
                'archiveStatus': 'LOCAL', 'analysisRevision': 1 if analysis.analysis_status == 'COMPLETED' else 0,
                'artifacts': ['session.json', 'events.json', 'issue_analysis.json', 'session_record.json', 'action_items.md', 'classroom_summary.md']}
            for name, payload in [('issue_analysis.json', analysis.model_dump(by_alias=True)), ('session_record.json', result)]:
                temporary = directory / (name + '.tmp')
                temporary.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding='utf-8')
                temporary.replace(directory / name)
            (directory / 'action_items.md').write_text(render_action_items_markdown(analysis), encoding='utf-8')
            (directory / 'classroom_summary.md').write_text(render_summary_markdown(result), encoding='utf-8')
            return analysis.model_dump(by_alias=True)

    def get_result(self, session_id: str) -> dict[str, Any]:
        runtime = self._sessions.get(session_id)
        if runtime is not None:
            with runtime.lock:
                return self._build_result(runtime)
        path = self.storage_root / session_id / "session_record.json"
        if path.exists():
            result = json.loads(path.read_text(encoding="utf-8"))
            if session_id in self._archived: result['session'] = self.get_session(session_id)
            return result
        if session_id in self._archived:
            session = self._archived[session_id]
            return {
                "schemaVersion": "2.0",
                "executionStatus": session['status'],
                "analysisStatus": "PENDING",
                "archiveStatus": "LOCAL",
                "session": session,
                "events": self.get_events(session_id),
                "statistics": {},
                "summary": {},
                "issues": [],
                "actionItems": [],
                "artifacts": [],
            }
        raise KeyError(session_id)

    def artifact_path(self, session_id: str, kind: str) -> Path:
        mapping = {
            "session_record": "session_record.json",
            "classroom_summary": "classroom_summary.md",
            "issue_analysis": "issue_analysis.json",
            "action_items": "action_items.md",
            "events": "events.json",
            "session": "session.json",
        }
        filename = mapping.get(kind)
        if filename is None:
            raise ValueError(f"unknown artifact kind: {kind}")
        path = self.storage_root / session_id / filename
        if not path.exists():
            raise FileNotFoundError(path)
        return path

    def wait(self, session_id: str, timeout: float = 5.0) -> dict[str, Any]:
        """Testing/CLI helper; the HTTP API itself never blocks for completion."""
        runtime = self._require_runtime(session_id)
        thread = runtime.thread
        if thread:
            thread.join(timeout=timeout)
        return self.get_session(session_id)

    # ---------- worker ----------

    def _run_session(self, runtime: SessionRuntime) -> None:
        try:
            self._execute_event(runtime, ClassroomEvent(TRIGGER_INITIALIZATION))
            while True:
                with runtime.lock:
                    if runtime.stop_requested:
                        self._finish(
                            runtime,
                            SessionStatus.INTERRUPTED,
                            runtime.stop_reason or "MANUAL_END",
                        )
                        break
                    limit_reason = self._limit_reason(runtime)
                    if limit_reason:
                        self._finish(runtime, SessionStatus.INTERRUPTED, limit_reason)
                        break
                    if runtime.classroom.state.session_status != "active":
                        self._finish(runtime, SessionStatus.COMPLETED, "MANAGER_END_CLASS")
                        break
                    # Materials completion is an independent stop condition. We allow
                    # exactly one post-completion turn so the original Manager can emit
                    # its final summary/end_class decision; if it does not, the service
                    # still terminates deterministically instead of looping forever.
                    if (
                        runtime.materials_complete_sequence is not None
                        and len(runtime.event_store) > runtime.materials_complete_sequence
                    ):
                        self._finish(runtime, SessionStatus.COMPLETED, "MATERIALS_COMPLETED")
                        break

                    while (
                        runtime.status == SessionStatus.PAUSED
                        and not runtime.stop_requested
                        and self._remaining_wall_clock(runtime) > 0
                    ):
                        runtime.condition.wait(timeout=0.5)
                    if runtime.stop_requested:
                        continue
                    if self._remaining_wall_clock(runtime) <= 0:
                        continue

                    remaining = self._remaining_wall_clock(runtime)
                    if remaining <= 0:
                        self._finish(
                            runtime,
                            SessionStatus.INTERRUPTED,
                            "MAX_WALL_CLOCK_SECONDS",
                        )
                        break
                    timeout_seconds = min(runtime.limits.timeout_seconds, remaining)

                try:
                    queued = runtime.message_queue.get(timeout=timeout_seconds)
                except queue.Empty:
                    event = ClassroomEvent(TRIGGER_TIMEOUT)
                else:
                    if queued is _STOP:
                        continue
                    event = queued

                self._execute_event(runtime, event)

                with runtime.lock:
                    if (
                        not runtime.classroom.state.autonomous_mode
                        and runtime.status not in _TERMINAL
                        and not runtime.manual_paused
                    ):
                        runtime.status = SessionStatus.PAUSED
                        runtime.pause_reason = "MAX_CONSECUTIVE_TIMEOUTS"
                        self._persist_session(runtime)
        except Exception as exc:
            with runtime.lock:
                runtime.last_error = f"{type(exc).__name__}: {exc}"
                self._finish(runtime, SessionStatus.FAILED, "WORKER_EXCEPTION")
        finally:
            # Final artifacts are best-effort, but artifact failure must be visible.
            try:
                if runtime.status in _TERMINAL:
                    self._finalize_artifacts(runtime)
            except Exception as exc:
                with runtime.lock:
                    runtime.last_error = (
                        (runtime.last_error + " | ") if runtime.last_error else ""
                    ) + f"artifact_error: {type(exc).__name__}: {exc}"
                    self._persist_session(runtime)

    def _execute_event(self, runtime: SessionRuntime, event: ClassroomEvent) -> None:
        with runtime.lock:
            if runtime.status in _TERMINAL:
                return
            if runtime.stop_requested or self._remaining_wall_clock(runtime) <= 0:
                self._finish(runtime, SessionStatus.INTERRUPTED, runtime.stop_reason or 'MAX_WALL_CLOCK_SECONDS')
                return
            if len(runtime.event_store) >= runtime.limits.max_events:
                return
            occurred_at = utc_now()
            material_before = runtime.classroom.state.current_material

        started = time.perf_counter()
        runtime.classroom._process_event(event)
        entry = runtime.classroom._run_one_turn()
        with runtime.lock:
            if runtime.stop_requested or runtime.status in _TERMINAL or self._remaining_wall_clock(runtime) <= 0:
                self._finish(runtime, SessionStatus.INTERRUPTED, runtime.stop_reason or 'MAX_WALL_CLOCK_SECONDS')
                return
        duration_ms = max(0, round((time.perf_counter() - started) * 1000))
        completed_at = utc_now()

        decision = runtime.classroom.last_decision
        material_after = runtime.classroom.state.current_material
        material = material_after if (decision and decision.function == "next_material") else material_before
        material_ref = self._material_ref(material)

        if entry is not None:
            event_status = "COMPLETED"
            error = None
            speaker = entry.speaker
            function = entry.function
            content = entry.content
        else:
            event_status = "FAILED" if runtime.classroom.last_turn_error else "SKIPPED"
            error = runtime.classroom.last_turn_error
            speaker = decision.speaker if decision else None
            function = decision.function if decision else None
            content = ""

        record = ClassroomEventRecord(
            eventId=str(uuid.uuid4()),
            sequence=len(runtime.event_store) + 1,
            sessionId=runtime.session_id,
            occurredAt=occurred_at,
            completedAt=completed_at,
            wallClockDurationMs=duration_ms,
            estimatedTeachingSeconds=None,
            estimated=False,
            trigger=TriggerRecord(type=event.trigger_type.upper(), content=event.user_message),
            managerDecision=(
                ManagerDecisionRecord(
                    speaker=decision.speaker,
                    function=decision.function,
                    reason=decision.reason,
                    endClass=decision.end_class,
                )
                if decision
                else None
            ),
            speaker=speaker,
            roleLabel=ROLE_LABELS.get(speaker, speaker) if speaker else None,
            function=function,
            content=content,
            material=material_ref,
            status=event_status,
            error=error,
        )
        runtime.event_store.append(record)
        with runtime.lock:
            if (
                runtime.materials_complete_sequence is None
                and runtime.classroom.state.all_materials_finished
            ):
                runtime.materials_complete_sequence = record.sequence
            self._persist_session(runtime)

    # ---------- result/artifacts ----------

    def _build_result(self, runtime: SessionRuntime) -> dict[str, Any]:
        events = runtime.event_store.all()
        session = self._snapshot(runtime)
        statistics = build_statistics(events, runtime.materials)
        summary = build_summary(session=session, events=events, statistics=statistics)
        analysis = runtime.analysis
        artifact_names = [
            name
            for name in (
                "events.json",
                "session.json",
                "session_record.json",
                "classroom_summary.md",
                "issue_analysis.json",
                "action_items.md",
            )
            if (self.storage_root / runtime.session_id / name).exists()
        ]
        return {
            "schemaVersion": "2.1",
            "executionStatus": runtime.status.value,
            "analysisStatus": analysis.analysis_status if analysis is not None else "PENDING",
            "archiveStatus": "LOCAL",
            "analysisRevision": 1 if analysis is not None and analysis.analysis_status == 'COMPLETED' else 0,
            "session": session,
            "events": [e.model_dump(by_alias=True) for e in events],
            "statistics": statistics,
            "summary": summary,
            "issues": (
                [i.model_dump(by_alias=True) for i in analysis.issues] if analysis else []
            ),
            "actionItems": analysis.action_items if analysis else [],
            "analysisWarnings": analysis.warnings if analysis else [],
            "artifacts": artifact_names,
        }

    def _finalize_artifacts(self, runtime: SessionRuntime) -> None:
        # Serialize analysis itself, but release runtime.lock during the model
        # call so GET /sessions and GET /events never stall behind it.
        with runtime.analysis_lock:
            with runtime.lock:
                if runtime.analysis is not None and runtime.analysis.analysis_status == 'COMPLETED':
                    self._write_analysis_artifacts(runtime)
                    self._write_result_artifact(runtime)
                    return
                events = runtime.event_store.all()
                materials = list(runtime.materials)
                session_status = runtime.status.value
                stop_reason = runtime.stop_reason
                model_mode = runtime.model_mode.value
            analysis = IssueAnalysisAgent(runtime.classroom.llm).analyze(
                    session_id=runtime.session_id,
                    version_id=runtime.version_id,
                    events=events,
                    materials=materials,
                    session_status=session_status,
                    stop_reason=stop_reason,
                    model_mode=model_mode,
            )
            with runtime.lock:
                runtime.analysis = analysis
                self._write_analysis_artifacts(runtime)
                self._write_result_artifact(runtime)

    def _write_analysis_artifacts(self, runtime: SessionRuntime) -> None:
        session_dir = self.storage_root / runtime.session_id
        analysis = runtime.analysis or AnalysisResult(
            sessionId=runtime.session_id,
            sourceVersionId=runtime.version_id,
        )
        atomic_text(session_dir / "issue_analysis.json",
            json.dumps(analysis.model_dump(by_alias=True), ensure_ascii=False, indent=2),
        )
        atomic_text(session_dir / "action_items.md",
            render_action_items_markdown(analysis)
        )

    def _write_result_artifact(self, runtime: SessionRuntime) -> None:
        session_dir = self.storage_root / runtime.session_id
        result = self._build_result(runtime)
        atomic_text(session_dir / "session_record.json",
            json.dumps(result, ensure_ascii=False, indent=2)
        )
        atomic_text(session_dir / "classroom_summary.md",
            render_summary_markdown(result)
        )

    # ---------- persistence / recovery ----------

    def _snapshot(self, runtime: SessionRuntime) -> dict[str, Any]:
        return {
            "schemaVersion": "2.0",
            "sessionId": runtime.session_id,
            "lessonId": runtime.lesson_id,
            "versionId": runtime.version_id,
            "status": runtime.status.value,
            "modelMode": runtime.model_mode.value,
            "modelProvider": runtime.model_provider,
            "modelName": runtime.model_name,
            "createdAt": runtime.created_at,
            "startedAt": runtime.started_at,
            "finishedAt": runtime.finished_at,
            "stopReason": runtime.stop_reason,
            "pauseReason": runtime.pause_reason,
            "lastError": runtime.last_error,
            "limits": runtime.limits.model_dump(by_alias=True),
            "activeRoles": list(runtime.classroom.state.active_roles),
            "inputMode": runtime.input_mode,
            "inputMaterials": [self._formal_material(m) for m in runtime.input_materials],
            "materials": [self._formal_material(m) for m in runtime.materials],
            "source": runtime.source,
            "requestKey": runtime.request_key,
            "requestFingerprint": runtime.request_fingerprint,
        }

    def _persist_session(self, runtime: SessionRuntime) -> None:
        session_dir = self.storage_root / runtime.session_id
        session_dir.mkdir(parents=True, exist_ok=True)
        path = session_dir / "session.json"
        tmp = session_dir / "session.json.tmp"
        tmp.write_text(
            json.dumps(self._snapshot(runtime), ensure_ascii=False, indent=2),
            encoding="utf-8",
        )
        tmp.replace(path)

    def _recover_interrupted_sessions(self) -> None:
        for path in self.storage_root.glob("*/session.json"):
            try:
                record = json.loads(path.read_text(encoding="utf-8"))
                status = record.get("status")
                if status in {"READY", "RUNNING", "PAUSED", "STOPPING"}:
                    record["status"] = SessionStatus.INTERRUPTED.value
                    record["stopReason"] = "SERVICE_RESTART_UNRECOVERABLE"
                    record["finishedAt"] = utc_now()
                    atomic_text(path,
                        json.dumps(record, ensure_ascii=False, indent=2)
                    )
                session_id = record.get("sessionId")
                request_key = record.get("requestKey")
                if session_id:
                    self._archived[session_id] = record
                    if request_key:
                        self._request_keys[request_key] = session_id
            except Exception:
                # Corrupt historical records must not prevent service startup.
                continue

    # ---------- helpers ----------

    def _finish(
        self, runtime: SessionRuntime, status: SessionStatus, stop_reason: str
    ) -> None:
        runtime.status = status
        runtime.stop_reason = stop_reason
        runtime.pause_reason = None
        runtime.finished_at = runtime.finished_at or utc_now()
        if runtime.classroom.state.session_status == "active":
            runtime.classroom.state.end_session()
        self._persist_session(runtime)

    def _limit_reason(self, runtime: SessionRuntime) -> str | None:
        if len(runtime.event_store) >= runtime.limits.max_events:
            return "MAX_EVENTS"
        if self._remaining_wall_clock(runtime) <= 0:
            return "MAX_WALL_CLOCK_SECONDS"
        return None

    @staticmethod
    def _elapsed(runtime: SessionRuntime) -> float:
        if not runtime.started_at:
            return 0.0
        text = runtime.started_at.replace("Z", "+00:00")
        start = datetime.fromisoformat(text)
        return max(0.0, (datetime.now(timezone.utc) - start).total_seconds())

    def _remaining_wall_clock(self, runtime: SessionRuntime) -> float:
        return runtime.limits.max_wall_clock_seconds - self._elapsed(runtime)

    @staticmethod
    def _material_ref(material: dict[str, Any] | None) -> MaterialRef | None:
        if not material:
            return None
        return MaterialRef(
            materialId=str(material.get("materialId", material.get("id", ""))),
            title=str(material.get("title", "")),
            sourceSectionId=material.get("sourceSectionId"),
        )

    @staticmethod
    def _formal_material(material: dict[str, Any]) -> dict[str, Any]:
        result = {
            "materialId": str(material.get("materialId", material.get("id", ""))),
            "title": material.get("title"),
            "teachingScript": material.get("teachingScript", material.get("teaching_script")),
            "keyPoints": material.get("keyPoints", material.get("key_points", [])),
            "sourceSectionId": material.get("sourceSectionId"),
            "metadata": material.get("metadata", {}),
            "teacherActivity": material.get("teacher_activity", ""),
            "studentActivity": material.get("student_task", ""),
            "evaluation": material.get("evaluation_control", ""),
            "sourceRef": material.get("sourceRef", {}),
        }
        if material.get("duration_minutes") is not None:
            result["durationMinutes"] = material.get("duration_minutes")
        if material.get("student_task"):
            result["studentTask"] = material.get("student_task")
        if material.get("evaluation_control"):
            result["evaluationControl"] = material.get("evaluation_control")
        return result

    @staticmethod
    def _prepare_materials(
        input_materials: list[dict[str, Any]],
    ) -> tuple[list[dict[str, Any]], list[ParsedTeachingSection] | None, str]:
        """按学姐最新版规则，把单个“教学过程” Markdown material 拆成课堂环节。

        已经是多 material 的输入保持原样，保证旧 F2/F4 对接和历史测试不受影响。
        只有识别到标准表头“环节与教师活动”时才自动拆分。
        """
        if len(input_materials) != 1:
            return input_materials, None, "DIRECT"

        source = input_materials[0]
        if source.get("sourceRef"):
            return input_materials, None, "DIRECT"
        script = source.get("teachingScript", source.get("teaching_script", "")) or ""
        if "环节与教师活动" not in script:
            return input_materials, None, "DIRECT"

        sections = parse_teaching_script(script)
        parent_id = str(source.get("materialId", source.get("id", "1")))
        parent_section_id = source.get("sourceSectionId")
        parent_meta = dict(source.get("metadata", {}))
        parent_key_points = list(source.get("keyPoints", source.get("key_points", [])))
        expanded: list[dict[str, Any]] = []
        for section in sections:
            idx = section.section_index + 1
            metadata = dict(parent_meta)
            metadata.update({
                "splitFromTeachingProcess": True,
                "sourceMaterialId": parent_id,
                "teachingSectionIndex": idx,
                "teachingSectionTitle": section.title,
                "parentSourceSectionId": parent_section_id,
            })
            material_id = f"{parent_id}-section-{idx}"
            expanded.append({
                "id": material_id,
                "materialId": material_id,
                "title": section.title,
                "teaching_script": section.teacher_activity,
                "teachingScript": section.teacher_activity,
                "key_points": list(parent_key_points),
                "keyPoints": list(parent_key_points),
                "sourceSectionId": parent_section_id,
                "duration_minutes": section.duration_minutes,
                "student_task": section.student_task,
                "evaluation_control": section.evaluation_control,
                "raw_content": section.raw_content,
                "metadata": metadata,
            })
        return expanded, sections, "TEACHING_PROCESS_SPLIT"

    def _require_runtime(self, session_id: str) -> SessionRuntime:
        runtime = self._sessions.get(session_id)
        if runtime is None:
            raise KeyError(session_id)
        return runtime

    @staticmethod
    def _ensure_not_terminal(runtime: SessionRuntime) -> None:
        if runtime.status in _TERMINAL or runtime.status == SessionStatus.STOPPING:
            raise RuntimeError(f"session is not controllable in status {runtime.status.value}")

    @staticmethod
    def _ensure_accepts_input(runtime: SessionRuntime) -> None:
        if runtime.status in _TERMINAL or runtime.status == SessionStatus.STOPPING:
            raise RuntimeError(f"session does not accept messages in status {runtime.status.value}")

    @staticmethod
    def _resolve_model_mode(requested: ModelMode | None) -> ModelMode:
        if requested is not None:
            return requested
        configured = os.getenv("F3_MODEL_MODE", "").strip().upper()
        if configured in {"REAL", "MOCK"}:
            return ModelMode(configured)
        return ModelMode.REAL if os.getenv("API_KEY", "").strip() else ModelMode.MOCK

    @staticmethod
    def _actual_mode(requested: ModelMode, llm: Any) -> ModelMode:
        return ModelMode.MOCK if getattr(llm, "name", "") == "mock" else ModelMode.REAL

    @staticmethod
    def _default_llm_factory(mode: ModelMode):
        if mode == ModelMode.MOCK:
            return MockProvider()
        return create_llm()
