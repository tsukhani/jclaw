package channels;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → Slack mrkdwn converter (JCLAW-341). Slack's message formatting is
 * its own dialect, not CommonMark: {@code *bold*} (single star), {@code _italic_},
 * {@code ~strike~}, {@code `code`}, fenced {@code ```} blocks, {@code <url|text>}
 * links, {@code >} quotes — and there is no heading or table grammar. Agents emit
 * GitHub-flavored Markdown, so without conversion {@code **bold**}, {@code # H},
 * and {@code [text](url)} render literally in Slack.
 *
 * <p>Parses with flexmark (already on the classpath) and walks the AST, so code
 * spans/fences are structurally protected from the inline transforms. Mirrors
 * {@link TelegramMarkdownFormatter}'s emitter shape, targeting mrkdwn instead of
 * Telegram HTML.
 */
public final class SlackMarkdownFormatter {

    private static final Parser PARSER = Parser.builder(new MutableDataSet())
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AutolinkExtension.create()))
            .build();

    private SlackMarkdownFormatter() {}

    /** Convert {@code markdown} to Slack mrkdwn. Null/blank input → empty string. */
    public static String format(String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node doc = PARSER.parse(markdown);
        return new Emitter().render(doc);
    }

    /** Slack treats {@code & < >} as control characters; escape them in literal
     *  text (Slack displays the un-escaped character). */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class Emitter extends FlexmarkChannelEmitter {

        @Override protected void emitHeading(Heading h) { out.append('*'); emitChildren(h); out.append("*\n\n"); }

        @Override protected void emitParagraph(Paragraph p) { emitChildren(p); out.append("\n\n"); }

        @Override protected void emitStrongEmphasis(StrongEmphasis s) { wrap(s, "*"); }

        @Override protected void emitEmphasis(Emphasis em) { wrap(em, "_"); }

        @Override protected void emitStrikethrough(Strikethrough st) { wrap(st, "~"); }

        @Override protected void emitCode(Code code) {
            out.append('`').append(escape(code.getText().toString())).append('`');
        }

        @Override protected void emitFencedCodeBlock(FencedCodeBlock fcb) { emitFence(fcb.getContentChars().toString()); }

        @Override protected void emitIndentedCodeBlock(IndentedCodeBlock icb) { emitFence(icb.getContentChars().toString()); }

        @Override protected void emitBlockQuote(BlockQuote bq) { emitQuote(bq); }

        @Override protected void emitLink(Link link) { appendLink(link.getUrl().toString(), link); }

        @Override protected void emitAutoLink(AutoLink al) { out.append('<').append(al.getUrl().toString()).append('>'); }

        @Override protected void emitText(Text t) { out.append(escape(t.getChars().toString())); }

        @Override protected void emitSoftLineBreak() { out.append('\n'); }

        @Override protected void emitHardLineBreak() { out.append('\n'); }

        @Override protected void emitThematicBreak() { out.append("──────────\n\n"); }

        /** Slack has no heading/table/typographic grammar; the raw-HTML nodes are the
         *  only extra types its parser produces — escape them (Slack displays them). */
        @Override protected void emitFallback(Node node) {
            if (node instanceof HtmlInline h) out.append(escape(h.getChars().toString()));
            else if (node instanceof HtmlBlock h) out.append(escape(h.getChars().toString()));
            else super.emitFallback(node);
        }

        void wrap(Node n, String delim) {
            out.append(delim);
            emitChildren(n);
            out.append(delim);
        }

        void emitFence(String content) {
            String c = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
            out.append("```\n").append(escape(c)).append("\n```\n\n");
        }

        void appendLink(String url, Node link) {
            String label = escape(collectText(link).trim());
            if (label.isEmpty() || label.equals(escape(url))) {
                out.append('<').append(url).append('>');
            } else {
                out.append('<').append(url).append('|').append(label).append('>');
            }
        }

        void emitQuote(BlockQuote bq) {
            var sub = new Emitter();
            sub.emitChildren(bq);
            for (String line : sub.out.toString().stripTrailing().split("\n", -1)) {
                out.append("> ").append(line).append('\n');
            }
            out.append('\n');
        }

        @Override protected void emitBulletList(BulletList list) {
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                out.append("• ");
                emitChildren(item);
                trimTrailingNewlines();
                out.append('\n');
            }
            out.append('\n');
        }

        @Override protected void emitOrderedList(OrderedList list) {
            int n = list.getStartNumber();
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                out.append(n++).append(". ");
                emitChildren(item);
                trimTrailingNewlines();
                out.append('\n');
            }
            out.append('\n');
        }

        static String collectText(Node root) {
            var sb = new StringBuilder();
            collectInto(root, sb);
            return sb.toString();
        }

        static void collectInto(Node node, StringBuilder sb) {
            for (Node c = node.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof Text t) sb.append(t.getChars());
                else collectInto(c, sb);
            }
        }

        // Slack has no table grammar, so render each body row as a bulleted line
        // keyed by the header cells — and crucially run the cell content through
        // the inline emitter so **bold** etc. convert (a code fence would keep
        // them literal). Mirrors TelegramMarkdownFormatter's BULLETS mode.
        @Override protected void emitTable(TableBlock table) {
            TableHead head = findChild(table, TableHead.class);
            TableBody body = findChild(table, TableBody.class);
            if (body == null) return;
            List<String> headers = collectHeaderLabels(head);
            for (Node row = body.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof TableRow) emitTableBodyRow(row, headers);
            }
            out.append('\n');
        }

        void emitTableBodyRow(Node row, List<String> headers) {
            List<TableCell> cells = new ArrayList<>();
            for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof TableCell tc) cells.add(tc);
            }
            if (cells.isEmpty()) return;
            out.append("• ");
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) out.append(" — ");
                if (i < headers.size() && !headers.get(i).isBlank()) {
                    out.append('*').append(headers.get(i)).append("*: ");
                }
                emitChildren(cells.get(i));
            }
            out.append('\n');
        }

        List<String> collectHeaderLabels(TableHead head) {
            List<String> labels = new ArrayList<>();
            if (head == null) return labels;
            for (Node row = head.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof TableRow) {
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell tc) labels.add(escape(collectText(tc).trim()));
                    }
                    return labels;
                }
            }
            return labels;
        }
    }
}
