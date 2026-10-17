"""Teachers review whole chapters; accepted edits retain authoritative document structure."""
from copy import deepcopy
from dataclasses import replace
import json
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient
import pytest

from app import structured_adapter as adapter
from app.api import create_app
from app.providers.interfaces import SectionDraft, SuggestionDraft
from test_workflow import current, target, post, finish_round, create


def original_document():
    path = Path(__file__).resolve().parents[3] / 'Lessongen-main/web/src/test/resources/fixtures/original-v0-structure.json'
    return json.loads(path.read_text(encoding='utf-8'))


def create_document(client, doc):
    response = client.post('/api/sessions', json={
        'request_key': str(uuid4()), 'metadata': {'subject': '数学', 'grade': '五', 'topic': '面积'},
        'content': adapter.project(doc), 'structured_content': doc})
    assert response.status_code == 200, response.text
    return response.json()


def chapter_document():
    return {'schemaVersion': '1.1', 'blocks': [
        {'id': 'intro', 'type': 'paragraph', 'text': '五年级 · 40分钟'},
        {'id': 'h1', 'type': 'heading', 'level': 1, 'text': '一、教学过程'},
        {'id': 'p1', 'type': 'paragraph', 'text': '先观察图形。'},
        {'id': 'table', 'type': 'teaching_process', 'rowIds': ['header', 'r1', 'r2'],
         'columnBindings': {'title': 0, 'teacherActivity': 1, 'studentActivity': 2},
         'tableRows': [['环节', '教师', '学生', '备注'],
                       ['导入', '第一行\n第二行 | ' + r'C:\new', '观察', '备注一'],
                       ['探究', '请学生拼图', '操作', '备注二']],
         'teachingProcess': {'rows': [
             {'sourceRowIndex': 1, 'rowId': 'r1', 'title': '导入',
              'teacherActivity': '第一行\n第二行 | ' + r'C:\new', 'studentActivity': '观察', 'evaluation': ''},
             {'sourceRowIndex': 2, 'rowId': 'r2', 'title': '探究',
              'teacherActivity': '请学生拼图', 'studentActivity': '操作', 'evaluation': ''}]}},
        {'id': 'h2', 'type': 'heading', 'level': 1, 'text': '二、作业设计'},
        {'id': 'list', 'type': 'list', 'ordered': False, 'items': ['测量面积', '解释公式']}]}


def test_real_43_unit_lesson_becomes_ten_chapters_without_losing_content():
    doc = original_document()
    before = deepcopy(doc)
    units = adapter.sections(doc, adapter.project(doc))
    assert len(units) == 10
    assert [draft.title for draft, _ in units] == [
        '一、教材与内容分析', '二、学情分析', '三、教学目标', '四、教学重点与难点',
        '五、教学资源', '六、教学过程', '七、形成性评价设计', '八、作业设计',
        '九、板书设计', '十、教学后反思预设']
    assert units[2][0].section_type == 'objectives'
    assert units[5][0].section_type == 'teaching_process'
    assert units[5][1]['blockIds'] == ['b23', 'b24']
    assert '评价与证据' in units[5][0].content
    assert '6. 总结与作业' in units[5][0].content
    assert [key for _, ref in units for key in ref['blockIds']] == [f'b{i}' for i in range(5, 39)]
    assert doc == before


def test_nested_headings_and_document_title_do_not_create_extra_units():
    doc = {'schemaVersion': '1.1', 'blocks': [
        {'id': 'title', 'type': 'heading', 'level': 1, 'text': '三角形的面积教学设计'},
        {'id': 'meta', 'type': 'paragraph', 'text': '五年级'},
        {'id': 'a', 'type': 'heading', 'level': 2, 'text': '一、教学目标'},
        {'id': 'b', 'type': 'heading', 'level': 3, 'text': '知识与技能'},
        {'id': 'c', 'type': 'paragraph', 'text': '理解公式'},
        {'id': 'd', 'type': 'heading', 'level': 2, 'text': '二、教学过程'},
        {'id': 'e', 'type': 'heading', 'level': 3, 'text': '导入'},
        {'id': 'f', 'type': 'paragraph', 'text': '观察图形'}]}
    units = adapter.sections(doc, adapter.project(doc))
    assert [ref['blockIds'] for _, ref in units] == [['a', 'b', 'c'], ['d', 'e', 'f']]


