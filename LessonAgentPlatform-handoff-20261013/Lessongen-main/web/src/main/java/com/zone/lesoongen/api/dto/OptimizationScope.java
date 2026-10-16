package com.zone.lesoongen.api.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Set;

/** Explicit authorization to change content fields, never lesson identity. */
public record OptimizationScope(String mode,
        @JsonProperty("editable_paths") @JsonAlias("editablePaths") List<String> editablePaths) {
    private static final Set<String> ROOTS = Set.of("design_thesis", "driving_question", "learning_trajectory",
            "curriculum_standards", "content_analysis", "student_analysis", "learning_objectives", "key_points",
            "difficult_points", "teaching_strategy", "resources", "teaching_artifacts", "procedure_steps",
            "assessment_plan", "differentiation", "homework", "board_design", "reflection", "references", "template_extensions");

    public OptimizationScope {
        editablePaths = editablePaths == null ? List.of() : List.copyOf(editablePaths);
        if (!"local".equals(mode) && !"full".equals(mode)) throw new IllegalArgumentException("请选择明确的优化范围");
        if (("local".equals(mode) && editablePaths.isEmpty()) || ("full".equals(mode) && !editablePaths.isEmpty())
                || editablePaths.size() > 100) throw new IllegalArgumentException("局部范围必须选择栏目，全文范围不能同时指定局部栏目");
        for (String path : editablePaths) {
            if (path == null || path.length() > 1000 || !path.startsWith("/") || path.endsWith("/")
                    || path.contains("//") || !ROOTS.contains(path.split("/", -1)[1])
                    || path.matches(".*~(?![01]).*") || path.matches(".*/(?:\\*|-|\\.|\\.\\.)(?:/.*)?"))
                throw new IllegalArgumentException("优化范围必须指向可修改的真实内容字段");
        }
    }
}
