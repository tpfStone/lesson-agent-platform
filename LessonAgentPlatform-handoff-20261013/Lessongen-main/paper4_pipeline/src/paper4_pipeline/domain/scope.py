"""Resolve scope against real fields and compare the entire saved baseline."""
from __future__ import annotations

import re
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    from paper4_pipeline.domain.models import LessonPlanDocument, LessonTask


EDITABLE_ROOTS = frozenset({
    "design_thesis", "driving_question", "learning_trajectory", "curriculum_standards",
    "content_analysis", "student_analysis", "learning_objectives", "key_points",
    "difficult_points", "teaching_strategy", "resources", "teaching_artifacts",
    "procedure_steps", "assessment_plan", "differentiation", "homework", "board_design",
    "reflection", "references", "template_extensions",
})
LABELS = {
    "设计主张": "design_thesis", "驱动问题": "driving_question", "学习轨迹": "learning_trajectory",
    "课程标准": "curriculum_standards", "教学内容分析": "content_analysis", "学情分析": "student_analysis",
    "学习目标": "learning_objectives", "教学目标": "learning_objectives", "教学重点": "key_points",
    "教学难点": "difficult_points", "教学策略": "teaching_strategy", "教学资源": "resources",
    "教学材料": "teaching_artifacts", "教学过程": "procedure_steps", "评价设计": "assessment_plan",
    "差异化支持": "differentiation", "作业": "homework", "课后作业": "homework",
    "板书": "board_design", "反思": "reflection", "备注": "reflection", "参考资料": "references",
}


def token(value: str) -> str:
    return value.replace("~", "~0").replace("/", "~1")


def validate_path_syntax(path: str) -> str:
    if not path.startswith("/") or any(not part for part in path.split("/")[1:]):
        raise ValueError(f"optimization scope requires a real field path: {path}")
    parts = path.split("/")[1:]
    if parts[0] not in EDITABLE_ROOTS:
        raise ValueError(f"optimization scope cannot edit this field: {path}")
    for part in parts:
        if re.search(r"~(?![01])", part) or part in {"*", "-", ".", ".."}:
            raise ValueError(f"invalid optimization scope path: {path}")
    return path


def legacy_scope_paths(focus: list[str]) -> list[str]:
    paths = []
    for value in focus:
        name = value.strip()
        path = name if name.startswith("/") else "/" + LABELS.get(name, name)
        try:
            paths.append(validate_path_syntax(path))
        except ValueError as exc:
            raise ValueError("无法可靠确定优化范围，请明确选择可修改的栏目（optimization scope）") from exc
    return paths


def canonical_path(document: LessonPlanDocument, path: str) -> str:
    validate_path_syntax(path)
    current: Any = document.model_dump(mode="json")
    resolved = []
    for raw in path.split("/")[1:]:
        segment = raw.replace("~1", "/").replace("~0", "~")
        if isinstance(current, dict) and segment in current:
            current = current[segment]
        elif isinstance(current, list):
            if segment.isdigit():
                index = int(segment)
                if str(index) != segment or index >= len(current):
                    raise ValueError(f"optimization scope does not resolve: {path}")
            else:
                matches = [i for i, item in enumerate(current) if isinstance(item, dict)
                           and any(key.endswith("_id") and value == segment for key, value in item.items())]
                if len(matches) != 1:
                    raise ValueError(f"optimization scope does not resolve uniquely: {path}")
                index = matches[0]
            segment = str(index)
            current = current[index]
        else:
            raise ValueError(f"optimization scope does not resolve: {path}")
        resolved.append(token(segment))
    return "/" + "/".join(resolved)


def local_paths(task: LessonTask) -> list[str] | None:
    scope = task.optimization_scope
    if task.mode.value != "optimize" or scope is None or scope.mode == "full":
        return None
    return scope.editable_paths


def path_intersects_scope(task: LessonTask, path: str) -> bool:
    paths = local_paths(task)
    if paths is None:
        return True
    try:
        canonical = canonical_path(task.initial_plan, path)
    except ValueError:
        return False
    return any(canonical == p or canonical.startswith(p + "/") or p.startswith(canonical + "/")
               for p in paths)


def outside_scope_changes(task: LessonTask, candidate: LessonPlanDocument) -> list[str]:
    paths = local_paths(task)
    if paths is None:
        return []
    baseline = task.initial_plan.model_dump(mode="json")
    after = candidate.model_dump(mode="json")
    changes: list[str] = []

    def visit(before: Any, value: Any, path: str) -> None:
        if before == value or any(path == p or path.startswith(p + "/") for p in paths):
            return
        if isinstance(before, dict) and isinstance(value, dict):
            for key in sorted(before.keys() | value.keys()):
                child = path + "/" + token(key)
                if key not in before or key not in value:
                    changes.append(child)
                else:
                    visit(before[key], value[key], child)
        elif isinstance(before, list) and isinstance(value, list) and len(before) == len(value):
            for index, (left, right) in enumerate(zip(before, value)):
                visit(left, right, path + "/" + str(index))
        else:
            changes.append(path)

    visit(baseline, after, "")
    return changes


def enforce_scope(task: LessonTask, candidate: LessonPlanDocument) -> None:
    changed = outside_scope_changes(task, candidate)
    if changed:
        raise ValueError("out of optimization scope: " + ", ".join(changed))