@pytest.mark.parametrize('leading_blank', [False, True])
def test_single_document_heading_above_sibling_chapters_is_context(leading_blank):
    doc = {'schemaVersion': '1.1', 'blocks': [
        {'id': 'title', 'type': 'heading', 'level': 1, 'text': '三角形的面积'},
        {'id': 'a', 'type': 'heading', 'level': 2, 'text': '教学目标'},
        {'id': 'b', 'type': 'paragraph', 'text': '理解公式'},
        {'id': 'c', 'type': 'heading', 'level': 2, 'text': '教学过程'},
        {'id': 'd', 'type': 'paragraph', 'text': '讨论规律'}]}
    if leading_blank:
        doc['blocks'].insert(0, {'id': 'empty', 'type': 'paragraph', 'text': ''})
    assert [ref['blockIds'] for _, ref in adapter.sections(doc, adapter.project(doc))] == [['a', 'b'], ['c', 'd']]


def test_plaintext_title_after_leading_whitespace_does_not_absorb_chapters(client):
    text = '\n# 面积\n## 一、教学目标\n理解面积\n## 二、教学过程\n观察图形'
    state, _ = create(client, text)
    assert [s['title'] for s in state['sections']] == ['教案概述', '一、教学目标', '二、教学过程']
    assert ''.join(s['current_content'] for s in state['sections']) == text


def test_inferred_teaching_steps_belong_to_main_chapter(client):
    text = '一、教学目标\n理解面积\n二、教学过程\n导入\n观察\n新知探究\n拼图\n三、作业设计\n测量'
    state, _ = create(client, text)
    assert [s['title'] for s in state['sections']] == ['一、教学目标', '二、教学过程', '三、作业设计']
    assert '导入\n观察\n新知探究\n拼图' in state['sections'][1]['current_content']


def test_text_heading_fallback_keeps_numbered_exercises_and_subheadings_together():
    texts = ['面积教学设计', '一、教材与内容分析', '认识面积。', '二、教学过程',
             '（一）导入', '1. 计算三角形的面积。', '2. 解释计算过程。', '三、作业设计', '测量课桌。']
    doc = {'schemaVersion': '1.1', 'blocks': [
        {'id': str(i), 'type': 'paragraph', 'text': text} for i, text in enumerate(texts)]}
    units = adapter.sections(doc, adapter.project(doc))
    assert [draft.title for draft, _ in units] == ['一、教材与内容分析', '二、教学过程', '三、作业设计']
    assert units[1][1]['blockIds'] == ['3', '4', '5', '6']


def test_inferred_chapter_owns_its_teaching_table():
    doc = chapter_document()
    for block in doc['blocks']:
        if block['type'] == 'heading':
            block['type'] = 'paragraph'
            block.pop('level')
    units = adapter.sections(doc, adapter.project(doc))
    assert [ref['blockIds'] for _, ref in units] == [['h1', 'p1', 'table'], ['h2', 'list']]


def test_structure_without_headings_stays_one_unit_and_empty_input_is_rejected(client):
    doc = {'schemaVersion': '1.1', 'blocks': [
        {'id': 'a', 'type': 'paragraph', 'text': '观察图形。'},
        {'id': 'b', 'type': 'paragraph', 'text': '讨论规律。'}]}
    assert len(adapter.sections(doc, adapter.project(doc))) == 1
    response = client.post('/api/sessions', json={
        'request_key': str(uuid4()), 'metadata': {'subject': '数学', 'grade': '五', 'topic': '面积'},
        'content': '', 'structured_content': {'schemaVersion': '1.1', 'blocks': []}})
    assert response.status_code == 422


def test_plaintext_new_sessions_group_main_headings_and_keep_exact_source(client):
    text = '# 面积教学设计\r\n五年级\r\n## 一、教学目标\r\n理解公式\r\n### 知识与技能\r\n解释关系\r\n## 二、教学过程\r\n### 导入\r\n观察图形\r\n'
    state, _ = create(client, text)
    assert [s['title'] for s in state['sections']] == ['教案概述', '一、教学目标', '二、教学过程']
    assert ''.join(s['current_content'] for s in state['sections']) == text


