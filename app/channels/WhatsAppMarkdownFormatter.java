package channels;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlEntity;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.MailLink;
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
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Markdown → WhatsApp formatting converter (JCLAW-1409). WhatsApp's dialect is
 * {@code *bold*}, {@code _italic_}, {@code ~strike~}, {@code `code`}, {@code ```mono```},
 * {@code -}/{@code 1.} lists and {@code >} quotes, with no heading, table, link-text or
 * escape grammar — so a link becomes {@code text (url)} and literal text passes through
 * unescaped. Otherwise follows {@link SlackMarkdownFormatter}'s choices.
 */
public final class WhatsAppMarkdownFormatter {

    private static final Parser PARSER = Parser.builder(new MutableDataSet())
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    AutolinkExtension.create()))
            .build();

    private WhatsAppMarkdownFormatter() {}

    /** Convert {@code markdown} to WhatsApp formatting. Null/empty input → empty string. */
    public static String format(@Nullable String markdown) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node doc = PARSER.parse(markdown);
        return new Emitter().render(doc);
    }

    private static final class Emitter extends FlexmarkChannelEmitter {

        @Override protected void emitHeading(Heading h) { out.append('*'); emitChildren(h); out.append("*\n\n"); }

        @Override protected void emitParagraph(Paragraph p) { emitChildren(p); out.append("\n\n"); }

        @Override protected void emitStrongEmphasis(StrongEmphasis s) { wrap(s, "*"); }

        @Override protected void emitEmphasis(Emphasis em) { wrap(em, "_"); }

        @Override protected void emitStrikethrough(Strikethrough st) { wrap(st, "~"); }

        @Override protected void emitCode(Code code) { out.append('`').append(code.getText()).append('`'); }

        @Override protected void emitFencedCodeBlock(FencedCodeBlock fcb) { emitFence(fcb.getContentChars().toString()); }

        @Override protected void emitIndentedCodeBlock(IndentedCodeBlock icb) { emitFence(icb.getContentChars().toString()); }

        @Override protected void emitBlockQuote(BlockQuote bq) {
            var sub = new Emitter();
            sub.emitChildren(bq);
            for (String line : sub.out.toString().stripTrailing().split("\n", -1)) {
                out.append("> ").append(line).append('\n');
            }
            out.append('\n');
        }

        @Override protected void emitLink(Link link) {
            String url = link.getUrl().toString();
            String label = collectText(link).trim();
            if (label.isEmpty() || label.equals(url)) {
                out.append(url);
            } else {
                emitChildren(link);
                out.append(" (").append(url).append(')');
            }
        }

        @Override protected void emitAutoLink(AutoLink al) { out.append(al.getUrl()); }

        // No escape grammar to carry a backslash escape over, so print the character it protects.
        @Override protected void emitText(Text t) { out.append(t.getChars().unescape()); }

        @Override protected void emitSoftLineBreak() { out.append('\n'); }

        @Override protected void emitHardLineBreak() { out.append('\n'); }

        @Override protected void emitThematicBreak() { out.append("──────────\n\n"); }

        /** WhatsApp has no HTML grammar and needs no escaping: raw HTML passes through as text.
         *  An entity and an angle-bracket email have no child text, so the default walk would drop them. */
        @Override protected void emitFallback(Node node) {
            switch (node) {
                case HtmlInline h -> out.append(h.getChars());
                case HtmlBlock h -> out.append(h.getChars());
                case HtmlEntity e -> out.append(e.getChars().unescape());
                case MailLink m -> out.append(m.getText());
                default -> super.emitFallback(node);
            }
        }

        void wrap(Node n, String delim) {
            out.append(delim);
            emitChildren(n);
            out.append(delim);
        }

        void emitFence(String content) {
            String c = content.endsWith("\n") ? content.substring(0, content.length() - 1) : content;
            out.append("```").append(c).append("```\n\n");
        }

        @Override protected void emitBulletList(BulletList list) {
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                out.append("- ");
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

        // No table grammar: one bullet line per body row, each cell keyed by its header,
        // cell content through the inline emitter so a link or bold inside converts.
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
            out.append("- ");
            for (int i = 0; i < cells.size(); i++) {
                if (i > 0) out.append(" — ");
                if (i < headers.size() && !headers.get(i).isBlank()) {
                    out.append('*').append(headers.get(i)).append("*: ");
                }
                emitChildren(cells.get(i));
            }
            out.append('\n');
        }

        List<String> collectHeaderLabels(@Nullable TableHead head) {
            List<String> labels = new ArrayList<>();
            if (head == null) return labels;
            for (Node row = head.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof TableRow) {
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell tc) labels.add(collectText(tc).trim());
                    }
                    return labels;
                }
            }
            return labels;
        }
    }
}
