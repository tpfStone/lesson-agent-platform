"""Real local HTTP/Windows-process checks; no external model calls."""
from contextlib import contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import hashlib
import os
from pathlib import Path
import subprocess
import sys
import threading
import time

import pytest

from app.contracts.session import CreateSessionRequest, SessionStatus
from app.llm.provider import OpenAICompatProvider
from app.web.service import ClassroomService, utc_now


@contextmanager
def model_server(*, block=True, reply='{"issues": []}'):
    entered = threading.Event()
    release = threading.Event()
    calls = []

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def do_POST(self):
            calls.append(json.loads(self.rfile.read(int(self.headers['Content-Length']))))
            entered.set()
            if block:
                release.wait(12)
            body = json.dumps({'choices': [{'message': {'content': reply}}],
                               'usage': {'prompt_tokens': 3, 'completion_tokens': 2}}).encode()
            try:
                self.send_response(200)
                self.send_header('Content-Length', str(len(body)))
                self.end_headers()
                self.wfile.write(body)
            except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
                pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    worker = threading.Thread(target=server.serve_forever, daemon=True)
    worker.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}/v1', entered, release, calls
    finally:
        release.set()
        server.shutdown()
        server.server_close()
        worker.join(2)


def create(service, **overrides):
    data = dict(lessonId='lesson', versionId='v0', requestKey='one', modelMode='REAL',
                materials=[dict(materialId='row', title='Introduction', teachingScript='Explain area')])
    data.update(overrides)
    state, _ = service.create_session(CreateSessionRequest.model_validate(data))
    return service._sessions[state['sessionId']]


def test_new_classroom_has_no_forced_total_duration(tmp_path):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    runtime = create(service, modelMode='MOCK')
    runtime.started_at = '2020-01-01T00:00:00Z'
    runtime.status = SessionStatus.RUNNING
    assert service._limit_reason(runtime) is None


def test_pause_time_does_not_consume_explicit_classroom_limit(tmp_path):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    runtime = create(service, modelMode='MOCK', maxWallClockSeconds=10)
    runtime.started_at = utc_now()
    runtime.status = SessionStatus.RUNNING
    service.pause(runtime.session_id)
    before = service._remaining_wall_clock(runtime)
    time.sleep(.2)
    after = service._remaining_wall_clock(runtime)
    assert before - after < .05
    service.resume(runtime.session_id)
    assert service._remaining_wall_clock(runtime) >= after - .05


def test_stuck_inflight_call_times_out_even_while_paused_and_never_retries(tmp_path, monkeypatch):
    monkeypatch.setenv('F3_MODEL_TIMEOUT_SECONDS', '1')
    with model_server() as (url, entered, release, calls):
        service = ClassroomService(storage_root=tmp_path, auto_start=False,
            llm_factory=lambda _: OpenAICompatProvider('test-key', url, 'test'))
        runtime = create(service)
        service.start_session(runtime.session_id)
        try:
            assert entered.wait(6)
            service.pause(runtime.session_id)
            runtime.thread.join(7)
            assert not runtime.thread.is_alive(), 'Hard timeout must stop a paused blocked request'
            state = service.get_session(runtime.session_id)
            assert state['status'] == 'INTERRUPTED'
            assert state['stopReason'] == 'MODEL_HARD_TIMEOUT'
            assert service.get_events(runtime.session_id) == []
            assert len(calls) == 1, 'No retry or automatic paid analysis after an unknown request'
            ledger = [json.loads(x) for x in (tmp_path / runtime.session_id / 'model_usage.jsonl').read_text().splitlines()]
            assert len([r for r in ledger if r['event'] == 'request_started']) == 1
            assert any(r.get('status') == 'HARD_TIMEOUT' and r.get('usage') is None for r in ledger)
        finally:
            service.end(runtime.session_id)
            release.set()
            runtime.thread.join(3)


def test_end_terminates_inflight_worker_before_model_timeout(tmp_path, monkeypatch):
    monkeypatch.setenv('F3_MODEL_TIMEOUT_SECONDS', '30')
    with model_server() as (url, entered, release, calls):
        service = ClassroomService(storage_root=tmp_path, auto_start=False,
            llm_factory=lambda _: OpenAICompatProvider('test-key', url, 'test'))
        runtime = create(service)
        service.start_session(runtime.session_id)
        try:
            assert entered.wait(6)
            service.end(runtime.session_id)
            runtime.thread.join(4)
            assert not runtime.thread.is_alive(), 'End must cancel the contained worker promptly'
            assert service.get_session(runtime.session_id)['stopReason'] == 'MANUAL_END'
            assert service.get_events(runtime.session_id) == []
            assert len(calls) == 1
        finally:
            release.set()
            runtime.thread.join(3)


