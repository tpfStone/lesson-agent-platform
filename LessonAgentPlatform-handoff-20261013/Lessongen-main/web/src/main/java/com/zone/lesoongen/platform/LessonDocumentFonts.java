package com.zone.lesoongen.platform;

import java.io.IOException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument;

/** The same explicit fonts apply to Chinese, Latin, numbers and newly typed text. */
final class LessonDocumentFonts {
    static final String BODY = "宋体";
    static final String HEADING = "黑体";
    private LessonDocumentFonts() {}

    static void apply(XWPFRun run, boolean heading) {
        for (var range : XWPFRun.FontCharRange.values()) run.setFontFamily(heading ? HEADING : BODY, range);
    }

    static void initialize(XWPFDocument document) throws IOException {
        String normal = fonts(BODY);
        var xml = new StringBuilder("<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
                .append("<w:docDefaults><w:rPrDefault><w:rPr>").append(normal)
                .append("<w:sz w:val=\"22\"/></w:rPr></w:rPrDefault></w:docDefaults>")
                .append("<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/>")
                .append("<w:rPr>").append(normal).append("</w:rPr></w:style>");
        for (int level = 1; level <= 9; level++) {
            xml.append("<w:style w:type=\"paragraph\" w:styleId=\"Heading").append(level)
                    .append("\"><w:name w:val=\"heading ").append(level)
                    .append("\"/><w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:qFormat/>")
                    .append("<w:pPr><w:keepNext/><w:outlineLvl w:val=\"").append(level - 1)
                    .append("\"/></w:pPr><w:rPr>").append(fonts(HEADING))
                    .append("<w:b/><w:sz w:val=\"").append(level == 1 ? 32 : level == 2 ? 28 : 24)
                    .append("\"/></w:rPr></w:style>");
        }
        xml.append("<w:style w:type=\"paragraph\" w:styleId=\"Title\"><w:name w:val=\"Title\"/>")
                .append("<w:basedOn w:val=\"Normal\"/><w:next w:val=\"Normal\"/><w:rPr>")
                .append(fonts(HEADING)).append("<w:b/><w:sz w:val=\"36\"/></w:rPr></w:style></w:styles>");
        try { document.createStyles().setStyles(StylesDocument.Factory.parse(xml.toString()).getStyles()); }
        catch (org.apache.xmlbeans.XmlException error) { throw new IOException("Invalid generated document styles", error); }
    }

    private static String fonts(String name) {
        return "<w:rFonts w:ascii=\"" + name + "\" w:hAnsi=\"" + name + "\" w:eastAsia=\"" + name + "\" w:cs=\"" + name + "\"/>";
    }
}
