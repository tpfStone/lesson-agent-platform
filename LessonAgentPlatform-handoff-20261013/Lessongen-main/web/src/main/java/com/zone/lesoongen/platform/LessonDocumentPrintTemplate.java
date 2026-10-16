package com.zone.lesoongen.platform;

/** Independent ODF print layout, generated from version semantics without reading a DOCX. */
public final class LessonDocumentPrintTemplate {
    private LessonDocumentPrintTemplate() {}

    public static String render(LessonDocument document, String title, int version, boolean degraded) {
        PlatformLessonDocumentService.validate(document);
        var xml = new StringBuilder("""
                <?xml version="1.0" encoding="UTF-8"?>
                <office:document xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" xmlns:table="urn:oasis:names:tc:opendocument:xmlns:table:1.0" xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" office:version="1.3" office:mimetype="application/vnd.oasis.opendocument.text">
                <office:font-face-decls><style:font-face style:name="PrintBody" svg:font-family="宋体"/><style:font-face style:name="PrintHeading" svg:font-family="黑体"/></office:font-face-decls>
                <office:styles>
                <style:default-style style:family="paragraph"><style:paragraph-properties fo:line-height="125%" fo:margin-bottom="2mm"/><style:text-properties style:font-name="PrintBody" style:font-name-asian="PrintBody" style:font-name-complex="PrintBody" fo:font-size="10pt" style:font-size-asian="10pt" fo:color="#182839"/></style:default-style>
                <style:style style:name="Title" style:family="paragraph" style:master-page-name="Standard"><style:paragraph-properties fo:keep-with-next="always" fo:margin-bottom="3mm"/><style:text-properties style:font-name="PrintHeading" style:font-name-asian="PrintHeading" style:font-name-complex="PrintHeading" fo:font-size="19pt" style:font-size-asian="19pt" fo:font-weight="bold" style:font-weight-asian="bold"/></style:style>
                <style:style style:name="Heading" style:family="paragraph"><style:paragraph-properties fo:keep-with-next="always" fo:margin-top="3mm" fo:margin-bottom="2mm"/><style:text-properties style:font-name="PrintHeading" style:font-name-asian="PrintHeading" style:font-name-complex="PrintHeading" fo:font-size="13pt" style:font-size-asian="13pt" fo:font-weight="bold" style:font-weight-asian="bold"/></style:style>
                <style:style style:name="Cell" style:family="paragraph"><style:paragraph-properties fo:line-height="115%" fo:margin-top="0mm" fo:margin-bottom="0mm"/><style:text-properties fo:font-size="9pt" style:font-size-asian="9pt"/></style:style>
                <style:style style:name="Header" style:family="paragraph" style:parent-style-name="Cell"><style:text-properties fo:font-weight="bold" style:font-weight-asian="bold"/></style:style>
                <style:style style:name="Label" style:family="paragraph"><style:paragraph-properties fo:keep-with-next="always"/><style:text-properties fo:font-size="9pt" style:font-size-asian="9pt" fo:color="#526273"/></style:style>
                </office:styles><office:automatic-styles>
                <style:page-layout style:name="Page"><style:page-layout-properties fo:page-width="210mm" fo:page-height="297mm" fo:margin="20mm" style:print-orientation="portrait"/></style:page-layout>
                <style:style style:name="Table" style:family="table"><style:table-properties style:width="170mm" table:align="margins" table:border-model="collapsing" fo:margin-top="2mm" fo:margin-bottom="3mm"/></style:style>
                <style:style style:name="Column" style:family="table-column"><style:table-column-properties style:rel-column-width="1*"/></style:style>
                <style:style style:name="Short" style:family="table-row"><style:table-row-properties fo:keep-together="always"/></style:style>
                <style:style style:name="Long" style:family="table-row"><style:table-row-properties fo:keep-together="auto"/></style:style>
                <style:style style:name="Border" style:family="table-cell"><style:table-cell-properties fo:border="0.5pt solid #899baa" fo:padding="1.2mm" style:vertical-align="top"/></style:style>
                <style:style style:name="HeadBorder" style:family="table-cell"><style:table-cell-properties fo:border="0.5pt solid #899baa" fo:padding="1.2mm" fo:background-color="#eaf0f5" style:vertical-align="top"/></style:style>
                </office:automatic-styles><office:master-styles><style:master-page style:name="Standard" style:page-layout-name="Page"/></office:master-styles><office:body><office:text>
                """);
        paragraph(xml, "Title", title);
        paragraph(xml, "Label", "教案版本 V" + version);
        if (degraded) paragraph(xml, "Label", "文本兼容排版：此版本缺少已验证结构，未恢复原始 Word 版式。");
        int tableIndex = 0;
        for (var block : document.blocks()) {
            if (!block.tableRows().isEmpty()) {
                var groups = LessonTableLayout.columnGroups(block);
                for (int group = 0; group < groups.size(); group++) {
                    if (groups.size() > 1) paragraph(xml, "Label", LessonTableLayout.groupLabel(group, groups.size()));
                    xml.append("<table:table table:name=\"PrintTable").append(++tableIndex).append("\" table:style-name=\"Table\">");
                    for (int ignored : groups.get(group)) xml.append("<table:table-column table:style-name=\"Column\"/>");
                    int headers = LessonTableLayout.headerRows(block);
                    if (headers > 0) xml.append("<table:table-header-rows>");
                    for (int row = 0; row < block.tableRows().size(); row++) {
                        if (row == headers && headers > 0) xml.append("</table:table-header-rows>");
                        boolean header = row < headers;
                        xml.append("<table:table-row table:style-name=\"")
                                .append(LessonTableLayout.allowRowSplit(block.tableRows().get(row)) ? "Long" : "Short").append("\">");
                        for (int column : groups.get(group)) {
                            xml.append("<table:table-cell table:style-name=\"").append(header ? "HeadBorder" : "Border")
                                    .append("\" office:value-type=\"string\">");
                            paragraph(xml, header ? "Header" : "Cell", LessonTableLayout.cell(block.tableRows().get(row), column));
                            xml.append("</table:table-cell>");
                        }
                        xml.append("</table:table-row>");
                    }
                    if (headers == block.tableRows().size()) xml.append("</table:table-header-rows>");
                    xml.append("</table:table>");
                }
            } else if (!block.items().isEmpty()) {
                for (int i = 0; i < block.items().size(); i++)
                    paragraph(xml, null, (Boolean.TRUE.equals(block.ordered()) ? (i + 1) + ". " : "• ") + block.items().get(i));
            } else if (block.text() != null) {
                paragraph(xml, "heading".equals(block.type()) ? "Heading" : null, block.text());
            }
        }
        return xml.append("</office:text></office:body></office:document>").toString();
    }

    private static void paragraph(StringBuilder xml, String style, String text) {
        xml.append("<text:p");
        if (style != null) xml.append(" text:style-name=\"").append(style).append('"');
        xml.append('>').append(lines(text)).append("</text:p>");
    }

    // ODF collapses literal whitespace. Elements preserve user spaces, tabs and line breaks.
    private static String lines(String text) {
        return escape(text).replace("\r\n", "\n").replace('\r', '\n')
                .replace(" ", "<text:s/>").replace("\t", "<text:tab/>").replace("\n", "<text:line-break/>");
    }
    private static String escape(String text) {
        return text == null ? "" : text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
