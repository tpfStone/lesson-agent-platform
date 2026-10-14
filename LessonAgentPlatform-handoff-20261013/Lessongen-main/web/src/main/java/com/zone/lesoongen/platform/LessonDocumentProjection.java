package com.zone.lesoongen.platform;

import java.util.ArrayList;
import java.util.List;

/** Fixed LF projection; escaped cell newlines cannot become extra table rows. */
public final class LessonDocumentProjection {
    public static final String VERSION = "lesson-text-1.1";
    private LessonDocumentProjection() {}
    public record Projection(String content, String projectionVersion) {}
    public static Projection project(LessonDocument document) {
        LessonDocumentValidator.validate(document).requireValid();
        List<String> parts = new ArrayList<>();
        for (var b : document.blocks()) {
            if (!b.tableRows().isEmpty()) {
                parts.add(String.join("\n", b.tableRows().stream().map(row -> String.join(" | ",
                        row.stream().map(LessonDocumentProjection::cell).toList())).toList()));
            } else if (!b.items().isEmpty()) {
                List<String> items = new ArrayList<>();
                for (int i = 0; i < b.items().size(); i++) items.add(
                        (Boolean.TRUE.equals(b.ordered()) ? (i + 1) + ". " : "• ") + b.items().get(i));
                parts.add(String.join("\n", items));
            } else if (b.text() != null && !b.text().isBlank()) parts.add(LessonDocumentSupport.normalize(b.text()));
        }
        return new Projection(String.join("\n\n", parts).strip(), VERSION);
    }
    private static String cell(String value) {
        return LessonDocumentSupport.normalize(value).replace("\\", "\\\\").replace("|", "\\|").replace("\n", "\\n");
    }
}