def test_analysis_has_independent_total_deadline_and_preserves_events(tmp_path, monkeypatch):
    from app.contracts.event import ClassroomEventRecord
    monkeypatch.setenv('F3_MODEL_TIMEOUT_SECONDS', '30')
    monkeypatch.setenv('F3_ANALYSIS_TIMEOUT_SECONDS', '1.5')
    with model_server() as (url, entered, release, calls):
        service = ClassroomService(storage_root=tmp_path, auto_start=False,
            llm_factory=lambda _: OpenAICompatProvider('test-key', url, 'test'))
        runtime = create(service)
        runtime.event_store.append(ClassroomEventRecord(eventId='e1', sequence=1, sessionId=runtime.session_id,
            occurredAt=utc_now(), completedAt=utc_now(), wallClockDurationMs=1,
            trigger={'type': 'USER_INPUT'}, speaker='teacher', function='teach', content='area evidence'))
        service._finish(runtime, SessionStatus.COMPLETED, 'TEST_COMPLETE')
        original = (tmp_path / runtime.session_id / 'events.json').read_bytes()
        result = []
        thread = threading.Thread(target=lambda: result.append(service.analyze(runtime.session_id)), daemon=True)
        thread.start()
        try:
            assert entered.wait(6)
            thread.join(6)
            assert not thread.is_alive(), 'Analysis deadline must include all its model calls'
            assert result[0]['analysisStatus'] == 'FAILED'
            assert any('ANALYSIS_HARD_TIMEOUT' in w for w in result[0]['warnings'])
            assert (tmp_path / runtime.session_id / 'events.json').read_bytes() == original
            assert len(calls) == 1
            if os.name == 'nt':
                worker_files = list((tmp_path / runtime.session_id / 'workers').rglob('worker.json'))
                assert len(worker_files) == 2, 'Analysis and its nested model request must both be contained'
                assert all(not process_alive(json.loads(path.read_text())['pid']) for path in worker_files)
            restored = ClassroomService(storage_root=tmp_path, auto_start=False,
                llm_factory=lambda _: (_ for _ in ()).throw(AssertionError('restart must not call a model')))
            assert restored.get_result(runtime.session_id)['analysisStatus'] == 'FAILED'
        finally:
            release.set()
            thread.join(3)


def test_isolated_calls_preserve_parent_operation_identity_and_usage(tmp_path):
    from app.llm.metering import usage_operation
    with model_server(block=False, reply='answer') as (url, _, _, calls):
        provider = OpenAICompatProvider('test-key', url, 'test')
        provider.bind_usage(tmp_path / 'model_usage.jsonl', 'session')
        with usage_operation('manager'):
            assert provider.chat_isolated([{'role': 'user', 'content': 'private prompt'}]) == 'answer'
            assert provider.chat_isolated([]) == 'answer'
        rows = [json.loads(line) for line in (tmp_path / 'model_usage.jsonl').read_text().splitlines()]
        finished = [r for r in rows if r['event'] == 'request_finished']
        assert len(calls) == 2
        assert [r['application_attempt'] for r in finished] == [1, 2]
        assert len({r['operation_id'] for r in finished}) == 1
        assert {r['operation'] for r in finished} == {'manager'}
        assert all(r['usage'] == {'prompt_tokens': 3, 'completion_tokens': 2} for r in finished)
        assert finished[1]['retry_of_call_id'] == finished[0]['call_id']
        assert all(r['status'] == 'SUCCEEDED' for r in finished)
        assert 'private prompt' not in json.dumps(rows)
        assert 'test-key' not in json.dumps(rows)


def wait_until(predicate, timeout=8):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if predicate():
            return
        time.sleep(.025)
    assert predicate(), 'condition did not become true before deadline'


def process_alive(pid):
    import win32api
    import win32event
    try:
        handle = win32api.OpenProcess(0x00100000, False, pid)
    except Exception:
        return False
    try:
        return win32event.WaitForSingleObject(handle, 0) == 258
    finally:
        handle.Close()


@pytest.mark.skipif(os.name != 'nt', reason='Windows target Job lifecycle')
def test_hard_timeout_kills_worker_and_descendant_before_return(tmp_path, monkeypatch):
    from app.isolated_worker import WorkerTimeout, run_isolated
    monkeypatch.setenv('PYTHONPATH', str(Path(__file__).parent.resolve()))
    marker = tmp_path / 'pids.json'
    with pytest.raises(WorkerTimeout):
        run_isolated('worker_timeout_probe:spawn_descendant', [str(marker)], {}, tmp_path / 'attempt', 1)
    pids = json.loads(marker.read_text())
    assert not process_alive(pids['worker'])
    assert not process_alive(pids['child'])
    assert not (tmp_path / 'attempt' / 'outcome.json').exists()


