"""Platform publication and accounting with real isolated workers, no network."""
import json
import os
from pathlib import Path

import pytest

import annotation_execution
import platform_api as api
from isolated_worker import run_isolated


ANNOTATOR = r'''
import os, time
from pathlib import Path
from usage_meter import UsageLedger
def annotate(base_dir, config, filename, **kwargs):
    root = Path(config['output']['dir'])
    root.mkdir(parents=True)
    ledger = UsageLedger(config['_usage_ledger_path'],config['_run_id'],'controlled')
    request = ledger.start(ledger.begin_call())
    if config.get('block'): time.sleep(30)
    ledger.finish(request,status='SUCCEEDED')
    (root/'report.txt').write_text('controlled report')
    return {'result': {'总体评价':'controlled','维度评分': config['rubric']},
            'files':{'report':str(root/'report.txt')}, 'elapsed':0}
'''


@pytest.fixture
def runtime(tmp_path, monkeypatch):
    (tmp_path / 'controlled_annotator.py').write_text(ANNOTATOR, encoding='utf-8')
    monkeypatch.setenv('PYTHONPATH', str(tmp_path) + os.pathsep + str(Path(api.__file__).parent))
    monkeypatch.setattr(api, 'RUN_ROOT', tmp_path / 'runs')
    monkeypatch.setattr(api, 'BATCH_ROOT', tmp_path / 'batches')
    def delegate(target, args, kwargs, attempt, timeout):
        assert target == 'service:run_annotation'
        return run_isolated('controlled_annotator:annotate', args, kwargs, attempt, timeout)
    monkeypatch.setattr(annotation_execution, 'run_isolated', delegate)
    return tmp_path


@pytest.mark.parametrize('block', [True, False])
def test_parent_publishes_only_success_and_timeout_does_not_rebill(runtime,monkeypatch,block):
    monkeypatch.setenv('F1_ANNOTATION_HARD_TIMEOUT_SECONDS', '1.5' if block else '10')
    rubric = [{'维度名称':child,'满分':maximum//len(children),'得分':maximum//len(children)}
              for _,maximum,children in api.GROUPS for child in children]
    monkeypatch.setattr(api, 'load_config', lambda _: {'block':block,'rubric':rubric,
                                                       'api_keys':{'deepseek':'test-secret-never-on-disk'}})
    request = api.AnnotationRequest(request_key='same-key', lesson_id='lesson',version_id='version',
                                    subject='数学',grade='三',topic='面积',content='内容')
    run_id, directory, _ = api._initialize_run(request)
    api._execute(run_id, request)
    state = api._read_state(directory)
    rows = [json.loads(line) for line in (directory / 'output/model_usage.jsonl').read_text().splitlines()]
    assert len([row for row in rows if row['event']=='request_started']) == 1
    assert {row['task_id'] for row in rows} == {run_id}
    if block:
        assert state['status']=='FAILED' and state['errorCode']=='F1_HARD_TIMEOUT'
        assert not (directory/'result.json').exists()
        assert not any(row['event']=='request_finished' for row in rows)
        with pytest.raises(api.HTTPException) as error: api.result(run_id)
        assert error.value.status_code == 409
    else:
        assert state['status']=='COMPLETED'
        assert api.result(run_id)['summary']=='controlled'
        assert len(state['artifacts'])==1
        assert all(path.startswith('attempts') for path in state['artifacts'].values())
    # Failed and completed tasks are both terminal. Neither duplicate scheduling
    # nor restart recovery can charge another request with the same key.
    assert api._initialize_run(request)[0] == run_id
    api._execute(run_id,request)
    api.recover_tasks()
    assert len(list((directory/'attempts').iterdir()))==1
    assert len((directory/'output/model_usage.jsonl').read_text().splitlines())==len(rows)
    assert not any('test-secret-never-on-disk' in file.read_text(encoding='utf-8',errors='ignore')
                   for file in (directory/'attempts').rglob('*') if file.is_file())


@pytest.mark.parametrize('deadline', [0,-1,'nan','inf'])
def test_invalid_timeout_cannot_disable_containment(runtime, deadline):
    with pytest.raises(ValueError):
        run_isolated('controlled_annotator:annotate',[],{},runtime/'invalid',deadline)
    assert not (runtime/'invalid').exists()
