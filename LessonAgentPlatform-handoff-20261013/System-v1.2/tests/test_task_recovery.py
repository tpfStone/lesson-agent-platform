import json
import pytest
from platform_task_store import TaskStore, atomic_json
import os


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


@pytest.mark.skipif(os.name != 'nt', reason='Windows MAX_PATH regression')
def test_atomic_state_temp_file_can_exceed_legacy_windows_path_limit(tmp_path):
    # Final state path fits legacy MAX_PATH; its unique atomic temporary name
    # crosses it in nested worker attempts and restored deployment directories.
    directory = tmp_path / ('d' * (235-len(str(tmp_path))-1))
    directory.mkdir()
    path = directory / 'supervision.json'
    assert len(str(path)) < 260
    atomic_json(path, {'status':'SUCCESS'})
    assert json.loads(path.read_text()) == {'status':'SUCCESS'}
    assert list(directory.iterdir()) == [path]
