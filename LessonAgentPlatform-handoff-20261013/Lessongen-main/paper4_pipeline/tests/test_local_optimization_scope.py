"""User scope is a deterministic boundary, independent of model instructions."""
import pytest

from paper4_pipeline.control.rules import check_lesson_plan
from paper4_pipeline.domain.models import LessonPlanDocument, LessonTask, Severity


def baseline():
    return LessonPlanDocument(plan_id="plan", task_id="task", metadata={
        "subject": "数学", "grade": "五年级", "topic": "面积", "duration_minutes": 45,
    }, learning_objectives=[{"objective_id": "o1", "description": "解释面积"}],
        procedure_steps=[{"step_id": "s1", "stage": "探究", "duration_minutes": 45,
                          "objective_ids": ["o1"], "teacher_actions": ["引导比较"],
                          "student_actions": ["测量"], "assessment": "解释依据"}],
        homework="原作业，保持逐字不变", reflection="原备注")


def task_for(document, **updates):
    values = dict(task_id="task", mode="optimize", subject="数学", grade="五年级",
                  topic="面积", duration_minutes=45, course_information="面积课",
                  initial_plan=document, required_sections=["learning_objectives", "procedure_steps"],
                  class_constraints={"optimization_focus": ["教学过程"]})
    values.update(updates)
    return LessonTask.model_validate(values)


def test_preexisting_missing_outside_scope_is_warning_not_failure():
    original = baseline()
    task = task_for(original, required_sections=["content_analysis", "homework"])
    candidate = original.model_copy(deep=True)
    candidate.procedure_steps[0].teacher_actions = ["比较后说明依据"]
    report = check_lesson_plan(candidate, task)
    assert report.passed
    assert any(v.location == "content_analysis" and v.severity == Severity.MEDIUM
               and "原稿" in v.message for v in report.violations)


@pytest.mark.parametrize("field,value", [
    ("homework", "原作业，保持逐字不变；另加拓展题"),
    ("homework", ""), ("reflection", "修改备注"), ("board_design", "新增板书"),
])
def test_outside_add_delete_or_edit_is_rejected(field, value):
    original = baseline()
    candidate = original.model_copy(deep=True)
    setattr(candidate, field, value)
    report = check_lesson_plan(candidate, task_for(original))
    assert not report.passed
    assert any(v.code == "out_of_scope_change" and v.location == "/" + field
               for v in report.violations)


def test_inside_new_hard_defect_still_fails():
    original = baseline()
    candidate = original.model_copy(deep=True)
    candidate.procedure_steps = []
    assert not check_lesson_plan(candidate, task_for(original)).passed


def test_missing_outside_steps_do_not_fail_local_homework_via_derived_zero_duration():
    original = baseline()
    original.procedure_steps = []
    task = explicit_task(original, ["/homework"])
    candidate = original.model_copy(update={"homework": "新作业"})
    assert check_lesson_plan(candidate, task).passed


def test_existing_nonempty_duration_mismatch_remains_hard():
    original = baseline()
    original.procedure_steps[0].duration_minutes = 40
    task = explicit_task(original, ["/homework"])
    candidate = original.model_copy(update={"homework": "新作业"})
    assert not check_lesson_plan(candidate, task).passed


def test_full_generation_keeps_completeness_rules():
    document = baseline()
    task = task_for(document, mode="generate", initial_plan=None,
                    class_constraints={}, required_sections=["content_analysis"])
    assert not check_lesson_plan(document, task).passed


def test_unresolvable_legacy_focus_does_not_become_full_rewrite():
    with pytest.raises(ValueError, match="范围|scope"):
        task_for(baseline(), class_constraints={"optimization_focus": ["帮我提高整体表现"]})


def explicit_task(document, paths):
    return task_for(document, optimization_scope={"mode": "local", "editable_paths": paths})


