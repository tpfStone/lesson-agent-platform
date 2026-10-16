package com.zone.lesoongen.platform;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.stream.IntStream;
import static org.junit.jupiter.api.Assertions.*;
class PlatformStructuredExportTest {
    @Test void allGeneratedTextHasExplicitChineseAndLatinFonts() throws Exception {
        var source = new LessonDocument("1.1", List.of(
                new LessonDocument.Block("h", "heading", 1, "面积 Lesson 123", null, List.of(), List.of(), null),
                new LessonDocument.Block("p", "paragraph", null, "正文 body 45\n备注 note", null, List.of(), List.of(), null),
                new LessonDocument.Block("l", "list", null, null, true, List.of("列表 item 6"), List.of(), null),
                LessonDocumentSupport.tableBlock("t", List.of(List.of("教师活动", "学生活动"), List.of("问题 Q1", "回答 A2")))));
        for (byte[] bytes : List.of(LessonDocumentDocxExporter.export(source), LessonDocumentDocxExporter.exportForDownload(source))) {
            try (var word = new XWPFDocument(new ByteArrayInputStream(bytes))) {
                for (var paragraph : word.getParagraphs()) for (var run : paragraph.getRuns())
                    assertFont(run, "Heading1".equals(paragraph.getStyle()) ? "黑体" : "宋体");
                for (var row : word.getTables().get(0).getRows()) for (var cell : row.getTableCells())
                    for (var paragraph : cell.getParagraphs()) for (var run : paragraph.getRuns()) assertFont(run, "宋体");
                assertTrue(word.getTables().get(0).getRow(0).getCell(0).getParagraphs().get(0).getRuns().get(0).isBold());
                assertNotNull(word.getStyles().getStyle("Normal"), "Newly typed text must have a stable default style");
                assertNotNull(word.getStyles().getStyle("Heading1"));
                assertTrue(word.getStyles().getStyle("Normal").getCTStyle().xmlText().contains("宋体"));
                assertTrue(word.getStyles().getStyle("Heading1").getCTStyle().xmlText().contains("黑体"));
            }
        }
    }

    private static void assertFont(org.apache.poi.xwpf.usermodel.XWPFRun run, String font) {
        for (var range : org.apache.poi.xwpf.usermodel.XWPFRun.FontCharRange.values())
            assertEquals(font, run.getFontFamily(range), run.text() + " / " + range);
    }
    @Test void moduleInputPreservesWideTableAndTeachingRowsOnReimport() throws Exception {
        var rows = new java.util.ArrayList<List<String>>();
        var header = new java.util.ArrayList<>(IntStream.range(0, 15).mapToObj(i -> "字段" + i).toList());
        header.set(0, "教学环节"); header.set(6, "教师活动"); header.set(9, "学生活动");
        rows.add(header);
        for (int row = 1; row <= 6; row++) {
            int index = row;
            rows.add(IntStream.range(0, 15).mapToObj(i -> "行" + index + "列" + i).toList());
        }
        var source = new LessonDocument("1.1", List.of(LessonDocumentSupport.tableBlock("module-wide", rows)));
        byte[] bytes = LessonDocumentDocxExporter.export(source);
        var reparsed = new DocxLessonExtractor().extract(LessonDocumentFiles.upload(bytes), 20L * 1024 * 1024).structuredContent();
        var tables = reparsed.blocks().stream().filter(b -> !b.tableRows().isEmpty()).toList();
        assertEquals(1, tables.size(), "module input must not be split into presentation column groups");
        assertEquals(rows, tables.get(0).tableRows());
        assertEquals(6, tables.get(0).teachingProcess().rows().size());
    }
    @Test void docxHasRealEditableTable() throws Exception {
        var document = LessonDocumentSupport.fromPlainText("教学目标\n理解面积\n\n教师活动 | 学生活动\n提问 | 回答\n\n作业设计\n测量三角形");
        byte[] bytes = LessonDocumentDocxExporter.export(document);
        try (var word = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals(1, word.getTables().size());
            assertEquals("回答", word.getTables().get(0).getRow(1).getCell(1).getText());
            assertTrue(word.getParagraphs().stream().anyMatch(p -> p.getText().equals("测量三角形")));
        }
    }

    @Test void wideTablePreservesEveryCellInReadableColumnGroups() throws Exception {
        var rows = List.of(IntStream.range(0, 15).mapToObj(i -> "字段" + i).toList(),
                IntStream.range(0, 15).mapToObj(i -> "内容" + i + "结束").toList());
        var source = new LessonDocument("1.1", List.of(LessonDocumentSupport.tableBlock("wide", rows)));
        try (var word = new XWPFDocument(new ByteArrayInputStream(LessonDocumentDocxExporter.exportForDownload(source)))) {
            assertEquals(4, word.getTables().size());
            for (var table : word.getTables()) {
                assertTrue(table.getRow(0).getTableCells().size() <= 5);
                assertEquals("字段0", table.getRow(0).getCell(0).getText());
                assertEquals("内容0结束", table.getRow(1).getCell(0).getText());
                assertTrue(table.getRow(0).isRepeatHeader());
            }
            var allCells = word.getTables().stream().flatMap(t -> t.getRows().stream())
                    .flatMap(r -> r.getTableCells().stream()).map(c -> c.getText()).toList();
            for (int i = 1; i < 15; i++) {
                assertEquals(1, java.util.Collections.frequency(allCells, "字段" + i));
                assertEquals(1, java.util.Collections.frequency(allCells, "内容" + i + "结束"));
            }
        }
    }

    @Test void preambleAndTeachingHeaderRepeatWithoutPromotingDataRows() throws Exception {
        var rows = List.of(List.of("课堂教学安排", "", "", ""),
                List.of("环节", "教师活动", "学生活动", "评价"),
                List.of("导入", "提问\n继续提问", "回答", "反馈"));
        var source = new LessonDocument("1.1", List.of(LessonDocumentSupport.tableBlock("teaching", rows)));
        try (var word = new XWPFDocument(new ByteArrayInputStream(LessonDocumentDocxExporter.exportForDownload(source)))) {
            assertEquals(1, word.getTables().size());
            var table = word.getTables().get(0);
            assertTrue(table.getRow(0).isRepeatHeader());
            assertTrue(table.getRow(1).isRepeatHeader());
            assertFalse(table.getRow(2).isRepeatHeader());
            assertEquals(List.of("提问", "继续提问"), table.getRow(2).getCell(1).getParagraphs().stream().map(p -> p.getText()).toList());
        }
    }
}
