package channels;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
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
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.SoftLineBreak;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
import com.vladsch.flexmark.ast.ThematicBreak;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

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
        var e = new Emitter();
        e.emitChildren(doc);
        return e.out.toString().stripTrailing();
    }

    /** Slack treats {@code & < >} as control characters; escape them in literal
     *  text (Slack displays the un-escaped character). */
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static final class Emitter {
        final StringBuilder out = new StringBuilder();

        void emitChildren(Node parent) {
            for (Node c = parent.getFirstChild(); c != null; c = c.getNext()) emit(c);
        }

        void emit(Node node) {
            switch (node) {
                case Heading h -> { out.append('*'); emitChildren(h); out.append("*\n\n"); }
                case Paragraph p -> { emitChildren(p); out.append("\n\n"); }
                case StrongEmphasis s -> wrap(s, "*");
                case Emphasis em -> wrap(em, "_");
                case Strikethrough st -> wrap(st, "~");
                case Code code -> out.append('`').append(escape(code.getText().toString())).append('`');
                case FencedCodeBlock fcb -> emitFence(fcb.getContentChars().toString());
                case IndentedCodeBlock icb -> emitFence(icb.getContentChars().toString());
                case BlockQuote bq -> emitQuote(bq);
                case BulletList bl -> emitBulletList(bl);
                case OrderedList ol -> emitOrderedList(ol);
                case TableBlock tb -> emitFence(tb.getChars().toString());
                case Link link -> emitLink(link.getUrl().toString(), link);
                case AutoLink al -> out.append('<').append(al.getUrl().toString()).append('>');
                case Text t -> out.append(escape(t.getChars().toString()));
                case SoftLineBreak _ -> out.append('\n');
                case HardLineBreak _ -> out.append('\n');
                case ThematicBreak _ -> out.append("──────────\n\n");
                case HtmlInline h -> out.append(escape(h.getChars().toString()));
                case HtmlBlock h -> out.append(escape(h.getChars().toString()));
                default -> emitChildren(node);
            }
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

        void emitLink(String url, Node link) {
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

        void emitBulletList(BulletList list) {
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                out.append("• ");
                emitChildren(item);
                trimTrailingNewlines();
                out.append('\n');
            }
            out.append('\n');
        }

        void emitOrderedList(OrderedList list) {
            int n = list.getStartNumber();
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                out.append(n++).append(". ");
                emitChildren(item);
                trimTrailingNewlines();
                out.append('\n');
            }
            out.append('\n');
        }

        void trimTrailingNewlines() {
            while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                out.setLength(out.length() - 1);
            }
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
    }
}
