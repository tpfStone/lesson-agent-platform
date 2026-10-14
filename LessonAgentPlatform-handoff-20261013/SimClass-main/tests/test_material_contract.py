from app.contracts.session import MaterialInput
from app.web.service import ClassroomService as SessionService


def test_structured_row_survives_runtime_and_formal_result():
    material = MaterialInput.model_validate({
        "materialId": "row-1", "title": "导入", "teachingScript": "教师：提问\n学生：回答\n评价：诊断",
        "durationMinutes": 5, "teacherActivity": "提问", "studentActivity": "回答", "evaluation": "诊断",
        "sourceRef": {"lessonId": "l", "versionId": "v", "blockId": "b", "rowId": "r"},
    }).to_classroom_material()
    assert material["duration_minutes"] == 5
    assert material["teacher_activity"] == "提问"
    assert material["student_task"] == "回答"
    assert material["evaluation_control"] == "诊断"
    assert material["sourceRef"]["rowId"] == "r"
    assert SessionService._prepare_materials([material])[2] == "DIRECT"
    formal = SessionService._formal_material(material)
    assert formal["studentActivity"] == "回答"
    assert formal["sourceRef"]["versionId"] == "v"
