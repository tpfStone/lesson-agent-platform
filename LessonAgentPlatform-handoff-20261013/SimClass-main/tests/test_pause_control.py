"""Controlled offline scheduling probes: no real model or wall-clock sleeps."""
import json
import hashlib
import threading

import pytest

from app.classroom.events import ClassroomEvent, TRIGGER_INITIALIZATION
from app.contracts.session import CreateSessionRequest, SessionStatus
from app.llm.mock import MockProvider
from app.llm.base import LLMError
from app.web.service import ClassroomService, utc_now


class SlowProvider(MockProvider):
    def __init__(self, blocked_call=1, failure=False):
        self.blocked_call = blocked_call
        self.calls = 0
        self.entered = threading.Event()
        self.release = threading.Event()
        self.returned = threading.Event()
        self.failure = failure
        self.timeline = []

    def chat(self, messages, temperature=0.7):
        self.calls += 1
        self.timeline.append({"event": "request_started", "call": self.calls, "at": utc_now()})
        if self.calls == self.blocked_call:
            self.entered.set()
            assert self.release.wait(3), "test must release the controlled response"
            if self.failure:
                self.returned.set()
                raise LLMError("controlled model timeout")
            result = super().chat(messages, temperature)
            self.returned.set()
            self.timeline.append({"event": "response_ready", "call": self.calls, "at": utc_now()})
            return result
        return super().chat(messages, temperature)


def make_runtime(tmp_path, provider):
    service = ClassroomService(storage_root=tmp_path, llm_factory=lambda _: provider, auto_start=False)
    session, _ = service.create_session(CreateSessionRequest.model_validate({
        "lessonId": "lesson", "versionId": "version", "requestKey": "key",
        "modelMode": "MOCK", "materials": [
            {"materialId": "row", "title": "Introduction", "teachingScript": "Explain the concept"}
        ],
    }))
    runtime = service._sessions[session["sessionId"]]
    runtime.status = SessionStatus.RUNNING
    return service, runtime


def run_turn(service, runtime):
    errors = []
    done = threading.Event()

    def execute():
        try:
            service._execute_event(runtime, ClassroomEvent(TRIGGER_INITIALIZATION))
        except BaseException as error:
            errors.append(error)
        finally:
            done.set()

    thread = threading.Thread(target=execute, daemon=True)
    thread.start()
    return thread, done, errors


@pytest.mark.parametrize("blocked_call", [1, 2], ids=["manager", "agent"])
def test_paused_inflight_reply_waits_for_resume_before_next_call_or_publication(tmp_path, blocked_call):
    provider = SlowProvider(blocked_call)
    service, runtime = make_runtime(tmp_path, provider)
    thread, done, errors = run_turn(service, runtime)
    try:
        assert provider.entered.wait(2)
        service.pause(runtime.session_id)
        provider.timeline.append({"event": "pause_acknowledged", "at": utc_now()})
        provider.release.set()
        assert provider.returned.wait(2)
        assert not done.wait(0.2), "in-flight reply must remain pending until resume"
        assert provider.calls == blocked_call, "pause must prevent the next model request"
        assert service.get_events(runtime.session_id) == []
        assert runtime.classroom.state.dialogue_history == []
        assert service.get_session(runtime.session_id)["status"] == "PAUSED"
        provider.timeline.append({"event": "paused_verified", "at": utc_now(), "calls": provider.calls, "eventCount": 0})
        service.resume(runtime.session_id)
        provider.timeline.append({"event": "resume_acknowledged", "at": utc_now()})
        assert done.wait(2)
        assert not errors
        events = service.get_events(runtime.session_id)
        assert len(events) == 1 and events[0]["status"] == "COMPLETED"
        assert events[0]["sequence"] == 1 and events[0]["function"] == "teach"
        assert provider.calls == 2
        persisted = json.loads((tmp_path / runtime.session_id / "events.json").read_text(encoding="utf-8"))
        assert persisted == events
        provider.timeline.append({"event": "publication_verified", "at": utc_now(), "eventCount": len(events)})
        (tmp_path / "control_timeline.json").write_text(json.dumps(provider.timeline, indent=2), encoding="utf-8")
    finally:
        service.end(runtime.session_id)
        provider.release.set()
        thread.join(2)


def test_event_dequeued_while_paused_does_not_start_a_model_request(tmp_path):
    provider = SlowProvider()
    service, runtime = make_runtime(tmp_path, provider)
    service.pause(runtime.session_id)
    thread, done, errors = run_turn(service, runtime)
    try:
        assert not provider.entered.wait(0.2), "dequeue after pause must wait before model admission"
        assert service.get_events(runtime.session_id) == []
        service.resume(runtime.session_id)
        assert provider.entered.wait(2)
        provider.release.set()
        assert done.wait(2)
        assert not errors
        assert len(service.get_events(runtime.session_id)) == 1
    finally:
        service.end(runtime.session_id)
        provider.release.set()
        thread.join(2)


