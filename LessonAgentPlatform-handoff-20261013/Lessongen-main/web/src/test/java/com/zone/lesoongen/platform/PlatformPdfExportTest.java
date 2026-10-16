package com.zone.lesoongen.platform;

import com.zone.lesoongen.application.AppException;
import com.zone.lesoongen.application.LessonResultService;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Mock storage and the external PDF renderer, but exercise actual endpoint selection and print template. */
class PlatformPdfExportTest {
    private final PlatformLessonService lessons = mock(PlatformLessonService.class);
    private final PlatformLessonDocumentService documents = mock(PlatformLessonDocumentService.class);
    private final PlatformLessonExportController controller = new PlatformLessonExportController(lessons,
            mock(LessonResultService.class), documents, new LessonVersionPdfService("") {
                @Override public byte[] render(LessonDocument document, String title, int version, boolean degraded) {
                    return LessonDocumentPrintTemplate.render(document, title, version, degraded).getBytes(StandardCharsets.UTF_8);
                }
            });

    @Test void pdfSelectsRequestedOldVersionAndItsVerifiedStructure() throws Exception {
        seed();
        when(documents.get("lesson", "old")).thenReturn(snapshot("VERIFIED"));
        String printed = printedText(controller.exportPdf("lesson", "old").getBody());
        assertTrue(printed.contains("教案版本 V1"));
        assertTrue(printed.contains("所选版本结构正文"));
        assertFalse(printed.contains("新版本正文"));
        assertFalse(printed.contains("旧版本兼容正文"));
        assertFalse(printed.contains("文本兼容排版"));
    }

    @Test void unverifiedStructureUsesSelectedBodyWithExplicitFallback() throws Exception {
        seed();
        when(documents.get("lesson", "old")).thenReturn(snapshot("MISMATCH"));
        String printed = printedText(controller.exportPdf("lesson", "old").getBody());
        assertTrue(printed.contains("旧版本兼容正文"));
        assertTrue(printed.contains("文本兼容排版"));
        assertFalse(printed.contains("所选版本结构正文"));
    }

    @Test void unknownVersionIsRejectedBeforeRendering() {
        seed();
        assertThrows(AppException.class, () -> controller.exportPdf("lesson", "unknown"));
        verifyNoInteractions(documents);
    }

    @Test void legacyWordDownloadUsesSelectedBodyAndTheSameFontPolicy() throws Exception {
        seed();
        when(documents.get("lesson", "old")).thenReturn(snapshot("MISMATCH"));
        try (var word = new org.apache.poi.xwpf.usermodel.XWPFDocument(new java.io.ByteArrayInputStream(
                controller.exportDocx("lesson", "old").getBody()))) {
            assertEquals("黑体", word.getParagraphs().get(0).getRuns().get(0).getFontFamily());
            var body = word.getParagraphs().stream().filter(p -> p.getText().equals("旧版本兼容正文")).findFirst().orElseThrow();
            for (var range : org.apache.poi.xwpf.usermodel.XWPFRun.FontCharRange.values())
                assertEquals("宋体", body.getRuns().get(0).getFontFamily(range));
            assertTrue(word.getParagraphs().stream().noneMatch(p -> p.getText().contains("新版本正文")));
            assertNotNull(word.getStyles().getStyle("Normal"));
        }
    }

    private static String printedText(byte[] xml) throws Exception {
        var document = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml));
        return visibleText(document.getElementsByTagName("office:text").item(0));
    }
    private static String visibleText(org.w3c.dom.Node node) {
        if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE) return node.getNodeValue();
        if (node.getNodeName().equals("text:s")) return " ";
        if (node.getNodeName().equals("text:tab")) return "\t";
        if (node.getNodeName().equals("text:line-break")) return "\n";
        var text = new StringBuilder();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) text.append(visibleText(child));
        return text.toString();
    }

    private void seed() {
        var summary = new PlatformLessonController.LessonSummary("lesson", "面积教案", "数学", "三年级", "面积", 40, "new", null);
        when(lessons.detail("lesson")).thenReturn(new PlatformLessonController.LessonDetail(summary, List.of(
                new PlatformLessonController.LessonVersion("old", "lesson", null, 1, "旧版本兼容正文", "old-hash", "MANUAL", null, null),
                new PlatformLessonController.LessonVersion("new", "lesson", "old", 2, "新版本正文", "new-hash", "MANUAL", null, null))));
    }
    private PlatformLessonDocumentService.VersionDocument snapshot(String binding) {
        return new PlatformLessonDocumentService.VersionDocument("lesson", "old", "1.1",
                LessonDocumentSupport.fromPlainText("所选版本结构正文"), "PARSED", "FIXTURE", "1", List.of(), null, null,
                "old-hash", "structure-hash", "canonical-1.1", binding, false);
    }
}
