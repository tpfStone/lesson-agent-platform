"""Durable batch recovery, including a real evaluator-process exit."""
import json
import os
from pathlib import Path
import subprocess
import sys

import pytest
import platform_api as api


class QueueExecutor:
    def __init__(self):
        self.pending = []

    def submit(self, function, *args):
        self.pending.append((function, args))

    def drain(self):
        while self.pending:
            function, args = self.pending.pop(0)
            function(*args)


def request():
    return api.BatchAnnotationRequest(request_key='restart-batch', items=[
        {'lesson_id': 'lesson', 'version_id': f'v{i}', 'subject': '数学',
         'grade': '五', 'topic': f'环节{i}', 'content': f'教案{i}'} for i in range(3)])


def install_runtime(root, crash=None):
    root = Path(root)
    api.RUN_ROOT, api.BATCH_ROOT = root / 'runs', root / 'batches'
    api.EXECUTOR = QueueExecutor()
    api.load_config = lambda _: {}

    def evaluate(_root, config, _filename, **_kwargs):
        run_id = config['_run_id']
        with (root / 'calls.jsonl').open('a', encoding='utf-8') as handle:
            handle.write(json.dumps({'runId': run_id}) + '\n')
            handle.flush()
        if crash == 'in_model' and len((root / 'calls.jsonl').read_text().splitlines()) == 2:
            os._exit(77)  # Kill the process while the second model request is unresolved.
        rubric = [{'维度名称': child, '满分': maximum // len(children),
                   '得分': maximum // len(children)}
                  for _name, maximum, children in api.GROUPS for child in children]
        return {'result': {'维度评分': rubric}, 'files': {}, 'elapsed': 0}

    def report(_results, directory):
        path = directory / 'batch_quality_report.json'
        path.write_text(json.dumps({'summary': 'controlled report'}), encoding='utf-8')
        return {'quality_json': str(path)}

    api.execute_annotation_worker = evaluate
    api.write_batch_quality_report = report
    api.generate_comparison = lambda *_args: (None, None)
    api.build_comparison_payload = lambda results: {'count': len(results)}
    if crash == 'after_result':
        write_state = api._write_state
        def exit_before_completed(directory, record):
            if (record.get('status') == 'COMPLETED' and directory.parent == api.RUN_ROOT
                    and len((root / 'calls.jsonl').read_text().splitlines()) == 2):
                os._exit(78)
            return write_state(directory, record)
        api._write_state = exit_before_completed


def crash_process(root, mode='in_model'):
    install_runtime(root, crash=mode)
    api.batch_annotate(request())
    api.EXECUTOR.drain()


@pytest.fixture
def runtime(tmp_path, monkeypatch):
    # Restore imported module attributes after the test, including monkeypatched services.
    for name in ('RUN_ROOT', 'BATCH_ROOT', 'EXECUTOR', 'load_config', 'execute_annotation_worker',
                 'write_batch_quality_report', 'generate_comparison', 'build_comparison_payload'):
        monkeypatch.setattr(api, name, getattr(api, name))
    install_runtime(tmp_path)
    return tmp_path


@pytest.mark.parametrize('mode,exit_code', [('in_model', 77), ('after_result', 78)])
def test_real_process_restart_continues_unstarted_items_without_rebilling_unknown(runtime, mode, exit_code):
    code = "import runpy; runpy.run_path(" + repr(str(Path(__file__).resolve())) + ")[\"crash_process\"](" + repr(str(runtime)) + ", " + repr(mode) + ")"
    child = subprocess.run([sys.executable, '-c', code], cwd=Path(api.__file__).parent,
                           capture_output=True, text=True, timeout=30)
    assert child.returncode == exit_code, child.stderr
    assert len((runtime / 'calls.jsonl').read_text().splitlines()) == 2
    batch_id = next((runtime / 'batches').iterdir()).name
    assert api._read_state(runtime / 'batches' / batch_id)['status'] == 'RUNNING'
    if mode == 'after_result':
        # A result file without a committed completion state remains unconfirmed.
        assert len(list((runtime / 'runs').glob('*/result.json'))) == 2
    api.recover_tasks()
    api.recover_tasks()  # Duplicate startup scheduling must be harmless.
    api.EXECUTOR.drain()
    actual = api.batch_result(batch_id)
    assert actual['status'] == 'COMPLETED_WITH_ERRORS'
    assert [item['status'] for item in actual['items']] == ['COMPLETED', 'INTERRUPTED', 'COMPLETED']
    assert (actual['succeeded'], actual['failed']) == (2, 1)
    calls = [json.loads(line)['runId'] for line in (runtime / 'calls.jsonl').read_text().splitlines()]
    assert len(calls) == 3 and len(set(calls)) == 3
    assert api.batch_annotate(request())['batchId'] == batch_id
    api.recover_tasks()
    api.EXECUTOR.drain()
    assert len((runtime / 'calls.jsonl').read_text().splitlines()) == 3


def test_missing_recorded_child_is_not_recreated_during_batch_recovery(runtime):
    api.batch_annotate(request())
    api.EXECUTOR.drain()
    batch_id = next((runtime / 'batches').iterdir()).name
    path = runtime / 'batches' / batch_id
    record = api._read_state(path)
    child_id = record['items'][0]['runId']
    (runtime / 'runs' / child_id / 'state.json').unlink()
    record['status'] = 'RUNNING'
    api._write_state(path, record)
    api.recover_tasks()
    api.EXECUTOR.drain()
    actual = api.batch_result(batch_id)
    assert actual['status'] == 'COMPLETED_WITH_ERRORS'
    assert actual['items'][0]['status'] == 'INTERRUPTED'
    assert actual['items'][0]['error'] == 'RECOVERY_CHILD_STATE_UNCONFIRMED'
    assert len((runtime / 'calls.jsonl').read_text().splitlines()) == 3


def test_queued_child_created_before_batch_checkpoint_executes_once(runtime):
    result = api.batch_annotate(request())
    saved = json.loads((runtime / 'batches' / result['batchId'] / 'input.json').read_text(encoding='utf-8'))
    # Simulate the durable child creation / parent checkpoint crash window.
    child = api.AnnotationRequest.model_validate(saved['items'][0])
    api._initialize_run(child)
    api.recover_tasks()
    api.recover_tasks()
    api.EXECUTOR.drain()
    actual = api.batch_result(result['batchId'])
    assert actual['status'] == 'COMPLETED'
    calls = [json.loads(line)['runId'] for line in (runtime / 'calls.jsonl').read_text().splitlines()]
    assert len(calls) == len(set(calls)) == 3
