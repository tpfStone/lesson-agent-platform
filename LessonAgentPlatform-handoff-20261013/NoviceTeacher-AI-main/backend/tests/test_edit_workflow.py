"""Exercise persisted node edits through the real workflow and HTTP boundary."""
from copy import deepcopy
from dataclasses import replace

from fastapi.testclient import TestClient
from sqlalchemy import select
import pytest
from pydantic import ValidationError

from app.api import create_app
from app.providers.interfaces import SuggestionDraft
from app import models as m
from test_chapter_units import chapter_document, create_document, original_document
from test_workflow import current, post, target, finish_round


def suggestion(edits, **overrides):
    return SuggestionDraft.model_validate(dict(issue='完善探究', reason='记录学习过程',
        pedagogical_basis='教师复核', revision='先记录，再解释。', revision_mode='replace',
        edits=edits, **overrides))


def edit(block, before, after, **address):
    return dict(operation='replace', block_id=block, expected_text=before, text=after, **address)


def install_provider(environment, generate):
    sessions, factory = environment
    build = factory.build
    class Provider:
        def generate(self, context):
            return generate(context)
    factory.build = lambda snapshot, history: replace(build(snapshot, history), suggestion_provider=Provider())
    return TestClient(create_app(sessions, factory))


def test_node_edits_survive_accept_recovery_and_round_snapshot(environment):
    doc = chapter_document()
    changes = [edit('table', '请学生拼图', '先拼图\n再说明依据', row_id='r2', column_index=1),
               edit('table', '操作', '小组记录 | 比较', row_id='r2', column_index=2)]
    seen = []
    def generate(context):
        node = next(f['content'] for f in context.user['fragments'] if f['contributor_type'] == 'current_section')
        seen.append(node)
        return [suggestion(changes)] if node['title'] == '一、教学过程' else []
    with install_provider(environment, generate) as client:
        state = create_document(client, doc)
        state = post(client, state, '/suggestions', target(state))
        nodes = seen[0]['editable_nodes']
        assert next(n['text'] for n in nodes if n.get('row_id') == 'r1' and n.get('column_index') == 1) == '第一行\n第二行 | C:\\new'
        saved = current(state)['suggestions'][0]
        assert saved['edits'] == [{**e, 'item_index': None} for e in changes]
        state = post(client, state, f"/suggestions/{saved['id']}/decision", {'decision': 'ACCEPT'})
        table = state['lesson_plan']['structured_content']['blocks'][3]
        assert table['tableRows'][2] == ['探究', '先拼图\n再说明依据', '小组记录 | 比较', '备注二']
        assert table['teachingProcess']['rows'][1]['teacherActivity'] == '先拼图\n再说明依据'
        assert state['lesson_plan_versions'][0]['structured_content'] == doc
        assert post(client, state, f"/suggestions/{saved['id']}/decision", {'decision': 'ACCEPT'}) == state
        state = post(client, state, '/complete-section', target(state))
        state = finish_round(client, state)
        restored = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
        assert restored == state
        assert restored['lesson_plan_versions'][-1]['structured_content'] == state['lesson_plan']['structured_content']


def test_stale_batch_has_no_partial_write_and_cannot_be_forced_into_append(environment):
    first = [edit('p1', '先观察图形。', '先比较图形。')]
    stale = [edit('table', '请学生拼图', '这部分也不能写入', row_id='r2', column_index=1),
             edit('p1', '先观察图形。', '已经过期')]
    with install_provider(environment, lambda _: [suggestion(first), suggestion(stale)]) as client:
        state = create_document(client, chapter_document())
        state = post(client, state, '/suggestions', target(state))
        a, b = current(state)['suggestions']
        state = post(client, state, f"/suggestions/{a['id']}/decision", {'decision': 'ACCEPT'})
        before = deepcopy(state['lesson_plan'])
        state = post(client, state, f"/suggestions/{b['id']}/decision", {'decision': 'ACCEPT'})
        assert state['lesson_plan'] == before
        assert current(state)['suggestions'][1]['decision'] is None
        assert state['revision_candidate']['can_append'] is False
        post(client, state, f"/suggestions/{b['id']}/decision", {'decision': 'ACCEPT',
            'confirm_append': True, 'expected_version_id': current(state)['version']['id']}, expected=409)
        recovered = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
        assert recovered['lesson_plan'] == before
        assert current(recovered)['suggestions'][1]['decision'] is None


def test_invalid_model_edit_is_retryable_without_saving_suggestions(environment):
    with install_provider(environment, lambda _: [suggestion([
            edit('not-in-chapter', '不存在', '不能新增')])]) as client:
        state = create_document(client, chapter_document())
        response = post(client, state, '/suggestions', target(state), expected=502)
        assert response['code'] == 'INVALID_STRUCTURED_REVISION'
        recovered = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
        assert current(recovered)['suggestions'] == []
        assert current(recovered)['review']['generated_at'] is None
        assert recovered['lesson_plan']['structured_content'] == chapter_document()


