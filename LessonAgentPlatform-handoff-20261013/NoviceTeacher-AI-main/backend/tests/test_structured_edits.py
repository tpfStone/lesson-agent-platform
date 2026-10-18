"""Chapter edits preserve typed nodes and fail atomically on ambiguous mappings."""
from copy import deepcopy

import pytest

from app import structured_adapter as adapter
from app.providers.interfaces import SuggestionDraft


def document():
    return {'schemaVersion': '1.1', 'blocks': [
        {'id': 'h', 'type': 'heading', 'text': '教学过程', 'level': 1},
        {'id': 'p1', 'type': 'paragraph', 'text': '观察三角形。'},
        {'id': 'p2', 'type': 'paragraph', 'text': '拼成平行四边形。'},
        {'id': 'p3', 'type': 'paragraph', 'text': '解释面积关系。'},
        {'id': 'list', 'type': 'list', 'ordered': True, 'items': ['记录底和高', '解释除以二']},
        {'id': 'table', 'type': 'teaching_process', 'rowIds': ['header', 'r1', 'r2'],
         'columnBindings': {'title': 0, 'teacherActivity': 1, 'studentActivity': 2, 'evaluation': 3},
         'tableRows': [['环节', '教师', '学生', '评价'],
                       ['1. 导入\n5分钟', '展示三角形', '观察图形', '检查观察'],
                       ['2. 小组探究\n12分钟', '引导拼图', '动手操作', '检查解释']],
         'teachingProcess': {'rows': [
             {'sourceRowIndex': 1, 'rowId': 'r1', 'title': '导入', 'durationMinutes': 5,
              'teacherActivity': '展示三角形', 'studentActivity': '观察图形', 'evaluation': '检查观察'},
             {'sourceRowIndex': 2, 'rowId': 'r2', 'title': '小组探究', 'durationMinutes': 12,
              'teacherActivity': '引导拼图', 'studentActivity': '动手操作', 'evaluation': '检查解释'}]}},
        {'id': 'next', 'type': 'heading', 'text': '作业设计', 'level': 1},
        {'id': 'homework', 'type': 'paragraph', 'text': '测量身边的三角形。'}]}


def source():
    return {'blockIds': ['h', 'p1', 'p2', 'p3', 'list', 'table']}


def suggestion(**values):
    edits = values.pop('edits', [])
    draft = SuggestionDraft(issue='完善活动', reason='更明确', pedagogical_basis='暂定',
        revision=values.pop('revision', '更新活动'), revision_mode=values.pop('revision_mode', 'replace'),
        **values)
    # The adapter accepts persisted mappings as well as validated model instances.
    return draft.model_copy(update={'edits': edits})


def edit(block_id, expected_text, text, **locator):
    return {'operation': 'replace', 'block_id': block_id, 'expected_text': expected_text,
            'text': text, **locator}


def revise(doc, ref, draft):
    previous = adapter.chapter_content(doc, ref)
    if draft.edits:
        content = previous
    elif draft.revision_mode == 'append':
        from app.providers.defaults import DefaultRevisionStrategy
        content = DefaultRevisionStrategy().apply(previous, draft)
    else:
        content = previous.replace(draft.target_text, draft.revision, 1)
    return adapter.revise(doc, ref, previous, content, draft)


