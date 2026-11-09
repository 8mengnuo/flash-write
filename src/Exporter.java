import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 多格式导出（TXT / Markdown / HTML / Word(docx) / RTF / PDF）。
 *
 * 前端把正文拆成「段落 → 文本片段（带粗体/斜体/下划线）」的扁平串传进来，
 * 这里只做排版和落盘，不碰 JavaFX，方便单独跑 --exporttest 做回归。
 *
 * 传输协议（都放在一个 String 里，避免桥接多次重入）：
 *   片段 = 掩码('0'~'7'，1=粗 2=斜 4=下划线) + '|' + 文本
 *   片段之间 \u0001，段落之间 \u0002
 */
public class Exporter {

    public static final class Run {
        public final String text;
        public final boolean bold, italic, underline;

        Run(String text, boolean bold, boolean italic, boolean underline) {
            this.text = text;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
        }
    }

    public static final class Block {
        public final List<Run> runs = new ArrayList<>();

        public boolean isEmpty() {
            for (Run r : runs) if (!r.text.isEmpty()) return false;
            return true;
        }

        public String text() {
            StringBuilder sb = new StringBuilder();
            for (Run r : runs) sb.append(r.text);
            return sb.toString();
        }
    }

    /* ---------------- 解析前端传来的片段串 ---------------- */

    public static List<Block> parse(String payload) {
        List<Block> out = new ArrayList<>();
        if (payload == null) return out;
        String[] blocks = payload.split("\u0002", -1);
        for (String bs : blocks) {
            Block b = new Block();
            if (!bs.isEmpty()) {
                for (String rs : bs.split("\u0001", -1)) {
                    if (rs.isEmpty()) continue;
                    int p = rs.indexOf('|');
                    String mask = p > 0 ? rs.substring(0, p) : "0";
                    String txt = p >= 0 ? rs.substring(p + 1) : rs;
                    if (txt.isEmpty()) continue;
                    int m = 0;
                    try { m = Integer.parseInt(mask.trim()); } catch (Exception ignored) {}
                    b.runs.add(new Run(txt, (m & 1) != 0, (m & 2) != 0, (m & 4) != 0));
                }
            }
            out.add(b);
        }
        return out;
    }

    /* ---------------- 纯文本 ---------------- */

    public static String txt(List<Block> blocks, String title, boolean withTitle) {
        StringBuilder sb = new StringBuilder();
        if (withTitle && title != null && !title.isEmpty()) sb.append(title).append("\n\n");
        for (Block b : blocks) sb.append(b.text()).append('\n');
        return sb.toString();
    }

    /* ---------------- Markdown ---------------- */

