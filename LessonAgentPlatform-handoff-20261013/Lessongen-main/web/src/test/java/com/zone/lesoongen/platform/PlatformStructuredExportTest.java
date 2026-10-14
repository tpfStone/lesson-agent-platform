package com.zone.lesoongen.platform;
import org.junit.jupiter.api.Test;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayInputStream;
import static org.junit.jupiter.api.Assertions.*;
class PlatformStructuredExportTest {
    @Test void docxHasRealEditableTable() throws Exception {
        var document = LessonDocumentSupport.fromPlainText("教学目标\n理解面积\n\n教师活动 | 学生活动\n提问 | 回答\n\n作业设计\n测量三角形");
        byte[] bytes = LessonDocumentDocxExporter.export(document);
        try (var word = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            assertEquals(1, word.getTables().size());
            assertEquals("回答", word.getTables().get(0).getRow(1).getCell(1).getText());
            assertTrue(word.getParagraphs().stream().anyMatch(p -> p.getText().equals("测量三角形")));
        }
    }
}