def test_atomic_typed_edits_update_cells_paragraphs_items_and_insert_after_original_anchor():
    doc, ref = document(), source()
    before = deepcopy(doc)
    raw = '先拼图\n再说明 | 底和高 ' + r'C:\new\notes'
    draft = suggestion(edits=[
        edit('table', '引导拼图', raw, row_id='r2', column_index=1),
        edit('table', '动手操作', '合作验证\n记录结果', row_id='r2', column_index=2),
        edit('p1', '观察三角形。', '比较两种三角形。'),
        edit('p2', '拼成平行四边形。', '用两个全等三角形拼图。'),
        edit('p3', '解释面积关系。', '说明面积减半。'),
        edit('list', '解释除以二', '用图解释除以二', item_index=1),
        edit('p2', '拼成平行四边形。', '记录一条发现。', operation='insert_after')])
    updated = revise(doc, ref, draft)
    assert doc == before
    blocks = {block['id']: block for block in updated['blocks']}
    assert blocks['table']['tableRows'][2] == ['2. 小组探究\n12分钟', raw, '合作验证\n记录结果', '检查解释']
    assert blocks['table']['teachingProcess']['rows'][1]['teacherActivity'] == raw
    assert blocks['table']['teachingProcess']['rows'][1]['studentActivity'] == '合作验证\n记录结果'
    assert blocks['table']['rowIds'] == ['header', 'r1', 'r2']
    assert [b['text'] for b in updated['blocks'][1:5]] == [
        '比较两种三角形。', '用两个全等三角形拼图。', '记录一条发现。', '说明面积减半。']
    assert blocks['list']['items'] == ['记录底和高', '用图解释除以二']
    assert updated['blocks'][-2:] == doc['blocks'][-2:]
    assert ref['blockIds'] == ['h', 'p1', 'p2', updated['blocks'][3]['id'], 'p3', 'list', 'table']
    adapter.validate(updated)


@pytest.mark.parametrize('bad_edit,code', [
    (edit('p2', '过期文本', '坏修改'), 'STALE_STRUCTURED_EDIT'),
    (edit('homework', '测量身边的三角形。', '坏修改'), 'EDIT_OUTSIDE_CHAPTER'),
    (edit('table', '引导拼图', '坏修改', row_id='missing', column_index=1), 'INVALID_EDIT_TARGET'),
    (edit('table', '引导拼图', '坏修改', row_id='r2', column_index=20), 'INVALID_EDIT_TARGET'),
    (edit('table', '引导拼图', '坏修改', row_id='r2', column_index=1, operation='insert_after'), 'INVALID_INSERTION_ANCHOR'),
])
def test_invalid_second_edit_keeps_document_and_source_reference_unchanged(bad_edit, code):
    doc, ref = document(), source()
    before, before_ref = deepcopy(doc), deepcopy(ref)
    with pytest.raises(ValueError, match=code):
        revise(doc, ref, suggestion(edits=[
            edit('p1', '观察三角形。', '插入的段落', operation='insert_after'), bad_edit]))
    assert doc == before
    assert ref == before_ref


def test_two_replacements_for_same_node_are_rejected_atomically():
    doc, ref = document(), source()
    before = deepcopy(doc)
    with pytest.raises(ValueError, match='OVERLAPPING_STRUCTURED_EDITS'):
        revise(doc, ref, suggestion(edits=[edit('p1', '观察三角形。', 'A'), edit('p1', '观察三角形。', 'B')]))
    assert doc == before


def test_structured_edits_require_current_chapter_projection():
    doc, ref = document(), source()
    with pytest.raises(ValueError, match='STALE_CHAPTER_CONTENT'):
        adapter.revise(doc, ref, '过期章节', '', suggestion(edits=[edit('p1', '观察三角形。', '新段落')]))


def test_editable_nodes_use_full_raw_text_and_stable_row_ids_within_chapter():
    doc, ref = document(), source()
    doc['blocks'][1]['text'] = '  原样保留\n' + r'C:\notes' + '  '
    nodes = adapter.editable_nodes(doc, ref)
    assert next(n for n in nodes if n['block_id'] == 'p1')['text'] == '  原样保留\n' + r'C:\notes' + '  '
    assert next(n for n in nodes if n.get('row_id') == 'r2' and n.get('column_index') == 0)['text'] == '2. 小组探究\n12分钟'
    assert next(n for n in nodes if n['block_id'] == 'list' and n.get('item_index') == 1)['text'] == '解释除以二'
    assert not any(n['block_id'] in ('next', 'homework') for n in nodes)


def test_typed_cell_rejects_complete_serialized_row():
    doc, ref = document(), source()
    with pytest.raises(ValueError, match='SERIALIZED_ROW_IN_CELL'):
        revise(doc, ref, suggestion(edits=[edit('table', '引导拼图',
            r'2. 小组探究\n12分钟 | 新教师 | 新学生 | 新评价', row_id='r2', column_index=1)]))