def test_original_triangle_chapters_fit_default_context_budget(environment):
    contexts = []
    document = original_document()
    document['schemaVersion'] = '1.1'
    for block in document['blocks']:
        if not block.get('tableRows'):
            continue
        block['rowIds'] = [f"{block['id']}-r{i}" for i in range(len(block['tableRows']))]
        for row in (block.get('teachingProcess') or {}).get('rows', []):
            row['rowId'] = block['rowIds'][row['sourceRowIndex']]
    with install_provider(environment, lambda context: contexts.append(context) or []) as client:
        state = create_document(client, document)
        state = finish_round(client, state)
        assert len(contexts) == 10
        teaching = next(f['content'] for ctx in contexts for f in ctx.user['fragments']
                        if f['contributor_type'] == 'current_section' and f['content']['title'] == '六、教学过程')
        assert any('小组探究\n12分钟' in n['text'] for n in teaching['editable_nodes'])
        assert state['lesson_plan']['structured_content'] == document


def test_legacy_table_without_row_ids_is_not_omitted_from_model_context(environment):
    contexts = []
    with install_provider(environment, lambda context: contexts.append(context) or []) as client:
        state = create_document(client, original_document())
        finish_round(client, state)
        teaching = next(f['content'] for ctx in contexts for f in ctx.user['fragments']
                        if f['contributor_type'] == 'current_section' and f['content']['title'] == '六、教学过程')
        assert '小组探究' in teaching['content']
        assert 'editable_nodes' not in teaching


@pytest.mark.parametrize('address', [
    {'row_id': 'r1'}, {'column_index': 0}, {'row_id': 'r1', 'column_index': True},
    {'row_id': 'r1', 'column_index': 0, 'item_index': 0},
    {'operation': 'insert_after', 'item_index': 0},
])
def test_invalid_address_cannot_be_interpreted_as_another_kind_of_node(address):
    with pytest.raises(ValidationError):
        suggestion([{**edit('table', '原文', '新文'), **address}])


def test_old_suggestion_with_null_edits_still_replaces_original_paragraph(environment):
    legacy = SuggestionDraft(issue='完善观察', reason='原因', pedagogical_basis='教师复核',
        revision_mode='replace', target_text='先观察图形。', revision='先观察再比较。')
    with install_provider(environment, lambda _: [legacy]) as client:
        state = create_document(client, chapter_document())
        state = post(client, state, '/suggestions', target(state))
        saved = current(state)['suggestions'][0]
        with environment[0]() as db:
            row = db.scalar(select(m.Suggestion).where(m.Suggestion.id == saved['id']))
            row.edits = None
            db.commit()
        state = post(client, state, f"/suggestions/{saved['id']}/decision", {'decision': 'ACCEPT'})
        assert state['lesson_plan']['structured_content']['blocks'][2]['text'] == '先观察再比较。'


def test_plaintext_session_cannot_accept_structural_edits(environment):
    from test_workflow import create
    with install_provider(environment, lambda _: [suggestion([edit('p', '原文', '改文')])]) as client:
        state, _ = create(client, '完整原文')
        result = post(client, state, '/suggestions', target(state), expected=502)
        assert result['code'] == 'INVALID_STRUCTURED_REVISION'


def test_append_only_snapshot_rejects_typed_replacement_at_generation(environment):
    environment[1].settings.revision_strategy = 'append_v2'
    with install_provider(environment, lambda _: [suggestion([
            edit('p1', '先观察图形。', '不能覆盖')])]) as client:
        state = create_document(client, chapter_document())
        result = post(client, state, '/suggestions', target(state), expected=502)
        assert result['code'] == 'INVALID_STRUCTURED_REVISION'
        restored = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
        assert restored['lesson_plan']['structured_content'] == chapter_document()
        assert current(restored)['suggestions'] == []


def test_persisted_typed_edit_cannot_bypass_append_only_snapshot(environment):
    with install_provider(environment, lambda _: [suggestion([
            edit('p1', '先观察图形。', '不能覆盖')])]) as client:
        state = create_document(client, chapter_document())
        state = post(client, state, '/suggestions', target(state))
        saved = current(state)['suggestions'][0]
        with environment[0]() as db:
            session = db.get(m.Session, state['session']['id'])
            session.config_snapshot = {**session.config_snapshot, 'revision': 'append_v2'}
            db.commit()
        state = post(client, state, f"/suggestions/{saved['id']}/decision", {'decision': 'ACCEPT'})
        assert state['revision_candidate']['can_append'] is False
        assert state['lesson_plan']['structured_content'] == chapter_document()
        assert current(state)['suggestions'][0]['decision'] is None
