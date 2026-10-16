package com.zone.lesoongen.platform;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LessonDocumentPrintTemplateTest {
    @Test void printUsesHeadingHeiAndBodySongForAsianAndLatinText() throws Exception {
        var dom = parse(LessonDocumentPrintTemplate.render(LessonDocumentSupport.fromPlainText("教学目标\n正文 body 123"), "标题 title", 1, false));
        var declarations = dom.getElementsByTagName("style:font-face");
        var fonts = new java.util.HashMap<String, String>();
        for (int i = 0; i < declarations.getLength(); i++) {
            var declaration = (org.w3c.dom.Element) declarations.item(i);
            fonts.put(declaration.getAttribute("style:name"), declaration.getAttribute("svg:font-family"));
        }
        var defaults = (org.w3c.dom.Element) dom.getElementsByTagName("style:default-style").item(0);
        var body = (org.w3c.dom.Element) defaults.getElementsByTagName("style:text-properties").item(0);
        for (var attribute : List.of("style:font-name", "style:font-name-asian", "style:font-name-complex"))
            assertEquals("宋体", fonts.get(body.getAttribute(attribute)), attribute);
        var styles = dom.getElementsByTagName("style:style");
        for (int i = 0; i < styles.getLength(); i++) {
            var style = (org.w3c.dom.Element) styles.item(i);
            if (!List.of("Title", "Heading").contains(style.getAttribute("style:name"))) continue;
            var properties = (org.w3c.dom.Element) style.getElementsByTagName("style:text-properties").item(0);
            for (var attribute : List.of("style:font-name", "style:font-name-asian", "style:font-name-complex"))
                assertEquals("黑体", fonts.get(properties.getAttribute(attribute)), attribute);
        }
    }
    @Test void printRowsUseNativeKeepTogetherAndAllowVeryLongCellsToContinue() throws Exception {
        var rows = List.of(List.of("环节", "内容"), List.of("短行", "第一行\n第二行"),
                List.of("长行", "跨页内容\n".repeat(100)));
        var source = new LessonDocument("1.1", List.of(LessonDocumentSupport.tableBlock("rows", rows)));
        var dom = parse(LessonDocumentPrintTemplate.render(source, "分页", 1, false));
        assertEquals("office:document", dom.getDocumentElement().getTagName());
        var tableRows = dom.getElementsByTagName("table:table-row");
        assertEquals("Short", ((org.w3c.dom.Element) tableRows.item(1)).getAttribute("table:style-name"));
        assertEquals("Long", ((org.w3c.dom.Element) tableRows.item(2)).getAttribute("table:style-name"));
        var properties = dom.getElementsByTagName("style:table-row-properties");
        assertEquals("always", ((org.w3c.dom.Element) properties.item(0)).getAttribute("fo:keep-together"));
        assertEquals("auto", ((org.w3c.dom.Element) properties.item(1)).getAttribute("fo:keep-together"));
        assertEquals(1, dom.getElementsByTagName("table:table-header-rows").getLength());
    }
    @Test void independentPrintTemplateEscapesContentAndIdentifiesSelectedVersion() throws Exception {
        var source = LessonDocumentSupport.fromPlainText("教学目标\n保留 <script> & 原文\n\n教师活动 | 学生活动\n提问 | 回答");
        String html = LessonDocumentPrintTemplate.render(source, "三角形 & 面积", 7, false);
        var dom = parse(html);
        assertEquals("三角形 & 面积", visibleText(dom.getElementsByTagName("text:p").item(0)));
        assertTrue(visibleText(dom.getDocumentElement()).contains("教案版本 V7"));
        assertTrue(visibleText(dom.getDocumentElement()).contains("保留 <script> & 原文"));
        assertEquals(0, dom.getElementsByTagName("script").getLength());
        assertEquals(1, dom.getElementsByTagName("table:table").getLength());
        assertEquals("教师活动学生活动", visibleText(dom.getElementsByTagName("table:table-header-rows").item(0)));
    }

    @Test void widePrintTableRetainsEveryCellAndLabelsRepeatedFirstColumn() throws Exception {
        var rows = List.of(IntStream.range(0, 15).mapToObj(i -> "字段" + i).toList(),
                IntStream.range(0, 15).mapToObj(i -> "内容" + i + "结束").toList());
        var source = new LessonDocument("1.1", List.of(LessonDocumentSupport.tableBlock("wide", rows)));
        var dom = parse(LessonDocumentPrintTemplate.render(source, "宽表", 2, true));
        assertEquals(4, dom.getElementsByTagName("table:table").getLength());
        assertTrue(dom.getDocumentElement().getTextContent().contains("首列重复"));
        assertTrue(dom.getDocumentElement().getTextContent().contains("文本兼容排版"));
        var cells = dom.getElementsByTagName("table:table-cell");
        var values = IntStream.range(0, cells.getLength()).mapToObj(i -> visibleText(cells.item(i))).toList();
        assertEquals(4, java.util.Collections.frequency(values, "内容0结束"));
        for (int i = 1; i < 15; i++) assertEquals(1, java.util.Collections.frequency(values, "内容" + i + "结束"));
    }

    @Test void whitespaceIsPreservedAsOdfTextElements() throws Exception {
        var source = new LessonDocument("1.1", List.of(new LessonDocument.Block("space", "paragraph", null,
                "  保留\t两格  与换行\n尾行  ", null, List.of(), List.of(), null)));
        var dom = parse(LessonDocumentPrintTemplate.render(source, "空白", 1, false));
        var paragraphs = dom.getElementsByTagName("text:p");
        assertEquals("  保留\t两格  与换行\n尾行  ", visibleText(paragraphs.item(paragraphs.getLength() - 1)));
    }

    private static String visibleText(org.w3c.dom.Node node) {
        if (node.getNodeType() == org.w3c.dom.Node.TEXT_NODE) return node.getNodeValue();
        if (node.getNodeName().equals("text:s")) return " ";
        if (node.getNodeName().equals("text:tab")) return "\t";
        if (node.getNodeName().equals("text:line-break")) return "\n";
        var result = new StringBuilder();
        for (var child = node.getFirstChild(); child != null; child = child.getNextSibling()) result.append(visibleText(child));
        return result.toString();
    }

    private static org.w3c.dom.Document parse(String html) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(html.getBytes(StandardCharsets.UTF_8)));
    }
}
