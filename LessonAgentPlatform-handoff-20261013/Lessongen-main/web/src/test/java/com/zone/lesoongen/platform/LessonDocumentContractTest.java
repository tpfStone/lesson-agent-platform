package com.zone.lesoongen.platform;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LessonDocumentContractTest {
    @Test void historicalSchemaKeepsItsOriginalDurationAndExtraCellSemantics() {
        var rows = List.of(List.of("教学环节", "时间", "教师活动", "学生活动"),
                List.of("讨论", "12", "提问", "回答"));
        var oldView = new LessonDocument.TeachingProcess(rows.get(0), List.of(
                new LessonDocument.TeachingProcessRow(1, "讨论", null, "提问", "回答", "", List.of("12"))));
        var oldBlock = new LessonDocument.Block("old", "teaching_process", null, null, null,
                List.of(), rows, oldView);
        assertTrue(LessonDocumentValidator.validate(new LessonDocument("1.0", List.of(oldBlock))).valid());
        var corruptView = new LessonDocument.TeachingProcess(rows.get(0), List.of(
                new LessonDocument.TeachingProcessRow(1, "讨论", null, "被改写", "回答", "", List.of("12"))));
        var corrupt = new LessonDocument.Block("old", "teaching_process", null, null, null,
                List.of(), rows, corruptView);
        assertFalse(LessonDocumentValidator.validate(new LessonDocument("1.0", List.of(corrupt))).valid());
        assertEquals(12, LessonDocumentSupport.tableBlock("new", rows).teachingProcess().rows().get(0).durationMinutes());
    }
    @Test void separateDurationColumnAndMultilineCellsSurviveProjection() {
        var b = LessonDocumentSupport.tableBlock("table", List.of(
                List.of("环节", "时间", "教师活动", "学生活动", "评价"),
                List.of("讨论", "12", "提问\n比较 A|B 和 \\n", "回答", "")));
        assertEquals(12, b.teachingProcess().rows().get(0).durationMinutes());
        assertEquals("讨论", b.teachingProcess().rows().get(0).title());
        var content = LessonDocumentProjection.project(new LessonDocument("1.1", List.of(b))).content();
        var reparsed = LessonDocumentSupport.fromPlainText(content);
        assertEquals(b.tableRows(), reparsed.blocks().get(0).tableRows());
    }
    @Test void rejectInconsistentTeachingRows() {
        var block = LessonDocumentSupport.tableBlock("table-1", List.of(
                List.of("教师活动", "学生活动", "其他"), List.of("提问", "回答", "保留")));
        var wrong = new LessonDocument.Block(block.id(), block.type(), null, null, null,
                List.of(), block.tableRows(), new LessonDocument.TeachingProcess(List.of(), List.of()),
                block.rowIds(), block.columnBindings());
        assertFalse(LessonDocumentValidator.validate(new LessonDocument("1.1", List.of(wrong))).valid());
    }
    @Test void duplicateTitlesKeepDifferentRowIds() {
        var b = LessonDocumentSupport.tableBlock("table-1", List.of(List.of("教学环节", "教师活动", "学生活动"),
                List.of("讨论", "提问", "回答"), List.of("讨论", "指导", "探究")));
        assertNotEquals(b.teachingProcess().rows().get(0).rowId(), b.teachingProcess().rows().get(1).rowId());
        assertTrue(LessonDocumentValidator.validate(new LessonDocument("1.1", List.of(b))).valid());
    }
    @Test void unsupportedSchemaIsExplicit() {
        assertTrue(LessonDocumentValidator.validate(new LessonDocument("2.0", List.of())).errors()
                .contains("UNSUPPORTED_SCHEMA"));
    }
    @Test void preserveUnknownBlocksAndColumns() {
        var document = new LessonDocument("1.1", List.of(
                new LessonDocument.Block("custom", "custom_note", null, "补充内容", null, List.of(), List.of(), null),
                LessonDocumentSupport.tableBlock("table", List.of(List.of("教师活动", "学生活动", "额外列"),
                        List.of("比较 A|B", "讨论", "不可丢失")))));
        var validation = LessonDocumentValidator.validate(document);
        assertTrue(validation.valid());
        assertFalse(validation.warnings().isEmpty());
        var text = LessonDocumentProjection.project(document).content();
        assertTrue(text.contains("补充内容"));
        assertTrue(text.contains("不可丢失"));
        assertTrue(text.contains("A\\|B"));
    }
}
