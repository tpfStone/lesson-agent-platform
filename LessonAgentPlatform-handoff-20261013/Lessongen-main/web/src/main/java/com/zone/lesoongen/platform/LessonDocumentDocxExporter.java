package com.zone.lesoongen.platform;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class LessonDocumentDocxExporter {
    private LessonDocumentDocxExporter() {}
    public static byte[] export(LessonDocument document) throws IOException {
        PlatformLessonDocumentService.validate(document);
        try (var word = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            for (var block : document.blocks()) {
                if (!block.tableRows().isEmpty()) {
                    int columns = block.tableRows().stream().mapToInt(java.util.List::size).max().orElse(1);
                    var table = word.createTable(block.tableRows().size(), columns);
                    for (int r = 0; r < block.tableRows().size(); r++) {
                        for (int c = 0; c < block.tableRows().get(r).size(); c++) {
                            var cell = table.getRow(r).getCell(c);
                            String[] lines = block.tableRows().get(r).get(c).split("\n", -1);
                            for (int i = 0; i < lines.length; i++) {
                                var p = i == 0 ? cell.getParagraphs().get(0) : cell.addParagraph();
                                p.createRun().setText(lines[i]);
                            }
                        }
                    }
                } else if (!block.items().isEmpty()) {
                    for (int i = 0; i < block.items().size(); i++) word.createParagraph().createRun().setText(
                            (Boolean.TRUE.equals(block.ordered()) ? (i + 1) + ". " : "• ") + block.items().get(i));
                } else if (block.text() != null) {
                    var p = word.createParagraph();
                    if ("heading".equals(block.type())) p.setStyle("Heading" + (block.level() == null ? 2 : block.level()));
                    for (String line : block.text().split("\n", -1)) { if (!p.getRuns().isEmpty()) p.createRun().addBreak(); p.createRun().setText(line); }
                }
            }
            word.write(out); return out.toByteArray();
        }
    }
}