@pytest.mark.parametrize("scope", [
    {"mode": "local", "editable_paths": []},
    {"mode": "local", "editable_paths": ["/metadata"]},
    {"mode": "local", "editable_paths": ["/procedure_steps/missing/teacher_actions"]},
    {"mode": "local", "editable_paths": ["/procedure_steps/*/teacher_actions"]},
    {"mode": "full", "editable_paths": ["/homework"]},
])
def test_invalid_explicit_scope_rejected(scope):
    with pytest.raises(ValueError, match="scope"):
        task_for(baseline(), optimization_scope=scope)


def test_nested_scope_rejects_sibling_duration_new_row_and_object_replacement():
    original = baseline()
    task = explicit_task(original, ["/procedure_steps/s1/teacher_actions"])
    assert task.optimization_scope.editable_paths == ["/procedure_steps/0/teacher_actions"]
    candidate = original.model_copy(deep=True)
    candidate.procedure_steps[0].teacher_actions = ["新活动"]
    assert check_lesson_plan(candidate, task).passed
    candidate.procedure_steps[0].student_actions = ["未授权活动"]
    assert not check_lesson_plan(candidate, task).passed
    candidate = original.model_copy(deep=True)
    candidate.procedure_steps[0].duration_minutes = 44  # within duration tolerance, still unauthorized
    assert not check_lesson_plan(candidate, task).passed
    candidate = original.model_copy(deep=True)
    candidate.procedure_steps.append(candidate.procedure_steps[0].model_copy(update={"step_id": "s2"}))
    assert not check_lesson_plan(candidate, task).passed


def critique(path="/procedure_steps/s1/teacher_actions"):
    from paper4_pipeline.domain.models import CritiqueItem
    return CritiqueItem(critique_id="c1", critic_profile_id="critic", dimension="teaching_logic",
                        issue_code="specificity", target_path=path, lesson_location="活动",
                        issue="活动不够明确", evidence="仅有引导比较", actionable_suggestion="说明比较依据")


def test_patch_cannot_smuggle_outside_edit_under_inside_critique():
    from paper4_pipeline.agents.patch_rewriter import PatchProposal, materialize_patch
    original = baseline()
    proposal = PatchProposal(edits=[{"critique_id": "c1", "path": "/homework",
                                    "value": "原作业，保持逐字不变；新增题", "reason": "顺带完善"}])
    with pytest.raises(ValueError, match="scope|范围"):
        materialize_patch(proposal, original, [critique()], task_for(original), 2)


def test_scope_outside_opinion_stays_suggestion_even_if_model_accepts():
    from paper4_pipeline.control.lifecycle import enforce_task_evidence_constraints
    from paper4_pipeline.domain.models import ValidationDecision, CritiqueBatch
    decision = ValidationDecision(decision_id="d1", critique_id="c1", decision="accept",
                                  grounded=True, relevant=True, actionable=True, reason="有帮助")
    batch = CritiqueBatch(batch_id="b1", plan_version_id="v0", round_index=1, items=[critique("/homework")])
    guarded = enforce_task_evidence_constraints(batch, [decision], task_for(baseline()))
    assert guarded[0].decision.value == "defer"
    assert "范围外" in guarded[0].reason


def test_outside_opinion_cannot_merge_into_inside_authorization():
    from paper4_pipeline.control.lifecycle import enforce_task_evidence_constraints
    from paper4_pipeline.domain.models import ValidationDecision, CritiqueBatch
    outside = critique("/homework").model_copy(update={"critique_id": "c2"})
    batch = CritiqueBatch(batch_id="b1", plan_version_id="v0", round_index=1, items=[critique(), outside])
    decisions = [ValidationDecision(decision_id="d1", critique_id="c1", decision="accept",
        grounded=True, relevant=True, actionable=True, reason="改活动"),
        ValidationDecision(decision_id="d2", critique_id="c2", decision="merge", canonical_critique_id="c1",
        grounded=True, relevant=True, actionable=True, reason="连同作业一起完善")]
    guarded = enforce_task_evidence_constraints(batch, decisions, task_for(baseline()))
    assert guarded[0].decision.value == "accept"
    assert guarded[1].decision.value == "defer"
    assert guarded[1].canonical_critique_id == ""


