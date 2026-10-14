package com.zone.lesoongen.platform;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public final class LessonDocumentMaterialAdapter {
    private LessonDocumentMaterialAdapter() {}
    public static List<Map<String, Object>> materials(PlatformLessonDocumentService.VersionDocument snapshot) {
        PlatformLessonDocumentService.validate(snapshot.document());
        if (!snapshot.usableForSimulation()) return List.of();
        String context = LessonDocumentProjection.project(new LessonDocument(snapshot.schemaVersion(), snapshot.document().blocks()
                .stream().filter(b -> b.teachingProcess() == null).toList())).content();
        List<Map<String, Object>> result = new ArrayList<>();
        for (var block : snapshot.document().blocks()) {
            if (block.teachingProcess() == null) continue;
            for (var row : block.teachingProcess().rows()) {
                String rowId = row.rowId() == null ? snapshot.versionId() + ":" + block.id() + ":" + row.sourceRowIndex() : row.rowId();
                Map<String, Object> material = new LinkedHashMap<>();
                material.put("materialId", rowId);
                material.put("title", row.title());
                material.put("durationMinutes", row.durationMinutes());
                material.put("teacherActivity", row.teacherActivity());
                material.put("studentActivity", row.studentActivity());
                material.put("evaluation", row.evaluation());
                material.put("teachingScript", "教师活动：" + row.teacherActivity() + "\n学生活动：" + row.studentActivity()
                        + "\n评价：" + row.evaluation() + (row.extraCells().isEmpty() ? "" : "\n补充：" + String.join("；", row.extraCells())));
                material.put("keyPoints", List.of());
                material.put("sourceSectionId", block.id());
                material.put("sourceRef", Map.of("lessonId", snapshot.lessonId(), "versionId", snapshot.versionId(),
                        "blockId", block.id(), "rowId", rowId));
                material.put("metadata", Map.of("sourceType", "STRUCTURED_VERSION", "structureSha256", snapshot.structureSha256(),
                        "documentContext", context));
                result.add(material);
            }
        }
        return List.copyOf(result);
    }
}
