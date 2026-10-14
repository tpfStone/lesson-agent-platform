import importlib.util
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from run_f3_demo import _normalize_materials, load_config
import json


def test_material_transport_keeps_all_fields():
    row = {"materialId": "r", "title": "导入", "teachingScript": "提问", "durationMinutes": 5,
           "teacherActivity": "提问", "studentActivity": "回答", "evaluation": "诊断",
           "sourceRef": {"lessonId": "l", "versionId": "v", "blockId": "b", "rowId": "r"}}
    actual = _normalize_materials([row])[0]
    for key in row:
        assert actual[key] == row[key]


def test_structured_config_needs_no_f4(tmp_path):
    path = tmp_path / "config.json"
    path.write_text(json.dumps({"lessonId": "l", "versionId": "v", "f3Url": "http://localhost:8003",
                              "materials": [{"materialId": "r", "title": "导入", "teachingScript": "提问"}]}), encoding="utf-8")
    config = load_config(path)
    assert config["f4SessionId"] is None
    assert len(config["materials"]) == 1


def test_legacy_mixed_and_nonclassroom_content_is_explicitly_retained():
    from adapters.f4_f3_adapter import convert_f4_sections_to_f3_materials
    materials = convert_f4_sections_to_f3_materials([
        {'id':'a','section_type':'introduction','current_content':'导入'},
        {'id':'b','section_type':'mixed','current_content':'讨论与总结'},
        {'id':'c','section_type':'homework','current_content':'课后测量'}])
    assert len(materials) == 2
    assert materials[1]['teaching_script'] == '讨论与总结'
    assert materials[1]['metadata']['warnings']
    assert materials[0]['metadata']['documentContext'] == '课后测量'