def test_end_during_manager_response_does_not_start_agent_or_publish(tmp_path):
    provider = SlowProvider()
    service, runtime = make_runtime(tmp_path, provider)
    thread, done, errors = run_turn(service, runtime)
    try:
        assert provider.entered.wait(2)
        assert service.end(runtime.session_id)["status"] == "STOPPING"
        provider.release.set()
        assert done.wait(2)
        assert not errors
        assert provider.calls == 1, "the ended session must not start an agent request"
        assert service.get_events(runtime.session_id) == []
        assert runtime.classroom.state.dialogue_history == []
        assert service.get_session(runtime.session_id)["status"] == "INTERRUPTED"
        assert service.get_session(runtime.session_id)["stopReason"] == "MANUAL_END"
        with pytest.raises(RuntimeError, match="not controllable"):
            service.resume(runtime.session_id)
    finally:
        provider.release.set()
        thread.join(2)


def test_end_between_turn_completion_and_record_publication_discards_late_event(tmp_path, monkeypatch):
    import app.web.service as module

    provider = MockProvider()
    service, runtime = make_runtime(tmp_path, provider)
    record_type = module.ClassroomEventRecord
    building = threading.Event()
    release = threading.Event()

    def delayed_record(**values):
        building.set()
        assert release.wait(3)
        return record_type(**values)

    monkeypatch.setattr(module, "ClassroomEventRecord", delayed_record)
    thread, done, errors = run_turn(service, runtime)
    try:
        assert building.wait(2)
        service.end(runtime.session_id)
        release.set()
        assert done.wait(2)
        assert not errors
        assert service.get_events(runtime.session_id) == []
        assert service.get_session(runtime.session_id)["status"] == "INTERRUPTED"
    finally:
        release.set()
        thread.join(2)


def test_paused_inflight_failure_waits_until_resume_before_publishing_failed_event(tmp_path):
    provider = SlowProvider(failure=True)
    service, runtime = make_runtime(tmp_path, provider)
    thread, done, errors = run_turn(service, runtime)
    try:
        assert provider.entered.wait(2)
        service.pause(runtime.session_id)
        provider.release.set()
        assert provider.returned.wait(2)
        assert not done.wait(0.2)
        assert service.get_events(runtime.session_id) == []
        service.resume(runtime.session_id)
        assert done.wait(2)
        assert not errors
        events = service.get_events(runtime.session_id)
        assert len(events) == 1 and events[0]["status"] == "FAILED"
        assert "controlled model timeout" in events[0]["error"]
        assert provider.calls == 1
    finally:
        service.end(runtime.session_id)
        provider.release.set()
        thread.join(2)


@pytest.mark.parametrize("stop_by", ["end", "budget"])
def test_waiting_paused_worker_exits_on_end_or_budget_without_replaying(tmp_path, stop_by):
    provider = SlowProvider()
    service, runtime = make_runtime(tmp_path, provider)
    # A total limit is now optional; this branch explicitly opts into one.
    runtime.limits.max_wall_clock_seconds = 900
    service.start_session(runtime.session_id)
    try:
        assert provider.entered.wait(2)
        service.pause(runtime.session_id)
        provider.release.set()
        assert provider.returned.wait(2)
        if stop_by == "end":
            service.end(runtime.session_id)
        else:
            with runtime.lock:
                runtime.started_at = "2020-01-01T00:00:00Z"
                runtime.condition.notify_all()
        session = service.wait(runtime.session_id, timeout=2)
        assert not runtime.thread.is_alive()
        assert session["status"] == "INTERRUPTED"
        assert session["stopReason"] == ("MANUAL_END" if stop_by == "end" else "MAX_WALL_CLOCK_SECONDS")
        assert provider.calls == 1
        assert service.get_events(runtime.session_id) == []
        result = service.get_result(runtime.session_id)
        assert result["executionStatus"] == "INTERRUPTED"
        assert result["analysisStatus"] == "COMPLETED"
        assert service.start_session(runtime.session_id)["status"] == "INTERRUPTED"
        assert provider.calls == 1
        persisted = json.loads((tmp_path / runtime.session_id / "session_record.json").read_text(encoding="utf-8"))
        assert persisted["executionStatus"] == "INTERRUPTED"
        assert persisted["events"] == []
    finally:
        service.end(runtime.session_id)
        provider.release.set()
        runtime.thread.join(2)