@pytest.mark.parametrize('text', [
    '2. 小组探究\n15分钟 | 新教师 | 新学生 | 新评价',
    r'2. 小组探究\n15分钟 | 新教师 | 新学生 | 新评价',
    '2. 新的探究\n15分钟 | 新教师 | 新学生 | 新评价',
    '2. 小组探究\n15分钟 | 新教师 | 新学生',
    r'2. 小组探究\n15分钟 | 新教师 | 新学生 | 新评价 | 多一列',
    '2. 新的探究\n15分钟 | 新教师 | 新学生',
    '2. 新的探究\n15分钟 | 新教师 | 新学生 | 新评价 | 多一列',
    '2. 小组探究\n15分钟 | 新教师',
])
def test_typed_cell_rejects_serialized_row_even_when_title_or_duration_changed(text):
    doc, ref = document(), source()
    previous = adapter.chapter_content(doc, ref)
    with pytest.raises(ValueError, match='SERIALIZED_ROW_IN_CELL'):
        adapter.revise(doc, ref, previous, previous, suggestion(edits=[
            edit('table', '引导拼图', text, row_id='r2', column_index=1)]))


def test_typed_cell_keeps_genuine_multiple_pipes_and_backslashes_as_raw_text():
    doc, ref = document(), source()
    raw = '比较 A | B | C | D\n保留路径 ' + r'C:\new\notes 和 \|'
    updated = revise(doc, ref, suggestion(edits=[
        edit('table', '引导拼图', raw, row_id='r2', column_index=1)]))
    assert updated['blocks'][5]['tableRows'][2][1] == raw


def test_typed_insertion_cannot_reintroduce_serialized_table_row_as_paragraph():
    doc, ref = document(), source()
    with pytest.raises(ValueError, match='TABLE_EDIT_REQUIRES_TARGET'):
        revise(doc, ref, suggestion(edits=[edit('p1', '观察三角形。',
            r'2. 小组探究\n12分钟 | 新教师 | 新学生 | 新评价', operation='insert_after')]))


@pytest.mark.parametrize('target', ['引导拼图', '引导拼图 | 动手操作 | 检查解释',
    r'2. 小组探究\n12分钟 | 引导拼图 | 动手操作 | 检查解释'])
def test_legacy_complete_row_revision_maps_to_anchored_row_and_decodes_projection_once(target):
    doc, ref = document(), source()
    before = deepcopy(doc)
    revision = r'2. 小组探究\n12分钟 | 先剪拼\n再验证 \| C:\\new | 合作操作 | 记录证据'
    updated = revise(doc, ref, suggestion(target_text=target, revision=revision))
    table = updated['blocks'][5]
    assert table['tableRows'][2] == ['2. 小组探究\n12分钟', '先剪拼\n再验证 | ' + r'C:\new', '合作操作', '记录证据']
    assert table['tableRows'][:2] == doc['blocks'][5]['tableRows'][:2]
    assert table['teachingProcess']['rows'][1]['teacherActivity'] == '先剪拼\n再验证 | ' + r'C:\new'
    assert doc == before


def test_legacy_v1_table_refuses_row_reconstruction_without_an_explicit_encoding():
    doc, ref = document(), source()
    doc['schemaVersion'] = '1.0'
    doc['blocks'][5].pop('rowIds')
    for row in doc['blocks'][5]['teachingProcess']['rows']:
        row.pop('rowId')
    before = deepcopy(doc)
    with pytest.raises(ValueError, match='LEGACY_ROW_ENCODING_AMBIGUOUS'):
        revise(doc, ref, suggestion(target_text='引导拼图',
            revision=r'2. 小组探究\n12分钟 | 引导剪拼\n解释公式 | 合作操作 | 记录解释'))
    assert doc == before


