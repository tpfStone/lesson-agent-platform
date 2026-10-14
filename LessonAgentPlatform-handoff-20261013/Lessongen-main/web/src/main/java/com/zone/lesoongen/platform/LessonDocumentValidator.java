package com.zone.lesoongen.platform;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Validates authoritative cells and their derived teaching view before any consumer uses them. */
public final class LessonDocumentValidator {
    private LessonDocumentValidator() {}
    public record Validation(List<String> errors, List<String> warnings) {
        public boolean valid() { return errors.isEmpty(); }
        public void requireValid() {
            if (!valid()) throw new IllegalArgumentException(String.join(", ", errors));
        }
    }
    public static Validation validate(LessonDocument document) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (document == null) return new Validation(List.of("DOCUMENT_REQUIRED"), List.of());
        if (!Set.of("1.0", "1.1").contains(document.schemaVersion())) errors.add("UNSUPPORTED_SCHEMA");
        if (document.blocks().size() > 5000) errors.add("TOO_MANY_BLOCKS");
        Set<String> ids = new HashSet<>(), rowIds = new HashSet<>();
        long characters = 0;
        for (var b : document.blocks()) {
            if (b.id() == null || b.id().isBlank() || !ids.add(b.id())) errors.add("DUPLICATE_OR_EMPTY_BLOCK_ID");
            if (b.type() == null || b.type().isBlank()) { errors.add("BLOCK_TYPE_REQUIRED"); continue; }
            if (!Set.of("heading", "paragraph", "list", "table", "teaching_process").contains(b.type()))
                warnings.add("UNKNOWN_BLOCK_PRESERVED:" + b.id());
            characters += b.text() == null ? 0 : b.text().length();
            characters += b.items().stream().mapToLong(String::length).sum();
            characters += b.tableRows().stream().flatMap(List::stream).mapToLong(String::length).sum();
            if (!b.tableRows().isEmpty()) {
                int width = b.tableRows().get(0).size();
                if (width == 0 || width > 100 || b.tableRows().size() > 5000) errors.add("INVALID_TABLE_SIZE");
                if (b.tableRows().stream().anyMatch(r -> r.size() != width)) warnings.add("IRREGULAR_TABLE:" + b.id());
                if (!b.rowIds().isEmpty() && b.rowIds().size() != b.tableRows().size()) errors.add("ROW_ID_COUNT_MISMATCH");
                for (var id : b.rowIds()) if (id.isBlank() || !rowIds.add(id)) errors.add("DUPLICATE_OR_EMPTY_ROW_ID");
                if ("1.1".equals(document.schemaVersion()) && b.rowIds().isEmpty()) errors.add("ROW_IDS_REQUIRED");
                if (!b.columnBindings().isEmpty() && !b.columnBindings().equals(LessonDocumentSupport.columnBindings(b.tableRows())))
                    errors.add("COLUMN_BINDINGS_MISMATCH");
            }
            var derived = "1.0".equals(document.schemaVersion())
                    ? LegacyLessonDocumentSupport.teachingProcess(b.tableRows())
                    : LessonDocumentSupport.teachingProcess(b.tableRows(), b.rowIds());
            if (b.teachingProcess() != null && !b.teachingProcess().equals(derived)) errors.add("INCONSISTENT_TEACHING_ROWS");
            if ("teaching_process".equals(b.type()) && derived == null) errors.add("INVALID_TEACHING_PROCESS");
        }
        if (characters > 100_000) errors.add("DOCUMENT_TOO_LARGE");
        return new Validation(List.copyOf(errors), List.copyOf(warnings));
    }
}
