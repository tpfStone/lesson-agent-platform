package com.zone.lesoongen.platform;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Map;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class F2SourceColumnExportTest {
    @Test void savedF2VersionRetainsVisibleNotesAndTheirRowsWithoutInventingTeachingSteps() throws Exception {
        var plan = JsonMapper.builder().build().valueToTree(Map.of(
                "procedure_steps", List.of(Map.of("stage", "导入", "duration_minutes", 5,
                    "teacher_actions", List.of("提问"), "student_actions", List.of("回答"), "assessment", List.of("解释"))),
                "template_extensions", Map.of("source_table_columns", List.of(Map.of(
                    "table_locator", "table:1", "column_index", 5, "heading", "额外备注",
                    "rows", List.of(Map.of("row_index", 1, "stage", "导入", "text", "保留原备注 note 123")))))));
        var document = F2LessonDocumentMapper.map(plan);
        var tables = document.blocks().stream().filter(block -> !block.tableRows().isEmpty()).toList();
        assertEquals(2, tables.size());
        var notes = tables.stream().filter(table -> table.tableRows().get(0)
                .equals(List.of("教学环节", "额外备注"))).findFirst().orElseThrow();
        assertEquals(List.of("导入", "保留原备注 note 123"), notes.tableRows().get(1));
        assertTrue(notes.teachingProcess() == null || notes.teachingProcess().rows().isEmpty());
        try (var word = new XWPFDocument(new ByteArrayInputStream(LessonDocumentDocxExporter.exportForDownload(document)))) {
            assertEquals(2, word.getTables().size());
            var noteTable = word.getTables().stream().filter(table -> table.getRow(0).getTableCells().size() == 2
                    && table.getRow(0).getCell(1).getText().equals("额外备注")).findFirst().orElseThrow();
            assertEquals("保留原备注 note 123", noteTable.getRow(1).getCell(1).getText());
            assertTrue(noteTable.getRow(0).isRepeatHeader());
        }
        assertFalse(LessonDocumentProjection.project(document).content().contains("source_table_columns"));
    }
}
