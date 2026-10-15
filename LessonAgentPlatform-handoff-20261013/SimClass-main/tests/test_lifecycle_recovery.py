import pytest
from app.web.service import ClassroomService
from app.contracts.session import CreateSessionRequest


def request(**overrides):
    values = dict(lessonId='l', versionId='v', requestKey='key', modelMode='MOCK',
                  materials=[dict(materialId='r', title='导入', teachingScript='提问')])
    values.update(overrides)
    return CreateSessionRequest.model_validate(values)


def test_same_key_different_payload_conflicts_before_and_after_restart(tmp_path):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    first, _ = service.create_session(request())
    assert service.create_session(request())[0]['sessionId'] == first['sessionId']
    with pytest.raises(ValueError, match='REQUEST_KEY_CONFLICT'):
        service.create_session(request(versionId='other'))
    restarted = ClassroomService(storage_root=tmp_path, auto_start=False)
    assert restarted.create_session(request())[0]['stopReason'] == 'SERVICE_RESTART_UNRECOVERABLE'
    with pytest.raises(ValueError, match='REQUEST_KEY_CONFLICT'):
        restarted.create_session(request(versionId='other'))


def test_concurrency_is_bounded(tmp_path, monkeypatch):
    monkeypatch.setenv('F3_MAX_ACTIVE_SESSIONS', '1')
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    service.create_session(request())
    with pytest.raises(RuntimeError, match='CAPACITY'):
        service.create_session(request(requestKey='second'))


@pytest.mark.parametrize('auto_start', [False, True])
def test_creation_persist_failure_allows_durable_retry_and_restart(tmp_path, monkeypatch, auto_start):
    import json
    from pathlib import Path
    service = ClassroomService(storage_root=tmp_path, auto_start=auto_start)
    started = []
    # Exercise real start_session and its worker startup without running a model turn.
    monkeypatch.setattr(service, '_run_session', lambda runtime: started.append(runtime.session_id))
    replace = Path.replace
    fail_once = True
    def interrupted_publish(source, target):
        nonlocal fail_once
        if Path(target).name == 'session.json' and fail_once:
            fail_once = False
            raise OSError('injected session publication failure')
        return replace(source, target)
    monkeypatch.setattr(Path, 'replace', interrupted_publish)
    with pytest.raises(OSError, match='publication failure'):
        service.create_session(request())
    assert started == []

    retried, reused = service.create_session(request())
    session_id = retried['sessionId']
    path = tmp_path / session_id / 'session.json'
    assert path.is_file(), 'A successful retry must publish durable session state'
    assert json.loads(path.read_text(encoding='utf-8'))['sessionId'] == session_id
    if auto_start:
        service._sessions[session_id].thread.join(timeout=2)
    assert started == ([session_id] if auto_start else [])
    assert service.create_session(request())[0]['sessionId'] == session_id
    assert started == ([session_id] if auto_start else [])

    restarted = ClassroomService(storage_root=tmp_path, auto_start=False)
    restored, reused = restarted.create_session(request())
    assert reused and restored['sessionId'] == session_id
    assert restored['stopReason'] == 'SERVICE_RESTART_UNRECOVERABLE'


def test_missing_request_cancellation_fences_late_runner_after_restart(tmp_path):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    assert service.cancel_request_if_absent('key')['status'] == 'NOT_CREATED'
    restarted = ClassroomService(storage_root=tmp_path, auto_start=False)
    with pytest.raises(ValueError, match='REQUEST_CANCELLED'):
        restarted.create_session(request())
    session, _ = restarted.create_session(request(requestKey='new-key'))
    assert restarted.cancel_request_if_absent('new-key')['sessionId'] == session['sessionId']


def test_archived_session_can_be_analyzed_without_replaying_classroom(tmp_path, monkeypatch):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    first, _ = service.create_session(request())
    restored = ClassroomService(storage_root=tmp_path, auto_start=False)
    from app.classroom.classroom import Classroom
    monkeypatch.setattr(Classroom, '_run_one_turn', lambda *_: (_ for _ in ()).throw(AssertionError('must not replay')))
    restored.analyze(first['sessionId'])
    result = restored.get_result(first['sessionId'])
    assert result['analysisStatus'] == 'COMPLETED'
    assert result['session']['stopReason'] == 'SERVICE_RESTART_UNRECOVERABLE'
    assert result['events'] == []


def test_failed_analysis_can_retry_without_replaying_execution(tmp_path):
    from app.contracts.event import ClassroomEventRecord
    from app.contracts.session import SessionStatus
    from app.llm.base import LLMError
    class Provider:
        name = 'injected'
        calls = 0
        def chat(self, *args, **kwargs):
            self.calls += 1
            if self.calls == 1: raise LLMError('injected timeout')
            return '{"issues": []}'
    provider = Provider()
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    first, _ = service.create_session(request())
    runtime = service._sessions[first['sessionId']]
    runtime.classroom.llm = provider
    runtime.status = SessionStatus.COMPLETED
    runtime.event_store.append(ClassroomEventRecord(eventId='e', sequence=1, sessionId=first['sessionId'],
        occurredAt='2026-10-14T00:00:00Z', completedAt='2026-10-14T00:00:01Z', wallClockDurationMs=1000,
        trigger={'type':'timeout'}, speaker='teacher', function='explain', content='已完成课堂'))
    assert service.analyze(first['sessionId'])['analysisStatus'] == 'FAILED'
    assert service.get_result(first['sessionId'])['analysisStatus'] == 'FAILED'
    assert service.analyze(first['sessionId'])['analysisStatus'] == 'COMPLETED'
    assert provider.calls == 2 and len(service.get_events(first['sessionId'])) == 1