def test_engine_scope_survives_build_task_and_baseline_roundtrip():
    from paper4_pipeline.web_api.schemas import EngineLessonInput
    from paper4_pipeline.web_api.runner import build_task
    engine = EngineLessonInput(mode="optimize", subject="数学", grade="五年级", topic="面积",
        optimization_focus=["提高提问质量"],
        optimization_scope={"mode": "local", "editable_paths": ["/procedure_steps/s1/questions"]})
    task = build_task(engine, task_id="task", initial_plan=baseline(), source_sha256="a" * 64)
    restored = LessonTask.model_validate_json(task.model_dump_json())
    assert restored.optimization_scope.editable_paths == ["/procedure_steps/0/questions"]
    assert restored.initial_plan == baseline()
    assert "web:docx:" + "a" * 64 in restored.source_refs


def test_engine_rejects_ambiguous_focus_before_normalization():
    from paper4_pipeline.web_api.schemas import EngineLessonInput
    with pytest.raises(ValueError, match="范围|scope"):
        EngineLessonInput(mode="optimize", subject="数学", grade="五年级", topic="面积",
                          optimization_focus=["增加趣味性"])


def historical_result():
    from datetime import datetime, timezone
    from paper4_pipeline.domain.models import LessonPlanVersion, PipelineResult
    original = baseline()
    rule = check_lesson_plan(original, task_for(original))
    version = LessonPlanVersion(version_id="v0", iteration=0, document=original,
        created_by_profile_id="input", created_at=datetime.now(timezone.utc),
        document_hash="0" * 64, rule_check_report=rule)
    return PipelineResult(run_id="run", task_id="task", task_mode="optimize", experiment_id="test",
        method_id="test", status="needs_human", stop_reason="max_rounds", best_version_id="v0",
        last_version_id="v0", versions=[version])


def test_summary_publishes_saved_scope_without_reclassifying_old_results():
    from paper4_pipeline.control.optimization import optimization_summary
    from paper4_pipeline.domain.models import PipelineResult
    old = historical_result()
    original_json = old.model_dump_json()
    old_summary = optimization_summary(old)
    assert old_summary.get("scope") is None
    assert old.model_dump_json() == original_json
    assert old.status.value == "needs_human"
    payload = old.model_dump(mode="json")
    payload["optimization_scope"] = {"mode": "local", "editable_paths": ["/procedure_steps"]}
    current = PipelineResult.model_validate(payload)
    summary = optimization_summary(current)
    assert summary["scope"] == payload["optimization_scope"]
    assert summary["baseline_content_hash"] == old_summary["baseline_content_hash"]


def test_service_revalidates_scope_before_constructing_provider(tmp_path, monkeypatch):
    import paper4_pipeline.service as service
    from paper4_pipeline.domain.models import ExperimentConfig
    from paper4_pipeline.agents.profiles import default_profile_registry
    task = task_for(baseline()).model_copy(update={"optimization_scope": {"mode": "local", "editable_paths": []}})
    calls = []
    def forbidden(_):
        calls.append("provider")
        raise AssertionError("invalid scope reached provider")
    monkeypatch.setattr(service, "build_live_suite", forbidden)
    with pytest.raises(ValueError, match="scope"):
        service.PipelineService(tmp_path).run(task, ExperimentConfig(experiment_id="test", method_id="test",
            role_model_configs={key: value.model for key, value in default_profile_registry().items()}))
    assert calls == []


