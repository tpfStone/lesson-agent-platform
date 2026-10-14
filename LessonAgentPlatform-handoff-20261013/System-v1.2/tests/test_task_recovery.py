import json
import pytest
from platform_task_store import TaskStore


def test_lost_response_reuses_same_task_and_rejects_changed_payload(tmp_path):
    store = TaskStore(tmp_path)
    first, created = store.submit('key', {'content': 'one'})
    assert created
    same, created = TaskStore(tmp_path).submit('key', {'content': 'one'})
    assert not created and same['runId'] == first['runId']
    with pytest.raises(ValueError): store.submit('key', {'content': 'two'})


def test_restart_requeues_only_unstarted_tasks(tmp_path):
    store = TaskStore(tmp_path)
    queued, _ = store.submit('queued', {'content': 'queued'})
    running, _ = store.submit('running', {'content': 'running'})
    path = tmp_path / running['runId'] / 'state.json'
    state = json.loads(path.read_text()); state['status'] = 'RUNNING'
    path.write_text(json.dumps(state))
    recoverable = store.recover()
    assert [x[0] for x in recoverable] == [queued['runId']]
    assert json.loads(path.read_text())['status'] == 'INTERRUPTED'