def test_retry_repairs_result_after_analysis_artifact_was_published(tmp_path, monkeypatch):
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    first, _ = service.create_session(request())
    restored = ClassroomService(storage_root=tmp_path, auto_start=False)
    restored.analyze(first['sessionId'])
    (tmp_path / first['sessionId'] / 'session_record.json').unlink()
    from app.analysis.issue_agent import IssueAnalysisAgent
    monkeypatch.setattr(IssueAnalysisAgent, 'analyze', lambda *a, **k: (_ for _ in ()).throw(AssertionError('must reuse analysis')))
    restored.analyze(first['sessionId'])
    assert restored.get_result(first['sessionId'])['analysisStatus'] == 'COMPLETED'


def test_expired_budget_does_not_start_a_model_turn(tmp_path, monkeypatch):
    from app.classroom.events import ClassroomEvent, TRIGGER_TIMEOUT
    from app.contracts.session import SessionStatus
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    first, _ = service.create_session(request(maxWallClockSeconds=1))
    runtime = service._sessions[first['sessionId']]
    runtime.started_at = '2020-01-01T00:00:00Z'
    runtime.status = SessionStatus.RUNNING
    monkeypatch.setattr(runtime.classroom, '_run_one_turn', lambda: (_ for _ in ()).throw(AssertionError('late model call')))
    service._execute_event(runtime, ClassroomEvent(TRIGGER_TIMEOUT))
    assert runtime.stop_reason == 'MAX_WALL_CLOCK_SECONDS'
    assert service.get_events(first['sessionId']) == []


def test_artifact_publish_failure_keeps_previous_json(tmp_path, monkeypatch):
    from pathlib import Path
    from app.contracts.session import SessionStatus
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    session, _ = service.create_session(request())
    runtime = service._sessions[session['sessionId']]
    service._finish(runtime, SessionStatus.COMPLETED, 'TEST')
    service.analyze(session['sessionId'])
    path = tmp_path / session['sessionId'] / 'session_record.json'
    original = path.read_bytes()
    replace = Path.replace
    def fail_publish(source, target):
        if Path(target) == path: raise OSError('injected publication failure')
        return replace(source, target)
    monkeypatch.setattr(Path, 'replace', fail_publish)
    with pytest.raises(OSError, match='publication'):
        service._write_result_artifact(runtime)
    assert path.read_bytes() == original


def test_terminal_runtime_eviction_keeps_read_analysis_and_idempotency(tmp_path, monkeypatch):
    from app.contracts.session import SessionStatus
    monkeypatch.setenv('F3_MAX_RETAINED_TERMINAL_SESSIONS', '0')
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    first, _ = service.create_session(request())
    runtime = service._sessions[first['sessionId']]
    service._finish(runtime, SessionStatus.COMPLETED, 'TEST')
    service.analyze(first['sessionId'])
    service.create_session(request(requestKey='next'))
    assert first['sessionId'] not in service._sessions
    assert service.get_session(first['sessionId'])['status'] == 'COMPLETED'
    assert service.get_result(first['sessionId'])['analysisStatus'] == 'COMPLETED'
    assert service.create_session(request())[1] is True
    assert service.analyze(first['sessionId'])['analysisStatus'] == 'COMPLETED'


def test_eviction_publishes_successful_analysis_before_discarding_memory(tmp_path, monkeypatch):
    from app.analysis.issue_agent import IssueAnalysisAgent
    from app.contracts.session import SessionStatus
    monkeypatch.setenv('F3_MAX_RETAINED_TERMINAL_SESSIONS', '0')
    calls = []
    analyze = IssueAnalysisAgent.analyze
    def counted(*args, **kwargs):
        calls.append(1)
        return analyze(*args, **kwargs)
    monkeypatch.setattr(IssueAnalysisAgent, 'analyze', counted)
    service = ClassroomService(storage_root=tmp_path, auto_start=False)
    session, _ = service.create_session(request())
    runtime = service._sessions[session['sessionId']]
    service._finish(runtime, SessionStatus.COMPLETED, 'TEST')
    publish = service._write_analysis_artifacts
    def failed_publish(_runtime): raise OSError('temporary disk failure')
    monkeypatch.setattr(service, '_write_analysis_artifacts', failed_publish)
    with pytest.raises(OSError): service.analyze(session['sessionId'])
    service.create_session(request(requestKey='second'))
    assert session['sessionId'] in service._sessions
    monkeypatch.setattr(service, '_write_analysis_artifacts', publish)
    service.create_session(request(requestKey='third'))
    assert session['sessionId'] not in service._sessions
    service.analyze(session['sessionId'])
    assert len(calls) == 1
