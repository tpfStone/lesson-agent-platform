import json
from pathlib import Path
import platform_api


def test_retry_does_not_rescore(tmp_path, monkeypatch):
    monkeypatch.setattr(platform_api, 'RUN_ROOT', tmp_path)
    run_id = 'a' * 32
    directory = tmp_path / run_id
    directory.mkdir()
    (directory / 'state.json').write_text(json.dumps({'status': 'COMPLETED', 'artifacts': {}}))
    (directory / 'result.json').write_text(json.dumps({'rawResult': {'score': 87}, 'score': {'total': 87},
        'artifactStatus': {'docx': {'status': 'FAILED'}}}))
    def forbidden(*args, **kwargs): raise AssertionError('must not rescore')
    monkeypatch.setattr(platform_api, 'execute_annotation_worker', forbidden)
    import service
    def export(result, path):
        assert result == {'score': 87}
        Path(path).write_bytes(b'controlled-export')
        return path
    monkeypatch.setattr(service, 'generate_docx', export)
    actual = platform_api.retry_artifact(run_id, 'docx')
    assert actual['score']['total'] == 87
    assert actual['artifactStatus']['docx']['status'] == 'READY'


def test_batch_lost_response_reuses_persisted_input(tmp_path, monkeypatch):
    from fastapi import HTTPException
    import pytest
    calls = []
    class Executor:
        def submit(self, *args): calls.append(args)
    monkeypatch.setattr(platform_api, 'BATCH_ROOT', tmp_path)
    monkeypatch.setattr(platform_api, 'EXECUTOR', Executor())
    item = {'lesson_id':'l','version_id':'v','subject':'数学','grade':'五','topic':'面积','content':'教案'}
    request = platform_api.BatchAnnotationRequest(items=[item, {**item, 'version_id':'v2'}], request_key='batch-key')
    first = platform_api.batch_annotate(request)
    second = platform_api.batch_annotate(request)
    assert first['batchId'] == second['batchId'] and len(calls) == 1
    saved = json.loads((tmp_path / first['batchId'] / 'input.json').read_text(encoding='utf-8'))
    assert saved['items'][0]['request_key'] != saved['items'][1]['request_key']
    changed = request.model_copy(update={'items':[request.items[0].model_copy(update={'content':'另一个版本'}),request.items[1]]})
    with pytest.raises(HTTPException) as error: platform_api.batch_annotate(changed)
    assert error.value.status_code == 409
