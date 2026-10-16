"""Meter real provider behavior at a controlled requests transport boundary."""
import json

import pytest
import requests

from app.contracts.session import CreateSessionRequest
from app.llm.base import LLMError
from app.llm.provider import OpenAICompatProvider
from app.web.service import ClassroomService


class TransportProbe:
    """Inject the real direct transport to unit-test accounting without IPC.

    Hard-boundary behavior uses real loopback HTTP in test_worker_timeout.py.
    This adapter is test-only; production has no switch to bypass containment.
    """
    def __init__(self, *args):
        self.provider = OpenAICompatProvider(*args)

    def __getattr__(self, name):
        return getattr(self.provider, name)

    def chat(self, *args, **kwargs):
        return self.provider.chat(*args, **kwargs)


def setup_session(tmp_path):
    provider = TransportProbe('test-secret', 'https://invalid.example/v1', 'test-model')
    service = ClassroomService(storage_root=tmp_path, llm_factory=lambda mode: provider, auto_start=False)
    state, _ = service.create_session(CreateSessionRequest.model_validate({
        'lessonId': 'lesson', 'versionId': 'version', 'requestKey': 'request', 'modelMode': 'REAL',
        'materials': [{'materialId': 'material', 'title': 'title', 'teachingScript': 'content'}]}))
    return provider, service, state['sessionId']


def response(body, status=200):
    result = requests.Response()
    result.status_code = status
    result._content = json.dumps(body).encode()
    result.headers['x-request-id'] = 'provider-response'
    return result


def records(tmp_path, session_id):
    path = tmp_path / session_id / 'model_usage.jsonl'
    assert path.exists(), 'Session requests must have a durable usage ledger'
    return [json.loads(line) for line in path.read_text(encoding='utf-8').splitlines()]


def test_session_requests_capture_usage_and_timeout_without_sensitive_text(tmp_path, monkeypatch):
    provider, _, session_id = setup_session(tmp_path)
    replies = iter([response({'choices': [{'message': {'content': 'answer'}}],
                             'usage': {'prompt_tokens': 3, 'completion_tokens': 2, 'total_tokens': 5,
                                       'sensitive': 'hidden-usage-field'}}),
                    requests.Timeout('private-network-error')])
    def send(self, request, **kwargs):
        reply = next(replies)
        if isinstance(reply, Exception):
            raise reply
        return reply
    monkeypatch.setattr(requests.Session, 'send', send)
    messages = [{'role': 'user', 'content': 'private-lesson'}]
    assert provider.chat(messages) == 'answer'
    with pytest.raises(LLMError):
        provider.chat(messages)
    finished = [row for row in records(tmp_path, session_id) if row['event'] == 'request_finished']
    assert len(finished) == 2
    assert finished[0]['usage'] == {'prompt_tokens': 3, 'completion_tokens': 2, 'total_tokens': 5}
    assert finished[1]['usage'] is None and finished[1]['status'] == 'TRANSPORT_ERROR'
    assert {row['task_id'] for row in finished} == {session_id}
    assert len({row['request_id'] for row in finished}) == 2
    text = json.dumps(finished)
    for secret in ['test-secret', 'private-lesson', 'private-network-error', 'hidden-usage-field']:
        assert secret not in text


@pytest.mark.parametrize('body,status,expected_status', [
    ({'choices': [{'message': {'content': 'answer'}}]}, 200, 'SUCCEEDED'),
    ({'error': {'message': 'private error'}}, 429, 'HTTP_ERROR'),
    ({'usage': {'total_tokens': 9}}, 200, 'RESPONSE_ERROR'),
])
def test_missing_usage_and_invalid_outputs_are_distinguishable(tmp_path, monkeypatch, body, status, expected_status):
    provider, _, session_id = setup_session(tmp_path)
    monkeypatch.setattr(requests.Session, 'send', lambda *args, **kwargs: response(body, status))
    if expected_status == 'SUCCEEDED':
        assert provider.chat([]) == 'answer'
    else:
        with pytest.raises(LLMError):
            provider.chat([])
    finished = [row for row in records(tmp_path, session_id) if row['event'] == 'request_finished']
    assert len(finished) == 1 and finished[0]['status'] == expected_status
    assert finished[0]['usage'] == ({'total_tokens': 9} if expected_status == 'RESPONSE_ERROR' else None)


def test_manager_validation_retries_have_one_operation(tmp_path, monkeypatch):
    _, service, session_id = setup_session(tmp_path)
    monkeypatch.setattr(requests.Session, 'send', lambda *args, **kwargs:
                        response({'choices': [{'message': {'content': 'invalid decision'}}]}))
    classroom = service._sessions[session_id].classroom
    classroom.manager.decide(classroom.state)
    finished = [row for row in records(tmp_path, session_id) if row['event'] == 'request_finished']
    assert len(finished) == 3
    assert {row['operation'] for row in finished} == {'manager'}
    assert len({row['operation_id'] for row in finished}) == 1
    assert [row['application_attempt'] for row in finished] == [1, 2, 3]
    assert finished[1]['retry_of_call_id'] == finished[0]['call_id']


