"""Conservative text provenance checks before a local edit gets its baseline.

Word layout is not canonical JSON. We can prove literal source coverage and
standard heading/column bindings, but cannot prove a paraphrase equivalent.
Unverifiable normalization is rejected, never padded with a hidden raw dump.
"""
from __future__ import annotations

import re
from typing import Any

from paper4_pipeline.domain.models import LessonPlanDocument
from paper4_pipeline.domain.scope import LABELS, token
from paper4_pipeline.web_api.schemas import EngineLessonInput, RawLessonDocument


_HEADINGS = {**{key: "/" + value for key, value in LABELS.items()},
    "作业设计": "/homework", "课后练习": "/homework", "板书设计": "/board_design",
    "教学反思": "/reflection", "教学准备": "/resources", "教学评价": "/assessment_plan",
    "科目": "/metadata/subject", "学科": "/metadata/subject", "年级": "/metadata/grade",
    "课题": "/metadata/topic", "课时": "/metadata/duration_minutes", "教材版本": "/metadata/textbook_version",
    "学情": "/student_analysis", "准备": "/resources",
}
_COLUMNS = {
    "环节": "stage", "教学环节": "stage", "阶段": "stage",
    "时间": "duration_minutes", "时长": "duration_minutes", "时间（分钟）": "duration_minutes",
    "教师活动": "teacher_actions", "教师行为": "teacher_actions",
    "学生活动": "student_actions", "学生行为": "student_actions",
    "评价": "assessment", "评价证据": "assessment", "评价方式": "assessment", "设计意图": "design_rationale",
}


def _compact(text: str) -> str:
    return re.sub(r"\s+", "", text)


def _atoms(text: str) -> list[str]:
    return [value.strip() for value in re.split(r"[\r\n；;]+", text) if value.strip()]


def _heading(text: str) -> tuple[str | None, str]:
    value = re.sub(r"^[一二三四五六七八九十\d]+[、.．]\s*", "", text.strip())
    for label in sorted(_HEADINGS, key=len, reverse=True):
        if value.rstrip("：:") == label:
            return _HEADINGS[label], ""
        if value.startswith((label + "：", label + ":")):
            return _HEADINGS[label], value[len(label) + 1:].strip()
    return None, text


def _labelled_parts(text: str, inherited: str | None) -> list[tuple[str | None, str]]:
    # Split only at an explicit next label. Preserve punctuation in body text;
    # a separator cannot by itself authorize moving text to another field.
    labels = "|".join(re.escape(label) for label in sorted(_HEADINGS, key=len, reverse=True))
    parts = re.split(r"(?<=[；;。|\n])\s*(?=(?:" + labels + r")[：:])", text)
    result = []
    for part in parts:
        heading, value = _heading(part)
        inherited = heading or inherited
        result.append((inherited, value))
    return result


def _leaves(value: Any, path: str = "") -> list[tuple[str, str]]:
    if isinstance(value, dict):
        return [entry for key, item in value.items()
                if key not in {"schema_version", "task_id", "plan_id", "template_id"}
                and not key.endswith(("_id", "_ids"))
                for entry in _leaves(item, path + "/" + token(key))]
    if isinstance(value, list):
        return [entry for index, item in enumerate(value) for entry in _leaves(item, path + "/" + str(index))]
    if isinstance(value, (str, int)) and str(value).strip():
        return [(path, str(value))]
    return []


def _bound(path: str, heading: str | None, column: str | None) -> bool:
    return ((not heading or path == heading or path.startswith(heading + "/"))
            and (not column or bool(re.match(r"^/procedure_steps/\d+/" + column + r"(?:/|$)", path))))


def _claim_occurrence(needle: str, locations: list[tuple[str, str]],
                      used: dict[str, list[tuple[int, int]]], *,
                      field_order: dict[str, tuple[int, int]] | None = None,
                      leaf_order: dict[str, int] | None = None) -> bool:
    """One source occurrence cannot justify two output occurrences (or vice versa)."""
    for identity, value in locations:
        # A scalar list is one field: moving its items is also a source edit.
        field = re.sub(r"/\d+$", "", identity)
        ordinal = (leaf_order or {}).get(identity, 0)
        start = value.find(needle)
        while start >= 0:
            end = start + len(needle)
            in_order = field_order is None or (ordinal, start) >= field_order.get(field, (-1, -1))
            if in_order and all(end <= left or start >= right for left, right in used.get(identity, [])):
                used.setdefault(identity, []).append((start, end))
                if field_order is not None:
                    field_order[field] = (ordinal, end)
                return True
            start = value.find(needle, start + 1)
    return False


