package channels;

import com.vladsch.flexmark.ast.*;
import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.Strikethrough;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListItem;
import com.vladsch.flexmark.ext.tables.TableBlock;
import com.vladsch.flexmark.ext.tables.TableBody;
import com.vladsch.flexmark.ext.tables.TableCell;
import com.vladsch.flexmark.ext.tables.TableHead;
import com.vladsch.flexmark.ext.tables.TableRow;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.ext.typographic.TypographicExtension;
import com.vladsch.flexmark.ext.typographic.TypographicQuotes;
import com.vladsch.flexmark.ext.typographic.TypographicSmarts;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Markdown → Telegram-safe HTML converter (JCLAW-91). Telegram's Bot API supports
 * a small HTML subset: {@code <b>}, {@code <i>}, {@code <s>}, {@code <u>},
 * {@code <code>}, {@code <pre>}, {@code <a href="...">}, {@code <blockquote>},
 * {@code <tg-spoiler>}. There is no table, heading, or list grammar — which is
 * why agents that emit GitHub-flavored markdown produce raw pipes and {@code #}
 * characters in Telegram chat.
 *
 * <p>The formatter parses the input with flexmark-java (already on the classpath
 * for other markdown rendering) and walks the AST, emitting Telegram-safe HTML
 * directly. Tables are the interesting case: {@link TableMode#BULLETS} renders
 * each body row as a bulleted line keyed by the header row's cells;
 * {@link TableMode#CODE} wraps the verbatim pipe text in {@code <pre><code>};
 * {@link TableMode#OFF} drops tables entirely.
 *
 * <p>Paired with {@link #chunkHtml} for splitting long messages at safe block
 * boundaries without leaving unclosed tags.
 */
public final class TelegramMarkdownFormatter {

    /** How table blocks are rendered for Telegram, which has no native table grammar. */
    public enum TableMode { OFF, BULLETS, CODE }

    /** Default tag stack reset at chunk boundaries inside oversized fenced code blocks. */
    private static final String PRE_CODE_OPEN = "<pre><code>";
    private static final String PRE_CODE_CLOSE = "</code></pre>";

    private static final Parser PARSER = Parser.builder(new MutableDataSet())
            .extensions(List.of(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    // JCLAW-92 polish: promote bare URLs to clickable links, apply
                    // smart punctuation (curly quotes, em/en dashes, ellipsis), and
                    // recognize GFM task-list syntax so `- [ ]` / `- [x]` get their
                    // own AST node (rendered with Unicode boxes in emitBulletList).
                    AutolinkExtension.create(),
                    TypographicExtension.create(),
                    TaskListExtension.create()))
            .build();

    private TelegramMarkdownFormatter() {}

    /** Convert {@code markdown} to Telegram-safe HTML with the default {@code BULLETS} table mode. */
    public static String toHtml(String markdown) {
        return toHtml(markdown, TableMode.BULLETS);
    }

    /**
     * Convert {@code markdown} to Telegram-safe HTML. Null/blank inputs produce
     * the empty string. Trailing newlines on the output are stripped so the
     * result doesn't carry gratuitous whitespace into the send payload.
     */
    public static String toHtml(String markdown, TableMode tableMode) {
        if (markdown == null || markdown.isEmpty()) return "";
        Node doc = PARSER.parse(markdown);
        var emitter = new Emitter(tableMode);
        emitter.emitChildren(doc);
        return emitter.out.toString().stripTrailing();
    }

    // ── Internal emitter ──

    private static final class Emitter {
        final StringBuilder out = new StringBuilder();
        final TableMode tableMode;

        Emitter(TableMode m) { this.tableMode = m; }

        void emitChildren(Node parent) {
            for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
                emit(child);
            }
        }

        void emit(Node node) {
            if (node instanceof Heading) {
                out.append("<b>");
                emitChildren(node);
                out.append("</b>\n\n");
            }
            else if (node instanceof Paragraph) {
                emitChildren(node);
                out.append("\n\n");
            }
            else if (node instanceof StrongEmphasis) {
                out.append("<b>");
                emitChildren(node);
                out.append("</b>");
            }
            else if (node instanceof Emphasis) {
                out.append("<i>");
                emitChildren(node);
                out.append("</i>");
            }
            else if (node instanceof Strikethrough) {
                out.append("<s>");
                emitChildren(node);
                out.append("</s>");
            }
            else if (node instanceof Code code) {
                out.append("<code>");
                out.append(escapeHtml(code.getText().toString()));
                out.append("</code>");
            }
            else if (node instanceof FencedCodeBlock fcb) {
                emitFencedCodeBlock(fcb);
            }
            else if (node instanceof IndentedCodeBlock icb) {
                out.append(PRE_CODE_OPEN);
                out.append(escapeHtml(icb.getContentChars().toString()));
                out.append(PRE_CODE_CLOSE);
                out.append("\n\n");
            }
            else if (node instanceof BlockQuote) {
                out.append("<blockquote>");
                emitChildren(node);
                trimTrailingNewlines();
                out.append("</blockquote>\n\n");
            }
            else if (node instanceof BulletList bl) {
                emitBulletList(bl);
            }
            else if (node instanceof OrderedList ol) {
                emitOrderedList(ol);
            }
            else if (node instanceof Link link) {
                out.append("<a href=\"");
                out.append(escapeAttr(link.getUrl().toString()));
                out.append("\">");
                emitChildren(node);
                out.append("</a>");
            }
            else if (node instanceof AutoLink al) {
                out.append("<a href=\"");
                out.append(escapeAttr(al.getUrl().toString()));
                out.append("\">");
                out.append(escapeHtml(al.getUrl().toString()));
                out.append("</a>");
            }
            else if (node instanceof Text t) {
                out.append(escapeHtml(t.getChars().toString()));
            }
            else if (node instanceof TypographicSmarts ts) {
                // --, ---, ..., etc. Flexmark stores replacements as HTML entity
                // strings (e.g. "&ndash;"); we decode to the actual Unicode
                // character so the emitted HTML reads cleanly and doesn't need
                // entity-escaping at the chunk-boundary level.
                out.append(decodeTypographicEntities(ts.getTypographicText()));
            }
            else if (node instanceof TypographicQuotes tq) {
                out.append(decodeTypographicEntities(tq.getTypographicOpening()));
                emitChildren(tq);
                out.append(decodeTypographicEntities(tq.getTypographicClosing()));
            }
            else if (node instanceof SoftLineBreak || node instanceof HardLineBreak) {
                out.append("\n");
            }
            else if (node instanceof ThematicBreak) {
                // Telegram has no <hr>; emit a visual separator.
                out.append("—\n\n");
            }
            else if (node instanceof TableBlock tb) {
                emitTable(tb);
            }
            else if (node instanceof HtmlInline || node instanceof HtmlBlock
                    || node instanceof HtmlCommentBlock || node instanceof HtmlInlineComment) {
                // Agents occasionally emit raw HTML. Don't trust it — pass through as
                // escaped text so we can't unintentionally ship script tags, open
                // unexpected tags, or inject attributes.
                out.append(escapeHtml(node.getChars().toString()));
            }
            else {
                // Fallback: walk children. Covers container nodes we haven't enumerated
                // (e.g. ListItem is handled by its parent list's walker, but standalone
                // parent nodes not covered above should still emit their text.)
                emitChildren(node);
            }
        }

        private void emitFencedCodeBlock(FencedCodeBlock fcb) {
            String lang = fcb.getInfo().toString().trim();
            out.append("<pre>");
            if (!lang.isEmpty()) {
                out.append("<code class=\"language-").append(escapeAttr(lang)).append("\">");
            } else {
                out.append("<code>");
            }
            out.append(escapeHtml(fcb.getContentChars().toString()));
            out.append(PRE_CODE_CLOSE);
            out.append("\n\n");
        }

        private void emitBulletList(BulletList list) {
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                // TaskListItem extends BulletListItem, so it rides through this same
                // loop; swap the leading bullet for a Unicode checkbox glyph.
                if (item instanceof TaskListItem tli) {
                    out.append(tli.isItemDoneMarker() ? "☑ " : "☐ ");
                }
                else {
                    out.append("• ");
                }
                emitChildren(item);
                trimTrailingNewlines();
                out.append("\n");
            }
            out.append("\n");
        }

        private void emitOrderedList(OrderedList list) {
            int n = list.getStartNumber();
            for (Node item = list.getFirstChild(); item != null; item = item.getNext()) {
                out.append(n).append(". ");
                emitChildren(item);
                trimTrailingNewlines();
                out.append("\n");
                n++;
            }
            out.append("\n");
        }

        private void emitTable(TableBlock table) {
            if (tableMode == TableMode.OFF) return;

            TableHead head = findChild(table, TableHead.class);
            TableBody body = findChild(table, TableBody.class);

            if (tableMode == TableMode.CODE) {
                out.append(PRE_CODE_OPEN);
                out.append(escapeHtml(table.getChars().toString()));
                out.append(PRE_CODE_CLOSE);
                out.append("\n\n");
                return;
            }

            // BULLETS: key body cells by header cell text. Flexmark's TableCell
            // spans the entire source substring (including delimiter pipes), so we
            // walk its Text children to get the visible cell contents instead.
            List<String> headerLabels = collectHeaderLabels(head);
            if (body == null) return;

            for (Node row = body.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                List<TableCell> cells = new ArrayList<>();
                for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
                    if (c instanceof TableCell cell) cells.add(cell);
                }
                if (cells.isEmpty()) continue;
                out.append("• ");
                for (int i = 0; i < cells.size(); i++) {
                    if (i > 0) out.append(" — ");
                    if (i < headerLabels.size() && !headerLabels.get(i).isBlank()) {
                        out.append("<b>").append(escapeHtml(headerLabels.get(i))).append("</b>: ");
                    }
                    emitChildren(cells.get(i));
                }
                out.append("\n");
            }
            out.append("\n");
        }

        private List<String> collectHeaderLabels(TableHead head) {
            List<String> labels = new ArrayList<>();
            if (head == null) return labels;
            for (Node row = head.getFirstChild(); row != null; row = row.getNext()) {
                if (!(row instanceof TableRow)) continue;
                for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                    if (!(cell instanceof TableCell tc)) continue;
                    labels.add(collectInlineText(tc));
                }
                break; // Only the first row matters.
            }
            return labels;
        }

        /**
         * Walk a node's subtree and concatenate the chars of every {@link Text}
         * descendant. Needed for table header cells because flexmark stores the
         * cell text inside Text child nodes while the cell's own {@code getChars}
         * span includes the surrounding delimiter pipes.
         */
        private static String collectInlineText(Node root) {
            StringBuilder sb = new StringBuilder();
            collectInlineTextInto(root, sb);
            return sb.toString().trim();
        }

        private static void collectInlineTextInto(Node node, StringBuilder sb) {
            if (node instanceof Text t) {
                sb.append(t.getChars());
                return;
            }
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) {
                collectInlineTextInto(child, sb);
            }
        }

        private void trimTrailingNewlines() {
            while (out.length() > 0 && out.charAt(out.length() - 1) == '\n') {
                out.setLength(out.length() - 1);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T extends Node> T findChild(Node parent, Class<T> type) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNext()) {
            if (type.isInstance(child)) return (T) child;
        }
        return null;
    }

    /**
     * Flexmark's typographic extension returns HTML entity references
     * (e.g. {@code &ndash;}) rather than Unicode code points for its
     * replacements. Decode the handful we actually receive into their Unicode
     * equivalents so the output HTML stream contains real characters, not
     * entities we'd then have to preserve through {@link #escapeHtml}.
     */
    private static final Map<String, String> TYPOGRAPHIC_ENTITIES = Map.of(
            "&ndash;", "\u2013",
            "&mdash;", "\u2014",
            "&hellip;", "\u2026",
            "&ldquo;", "\u201C",
            "&rdquo;", "\u201D",
            "&lsquo;", "\u2018",
            "&rsquo;", "\u2019",
            "&laquo;", "\u00AB",
            "&raquo;", "\u00BB");

    private static String decodeTypographicEntities(String s) {
        if (s == null || s.isEmpty()) return "";
        // Only a handful of entities ever appear here; a linear scan with
        // indexOf is cheaper than compiling a regex for every call.
        for (var entry : TYPOGRAPHIC_ENTITIES.entrySet()) {
            if (s.contains(entry.getKey())) {
                s = s.replace(entry.getKey(), entry.getValue());
            }
        }
        return s;
    }

    /**
     * HTML-escape text so Telegram's HTML parser doesn't mistake literal {@code &},
     * {@code <}, or {@code >} in the source for tag markup.
     */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Escape a string for safe inclusion in an HTML attribute value (double-quoted).
     * Same as {@link #escapeHtml} plus {@code "} to close-attribute-escape.
     */
    public static String escapeAttr(String s) {
        if (s == null) return "";
        var sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    // ── Tag-aware chunker ──

    /**
     * Split an HTML string into chunks no longer than {@code maxLen} characters
     * without leaving unclosed tags. Splits at block boundaries (double-newlines)
     * when possible; oversized single blocks (typically very long fenced code
     * fences) get re-wrapped so each resulting chunk is independently well-formed
     * HTML. Call on {@link #toHtml}'s output before sending to Telegram, which
     * caps messages at 4096 chars.
     */
    public static List<String> chunkHtml(String html, int maxLen) {
        if (html == null || html.isEmpty()) return List.of();
        if (maxLen <= 0) throw new IllegalArgumentException("maxLen must be positive");
        if (html.length() <= maxLen) return List.of(html);

        List<String> chunks = new ArrayList<>();
        String[] blocks = html.split("\n\n", -1);
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {
            if (block.isEmpty()) continue;

            if (block.length() > maxLen) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                chunks.addAll(splitOversizedBlock(block, maxLen));
                continue;
            }

            int added = current.length() == 0 ? block.length() : current.length() + 2 + block.length();
            if (added > maxLen) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n\n");
            current.append(block);
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }

    /**
     * Handle a single block that exceeds {@code maxLen}. Detects the common case
     * of a fenced code block ({@code <pre><code ...>...</code></pre>}) and splits
     * its content by lines, re-wrapping each chunk in the same open/close pair so
     * rendering stays legal HTML. Non-code oversized blocks fall back to a line
     * split without re-wrapping — they'll render as multiple adjacent messages
     * rather than a single block, which is acceptable given how rare those are.
     */
    private static List<String> splitOversizedBlock(String block, int maxLen) {
        int preEnd = preOpenTagEnd(block);
        if (preEnd >= 0 && block.endsWith(PRE_CODE_CLOSE)) {
            String openTag = block.substring(0, preEnd);
            String inner = block.substring(preEnd, block.length() - PRE_CODE_CLOSE.length());
            int overhead = openTag.length() + PRE_CODE_CLOSE.length();
            int innerMax = maxLen - overhead;
            if (innerMax <= 0) {
                // Pathological maxLen; just fall through to line split without wrapping.
                return splitByLines(block, maxLen);
            }
            List<String> pieces = splitByLines(inner, innerMax);
            List<String> wrapped = new ArrayList<>(pieces.size());
            for (String p : pieces) {
                wrapped.add(openTag + p + PRE_CODE_CLOSE);
            }
            return wrapped;
        }
        return splitByLines(block, maxLen);
    }

    /**
     * Return the index past a leading {@code <pre>[<code ...>]} open tag pair
     * if {@code block} starts with one, else -1.
     */
    private static int preOpenTagEnd(String block) {
        if (!block.startsWith("<pre>")) return -1;
        int i = "<pre>".length();
        if (i < block.length() && block.startsWith("<code", i)) {
            int gt = block.indexOf('>', i);
            if (gt > 0) return gt + 1;
        }
        return -1;
    }

    /**
     * Greedy line-based split: accumulate lines until adding the next one would
     * exceed {@code maxLen}, then start a new chunk. A single line longer than
     * {@code maxLen} is hard-cut at {@code maxLen} characters — pathological but
     * not worth more machinery.
     */
    private static List<String> splitByLines(String block, int maxLen) {
        List<String> chunks = new ArrayList<>();
        String[] lines = block.split("\n", -1);
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (line.length() > maxLen) {
                if (current.length() > 0) {
                    chunks.add(current.toString());
                    current.setLength(0);
                }
                for (int off = 0; off < line.length(); off += maxLen) {
                    chunks.add(line.substring(off, Math.min(line.length(), off + maxLen)));
                }
                continue;
            }
            int added = current.length() == 0 ? line.length() : current.length() + 1 + line.length();
            if (added > maxLen) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) current.append("\n");
            current.append(line);
        }
        if (current.length() > 0) chunks.add(current.toString());
        return chunks;
    }
}