def test_legacy_v1_raw_backslash_cannot_be_misread_as_an_escape():
    doc = {'schemaVersion': '1.0', 'blocks': [
        {'id': 'h', 'type': 'heading', 'level': 1, 'text': '教学过程'},
        {'id': 't', 'type': 'table', 'tableRows': [['stage', 'teacher'], ['start', 'old']]}]}
    ref = {'blockIds': ['h', 't']}
    before = deepcopy(doc)
    with pytest.raises(ValueError, match='LEGACY_ROW_ENCODING_AMBIGUOUS'):
        revise(doc, ref, suggestion(target_text='old', revision=r'start | C:\new'))
    assert doc == before


@pytest.mark.parametrize('revision', [
    r'1. 导入\n5分钟 | 新教师 | 新学生 | 新评价',
    r'2. 小组探究\n12分钟 | 新教师 | 新学生',
    r'2. 小组探究\n12分钟 | 新教师 | 新学生 | 新评价 | 多一格'])
def test_legacy_mismatched_row_label_or_column_count_never_enters_single_cell(revision):
    doc, ref = document(), source()
    before = deepcopy(doc)
    with pytest.raises(ValueError, match='INVALID_ROW_REPLACEMENT'):
        revise(doc, ref, suggestion(target_text='引导拼图', revision=revision))
    assert doc == before


def test_legacy_single_cell_raw_newlines_and_backslashes_are_not_projection_decoded():
    doc, ref = document(), source()
    raw = '真实换行\n保留 ' + r'\n 和 C:\new 以及 \|'
    updated = revise(doc, ref, suggestion(target_text='引导拼图', revision=raw))
    assert updated['blocks'][5]['tableRows'][2][1] == raw


@pytest.mark.parametrize('revision,expected', [
    ('新观察。\n\n新拼图。\n\n新解释。\n\n增加验证。', ['新观察。', '新拼图。', '新解释。', '增加验证。']),
    ('合并观察和拼图。\n\n解释面积。', ['合并观察和拼图。', '解释面积。'])])
def test_legacy_full_contiguous_paragraph_target_keeps_blocks_and_updates_chapter_ids(revision, expected):
    doc, ref = document(), source()
    target = '观察三角形。\n\n拼成平行四边形。\n\n解释面积关系。'
    updated = revise(doc, ref, suggestion(target_text=target, revision=revision))
    paragraphs = updated['blocks'][1:1 + len(expected)]
    assert [b['text'] for b in paragraphs] == expected
    assert [b['id'] for b in paragraphs[:min(3, len(expected))]] == ['p1', 'p2', 'p3'][:len(expected)]
    assert all(b['type'] == 'paragraph' for b in paragraphs)
    assert updated['blocks'][-4:] == doc['blocks'][-4:]
    assert ref['blockIds'] == [b['id'] for b in updated['blocks'][:-2]]


def test_legacy_partial_cross_paragraph_edit_stays_unsupported():
    doc, ref = document(), source()
    with pytest.raises(ValueError, match='UNMAPPABLE_SECTION_EDIT'):
        revise(doc, ref, suggestion(target_text='三角形。\n\n拼成平行四边形。', revision='新活动。'))


@pytest.mark.parametrize('target', [None, '引导拼图'])
def test_legacy_append_of_serialized_existing_table_row_is_rejected(target):
    doc, ref = document(), source()
    before, before_ref = deepcopy(doc), deepcopy(ref)
    with pytest.raises(ValueError, match='TABLE_EDIT_REQUIRES_TARGET'):
        revise(doc, ref, suggestion(target_text=target, revision_mode='append',
            revision=r'2. 小组探究\n12分钟 | 新教师 | 新学生 | 新评价'))
    assert doc == before
    assert ref == before_ref


def test_independent_append_and_unsupported_cross_cell_replacement_keep_legacy_contract():
    doc, ref = document(), source()
    updated = revise(doc, ref, suggestion(revision_mode='append', revision='课后收集学生的疑问。'))
    assert updated['blocks'][6]['text'] == '课后收集学生的疑问。'
    assert updated['blocks'][5] == doc['blocks'][5]
    assert updated['blocks'][-2:] == doc['blocks'][-2:]
    with pytest.raises(ValueError, match='UNMAPPABLE_SECTION_EDIT'):
        revise(doc, source(), suggestion(target_text='引导拼图 | 动手操作', revision='组织合作拼图'))
