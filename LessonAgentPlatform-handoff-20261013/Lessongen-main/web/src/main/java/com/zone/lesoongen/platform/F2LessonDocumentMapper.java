package com.zone.lesoongen.platform;
import tools.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Preserve all engine fields; only known procedure fields receive classroom semantics. */
public final class F2LessonDocumentMapper {
    private F2LessonDocumentMapper() {}
    public static LessonDocument map(JsonNode plan) {
        List<LessonDocument.Block> blocks = new ArrayList<>();
        for (var entry : plan.properties()) {
            String key = entry.getKey(); JsonNode value = entry.getValue();
            if ("procedure_steps".equals(key) && value.isArray() && !value.isEmpty()) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(List.of("教学环节", "教师活动", "学生活动", "评价", "其他信息"));
                for (var step : value) {
                    List<String> extra = new ArrayList<>();
                    for (var field : step.properties()) if (!Set.of("stage", "duration_minutes", "teacher_actions", "student_actions", "assessment").contains(field.getKey()))
                        extra.add(field.getKey() + "：" + text(field.getValue()));
                    String minutes = step.path("duration_minutes").isNumber() ? "（" + step.path("duration_minutes").asInt() + "分钟）" : "";
                    rows.add(List.of(step.path("stage").asText("教学环节") + minutes, text(step.path("teacher_actions")),
                            text(step.path("student_actions")), text(step.path("assessment")), String.join("\n", extra)));
                }
                blocks.add(LessonDocumentSupport.tableBlock(id(), rows));
            } else {
                blocks.add(new LessonDocument.Block(id(), "heading", 2, label(key), null, List.of(), List.of(), null));
                blocks.add(new LessonDocument.Block(id(), "paragraph", null, text(value), null, List.of(), List.of(), null));
            }
        }
        return new LessonDocument("1.1", blocks);
    }
    private static String id() { return "b-" + UUID.randomUUID(); }
    private static String text(JsonNode value) {
        if (value.isNull() || value.isMissingNode()) return "";
        if (value.isTextual()) return value.asText();
        List<String> result = new ArrayList<>();
        if (value.isArray()) value.forEach(v -> result.add(text(v)));
        else if (value.isObject()) value.properties().forEach(e -> result.add(label(e.getKey()) + "：" + text(e.getValue())));
        else return value.asText();
        return String.join("\n", result);
    }
    private static String label(String key) {
        return switch (key) {
            case "metadata" -> "基本信息"; case "learning_objectives" -> "学习目标";
            case "key_points" -> "教学重点"; case "difficult_points" -> "教学难点";
            case "homework" -> "作业设计"; case "board_design" -> "板书设计";
            case "assessment_plan" -> "评价设计"; case "student_analysis" -> "学情分析";
            case "content_analysis" -> "内容分析"; case "design_thesis" -> "设计主张";
            case "driving_question" -> "核心问题"; case "differentiation" -> "差异化支持";
            default -> key;
        };
    }
}