def test_analysis_retry_after_restart_stays_linked_to_same_session(tmp_path, monkeypatch):
    from app.contracts.event import ClassroomEventRecord
    from app.contracts.session import SessionStatus
    _, service, session_id = setup_session(tmp_path)
    runtime = service._sessions[session_id]
    runtime.status = SessionStatus.COMPLETED
    runtime.event_store.append(ClassroomEventRecord(eventId='event', sequence=1, sessionId=session_id,
        occurredAt='2026-10-15T00:00:00Z', completedAt='2026-10-15T00:00:01Z', wallClockDurationMs=1000,
        trigger={'type': 'timeout'}, speaker='teacher', function='explain', content='课堂证据'))
    service._persist_session(runtime)
    replies = iter([requests.Timeout('controlled timeout'),
                    response({'choices': [{'message': {'content': '{"issues": []}'}}],
                              'usage': {'total_tokens': 13}})])
    def send(self, request, **kwargs):
        value = next(replies)
        if isinstance(value, Exception):
            raise value
        return value
    monkeypatch.setattr(requests.Session, 'send', send)
    assert service.analyze(session_id)['analysisStatus'] == 'FAILED'
    restored = ClassroomService(storage_root=tmp_path, llm_factory=lambda mode:
        TransportProbe('test-secret', 'https://invalid.example/v1', 'test-model'), auto_start=False)
    assert restored.analyze(session_id)['analysisStatus'] == 'COMPLETED'
    assert restored.analyze(session_id)['analysisStatus'] == 'COMPLETED'
    finished = [row for row in records(tmp_path, session_id) if row['event'] == 'request_finished']
    assert len(finished) == 2
    assert {row['task_id'] for row in finished} == {session_id}
    assert {row['operation'] for row in finished} == {'issue_analysis_chunk'}
    assert len({row['parent_operation_id'] for row in finished}) == 2
    assert all(row['parent_operation_id'] is not None for row in finished)
    assert finished[0]['usage'] is None and finished[1]['usage'] == {'total_tokens': 13}


def test_classroom_speaker_requests_are_labeled_without_prompt_logging(tmp_path, monkeypatch):
    _, service, session_id = setup_session(tmp_path)
    monkeypatch.setattr(requests.Session, 'send', lambda *args, **kwargs:
                        response({'choices': [{'message': {'content': 'teacher response'}}]}))
    classroom = service._sessions[session_id].classroom
    assert classroom.agents['teacher'].teach(classroom.state) == 'teacher response'
    finished = [row for row in records(tmp_path, session_id) if row['event'] == 'request_finished']
    assert len(finished) == 1 and finished[0]['operation'] == 'classroom_agent'


def test_http_error_preserves_available_usage(tmp_path, monkeypatch):
    provider, _, session_id = setup_session(tmp_path)
    monkeypatch.setattr(requests.Session, 'send', lambda *args, **kwargs:
                        response({'error': {'message': 'controlled'}, 'usage': {'total_tokens': 6}}, 500))
    with pytest.raises(LLMError):
        provider.chat([])
    finished = [row for row in records(tmp_path, session_id) if row['event'] == 'request_finished']
    assert len(finished) == 1 and finished[0]['status'] == 'HTTP_ERROR'
    assert finished[0]['usage'] == {'total_tokens': 6}


def test_analysis_evidence_repair_counts_both_calls_and_links_retry(tmp_path, monkeypatch):
    from app.analysis.issue_agent import IssueAnalysisAgent
    from app.contracts.event import ClassroomEventRecord
    provider, _, session_id = setup_session(tmp_path)
    replies = iter(['{"issues": [{"nativeIssueId": "bad-evidence"}]}', '{"issues": []}'])
    monkeypatch.setattr(requests.Session, 'send', lambda *args, **kwargs:
                        response({'choices': [{'message': {'content': next(replies)}}],
                                  'usage': {'total_tokens': 8}}))
    event = ClassroomEventRecord(eventId='e', sequence=1, sessionId=session_id,
        occurredAt='2026-10-15T00:00:00Z', completedAt='2026-10-15T00:00:01Z', wallClockDurationMs=1000,
        trigger={'type': 'timeout'}, speaker='teacher', function='explain', content='classroom evidence')
    result = IssueAnalysisAgent(provider).analyze(session_id=session_id, version_id='v',
        events=[event], materials=[], session_status='COMPLETED', stop_reason=None, model_mode='REAL')
    assert result.analysis_status == 'COMPLETED'
    finished = [row for row in records(tmp_path, session_id) if row['event'] == 'request_finished']
    assert len(finished) == 2
    assert {row['operation'] for row in finished} == {'issue_analysis_chunk'}
    assert len({row['operation_id'] for row in finished}) == 1
    assert len({row['parent_operation_id'] for row in finished}) == 1
    assert [row['application_attempt'] for row in finished] == [1, 2]
    assert finished[1]['retry_of_call_id'] == finished[0]['call_id']
    assert [row['usage']['total_tokens'] for row in finished] == [8, 8]


def test_accounting_disk_failure_does_not_turn_success_into_retry(tmp_path, monkeypatch, caplog):
    from pathlib import Path
    provider, _, session_id = setup_session(tmp_path)
    calls = []
    def send(*args, **kwargs):
        calls.append(True)
        return response({'choices': [{'message': {'content': 'answer'}}]})
    monkeypatch.setattr(requests.Session, 'send', send)
    original_open = Path.open
    def blocked(path, *args, **kwargs):
        if path.name == 'model_usage.jsonl':
            raise OSError('private disk detail')
        return original_open(path, *args, **kwargs)
    monkeypatch.setattr(Path, 'open', blocked)
    assert provider.chat([]) == 'answer' and len(calls) == 1
    assert 'MODEL_USAGE_WRITE_FAILED' in caplog.text and 'private disk detail' not in caplog.text
