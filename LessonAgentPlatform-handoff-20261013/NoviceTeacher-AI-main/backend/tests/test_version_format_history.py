"""Version display must use its own immutable source, never the latest structure."""
from copy import deepcopy
from uuid import uuid4

from sqlalchemy import select
from app import models as m, structured_adapter as adapter
from test_structured_lifecycle import document
from test_workflow import finish_round


def create_structured(client):
    doc = document()
    doc['blocks'][1]['tableRows'][1][1] = '第一行\n第二行 | ' + r'C:\new'
    response = client.post('/api/sessions', json={
        'request_key': str(uuid4()),
        'metadata': {'subject': '数学', 'grade': '五', 'topic': '面积'},
        'content': adapter.project(doc), 'structured_content': doc})
    assert response.status_code == 200, response.text
    return response.json(), doc


def test_initial_and_completed_versions_keep_own_structure_after_restart(client, environment):
    state, doc = create_structured(client)
    original = deepcopy(state['lesson_plan_versions'][0])
    assert original['structured_content'] == doc
    assert original['content_format'] == 'LESSON_DOCUMENT'
    assert original['format_provenance'] == 'SAVED_VERSION'
    state = finish_round(client, state, ('REJECT', 'REJECT'))
    assert state['lesson_plan_versions'][1]['structured_content'] == doc
    sessions, _ = environment
    # The latest mutable projection changes later; old snapshots must not follow it.
    with sessions.begin() as db:
        lesson = db.scalar(select(m.LessonPlan).where(m.LessonPlan.session_id == state['session']['id']))
        changed = deepcopy(lesson.structured_content)
        changed['blocks'][0]['text'] = '后续轮次新目标'
        lesson.structured_content = changed
        lesson.current_content = adapter.project(changed)
    restored = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
    assert restored['lesson_plan_versions'][0] == original
    assert restored['lesson_plan_versions'][1]['structured_content'] == doc
    with sessions() as db:
        versions = db.scalars(select(m.LessonPlanVersion).order_by(m.LessonPlanVersion.round_number)).all()
        assert [v.structured_content for v in versions] == [doc, doc]


def test_legacy_history_only_uses_same_lesson_exact_matching_structure(client, environment):
    state, doc = create_structured(client)
    sessions, _ = environment
    with sessions.begin() as db:
        original = db.scalar(select(m.LessonPlanVersion))
        original.structured_content = None
        original.content_format = None
        original.format_provenance = None
    restored = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()
    proved = restored['lesson_plan_versions'][0]
    assert proved['structured_content'] == doc
    assert proved['format_provenance'] == 'VERIFIED_CURRENT_MATCH'
    assert len(proved['content_sha256']) == 64
    assert proved['structure_sha256'] == adapter.digest(doc)
    with sessions.begin() as db:
        lesson = db.scalar(select(m.LessonPlan))
        changed = deepcopy(lesson.structured_content)
        changed['blocks'][0]['text'] = '不属于旧版的目标'
        lesson.structured_content = changed
        lesson.current_content = adapter.project(changed)
    unknown = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()['lesson_plan_versions'][0]
    assert unknown['structured_content'] is None
    assert unknown['content_format'] == 'UNKNOWN'
    assert unknown['content'] == adapter.project(doc)
    with sessions() as db:
        assert db.scalar(select(m.LessonPlanVersion)).content_format is None  # Read does not rewrite history.


def test_mixed_fallback_history_keeps_literal_edits_and_own_warnings(client, environment):
    state, doc = create_structured(client)
    sessions, _ = environment
    # Seed the actual unmappable-revision representation before round completion.
    fallback = adapter.fallback_edit(None, doc, {'blockId': 'process', 'sourceRowIndex': 1}, r'保留字面量 C:\new | 原文')
    with sessions.begin() as db:
        lesson = db.scalar(select(m.LessonPlan))
        lesson.structured_content = None
        lesson.fallback_projection = fallback
        lesson.current_content = adapter.project_fallback(fallback)
        lesson.structure_warnings = ['TEXT_FALLBACK: revision cannot map to cells']
    state = finish_round(client, state, ('REJECT', 'REJECT'))
    latest = state['lesson_plan_versions'][-1]
    assert latest['structured_content'] is None
    assert latest['content_format'] == 'MIXED_FALLBACK'
    assert latest['structure_warnings']
    assert latest['content'] == state['lesson_plan']['current_content']
    assert r'保留字面量 C:\new | 原文' in latest['content']


def test_equal_projection_without_original_structure_identity_stays_unknown(client, environment):
    state, doc = create_structured(client)
    sessions, _ = environment
    with sessions.begin() as db:
        version = db.scalar(select(m.LessonPlanVersion))
        version.structured_content = None
        version.content_format = None
        version.format_provenance = None
        lesson = db.scalar(select(m.LessonPlan))
        # A paragraph containing pipes projects identically to the original table.
        # Text equality alone cannot prove that the history had this new structure.
        lesson.structured_content = {'schemaVersion': '1.1', 'blocks': [
            {'id': 'different', 'type': 'paragraph', 'text': adapter.project(doc)}]}
    version = client.get(f"/api/sessions/{state['session']['id']}/current-state").json()['lesson_plan_versions'][0]
    assert version['structured_content'] is None
    assert version['content_format'] == 'UNKNOWN'


def test_legacy_completed_round_requires_unbroken_audit_checkpoint(client, environment):
    state, doc = create_structured(client)
    state = finish_round(client, state, ('REJECT', 'REJECT'))
    sessions, _ = environment
    with sessions.begin() as db:
        version = db.scalar(select(m.LessonPlanVersion).where(m.LessonPlanVersion.round_number == 1))
        version.structured_content = None
        version.content_format = None
        version.format_provenance = None
    path = f"/api/sessions/{state['session']['id']}/current-state"
    proved = client.get(path).json()['lesson_plan_versions'][1]
    assert proved['structured_content'] == doc
    assert proved['format_provenance'] == 'VERIFIED_CURRENT_MATCH'
    with sessions.begin() as db:
        session = db.scalar(select(m.Session))
        sequence = max(db.scalars(select(m.InteractionEvent.sequence))) + 1
        db.add(m.InteractionEvent(user_id=session.user_id, participant_id=session.participant_id,
            session_id=session.id, sequence=sequence, event_type='SECTION_UPDATED', event_payload={}))
    # Even if later editing ends with identical text, the old structure is unproven.
    assert client.get(path).json()['lesson_plan_versions'][1]['content_format'] == 'UNKNOWN'
