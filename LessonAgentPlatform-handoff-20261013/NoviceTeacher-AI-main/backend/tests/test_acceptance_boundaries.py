"""Offline acceptance cases for malformed structures and source identity."""
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from app.api import create_app


def payload():
    return {
        'request_key': str(uuid4()),
        'metadata': {'subject': '数学', 'grade': '三年级', 'topic': '分数'},
        'content': '教学目标\n理解分数。',
    }


@pytest.mark.parametrize('blocks', [
    [None],
    [{'id': 'b', 'type': 'paragraph', 'text': '正文', 'teachingProcess': [1]}],
    [{'id': 'b', 'type': 'table', 'columnBindings': [0]}],
])
def test_malformed_nested_structure_returns_422_without_creating_session(environment, blocks):
    sessions, factory = environment
    with TestClient(create_app(sessions, factory), raise_server_exceptions=False) as client:
        data = payload()
        data['structured_content'] = {'schemaVersion': '1.1', 'blocks': blocks}
        before = client.get('/api/sessions').json()
        response = client.post('/api/sessions', json=data)
        assert response.status_code == 422, response.text
        assert client.get('/api/sessions').json() == before


def test_plaintext_source_binding_is_part_of_request_identity(client):
    # The public CreateSession schema accepts a source binding independently of
    # structured_content and persists it on the lesson plan.
    data = payload()
    data['source_binding'] = {'versionId': 'source-a'}
    first = client.post('/api/sessions', json=data)
    assert first.status_code == 200, first.text
    assert first.json()['lesson_plan']['source_binding'] == data['source_binding']
    repeated = client.post('/api/sessions', json=data)
    assert repeated.status_code == 200, repeated.text
    assert repeated.json()['session']['id'] == first.json()['session']['id']

    data['source_binding'] = {'versionId': 'source-b'}
    response = client.post('/api/sessions', json=data)
    assert response.status_code == 409, response.text
    restored = client.get('/api/sessions/' + first.json()['session']['id'] + '/current-state')
    assert restored.json()['lesson_plan']['source_binding'] == {'versionId': 'source-a'}
