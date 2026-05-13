package services;

import com.lowagie.text.DocumentException;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.BulletListItem;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.HardLineBreak;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.OrderedListItem;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STTblWidth;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.xhtmlrenderer.pdf.ITextRenderer;

import java.io.FileOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Markdown-in, document-out writer.
 *
 * <p>All public entry points take a workspace-resolved target {@link Path} and
 * a markdown string and write the rendered document to disk. Flexmark parses
 * the markdown; POI XWPF handles DOCX; Flying Saucer + OpenPDF handles PDF via
 * an intermediate XHTML representation shared with the HTML output path.
 *
 * <p>XLSX and PPTX are explicitly out of scope for this iteration — their
 * natural input is structured data, not markdown.
 */
public class DocumentWriter {

    private DocumentWriter() {}

    private static final MutableDataSet FLEXMARK_OPTIONS = new MutableDataSet()
            .set(Parser.EXTENSIONS, List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListExtension.create(),
                    AutolinkExtension.create()));

    private static final Parser PARSER = Parser.builder(FLEXMARK_OPTIONS).build();
    private static final HtmlRenderer HTML_RENDERER = HtmlRenderer.builder(FLEXMARK_OPTIONS).build();

    private static final String DEFAULT_CSS = """
            body { font-family: 'Helvetica', 'Arial', sans-serif; font-size: 11pt; color: #222; line-height: 1.5; max-width: 800px; margin: 1em auto; }
            h1 { font-size: 22pt; margin-top: 0.8em; margin-bottom: 0.4em; }
            h2 { font-size: 18pt; margin-top: 0.8em; margin-bottom: 0.3em; }
            h3 { font-size: 15pt; margin-top: 0.7em; margin-bottom: 0.3em; }
            h4, h5, h6 { font-size: 12pt; margin-top: 0.6em; margin-bottom: 0.3em; }
            p { margin: 0.4em 0; }
            code { font-family: 'Courier New', monospace; background: #f2f2f2; padding: 1px 4px; border-radius: 3px; }
            pre { font-family: 'Courier New', monospace; background: #f2f2f2; padding: 8px 10px; border-radius: 3px; white-space: pre-wrap; }
            blockquote { border-left: 3px solid #bbb; padding-left: 10px; color: #555; margin: 0.6em 0; }
            table { border-collapse: collapse; margin: 0.6em 0; width: 100%; table-layout: auto; }
            th, td { border: 1px solid #888; padding: 4px 8px; text-align: left; vertical-align: top; }
            th { background: #eee; }
            hr { border: none; border-top: 1px solid #ccc; margin: 1em 0; }
            ul, ol { margin: 0.4em 0 0.4em 1.5em; padding: 0; }
            """;

    public static void writeHtml(Path target, String markdown) throws IOException {
        Files.createDirectories(target.getParent());
        Files.writeString(target, renderFullHtml(markdown), StandardCharsets.UTF_8);
    }

    public static void writePdf(Path target, String markdown) throws IOException {
        Files.createDirectories(target.getParent());
        var xhtml = toXhtml(renderFullHtml(markdown));
        try (var fos = new FileOutputStream(target.toFile())) {
            var renderer = new ITextRenderer();
            renderer.setDocumentFromString(xhtml);
            renderer.layout();
            renderer.createPDF(fos);
        } catch (DocumentException e) {
            throw new IOException("PDF rendering failed: " + e.getMessage(), e);
        }
    }

    public static void writeDocx(Path target, String markdown) throws IOException {
        Files.createDirectories(target.getParent());
        Node root = PARSER.parse(markdown);
        try (var doc = new XWPFDocument(); var fos = new FileOutputStream(target.toFile())) {
            var visitor = new DocxVisitor(doc);
            visitor.visitChildren(root);
            doc.write(fos);
        }
    }

    // ------------------------------------------------------------------
    // HTML path
    // ------------------------------------------------------------------

    private static String renderFullHtml(String markdown) {
        Node doc = PARSER.parse(markdown);
        String body = HTML_RENDERER.render(doc);
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8"/>
                <title>Document</title>
                <style>%s</style>
                </head>
                <body>
                %s
                </body>
                </html>
                """.formatted(DEFAULT_CSS, body);
    }

    private static String toXhtml(String html) {
        // Flying Saucer requires well-formed XHTML. Jsoup re-serialises with
        // self-closing void elements and properly escaped entities.
        Document parsed = Jsoup.parse(html);
        parsed.outputSettings()
                .syntax(Document.OutputSettings.Syntax.xml)
                .charset(StandardCharsets.UTF_8)
                .prettyPrint(false);
        return parsed.outerHtml();
    }

    // ------------------------------------------------------------------
    // DOCX path — AST visitor using POI XWPF
    // ------------------------------------------------------------------

    /**
     * Minimal POI XWPF writer. Avoids styles.xml and numbering.xml by inlining
     * formatting directly (font size, bold, indent, bullet character). The
     * result opens cleanly in Word / Pages / LibreOffice and matches the
     * structure of the source markdown, though without Word's native heading
     * or list-numbering styles.
     */
    private static class DocxVisitor {
        // Font sizes in half-points (DOCX native unit)
        private static final int BODY_FONT_SIZE = 11;
        private static final int CODE_FONT_SIZE = 10;
        // Spacing in twips (1/20 of a point, DOCX native unit)
        private static final int HEADING_SPACING_BEFORE = 240;
        private static final int HEADING_SPACING_AFTER = 120;
        private static final int CODE_BLOCK_SPACING = 120;
        private static final int CODE_BLOCK_INDENT = 200;
        private static final int LIST_INDENT_PER_LEVEL = 360;
        private static final int BLOCKQUOTE_INDENT = 360;
        // Table width in DXA (~6.25" of Letter/A4 content area at 1440 DXA/inch)
        private static final int TABLE_TOTAL_WIDTH = 9000;

        private final XWPFDocument doc;
        private XWPFParagraph currentParagraph;
        private int listDepth;
        private int orderedCounter;
        private boolean inOrderedList;

        DocxVisitor(XWPFDocument doc) { this.doc = doc; }

        void visitChildren(Node parent) {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                visit(child);
            }
        }

        void visit(Node node) {
            switch (node) {
                case Heading h            -> visitHeading(h);
                case Paragraph p          -> visitParagraph(p);
                case BulletList b         -> visitBulletList(b);
                case OrderedList o        -> visitOrderedList(o);
                case FencedCodeBlock f    -> visitCodeBlock(f.getContentChars().toString());
                case IndentedCodeBlock i  -> visitCodeBlock(i.getContentChars().toString());
                case BlockQuote q         -> visitBlockQuote(q);
                case ThematicBreak _      -> visitHorizontalRule();
                case TableBlock t         -> visitTable(t);
                case HtmlBlock hb         -> visitParagraphWithText(hb.getChars().toString());
                default                   -> visitChildren(node);
            }
        }

        private void visitHeading(Heading node) {
            var p = doc.createParagraph();
            p.setSpacingBefore(HEADING_SPACING_BEFORE);
            p.setSpacingAfter(HEADING_SPACING_AFTER);
            currentParagraph = p;
            int level = Math.clamp(node.getLevel(), 1, 6);
            int fontSize = switch (level) {
                case 1 -> 22;
                case 2 -> 18;
                case 3 -> 15;
                case 4 -> 13;
                case 5 -> 12;
                default -> 11;
            };
            emitInline(node, true, false, false, false, fontSize, null);
            currentParagraph = null;
        }

        private void visitParagraph(Paragraph node) {
            // Tight paragraphs inside list items don't get extra spacing.
            var parent = node.getParent();
            boolean inListItem = parent instanceof BulletListItem || parent instanceof OrderedListItem;
            if (inListItem) {
                // Reuse the list item's current paragraph.
                emitInline(node, false, false, false, false, BODY_FONT_SIZE, null);
            } else {
                var p = doc.createParagraph();
                currentParagraph = p;
                emitInline(node, false, false, false, false, BODY_FONT_SIZE, null);
                currentParagraph = null;
            }
        }

        private void visitParagraphWithText(String text) {
            var p = doc.createParagraph();
            var r = p.createRun();
            r.setText(text);
        }

        private void visitBulletList(BulletList node) {
            boolean wasOrdered = inOrderedList;
            inOrderedList = false;
            listDepth++;
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                visitListItem(child, "• ");
            }
            listDepth--;
            inOrderedList = wasOrdered;
        }

        private void visitOrderedList(OrderedList node) {
            boolean wasOrdered = inOrderedList;
            int previousCounter = orderedCounter;
            inOrderedList = true;
            orderedCounter = 0;
            listDepth++;
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                orderedCounter++;
                visitListItem(child, orderedCounter + ". ");
            }
            listDepth--;
            inOrderedList = wasOrdered;
            orderedCounter = previousCounter;
        }

        private void visitListItem(Node item, String marker) {
            var p = doc.createParagraph();
            p.setIndentationLeft(LIST_INDENT_PER_LEVEL * listDepth);
            currentParagraph = p;
            var markerRun = p.createRun();
            markerRun.setText(marker);
            // Render the item's inline content.
            for (Node child = item.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Paragraph para) {
                    emitInline(para, false, false, false, false, BODY_FONT_SIZE, null);
                } else if (child instanceof BulletList || child instanceof OrderedList) {
                    currentParagraph = null;
                    visit(child);
                    // Subsequent siblings of the nested list stay in a fresh item paragraph.
                    var cont = doc.createParagraph();
                    cont.setIndentationLeft(360 * listDepth);
                    currentParagraph = cont;
                } else {
                    emitInline(child, false, false, false, false, BODY_FONT_SIZE, null);
                }
            }
            currentParagraph = null;
        }

        private void visitCodeBlock(String content) {
            var p = doc.createParagraph();
            p.setSpacingBefore(CODE_BLOCK_SPACING);
            p.setSpacingAfter(CODE_BLOCK_SPACING);
            p.setIndentationLeft(CODE_BLOCK_INDENT);
            var lines = content.split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                var r = p.createRun();
                r.setFontFamily("Courier New");
                r.setFontSize(CODE_FONT_SIZE);
                r.setText(lines[i]);
                if (i < lines.length - 1) r.addBreak();
            }
        }

        private void visitBlockQuote(BlockQuote node) {
            var p = doc.createParagraph();
            p.setIndentationLeft(BLOCKQUOTE_INDENT);
            p.setAlignment(ParagraphAlignment.LEFT);
            currentParagraph = p;
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                if (child instanceof Paragraph para) {
                    emitInline(para, false, true, false, false, BODY_FONT_SIZE, null);
                    var br = p.createRun();
                    br.addBreak();
                } else {
                    visit(child);
                }
            }
            currentParagraph = null;
        }

        private void visitHorizontalRule() {
            var p = doc.createParagraph();
            var r = p.createRun();
            r.setText("────────────────────────────────────");
            r.setColor("888888");
        }

        private void visitTable(TableBlock table) {
            // Count rows and columns.
            int rows = 0;
            int cols = 0;
            for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
                if (section instanceof TableHead || section instanceof TableBody) {
                    for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                        if (row instanceof TableRow tr) {
                            rows++;
                            int c = 0;
                            for (Node cell = tr.getFirstChild(); cell != null; cell = cell.getNext()) {
                                if (cell instanceof TableCell) c++;
                            }
                            cols = Math.max(cols, c);
                        }
                    }
                }
            }
            if (rows == 0 || cols == 0) return;

            XWPFTable xwpfTable = doc.createTable(rows, cols);
            // POI's createTable leaves the grid and cell widths unset, which makes
            // Word / LibreOffice fall back to minimum content width — long words end
            // up wrapping one character per line. Pin a sensible total width
            // (~6.25" of Letter/A4 content area at 1440 DXA/inch) and distribute
            // evenly across columns in both tblGrid and per-cell tcW.
            final int totalWidth = TABLE_TOTAL_WIDTH;
            final int colWidth = totalWidth / cols;
            xwpfTable.setWidth(String.valueOf(totalWidth));
            var ctTbl = xwpfTable.getCTTbl();
            var ctGrid = ctTbl.getTblGrid() != null ? ctTbl.getTblGrid() : ctTbl.addNewTblGrid();
            while (ctGrid.sizeOfGridColArray() > 0) ctGrid.removeGridCol(0);
            for (int i = 0; i < cols; i++) {
                ctGrid.addNewGridCol().setW(BigInteger.valueOf(colWidth));
            }

            int rowIdx = 0;
            for (Node section = table.getFirstChild(); section != null; section = section.getNext()) {
                boolean isHeader = section instanceof TableHead;
                if (section instanceof TableHead || section instanceof TableBody) {
                    for (Node row = section.getFirstChild(); row != null; row = row.getNext()) {
                        if (!(row instanceof TableRow tr)) continue;
                        int colIdx = 0;
                        XWPFTableRow xwpfRow = xwpfTable.getRow(rowIdx);
                        for (Node cell = tr.getFirstChild(); cell != null; cell = cell.getNext()) {
                            if (!(cell instanceof TableCell tc)) continue;
                            XWPFTableCell xwpfCell = xwpfRow.getCell(colIdx);
                            // Fix the cell width too (tcW) — some viewers ignore
                            // tblGrid unless individual cells also declare a width.
                            var ctTc = xwpfCell.getCTTc();
                            var tcPr = ctTc.getTcPr() != null ? ctTc.getTcPr() : ctTc.addNewTcPr();
                            var tcW = tcPr.isSetTcW() ? tcPr.getTcW() : tcPr.addNewTcW();
                            tcW.setW(BigInteger.valueOf(colWidth));
                            tcW.setType(STTblWidth.DXA);

                            // Remove the default paragraph that POI auto-adds.
                            xwpfCell.removeParagraph(0);
                            var cellP = xwpfCell.addParagraph();
                            currentParagraph = cellP;
                            emitInline(tc, isHeader, false, false, false, BODY_FONT_SIZE, null);
                            currentParagraph = null;
                            colIdx++;
                        }
                        rowIdx++;
                    }
                }
            }
            // Trailing spacing paragraph.
            doc.createParagraph();
        }

        /** Render a node's inline children into {@link #currentParagraph}. */
        private void emitInline(Node node, boolean bold, boolean italic, boolean code, boolean strike,
                                int fontSize, String color) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                switch (child) {
                    case Text t ->
                            addRun(t.getChars().toString(), bold, italic, code, strike, fontSize, color);
                    case StrongEmphasis _ ->
                            emitInline(child, true, italic, code, strike, fontSize, color);
                    case Emphasis _ ->
                            emitInline(child, bold, true, code, strike, fontSize, color);
                    case Code c ->
                            addRun(c.getText().toString(), bold, italic, true, strike, fontSize, color);
                    case Strikethrough _ ->
                            emitInline(child, bold, italic, code, true, fontSize, color);
                    case Link l -> {
                        emitInline(child, bold, italic, code, strike, fontSize, "0563C1");
                        addRun(" (" + l.getUrl() + ")", bold, italic, code, strike, fontSize, "888888");
                    }
                    case SoftLineBreak _ when currentParagraph != null ->
                            currentParagraph.createRun().setText(" ");
                    case SoftLineBreak _ -> { /* no current paragraph */ }
                    case HardLineBreak _ when currentParagraph != null ->
                            currentParagraph.createRun().addBreak();
                    case HardLineBreak _ -> { /* no current paragraph */ }
                    case HtmlInline inline ->
                            addRun(inline.getChars().toString(), bold, italic, code, strike, fontSize, color);
                    default ->
                            emitInline(child, bold, italic, code, strike, fontSize, color);
                }
            }
        }

        private void addRun(String text, boolean bold, boolean italic, boolean code, boolean strike,
                            int fontSize, String color) {
            if (currentParagraph == null) currentParagraph = doc.createParagraph();
            var run = currentParagraph.createRun();
            run.setText(text);
            if (bold) run.setBold(true);
            if (italic) run.setItalic(true);
            if (strike) run.setStrikeThrough(true);
            if (code) {
                run.setFontFamily("Courier New");
                run.setFontSize(CODE_FONT_SIZE);
            } else {
                run.setFontSize(fontSize);
            }
            if (color != null) run.setColor(color);
        }
    }
}