def test_analysis_retry_updates_result_without_replaying_or_changing_event_file(tmp_path):
    provider = MockProvider()
    service, runtime = make_runtime(tmp_path, provider)
    service._execute_event(runtime, ClassroomEvent(TRIGGER_INITIALIZATION))
    service._finish(runtime, SessionStatus.COMPLETED, "TEST_COMPLETE")
    events_path = tmp_path / runtime.session_id / "events.json"
    before_hash = hashlib.sha256(events_path.read_bytes()).hexdigest()

    class AnalysisProvider:
        name = "controlled-analysis"

        def __init__(self):
            self.calls = 0
            self.entered = threading.Event()
            self.release = threading.Event()

        def chat(self, messages, **kwargs):
            self.calls += 1
            assert "IssueAnalysisAgent" in messages[0]["content"]
            if self.calls == 1:
                raise LLMError("controlled analysis timeout")
            self.entered.set()
            assert self.release.wait(3)
            return '{"issues": []}'

    analysis_provider = AnalysisProvider()
    runtime.classroom.llm = analysis_provider
    assert service.analyze(runtime.session_id)["analysisStatus"] == "FAILED"
    assert service.get_result(runtime.session_id)["analysisStatus"] == "FAILED"
    errors = []

    def retry():
        try:
            service.analyze(runtime.session_id)
        except BaseException as error:
            errors.append(error)

    thread = threading.Thread(target=retry, daemon=True)
    thread.start()
    try:
        assert analysis_provider.entered.wait(2)
        # Reads must remain available while the analysis response is in flight.
        assert service.get_session(runtime.session_id)["status"] == "COMPLETED"
        assert service.get_result(runtime.session_id)["analysisStatus"] == "FAILED"
        analysis_provider.release.set()
        thread.join(2)
        assert not thread.is_alive() and not errors
        result = service.get_result(runtime.session_id)
        assert result["executionStatus"] == "COMPLETED"
        assert result["analysisStatus"] == "COMPLETED"
        assert result["analysisRevision"] == 1
        assert len(result["events"]) == 1
        after_hash = hashlib.sha256(events_path.read_bytes()).hexdigest()
        assert after_hash == before_hash
        assert service.analyze(runtime.session_id)["analysisStatus"] == "COMPLETED"
        assert analysis_provider.calls == 2, "successful analysis must be reused"
        saved = json.loads((tmp_path / runtime.session_id / "session_record.json").read_text(encoding="utf-8"))
        assert saved["analysisStatus"] == "COMPLETED"
        assert saved["events"] == result["events"]
        (tmp_path / "analysis_retry_evidence.json").write_text(json.dumps({
            "eventsSha256Before": before_hash, "eventsSha256After": after_hash,
            "analysisCalls": analysis_provider.calls, "analysisStatus": saved["analysisStatus"],
            "analysisRevision": result["analysisRevision"], "eventCount": len(result["events"]),
        }, indent=2), encoding="utf-8")
    finally:
        analysis_provider.release.set()
        thread.join(2)


@pytest.mark.parametrize("action", ["pause", "end"])
def test_control_and_request_admission_are_atomic_and_inflight_is_observable(tmp_path, action):
    provider = SlowProvider()
    service, runtime = make_runtime(tmp_path, provider)
    controlled = runtime.classroom.manager.llm
    checkpoint = controlled.checkpoint
    checked = threading.Event()
    release_check = threading.Event()
    controlled_once = False

    def delayed_check():
        nonlocal controlled_once
        allowed = checkpoint()
        if not controlled_once:
            controlled_once = True
            checked.set()
            assert release_check.wait(3)
        return allowed

    controlled.checkpoint = delayed_check
    thread, done, errors = run_turn(service, runtime)
    acknowledged = threading.Event()
    snapshots = []

    def control():
        snapshots.append(getattr(service, action)(runtime.session_id))
        acknowledged.set()

    control_thread = threading.Thread(target=control, daemon=True)
    try:
        assert checked.wait(2)
        control_thread.start()
        assert not acknowledged.wait(0.2), "control cannot interleave between check and admission"
        release_check.set()
        assert provider.entered.wait(2)
        assert acknowledged.wait(2)
        active = snapshots[0]["inFlightModelRequest"]
        assert active["requestId"]
        assert active["admittedAt"]
        observed = service.get_session(runtime.session_id)["inFlightModelRequest"]
        assert observed["requestId"] == active["requestId"]
        assert observed["dispatchStartedAt"] >= observed["admittedAt"]
        provider.release.set()
        if action == "pause":
            assert not done.wait(0.2)
            assert provider.calls == 1
            assert service.get_events(runtime.session_id) == []
            service.resume(runtime.session_id)
        assert done.wait(2)
        assert not errors
        assert service.get_session(runtime.session_id)["inFlightModelRequest"] is None
        persisted = json.loads((tmp_path / runtime.session_id / "session.json").read_text(encoding="utf-8"))
        assert persisted["inFlightModelRequest"] is None
        assert provider.calls == (2 if action == "pause" else 1)
        assert len(service.get_events(runtime.session_id)) == (1 if action == "pause" else 0)
        if action == "end":
            assert service.get_session(runtime.session_id)["status"] == "INTERRUPTED"
        (tmp_path / "admission_evidence.json").write_text(json.dumps({
            "control": action, "acknowledgedSnapshot": snapshots[0],
            "observedInFlight": observed, "requestCount": provider.calls,
            "events": service.get_events(runtime.session_id),
        }, indent=2), encoding="utf-8")
    finally:
        release_check.set()
        provider.release.set()
        service.end(runtime.session_id)
        if control_thread.ident is not None:
            control_thread.join(2)
        thread.join(2)