def test_whole_chapter_round_completion_and_recovery_preserve_original(client):
    doc = original_document()
    state = create_document(client, doc)
    assert len(state['sections']) == 10
    state = finish_round(client, state, ('REJECT',))
    assert state['session']['status'] == 'ROUND_COMPLETED'
    assert len([s for s in state['sections'] if s['review']['completed_at']]) == 10
    assert state['lesson_plan']['structured_content'] == doc
    assert state['lesson_plan']['current_content'] == adapter.project(doc)
    restored = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
    assert restored == state
    assert restored['lesson_plan_versions'][-1]['structured_content'] == doc


@pytest.mark.parametrize('target_text,revision,block_id,field', [
    ('先观察图形。', '先观察并比较图形。', 'p1', 'text'),
    (r'第一行\n第二行 \| C:\\new', '用剪拼法\n解释底和高 | ' + r'C:\area', 'table', 'cell'),
    (r'第一行\n第二行 \| C:\\new', '任务清单\n[2] 解释底和高', 'table', 'cell'),
    ('测量面积', '测量并记录面积', 'list', 'item'),
])
def test_accepted_chapter_replacement_preserves_unrelated_cells_and_history(
        environment, target_text, revision, block_id, field):
    sessions, factory = environment
    build = factory.build
    class Provider:
        def generate(self, context):
            return [SuggestionDraft(issue='具体化活动', reason='可观察', pedagogical_basis='暂定',
                revision_mode='replace', target_text=target_text, revision=revision)]
    factory.build = lambda snapshot, history: replace(build(snapshot, history), suggestion_provider=Provider())
    with TestClient(create_app(sessions, factory)) as client:
        doc = chapter_document()
        if field == 'item':
            doc['blocks'] = doc['blocks'][-2:]
        state = create_document(client, doc)
        state = post(client, state, '/suggestions', target(state))
        suggestion = current(state)['suggestions'][0]
        state = post(client, state, f"/suggestions/{suggestion['id']}/decision", {'decision': 'ACCEPT'})
        updated = state['lesson_plan']['structured_content']
        assert updated is not None
        expected = deepcopy(doc)
        block = next(b for b in expected['blocks'] if b['id'] == block_id)
        if field == 'text': block['text'] = revision
        elif field == 'item': block['items'][0] = revision
        else:
            block['tableRows'][1][1] = revision
            block['teachingProcess']['rows'][0]['teacherActivity'] = revision
            block['teachingProcess']['rows'][0].update(durationMinutes=None, extraCells=['备注一'])
        assert updated == expected
        assert state['lesson_plan']['current_content'] == adapter.project(expected)
        assert state['lesson_plan_versions'][0]['structured_content'] == doc
        source_ids = current(state)['source_ref']['blockIds']
        assert current(state)['current_content'] == adapter.project({**updated,
            'blocks': [b for b in updated['blocks'] if b['id'] in source_ids]})
        assert post(client, state, f"/suggestions/{suggestion['id']}/decision", {'decision': 'ACCEPT'}) == state


def test_append_to_table_chapter_keeps_table_and_places_new_paragraph_before_next_heading(client):
    doc = chapter_document()
    state = create_document(client, doc)
    state = post(client, state, '/suggestions', target(state))
    for suggestion in current(state)['suggestions']:
        state = post(client, state, f"/suggestions/{suggestion['id']}/decision", {'decision': 'ACCEPT'})
    updated = state['lesson_plan']['structured_content']
    assert updated is not None
    assert [b['id'] for b in updated['blocks'][:4]] == ['intro', 'h1', 'p1', 'table']
    assert updated['blocks'][3] == doc['blocks'][3]
    assert updated['blocks'][-2:] == doc['blocks'][-2:]
    assert len(updated['blocks']) == 8
    assert current(state)['source_ref']['blockIds'][-2:] == [b['id'] for b in updated['blocks'][4:6]]
    state = post(client, state, '/complete-section', target(state))
    assert current(state)['title'] == '二、作业设计'