    public static String markdown(List<Block> blocks, String title, boolean withTitle) {
        StringBuilder sb = new StringBuilder();
        if (withTitle && title != null && !title.isEmpty()) sb.append("# ").append(title).append("\n\n");
        for (Block b : blocks) {
            String line = inlineMd(b);
            sb.append(line).append("\n\n");
        }
        String s = sb.toString();
        while (s.endsWith("\n\n")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String inlineMd(Block b) {
        StringBuilder sb = new StringBuilder();
        for (Run r : b.runs) {
            String t = r.text;
            if (t.isEmpty()) continue;
            boolean wrapB = r.bold && t.trim().length() > 0;
            boolean wrapI = r.italic && t.trim().length() > 0;
            String lead = leadingSpace(t), tail = trailingSpace(t);
            String core = t.trim().isEmpty() ? t : t.substring(lead.length(), t.length() - tail.length());
            if (core.isEmpty()) { sb.append(t); continue; }
            String piece = core;
            if (wrapI) piece = "*" + piece + "*";
            if (wrapB) piece = "**" + piece + "**";
            if (r.underline) piece = "<u>" + piece + "</u>";
            sb.append(lead).append(piece).append(tail);
        }
        return sb.toString();
    }

    private static String leadingSpace(String s) {
        int i = 0;
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        return s.substring(0, i);
    }

    private static String trailingSpace(String s) {
        int i = s.length();
        while (i > 0 && Character.isWhitespace(s.charAt(i - 1))) i--;
        return s.substring(i);
    }

    /* ---------------- HTML ---------------- */

    public static String html(List<Block> blocks, String title, boolean withTitle) {
        String t = title == null ? "" : title;
        String stamp = new SimpleDateFormat("yyyy-MM-dd HH:mm").format(new Date());
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"zh-CN\">\n<head>\n<meta charset=\"utf-8\">\n")
          .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">\n")
          .append("<title>").append(xml(t)).append("</title>\n<style>\n")
          .append(":root{--ink:#1b1b1f;--sub:#8a8a94;--bg:#ffffff;}\n")
          .append("*{box-sizing:border-box}\n")
          .append("body{margin:0;background:#f6f6f8;color:var(--ink);\n")
          .append("  font-family:\"Microsoft YaHei\",\"PingFang SC\",\"Noto Sans CJK SC\",system-ui,sans-serif;}\n")
          .append("article{max-width:760px;margin:0 auto;background:var(--bg);padding:56px 64px 80px;\n")
          .append("  box-shadow:0 1px 3px rgba(0,0,0,.08);min-height:100vh}\n")
          .append("h1{font-size:26px;line-height:1.4;margin:0 0 6px;letter-spacing:.02em}\n")
          .append(".meta{color:var(--sub);font-size:13px;margin-bottom:28px;\n")
          .append("  padding-bottom:16px;border-bottom:1px solid #ececf1}\n")
          .append("p{margin:0 0 1.05em;font-size:16.5px;line-height:1.95;text-align:justify;\n")
          .append("  text-indent:2em;word-break:break-word}\n")
          .append("p:empty{height:.6em}\n")
          .append("b,strong{font-weight:650}\n")
          .append("::selection{background:#ffe58a}\n")
          .append("@media (max-width:640px){article{padding:28px 20px}}\n")
          .append("@media print{body{background:#fff}article{box-shadow:none;padding:0}}\n")
          .append("</style>\n</head>\n<body>\n<article>\n");
        if (withTitle && !t.isEmpty()) sb.append("<h1>").append(xml(t)).append("</h1>\n");
        sb.append("<div class=\"meta\">").append(xml(stamp)).append(" · 闪写导出</div>\n");
        for (Block b : blocks) {
            sb.append("<p>");
            for (Run r : b.runs) {
                if (r.text.isEmpty()) continue;
                String piece = xml(r.text);
                if (r.underline) piece = "<u>" + piece + "</u>";
                if (r.italic) piece = "<em>" + piece + "</em>";
                if (r.bold) piece = "<strong>" + piece + "</strong>";
                sb.append(piece);
            }
            sb.append("</p>\n");
        }
        sb.append("</article>\n</body>\n</html>\n");
        return sb.toString();
    }

    /* ---------------- RTF ---------------- */

    public static String rtf(List<Block> blocks, String title, boolean withTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\\rtf1\\ansi\\ansicpg936\\deff0")
          .append("{\\fonttbl{\\f0\\fnil\\fcharset134 Microsoft YaHei;}}\n")
          .append("\\viewkind4\\uc1\\pard\\f0\\sl360\\slmult1\\fs24\n");
        if (withTitle && title != null && !title.isEmpty()) {
            sb.append("\\pard\\qc\\b\\fs36 ").append(rtfEsc(title)).append("\\b0\\fs24\\par\\pard\n");
        }
        for (Block b : blocks) {
            sb.append("\\pard\\fi480\\sl360\\slmult1\\fs24 ");
            for (Run r : b.runs) {
                if (r.text.isEmpty()) continue;
                if (r.bold) sb.append("\\b ");
                if (r.italic) sb.append("\\i ");
                if (r.underline) sb.append("\\ul ");
                sb.append(rtfEsc(r.text));
                if (r.underline) sb.append("\\ul0 ");
                if (r.italic) sb.append("\\i0 ");
                if (r.bold) sb.append("\\b0 ");
            }
            sb.append("\\par\n");
        }
        sb.append("}\n");
        return sb.toString();
    }

