package com.zone.lesoongen.platform;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

public final class LessonDocumentDocxExporter {
    private LessonDocumentDocxExporter() {}
    /** Module interchange keeps the original table topology, so reparsing does not invent or lose rows. */
    public static byte[] export(LessonDocument document) throws IOException {
        return export(document, false);
    }
    /** User-facing Word uses readable column groups for wide tables. */
    public static byte[] exportForDownload(LessonDocument document) throws IOException {
        return export(document, true);
    }
    private static byte[] export(LessonDocument document, boolean printLayout) throws IOException {
        PlatformLessonDocumentService.validate(document);
        try (var word = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            LessonDocumentFonts.initialize(word);
            var section = word.getDocument().getBody().addNewSectPr();
            var size = section.addNewPgSz();
            size.setW(java.math.BigInteger.valueOf(11906));
            size.setH(java.math.BigInteger.valueOf(16838));
            var margin = section.addNewPgMar();
            margin.setTop(java.math.BigInteger.valueOf(1134)); margin.setBottom(java.math.BigInteger.valueOf(1134));
            margin.setLeft(java.math.BigInteger.valueOf(1134)); margin.setRight(java.math.BigInteger.valueOf(1134));
            for (var block : document.blocks()) {
                if (!block.tableRows().isEmpty()) {
                    var groups = printLayout ? LessonTableLayout.columnGroups(block) : java.util.List.of(
                            java.util.stream.IntStream.range(0, block.tableRows().stream().mapToInt(java.util.List::size).max().orElse(1)).boxed().toList());
                    for (int group = 0; group < groups.size(); group++) {
                        if (groups.size() > 1) {
                            var label = word.createParagraph(); label.setSpacingAfter(80); label.setKeepNext(true);
                            var run = label.createRun(); LessonDocumentFonts.apply(run, false);
                            run.setText(LessonTableLayout.groupLabel(group, groups.size()));
                        }
                        var columns = groups.get(group);
                        var table = word.createTable(block.tableRows().size(), columns.size());
                        table.setWidth("100%");
                        var layout = table.getCTTbl().getTblPr().addNewTblLayout();
                        layout.setType(org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblLayoutType.FIXED);
                        var grid = table.getCTTbl().getTblGrid();
                        if (grid == null) grid = table.getCTTbl().addNewTblGrid();
                        for (int c = 0; c < columns.size(); c++) grid.addNewGridCol().setW(java.math.BigInteger.valueOf(9638 / columns.size()));
                        for (int r = 0; r < block.tableRows().size(); r++) {
                            var row = table.getRow(r);
                            row.setRepeatHeader(r < LessonTableLayout.headerRows(block));
                            // Short rows stay readable as a unit; tall cells can continue onto following pages.
                            row.setCantSplitRow(!LessonTableLayout.allowRowSplit(block.tableRows().get(r)));
                            for (int c = 0; c < columns.size(); c++) {
                                var cell = row.getCell(c);
                                cell.setWidth(Integer.toString(9638 / columns.size()));
                                if (r < LessonTableLayout.headerRows(block)) cell.setColor("EAF0F5");
                                String[] lines = LessonTableLayout.cell(block.tableRows().get(r), columns.get(c)).split("\n", -1);
                                for (int i = 0; i < lines.length; i++) {
                                    var p = i == 0 ? cell.getParagraphs().get(0) : cell.addParagraph();
                                    p.setSpacingAfter(60); p.setSpacingBefore(0);
                                    var run = p.createRun();
                                    LessonDocumentFonts.apply(run, false); run.setFontSize(10);
                                    run.setBold(r < LessonTableLayout.headerRows(block)); run.setText(lines[i]);
                                }
                            }
                        }
                        word.createParagraph().setSpacingAfter(100);
                    }
                } else if (!block.items().isEmpty()) {
                    for (int i = 0; i < block.items().size(); i++) {
                        var run = word.createParagraph().createRun(); LessonDocumentFonts.apply(run, false);
                        run.setText((Boolean.TRUE.equals(block.ordered()) ? (i + 1) + ". " : "• ") + block.items().get(i));
                    }
                } else if (block.text() != null) {
                    var p = word.createParagraph();
                    if ("heading".equals(block.type())) p.setStyle("Heading" + (block.level() == null ? 2 : block.level()));
                    for (String line : block.text().split("\n", -1)) {
                        boolean continuation = !p.getRuns().isEmpty();
                        var run = p.createRun(); LessonDocumentFonts.apply(run, "heading".equals(block.type()));
                        if (continuation) run.addBreak(); run.setText(line);
                    }
                }
            }
            word.write(out); return out.toByteArray();
        }
    }
}