def test_cross_cell_replacement_requires_confirmation_and_preserves_table(environment):
    sessions, factory = environment
    build = factory.build
    class Provider:
        def generate(self, context):
            return [SuggestionDraft(issue='完善活动', reason='原因', pedagogical_basis='暂定',
                revision_mode='replace', target_text='请学生拼图 | 操作', revision='组织合作拼图')]
    factory.build = lambda snapshot, history: replace(build(snapshot, history), suggestion_provider=Provider())
    with TestClient(create_app(sessions, factory)) as client:
        doc = chapter_document()
        state = create_document(client, doc)
        state = post(client, state, '/suggestions', target(state))
        suggestion = current(state)['suggestions'][0]
        state = post(client, state, f"/suggestions/{suggestion['id']}/decision", {'decision': 'ACCEPT'})
        assert current(state)['suggestions'][0]['decision'] is None
        assert state['lesson_plan']['structured_content'] == doc
        candidate = state['revision_candidate']
        state = post(client, state, f"/suggestions/{suggestion['id']}/decision", {
            'decision': 'ACCEPT', 'confirm_append': True, 'expected_version_id': candidate['expected_version_id']})
        assert state['lesson_plan']['structured_content']['blocks'][3] == doc['blocks'][3]
        assert current(state)['suggestions'][0]['decision']['decision'] == 'ACCEPT'
        assert '组织合作拼图' in current(state)['current_content']


def test_chapters_honor_append_only_revision_configuration(environment):
    sessions, factory = environment
    factory.settings.revision_strategy = 'append_v2'
    build = factory.build
    class Provider:
        def generate(self, context):
            return [SuggestionDraft(issue='完善观察', reason='原因', pedagogical_basis='暂定',
                revision_mode='replace', target_text='先观察图形。', revision='比较图形并说明理由。')]
    factory.build = lambda snapshot, history: replace(build(snapshot, history), suggestion_provider=Provider())
    with TestClient(create_app(sessions, factory)) as client:
        doc = chapter_document()
        state = create_document(client, doc)
        state = post(client, state, '/suggestions', target(state))
        suggestion = current(state)['suggestions'][0]
        state = post(client, state, f"/suggestions/{suggestion['id']}/decision", {'decision': 'ACCEPT'})
        updated = state['lesson_plan']['structured_content']
        assert updated['blocks'][:4] == doc['blocks'][:4]
        assert updated['blocks'][4]['text'] == '比较图形并说明理由。'
        assert updated['blocks'][5:] == doc['blocks'][4:]


def test_saved_legacy_row_session_is_not_regrouped_on_resume(client, monkeypatch):
    doc = chapter_document()
    row_content = '[1] 探究\n[2] 请学生拼图\n[3] 操作\n[4] 备注二'
    with monkeypatch.context() as patch:
        patch.setattr(adapter, 'sections', lambda document, content: [
            (SectionDraft(title='探究', content=row_content, section_type='teaching_process'),
             {'blockId': 'table', 'rowId': 'r2', 'sourceRowIndex': 2})])
        state = create_document(client, doc)
    restored = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
    assert restored['sections'] == state['sections']
    restored = finish_round(client, restored, ('REJECT',))
    restored = post(client, restored, f"/rounds/{restored['round']['id']}/continue")
    assert len(restored['sections']) == 1
    assert current(restored)['source_ref']['rowId'] == 'r2'


@pytest.mark.parametrize('target_text,revision', [('教师', '教师引导'), ('课后记录', '保留反思记录')])
def test_table_chapter_with_empty_edge_cells_keeps_exact_edit_location(environment, target_text, revision):
    sessions, factory = environment
    build = factory.build
    class Provider:
        def generate(self, context):
            return [SuggestionDraft(issue='修改表头', reason='原因', pedagogical_basis='暂定',
                revision_mode='replace', target_text=target_text, revision=revision)]
    factory.build = lambda snapshot, history: replace(build(snapshot, history), suggestion_provider=Provider())
    with TestClient(create_app(sessions, factory)) as client:
        doc = {'schemaVersion': '1.1', 'blocks': [
            {'id': 'h', 'type': 'heading', 'level': 1, 'text': '教学过程'},
            {'id': 't', 'type': 'table', 'rowIds': ['h', 'r'],
             'tableRows': [['', '教师', ''], ['导入', '提问', '']]},
            {'id': 'p', 'type': 'paragraph', 'text': '课后记录'}]}
        state = create_document(client, doc)
        state = post(client, state, '/suggestions', target(state))
        suggestion = current(state)['suggestions'][0]
        state = post(client, state, f"/suggestions/{suggestion['id']}/decision", {'decision': 'ACCEPT'})
        assert current(state)['suggestions'][0]['decision']['decision'] == 'ACCEPT'
        expected = deepcopy(doc)
        if target_text == '教师': expected['blocks'][1]['tableRows'][0][1] = revision
        else: expected['blocks'][2]['text'] = revision
        assert state['lesson_plan']['structured_content'] == expected
