from uuid import uuid4
import pytest


@pytest.mark.parametrize('teacher,expected', [
    ('讲' * 61, '教学环节 1'), ('(5分钟)', '教学环节 1'),
    ('1. 导入（5分钟） / 补充\r\n后续', '导入'), ('\x00 2. 比较： ', '比较'),
])
def test_revision_uses_java_title_derivation(teacher, expected):
    from app import structured_adapter as adapter
    normalized = teacher.replace('\r\n', '\n').replace('\x00', '').strip()
    doc = {'schemaVersion': '1.1', 'blocks': [{'id': 'b', 'type': 'teaching_process',
        'tableRows': [['教师活动', '学生活动'], [teacher, '回答']], 'rowIds': ['h', 'r'],
        'columnBindings': {'teacherActivity': 0, 'studentActivity': 1},
        'teachingProcess': {'headers': ['教师活动', '学生活动'], 'rows': [{
            'sourceRowIndex': 1, 'rowId': 'r', 'title': expected,
            'durationMinutes': 5 if '5分钟' in teacher else None,
            'teacherActivity': normalized, 'studentActivity': '回答', 'evaluation': '', 'extraCells': []}]}}]}
    updated = adapter.revise(doc, {'blockId': 'b', 'sourceRowIndex': 1, 'rowId': 'r'}, '',
                             f'[1] {teacher}\n[2] 修改后的回答')
    row = updated['blocks'][0]['teachingProcess']['rows'][0]
    assert row['title'] == expected
    assert row['teacherActivity'] == normalized
    assert row['studentActivity'] == '修改后的回答'


def document():
    return {"schemaVersion": "1.1", "blocks": [
        {"id": "objectives", "type": "paragraph", "text": "教学目标：理解面积"},
        {"id": "process", "type": "table", "tableRows": [["环节", "教师", "学生", "评价"]] +
         [[f"环节{i}", f"提问{i}", f"回答{i}", f"评价{i}"] for i in range(6)],
         "rowIds": [f"r{i}" for i in range(7)], "columnBindings": {"title": 0, "teacherActivity": 1, "studentActivity": 2, "evaluation": 3}},
        {"id": "homework", "type": "paragraph", "text": "作业：测量三角形"}]}


def test_structured_session_preserves_full_coverage(client):
    doc = document()
    # Projection is independently specified, including the original table header.
    content = "教学目标：理解面积\n\n环节 | 教师 | 学生 | 评价\n" + "\n".join(
        f"环节{i} | 提问{i} | 回答{i} | 评价{i}" for i in range(6)) + "\n\n作业：测量三角形"
    response = client.post('/api/sessions', json={"request_key": str(uuid4()),
        "metadata": {"subject": "数学", "grade": "五", "topic": "面积"}, "content": content,
        "structured_content": doc})
    assert response.status_code == 200
    state = response.json()
    assert len(state['sections']) == 8
    assert state['lesson_plan']['structured_content']['blocks'][1]['tableRows'][6][2] == '回答5'
    assert state['lesson_plan']['current_content'] == content
    assert len([s for s in state['sections'] if s['source_ref'].get('rowId')]) == 6


def test_unknown_schema_is_explicit(client):
    doc = document(); doc['schemaVersion'] = '2.0'
    response = client.post('/api/sessions', json={"request_key": str(uuid4()),
        "metadata": {"subject": "数学", "grade": "五", "topic": "面积"}, "content": "正文",
        "structured_content": doc})
    assert response.status_code == 422


def test_unmappable_edit_preserves_header_other_cells_and_followup_edits():
    from app import structured_adapter as adapter
    doc = document()
    doc['blocks'][1]['tableRows'][0].append('资源')
    for row in doc['blocks'][1]['tableRows'][1:]:
        row.append('保留的资源')
    state = adapter.fallback_edit(None, doc, {'blockId': 'process', 'sourceRowIndex': 2}, '确认的新内容')
    state = adapter.fallback_edit(state, None, {'blockId': 'homework'}, '新作业')
    text = adapter.project_fallback(state)
    assert '环节 | 教师 | 学生 | 评价 | 资源' in text
    assert '环节0 | 提问0 | 回答0 | 评价0 | 保留的资源' in text
    assert '环节1 |' not in text
    assert '确认的新内容' in text and '新作业' in text
    assert '教学目标：理解面积' in text and '回答5' in text