@pytest.mark.parametrize("smuggle", [False, True])
def test_real_service_graph_saves_baseline_scope_and_rejects_whole_document_escape(tmp_path, monkeypatch, smuggle):
    """Replace only model ports; execute the service, graph, review and exporters."""
    import json
    from types import SimpleNamespace
    from pathlib import Path
    import paper4_pipeline.service as service
    from paper4_pipeline.agents.profiles import default_profile_registry
    from paper4_pipeline.agents.protocols import AgentOutput
    from paper4_pipeline.agents.suite import AgentSuite
    from paper4_pipeline.agents.patch_rewriter import PatchProposal, materialize_patch
    from paper4_pipeline.domain.models import (
        ExperimentConfig, CritiqueBatch, ValidationBatch, ValidationDecision, EvaluationReport, RubricScores,
    )
    profiles = default_profile_registry()
    config = ExperimentConfig(experiment_id="offline", method_id="scope-test", optimization_max_rounds=1,
        max_model_calls=100, role_model_configs={key: value.model for key, value in profiles.items()},
        critic_profile_ids=["pedagogy_critic_v0_1"])
    seen = []

    def evaluate(task, version, unresolved, knowledge):
        score = 7 if version.version_id == "v0" else 9
        return AgentOutput(EvaluationReport(evaluation_id="e-" + version.version_id,
            evaluated_version_id=version.version_id, overall_score=score,
            rubric_scores=RubricScores(**{key: score for key in RubricScores.model_fields}),
            rule_checks_passed=version.rule_check_report.passed))

    def review(task, version, round_index, knowledge, prior_critiques):
        outside = critique("/homework").model_copy(update={"critique_id": "c2"})
        return AgentOutput(CritiqueBatch(batch_id="batch", plan_version_id=version.version_id,
                                        round_index=round_index, items=[critique(), outside]))

    def validate(task, version, batch, round_index, knowledge):
        return AgentOutput(ValidationBatch(batch_id="validation", critique_batch_id=batch.batch_id,
            round_index=round_index, decisions=[ValidationDecision(decision_id="d-" + item.critique_id,
                critique_id=item.critique_id, decision="accept", grounded=True, relevant=True,
                actionable=True, reason="local fixture") for item in batch.items]))

    def rewrite(task, version, accepted, round_index, knowledge):
        seen.extend(item.critique_id for item in accepted)
        outcome = materialize_patch(PatchProposal(edits=[{"critique_id": "c1",
            "path": "/procedure_steps/s1/teacher_actions", "value": ["比较后说明依据"], "reason": "具体化"}]),
            version.document, accepted, task, 2)
        if smuggle:
            outcome.document.homework += "；越界追加作业"
        return AgentOutput(outcome)

    suite = AgentSuite(execution_mode="live", profiles=profiles, designer=SimpleNamespace(), writer=SimpleNamespace(),
        critics=[SimpleNamespace(profile_id="pedagogy_critic_v0_1", review=review)],
        validator=SimpleNamespace(profile_id="validator_v0_1", validate=validate),
        judge=SimpleNamespace(profile_id="judge_v0_1", evaluate=evaluate),
        rewriter=SimpleNamespace(profile_id="rewriter_v0_1", rewrite=rewrite))
    monkeypatch.setattr(service, "build_live_suite", lambda _: suite)
    task = explicit_task(baseline(), ["/procedure_steps/s1/teacher_actions"])
    task.required_sections = ["learning_objectives", "procedure_steps", "content_analysis", "board_design"]
    result = service.PipelineService(tmp_path).run(task, config, run_id="scope-fixture", include_docx=False)
    assert seen == ["c1"], result.errors
    assert result.optimization_scope.editable_paths == ["/procedure_steps/0/teacher_actions"]
    assert result.versions[0].document == baseline()
    assert all(v.document.homework == baseline().homework for v in result.versions)
    assert next(item for item in result.critiques if item.critique_id == "c2").status.value == "deferred"
    events = [json.loads(line) for line in Path(result.trace_path).read_text(encoding="utf-8").splitlines()]
    saved_task = events[0]["input_summary"]["task"]
    assert saved_task["initial_plan"] == baseline().model_dump(mode="json")
    assert saved_task["optimization_scope"] == task.optimization_scope.model_dump(mode="json")
    if smuggle:
        assert result.best_version_id == result.last_version_id == "v0"
        assert result.stop_reason.value == "rewrite_failed"
    else:
        assert result.last_version_id == "v1", result.errors
        assert result.versions[-1].rule_check_report.passed
        assert result.versions[-1].document.procedure_steps[0].teacher_actions == ["比较后说明依据"]