def checked_source_coverage(raw: RawLessonDocument, document: LessonPlanDocument,
                            task: EngineLessonInput) -> dict[str, list[str]] | None:
    if task.optimization_scope is None or task.optimization_scope.mode != "local":
        return None
    leaves = [(path, text) for path, text in _leaves(document.model_dump(mode="json"))
              if not path.startswith("/template_extensions/source_table_columns/")]
    entries: list[tuple[str, str, str | None, str | None]] = []
    extra_columns: list[dict[str, Any]] = []
    activity_rows: list[list[str]] = []
    current_heading: str | None = None
    for paragraph in raw.paragraphs:
        parts = _labelled_parts(paragraph.text, current_heading)
        current_heading = parts[-1][0]
        entries.extend((paragraph.locator, text, heading, None) for heading, text in parts)
    for table in raw.tables:
        headers: dict[int, str] = {}
        extras: dict[int, tuple[int, dict[str, Any]]] = {}
        for row in sorted({cell.row for cell in table.cells}):
            cells = [cell for cell in table.cells if cell.row == row]
            column_labels = {cell.column: _COLUMNS[cell.text.strip()] for cell in cells
                             if cell.text.strip() in _COLUMNS}
            if len(column_labels) >= 2:
                headers = column_labels
                for cell in cells:
                    if cell.column not in headers and cell.text.strip():
                        extra = {"table_locator": table.locator, "column_index": cell.column,
                                 "heading": cell.text, "rows": []}
                        index = len(extra_columns)
                        extra_columns.append(extra)
                        extras[cell.column] = (index, extra)
                        entries.append((cell.locator, "", f"/template_extensions/source_table_columns/{index}/heading", None))
                    else:
                        entries.append((cell.locator, "", "/procedure_steps", None))
                continue
            row_heading: str | None = None
            row_locators = []
            for cell in sorted(cells, key=lambda item: item.column):
                if cell.column in extras:
                    index, extra = extras[cell.column]
                    stage = next((item.text for item in cells if headers.get(item.column) == "stage"), "")
                    entry_index = len(extra["rows"])
                    extra["rows"].append({"row_index": row, "stage": stage, "text": cell.text})
                    entries.append((cell.locator, "", f"/template_extensions/source_table_columns/{index}/rows/{entry_index}/text", None))
                    continue
                parts = _labelled_parts(cell.text, row_heading)
                row_heading = parts[-1][0]
                entries.extend((cell.locator, text, heading,
                                headers.get(cell.column) if not heading else None)
                               for heading, text in parts)
                if not row_heading and headers.get(cell.column) and cell.text.strip():
                    row_locators.append(cell.locator)
            if row_locators:
                activity_rows.append(row_locators)

    coverage: dict[str, list[str]] = {}
    problems = []
    used_output: dict[str, list[tuple[int, int]]] = {}
    field_order: dict[str, tuple[int, int]] = {}
    leaf_order = {path: index for index, (path, _) in enumerate(leaves)}
    for locator, source_text, heading, column in entries:
        matches = []
        for atom in _atoms(source_text):
            compact = _compact(atom)
            # Time cells often include their unit; the canonical schema stores minutes.
            if column == "duration_minutes" or heading == "/metadata/duration_minutes":
                compact = re.sub(r"(?:分钟|min)[。.]?$", "", compact, flags=re.IGNORECASE)
            time_value = column == "duration_minutes" or heading == "/metadata/duration_minutes"
            found = [path for path, value in leaves if _bound(path, heading, column)
                     and (compact == _compact(value) if time_value else compact in _compact(value))]
            if not found:
                problems.append(f"{locator} 原文无法在对应栏目核实")
            elif not _claim_occurrence(compact, [(path, _compact(value)) for path, value in leaves if path in found],
                    used_output, field_order=field_order, leaf_order=leaf_order):
                problems.append(f"{locator} 原文出现次数或同字段顺序未被完整保留")
            matches.extend(found)
        coverage[locator] = sorted(set(coverage.get(locator, []) + matches +
                                      ([heading] if not source_text.strip() and heading else [])))

    last_step = -1
    for row in activity_rows:
        candidates = None
        for locator in row:
            indices = {int(match.group(1)) for path in coverage[locator]
                       if (match := re.match(r"^/procedure_steps/(\d+)/", path))}
            candidates = indices if candidates is None else candidates & indices
        possible = sorted(index for index in (candidates or set()) if index > last_step)
        if not possible:
            problems.append(f"{row[0]} 原稿表格行被合并、重排或无法核实")
        else:
            last_step = possible[0]
    used_source: dict[str, list[tuple[int, int]]] = {}
    for path, value in leaves:
        if path.startswith("/metadata/") or path.endswith("/duration_minutes"):
            continue  # authoritative task metadata / time checked separately
        for atom in _atoms(value):
            if not _claim_occurrence(_compact(atom),
                    [(str(index), _compact(text)) for index, (_, text, heading, column) in enumerate(entries)
                     if _bound(path, heading, column)], used_source):
                problems.append(f"{path} 出现无法对应原稿的新增或改写内容")
    if problems:
        raise ValueError("局部优化前的原稿保全检查失败；请核对或整理原稿后重试：" + "；".join(problems[:8]))
    # Unknown teaching-table columns are copied by the program with explicit
    # row/stage provenance. They are visible content, never a model's raw dump.
    supplied = document.template_extensions.get("source_table_columns")
    if supplied is not None and supplied != extra_columns:
        raise ValueError("局部优化前的原稿保全检查失败：补充表格列与原稿不一致")
    if extra_columns:
        document.template_extensions = {**document.template_extensions, "source_table_columns": extra_columns}
    return coverage