    private static String rtfEsc(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '{': sb.append("\\{"); break;
                case '}': sb.append("\\}"); break;
                case '\n': sb.append("\\line "); break;
                case '\r': break;
                case '\t': sb.append("\\tab "); break;
                default:
                    if (c < 128) sb.append(c);
                    else sb.append("\\u").append((int) c).append('?');
            }
        }
        return sb.toString();
    }

    /* ---------------- Word (.docx) ---------------- */

    public static void docx(File f, List<Block> blocks, String title, boolean withTitle) throws Exception {
        String doc = documentXml(blocks, title, withTitle);
        try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
            put(z, "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
              + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
              + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
              + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
              + "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>"
              + "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>"
              + "<Override PartName=\"/docProps/core.xml\" ContentType=\"application/vnd.openxmlformats-package.core-properties+xml\"/>"
              + "<Override PartName=\"/docProps/app.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.extended-properties+xml\"/>"
              + "</Types>");
            put(z, "_rels/.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
              + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>"
              + "<Relationship Id=\"rId2\" Type=\"http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties\" Target=\"docProps/core.xml\"/>"
              + "<Relationship Id=\"rId3\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/extended-properties\" Target=\"docProps/app.xml\"/>"
              + "</Relationships>");
            put(z, "word/_rels/document.xml.rels",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
              + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
              + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>"
              + "</Relationships>");
            put(z, "word/document.xml", doc);
            put(z, "word/styles.xml", stylesXml());
            put(z, "docProps/core.xml", coreXml(title));
            put(z, "docProps/app.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
              + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
              + "<Application>闪写 FlashWrite</Application></Properties>");
        }
    }

    private static void put(ZipOutputStream z, String name, String content) throws Exception {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private static String documentXml(List<Block> blocks, String title, boolean withTitle) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
          .append("<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">")
          .append("<w:body>");
        if (withTitle && title != null && !title.isEmpty()) {
            sb.append("<w:p><w:pPr><w:pStyle w:val=\"ZTitle\"/></w:pPr><w:r><w:rPr><w:b/>")
              .append("<w:sz w:val=\"36\"/></w:rPr><w:t xml:space=\"preserve\">")
              .append(xml(title)).append("</w:t></w:r></w:p>");
        }
        for (Block b : blocks) {
            sb.append("<w:p><w:pPr>");
            /* OOXML 里 pPr 子元素有顺序要求：spacing 必须在 ind 前面 */
            sb.append("<w:spacing w:line=\"360\" w:lineRule=\"auto\"/>");
            /* firstLineChars=200（2 字符）+ firstLine 兜底；Word/WPS 都认 */
            sb.append("<w:ind w:firstLineChars=\"200\" w:firstLine=\"480\"/></w:pPr>");
            for (Run r : b.runs) {
                if (r.text.isEmpty()) continue;
                sb.append("<w:r>");
                if (r.bold || r.italic || r.underline) {
                    sb.append("<w:rPr>");
                    if (r.bold) sb.append("<w:b/>");
                    if (r.italic) sb.append("<w:i/>");
                    if (r.underline) sb.append("<w:u w:val=\"single\"/>");
                    sb.append("</w:rPr>");
                }
                sb.append("<w:t xml:space=\"preserve\">").append(xml(r.text)).append("</w:t></w:r>");
            }
            sb.append("</w:p>");
        }
        sb.append("<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>")
          .append("<w:pgMar w:top=\"1440\" w:right=\"1440\" w:bottom=\"1440\" w:left=\"1440\" ")
          .append("w:header=\"851\" w:footer=\"992\" w:gutter=\"0\"/></w:sectPr>")
          .append("</w:body></w:document>");
        return sb.toString();
    }

    private static String stylesXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
             + "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">"
             + "<w:docDefaults><w:rPrDefault><w:rPr>"
             /* 全部用宋体：Times New Roman 没有全角空格(U+3000)等字符，
                用户"段首缩进"插的 \u3000 到 Word 里就变成框框 —— 这就是框框的根因。
                再加 hint=eastAsia，引号等"两可字符"也强制走中文字体。 */
             + "<w:rFonts w:hint=\"eastAsia\" w:ascii=\"宋体\" w:hAnsi=\"宋体\" w:eastAsia=\"宋体\"/>"
             + "<w:sz w:val=\"24\"/><w:szCs w:val=\"24\"/></w:rPr></w:rPrDefault>"
             + "<w:pPrDefault><w:pPr><w:spacing w:line=\"360\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>"
             + "</w:docDefaults>"
             + "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\">"
             + "<w:name w:val=\"Normal\"/><w:qFormat/></w:style>"
             + "<w:style w:type=\"paragraph\" w:styleId=\"ZTitle\"><w:name w:val=\"ZTitle\"/>"
             + "<w:basedOn w:val=\"Normal\"/><w:qFormat/>"
             + "<w:pPr><w:spacing w:before=\"240\" w:after=\"360\"/>"
             + "<w:ind w:firstLineChars=\"0\" w:firstLine=\"0\"/><w:jc w:val=\"center\"/></w:pPr>"
             + "<w:rPr><w:b/><w:sz w:val=\"36\"/></w:rPr></w:style>"
             + "</w:styles>";
    }

    private static String coreXml(String title) {
        String iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(new Date());
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
             + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" "
             + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" "
             + "xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">"
             + "<dc:title>" + xml(title == null ? "" : title) + "</dc:title>"
             + "<dc:creator>闪写 FlashWrite</dc:creator>"
             + "<cp:lastModifiedBy>闪写 FlashWrite</cp:lastModifiedBy>"
             + "<dcterms:created xsi:type=\"dcterms:W3CDTF\">" + iso + "</dcterms:created>"
             + "<dcterms:modified xsi:type=\"dcterms:W3CDTF\">" + iso + "</dcterms:modified>"
             + "</cp:coreProperties>";
    }

    /* ---------------- PDF ---------------- */

    private static final float A4W = PDRectangle.A4.getWidth();
    private static final float A4H = PDRectangle.A4.getHeight();
    private static final float MARGIN_L = 62f, MARGIN_R = 62f;
    private static final float MARGIN_T = 78f, MARGIN_B = 76f;
    private static final float BODY_SIZE = 12f;
    private static final float LEADING = 22.5f;

    public static void pdf(File f, List<Block> blocks, String title, boolean withTitle) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            PDFont font = loadCjkFont(doc);
            PdfPen pen = new PdfPen(doc, font);

            if (withTitle && title != null && !title.isEmpty()) {
                pen.ensure(46);
                for (String line : pen.wrap(title, 17f, A4W - MARGIN_L - MARGIN_R)) {
                    pen.line(line, 17f, MARGIN_L, true, false, true);
                }
                pen.y -= 16;
            }

            for (Block b : blocks) {
                if (b.isEmpty()) { pen.ensure(LEADING); pen.y -= LEADING * 0.5f; continue; }
                /* 按片段顺序折行：粗体/下划线在行内逐段绘制 */
                List<Line> lines = pen.layout(b);
                boolean first = true;
                for (Line ln : lines) {
                    pen.ensure(LEADING);
                    float x = MARGIN_L + (first ? BODY_SIZE * 2 : 0);
                    for (Seg s : ln.segs) {
                        pen.seg(s.text, BODY_SIZE, x, s.run.bold, s.run.underline, false);
                        x += pen.width(s.text, BODY_SIZE);
                    }
                    pen.y -= LEADING;
                    first = false;
                }
            }

            pen.finish();
            /* 页脚页码 */
            int total = doc.getNumberOfPages();
            for (int i = 0; i < total; i++) {
                PDPage page = doc.getPage(i);
                try (PDPageContentStream cs = new PDPageContentStream(
                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                    String s = "- " + (i + 1) + " / " + total + " -";
                    float w = width(s, font, 9f);
                    cs.beginText();
                    cs.setFont(font, 9f);
                    cs.newLineAtOffset((A4W - w) / 2f, 34f);
                    cs.showText(s);
                    cs.endText();
                }
            }
            doc.save(f);
        }
    }

    /** 一行里的若干片段（共享同一 Run 的样式） */
    private static final class Seg {
        String text;
        final Run run;

        Seg(String text, Run run) { this.text = text; this.run = run; }
    }

    private static final class Line {
        final List<Seg> segs = new ArrayList<>();
        float width = 0;
        int chars = 0;

        void append(String ch, Run r, float w) {
            Seg last = segs.isEmpty() ? null : segs.get(segs.size() - 1);
            if (last != null && last.run == r) last.text += ch;
            else segs.add(new Seg(ch, r));
            width += w;
            chars++;
        }

        /** 摘掉行末最后一个码位（行首禁则用），返回 [文本, 宽度] */
        Object[] popLast(PDFont font) throws Exception {
            if (segs.isEmpty()) return null;
            Seg last = segs.get(segs.size() - 1);
            if (last.text.isEmpty()) { segs.remove(last); return null; }
            int cp = last.text.codePointBefore(last.text.length());
            int len = Character.charCount(cp);
            String carried = last.text.substring(last.text.length() - len);
            last.text = last.text.substring(0, last.text.length() - len);
            if (last.text.isEmpty()) segs.remove(segs.size() - 1);
            float w = font.getStringWidth(carried) / 1000f * BODY_SIZE;
            width -= w;
            chars--;
            return new Object[]{carried, w};
        }
    }

    /** 折行 + 逐行绘制的画笔 */
    private static final class PdfPen {
        final PDDocument doc;
        final PDFont font;
        PDPageContentStream cs;
        float y;

        PdfPen(PDDocument doc, PDFont font) throws Exception {
            this.doc = doc;
            this.font = font;
            newPage();
        }

        void newPage() throws Exception {
            if (cs != null) { cs.close(); cs = null; }
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            cs = new PDPageContentStream(doc, page);
            y = A4H - MARGIN_T;
        }

        void ensure(float h) throws Exception {
            if (y - h < MARGIN_B) newPage();
        }

        float width(String s, float size) throws Exception {
            if (s == null || s.isEmpty()) return 0;
            return font.getStringWidth(safe(s)) / 1000f * size;
        }

        /* 字体没有的码位（emoji 等）换成 ?，否则 showText 会抛异常整篇导出失败 */
        String safe(String s) {
            StringBuilder sb = new StringBuilder(s.length());
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                int len = Character.charCount(cp);
                String ch = s.substring(i, i + len);
                boolean ok;
                try {
                    font.encode(ch);
                    ok = true;
                } catch (Exception e) {
                    ok = false;
                }
                sb.append(ok ? ch : "?");
                i += len;
            }
            return sb.toString();
        }

        void line(String text, float size, float x, boolean bold, boolean underline,
                  boolean center) throws Exception {
            String s = safe(text);
            float w = font.getStringWidth(s) / 1000f * size;
            if (center) x = (A4W - w) / 2f;
            draw(s, size, x, y, bold, underline, w);
        }

        void seg(String text, float size, float x, boolean bold, boolean underline,
                 boolean center) throws Exception {
            String s = safe(text);
            float w = font.getStringWidth(s) / 1000f * size;
            if (center) x = (A4W - w) / 2f;
            draw(s, size, x, y, bold, underline, w);
        }

        private void draw(String s, float size, float x, float y, boolean bold,
                          boolean underline, float w) throws Exception {
            if (s.isEmpty()) return;
            cs.beginText();
            cs.setFont(font, size);
            cs.newLineAtOffset(x, y);
            cs.showText(s);
            cs.endText();
            if (bold) {
                /* 伪粗体：同一串再描一遍，偏移 0.35pt —— 不依赖字体里有没有 Bold 字面 */
                cs.beginText();
                cs.setFont(font, size);
                cs.newLineAtOffset(x + 0.35f, y);
                cs.showText(s);
                cs.endText();
            }
            if (underline) {
                cs.setLineWidth(0.6f);
                cs.moveTo(x, y - 2.2f);
                cs.lineTo(x + w, y - 2.2f);
                cs.stroke();
            }
        }

        void finish() throws Exception {
            if (cs != null) { cs.close(); cs = null; }
        }

        /** 标题之类的单行折行 */
        List<String> wrap(String text, float size, float maxW) throws Exception {
            List<String> out = new ArrayList<>();
            String s = safe(text == null ? "" : text);
            StringBuilder cur = new StringBuilder();
            float w = 0;
            for (int i = 0; i < s.length(); ) {
                int cp = s.codePointAt(i);
                int len = Character.charCount(cp);
                String ch = s.substring(i, i + len);
                float cw = font.getStringWidth(ch) / 1000f * size;
                if (w + cw > maxW && cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    w = 0;
                }
                cur.append(ch);
                w += cw;
                i += len;
            }
            if (cur.length() > 0) out.add(cur.toString());
            if (out.isEmpty()) out.add("");
            return out;
        }

        /** 逐片段折行（中文按字断，行首避开标点） */
        List<Line> layout(Block b) throws Exception {
            List<Line> out = new ArrayList<>();
            Line cur = new Line();
            float maxW = A4W - MARGIN_L - MARGIN_R;
            float limit = maxW - BODY_SIZE * 2;      // 首行缩进两字
            for (Run r : b.runs) {
                String s = safe(r.text);
                int i = 0;
                while (i < s.length()) {
                    int cp = s.codePointAt(i);
                    int len = Character.charCount(cp);
                    String ch = s.substring(i, i + len);
                    float w = font.getStringWidth(ch) / 1000f * BODY_SIZE;
                    float curLimit = out.isEmpty() ? limit : maxW;
                    if (cur.width + w > curLimit && cur.width > 0) {
                        /* 行首禁则：标点不能出现在行首时，把上一行最后一个字带下来 */
                        if (isNoStart(ch) && cur.chars > 1) {
                            Object[] carried = cur.popLast(font);
                            out.add(cur);
                            cur = new Line();
                            if (carried != null)
                                cur.append((String) carried[0], r, ((Float) carried[1]));
                        } else {
                            out.add(cur);
                            cur = new Line();
                        }
                        if (" ".equals(ch)) { i += len; continue; }   // 折行处不留空格
                    }
                    cur.append(ch, r, w);
                    i += len;
                }
            }
            if (cur.chars > 0 || out.isEmpty()) out.add(cur);
            return out;
        }

        private static boolean isNoStart(String ch) {
            return "，。、；：！？）》」』】…—,.;:!?)]}\"'".contains(ch);
        }
    }

    private static float width(String s, PDFont font, float size) throws Exception {
        return font.getStringWidth(s) / 1000f * size;
    }

    /** 找一个带中文字形的字体：优先单文件 TTF，其次 TTC，最后退回内置 Helvetica */
    private static PDFont loadCjkFont(PDDocument doc) throws Exception {
        String[] ttf = {
                "C:/Windows/Fonts/simhei.ttf",
                "C:/Windows/Fonts/Deng.ttf",
                "C:/Windows/Fonts/simkai.ttf",
                "C:/Windows/Fonts/simfang.ttf",
                "C:/Windows/Fonts/msyh.ttf",
                System.getProperty("user.home") + "/AppData/Local/Microsoft/Windows/Fonts/msyh.ttf"
        };
        for (String p : ttf) {
            File f = new File(p);
            if (!f.isFile()) continue;
            try {
                return PDType0Font.load(doc, f);
            } catch (Exception ignored) {}
        }
        String[] ttc = {"C:/Windows/Fonts/msyh.ttc", "C:/Windows/Fonts/simsun.ttc"};
        for (String p : ttc) {
            File f = new File(p);
            if (!f.isFile()) continue;
            try (TrueTypeCollection c = new TrueTypeCollection(f)) {
                final TrueTypeFont[] pick = new TrueTypeFont[1];
                c.processAllFonts(new TrueTypeCollection.TrueTypeFontProcessor() {
                    @Override
                    public void process(TrueTypeFont ttf) {
                        if (pick[0] == null) pick[0] = ttf;
                    }
                });
                if (pick[0] != null) return PDType0Font.load(doc, pick[0], true);
            } catch (Exception ignored) {}
        }
        return PDType1Font.HELVETICA;
    }

    /* ---------------- 统一入口 ---------------- */

    /** fmt: txt / md / html / docx / rtf / pdf */
    public static void export(File f, String fmt, String payload, String title, boolean withTitle)
            throws Exception {
        List<Block> blocks = parse(payload);
        String m = fmt == null ? "txt" : fmt.toLowerCase();
        switch (m) {
            case "md": case "markdown": writeText(f, markdown(blocks, title, withTitle)); break;
            case "html": case "htm": writeText(f, html(blocks, title, withTitle)); break;
            case "rtf": writeText(f, rtf(blocks, title, withTitle)); break;
            case "docx": docx(f, blocks, title, withTitle); break;
            case "pdf": pdf(f, blocks, title, withTitle); break;
            default: writeText(f, txt(blocks, title, withTitle)); break;
        }
    }

    private static void writeText(File f, String s) throws Exception {
        try (OutputStream o = new FileOutputStream(f)) {
            o.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String xml(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                case '\'': sb.append("&apos;"); break;
                default:
                    if (c < 0x20 && c != '\t') sb.append(' ');
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /* ---------------- 回归自测（--exporttest） ---------------- */

    public static void selfTest() {
        int pass = 0, fail = 0;
        File dir = new File("build/exp_test");
        dir.mkdirs();
        String payload = "0|起风了\u0002"
                       + "1|沈砚之\u00010|把刀收进袖子里，抬头看了看天。\u0002"
                       + "0|\u0002"
                       + "2|“走吧。”他说。\u0002"
                       + "4|下划线一段\u0002"
                       + "0|Emoji \uD83D\uDE00 也要能导出，不能整篇炸掉。";
        String title = "第一章 起风了";
        try {
            File t = new File(dir, "a.txt");
            export(t, "txt", payload, title, true);
            String got = new String(Files.readAllBytes(t.toPath()), StandardCharsets.UTF_8);
            if (got.contains("沈砚之") && got.contains(title)) { pass++; System.out.println("PASS txt"); }
            else { fail++; System.out.println("FAIL txt [" + got + "]"); }

            File md = new File(dir, "a.md");
            export(md, "md", payload, title, true);
            String g2 = new String(Files.readAllBytes(md.toPath()), StandardCharsets.UTF_8);
            if (g2.startsWith("# ") && g2.contains("**沈砚之**") && g2.contains("<u>")) { pass++; System.out.println("PASS md"); }
            else { fail++; System.out.println("FAIL md [" + g2.replace("\n", "\\n") + "]"); }

            File h = new File(dir, "a.html");
            export(h, "html", payload, title, true);
            String g3 = new String(Files.readAllBytes(h.toPath()), StandardCharsets.UTF_8);
            if (g3.contains("<h1>") && g3.contains("<strong>沈砚之</strong>") && g3.contains("</html>")) { pass++; System.out.println("PASS html"); }
            else { fail++; System.out.println("FAIL html"); }

            File r = new File(dir, "a.rtf");
            export(r, "rtf", payload, title, true);
            String g4 = new String(Files.readAllBytes(r.toPath()), StandardCharsets.UTF_8);
            if (g4.startsWith("{\\rtf1") && g4.contains("\\u27784?") && g4.endsWith("}\n")) { pass++; System.out.println("PASS rtf"); }
            else { fail++; System.out.println("FAIL rtf [" + g4.substring(0, Math.min(80, g4.length())) + "]"); }

            File d = new File(dir, "a.docx");
            export(d, "docx", payload, title, true);
            String xmlDoc = readZipEntry(d, "word/document.xml");
            boolean xmlOk = wellFormed(xmlDoc)
                    && wellFormed(readZipEntry(d, "[Content_Types].xml"))
                    && wellFormed(readZipEntry(d, "word/styles.xml"))
                    && wellFormed(readZipEntry(d, "_rels/.rels"))
                    && wellFormed(readZipEntry(d, "docProps/core.xml"));
            /* 框框回归：全角空格必须走中文字体（hint=eastAsia + 宋体），
               且 pPr 里 spacing 必须在 ind 前面（OOXML 顺序要求） */
            String styles = readZipEntry(d, "word/styles.xml");
            boolean fontOk = styles.contains("w:hint=\"eastAsia\"") && styles.contains("宋体");
            boolean orderOk = xmlDoc.contains(
                    "<w:spacing w:line=\"360\" w:lineRule=\"auto\"/><w:ind w:firstLineChars=\"200\"");
            if (d.length() > 800 && xmlOk && fontOk && orderOk && xmlDoc.contains("沈砚之")
                    && xmlDoc.contains("第一章")) { pass++; System.out.println("PASS docx (" + d.length() + "B)"); }
            else { fail++; System.out.println("FAIL docx len=" + d.length() + " xmlOk=" + xmlOk
                       + " fontOk=" + fontOk + " orderOk=" + orderOk); }

            File p = new File(dir, "a.pdf");
            export(p, "pdf", payload, title, true);
            String extracted;
            try (PDDocument doc = PDDocument.load(p)) {
                org.apache.pdfbox.text.PDFTextStripper st = new org.apache.pdfbox.text.PDFTextStripper() {
                    @Override
                    protected void writeString(String text,
                            java.util.List<org.apache.pdfbox.text.TextPosition> pos)
                            throws java.io.IOException {
                        if (!pos.isEmpty())
                            System.out.println("PDFX x=" + pos.get(0).getXDirAdj() + " [" + text + "]");
                        super.writeString(text, pos);
                    }
                };
                extracted = st.getText(doc);
            }
            boolean pdfOk = p.length() > 1000 && extracted.contains("沈砚之") && extracted.contains("下划线一段")
                    && extracted.contains("Emoji");
            if (pdfOk) { pass++; System.out.println("PASS pdf (" + p.length() + "B, text ok)"); }
            else { fail++; System.out.println("FAIL pdf len=" + p.length() + " text=[" + extracted.replace("\n", "|") + "]"); }

            /* 把 PDF 首页渲染成 PNG，人工核对字形（有没有豆腐块 / 乱码） */
            try (PDDocument doc = PDDocument.load(p)) {
                org.apache.pdfbox.rendering.PDFRenderer rd = new org.apache.pdfbox.rendering.PDFRenderer(doc);
                java.awt.image.BufferedImage img = rd.renderImageWithDPI(0, 110);
                javax.imageio.ImageIO.write(img, "png", new File(dir, "a_pdf_p1.png"));
                System.out.println("PNG build/exp_test/a_pdf_p1.png " + img.getWidth() + "x" + img.getHeight());
            }
        } catch (Exception e) {
            fail++;
            System.out.println("FAIL exception " + e);
            e.printStackTrace();
        }
        System.out.println("EXPORTTEST pass=" + pass + " fail=" + fail);
    }

    private static String readZipEntry(File f, String name) throws Exception {
        try (java.util.zip.ZipFile z = new java.util.zip.ZipFile(f)) {
            java.util.zip.ZipEntry e = z.getEntry(name);
            if (e == null) return "";
            try (java.io.InputStream in = z.getInputStream(e)) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
                return new String(bo.toByteArray(), StandardCharsets.UTF_8);
            }
        }
    }

    private static boolean wellFormed(String xml) {
        try {
            javax.xml.parsers.DocumentBuilderFactory f = javax.xml.parsers.DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            return true;
        } catch (Exception e) {
            System.out.println("XML_ERR " + e.getMessage());
            return false;
        }
    }
}