@pytest.mark.skipif(os.name != 'nt', reason='Windows target Job lifecycle')
def test_parent_death_closes_containment_and_does_not_leave_orphans(tmp_path, monkeypatch):
    monkeypatch.setenv('PYTHONPATH', str(Path(__file__).parent.resolve()))
    marker = tmp_path / 'pids.json'
    attempt = tmp_path / 'attempt'
    code = ('from app.isolated_worker import run_isolated; import sys; '
            'run_isolated("worker_timeout_probe:spawn_descendant", [sys.argv[1]], {}, sys.argv[2], 30)')
    parent = subprocess.Popen([sys.executable, '-c', code, str(marker), str(attempt)],
                              creationflags=subprocess.CREATE_NO_WINDOW)
    try:
        wait_until(marker.exists)
        pids = json.loads(marker.read_text())
        assert process_alive(pids['worker']) and process_alive(pids['child'])
        parent.kill()
        parent.wait(timeout=3)
        wait_until(lambda: not process_alive(pids['worker']) and not process_alive(pids['child']))
        wait_until(lambda: (attempt / 'supervision.json').exists())
        assert json.loads((attempt / 'supervision.json').read_text())['status'] == 'PARENT_EXITED'
    finally:
        if parent.poll() is None:
            parent.kill()
            parent.wait(timeout=3)


def test_restart_marks_unfinished_analysis_unknown_even_without_result_artifact(tmp_path):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    runtime = create(service, modelMode='MOCK')
    service._finish(runtime, SessionStatus.COMPLETED, 'TEST')
    (tmp_path / runtime.session_id / 'analysis_attempt.json').write_text(
        json.dumps({'attemptId': 'old-attempt', 'status': 'STARTED'}))
    restored = ClassroomService(storage_root=tmp_path, auto_start=False,
        llm_factory=lambda _: (_ for _ in ()).throw(AssertionError('no automatic paid retry')))
    assert restored.get_result(runtime.session_id)['analysisStatus'] == 'FAILED'
    assert 'ANALYSIS_INTERRUPTED_UNKNOWN' in str(restored.get_result(runtime.session_id)['analysisWarnings'])


def test_automatic_finalization_does_not_retry_failed_explicit_analysis(tmp_path):
    from app.contracts.event import ClassroomEventRecord
    from app.llm.base import LLMError
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    runtime = create(service, modelMode='MOCK')
    class FailedAnalysis:
        name = 'injected'
        calls = 0
        def chat(self, *args, **kwargs):
            self.calls += 1
            raise LLMError('controlled failure')
    provider = FailedAnalysis()
    runtime.classroom.llm = provider
    runtime.event_store.append(ClassroomEventRecord(eventId='e1', sequence=1, sessionId=runtime.session_id,
        occurredAt=utc_now(), completedAt=utc_now(), wallClockDurationMs=1,
        trigger={'type': 'USER_INPUT'}, speaker='teacher', function='teach', content='retained evidence'))
    service._finish(runtime, SessionStatus.COMPLETED, 'TEST_COMPLETE')
    assert service.analyze(runtime.session_id)['analysisStatus'] == 'FAILED'
    assert provider.calls == 1
    service._finalize_artifacts(runtime)
    assert provider.calls == 1, 'Automatic finalization must not repeat a failed explicit attempt'


def test_old_omitted_default_limit_replay_reuses_original_contract(tmp_path):
    request = CreateSessionRequest.model_validate(dict(lessonId='lesson', versionId='v0',
        requestKey='old-key', modelMode='MOCK', materials=[dict(materialId='row', title='title', teachingScript='text')]))
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    state, _ = service.create_session(request)
    runtime = service._sessions[state['sessionId']]
    old_payload = request.model_dump(mode='json', by_alias=True)
    old_payload['maxWallClockSeconds'] = 900
    runtime.request_fingerprint = hashlib.sha256(json.dumps(old_payload,
        sort_keys=True, ensure_ascii=False, separators=(',', ':')).encode()).hexdigest()
    runtime.limits.max_wall_clock_seconds = 900
    service._persist_session(runtime)
    restarted = ClassroomService(storage_root=tmp_path, auto_start=False)
    replay, reused = restarted.create_session(request)
    assert reused and replay['sessionId'] == runtime.session_id
    assert replay['limits']['maxWallClockSeconds'] == 900
    assert replay['requestFingerprint'] == runtime.request_fingerprint
    with pytest.raises(ValueError, match='CONFLICT'):
        restarted.create_session(request.model_copy(update={'version_id': 'changed'}))


def test_restart_result_execution_state_matches_recovered_session(tmp_path):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    runtime = create(service, modelMode='MOCK')
    runtime.status = SessionStatus.RUNNING
    service._persist_session(runtime)
    (tmp_path / runtime.session_id / 'session_record.json').write_text(json.dumps({
        'executionStatus': 'RUNNING', 'session': service.get_session(runtime.session_id),
        'events': [], 'analysisStatus': 'PENDING'}))
    restarted = ClassroomService(storage_root=tmp_path, auto_start=False)
    result = restarted.get_result(runtime.session_id)
    assert result['executionStatus'] == result['session']['status'] == 'INTERRUPTED'
    assert json.loads((tmp_path / runtime.session_id / 'session_record.json').read_text())['executionStatus'] == 'INTERRUPTED'
