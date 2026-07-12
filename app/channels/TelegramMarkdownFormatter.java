package channels;

import com.vladsch.flexmark.ast.AutoLink;
import com.vladsch.flexmark.ast.BlockQuote;
import com.vladsch.flexmark.ast.BulletList;
import com.vladsch.flexmark.ast.Code;
import com.vladsch.flexmark.ast.Emphasis;
import com.vladsch.flexmark.ast.FencedCodeBlock;
import com.vladsch.flexmark.ast.Heading;
import com.vladsch.flexmark.ast.HtmlBlock;
import com.vladsch.flexmark.ast.HtmlCommentBlock;
import com.vladsch.flexmark.ast.HtmlInline;
import com.vladsch.flexmark.ast.HtmlInlineComment;
import com.vladsch.flexmark.ast.IndentedCodeBlock;
import com.vladsch.flexmark.ast.Link;
import com.vladsch.flexmark.ast.OrderedList;
import com.vladsch.flexmark.ast.Paragraph;
import com.vladsch.flexmark.ast.StrongEmphasis;
import com.vladsch.flexmark.ast.Text;
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
import java.util.regex.Pattern;

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
    private static final String PRE_OPEN = "<pre>";
    private static final String PRE_CLOSE = "</pre>";
    private static final String PRE_CODE_OPEN = "<pre><code>";
    private static final String PRE_CODE_CLOSE = "</code></pre>";

    /** Telegram's HTML parse mode accepts a small no-attribute tag subset. When
     *  the agent emits raw HTML, we pass tags matching this allowlist through
     *  verbatim so bold/italic/etc. actually render — anything outside the
     *  allowlist (attributes, unknown tags, script, etc.) still gets escaped. */
    private static final Pattern SAFE_TG_TAG = Pattern.compile(
            "</?(?:b|strong|i|em|u|ins|s|strike|del|code|pre|blockquote)>",
            Pattern.CASE_INSENSITIVE);

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
        return new Emitter(tableMode).render(doc);
    }

    // ── Internal emitter ──

    private static final class Emitter extends FlexmarkChannelEmitter {
        final TableMode tableMode;

        Emitter(TableMode m) { this.tableMode = m; }

        @Override protected void emitHeading(Heading node) { wrapChildren(node, "<b>", "</b>\n\n"); }

        @Override protected void emitParagraph(Paragraph node) {
            emitChildren(node);
            out.append("\n\n");
        }

        @Override protected void emitStrongEmphasis(StrongEmphasis node) { wrapChildren(node, "<b>", "</b>"); }

        @Override protected void emitEmphasis(Emphasis node) { wrapChildren(node, "<i>", "</i>"); }

        @Override protected void emitStrikethrough(Strikethrough node) { wrapChildren(node, "<s>", "</s>"); }

        private void wrapChildren(Node node, String open, String close) {
            out.append(open);
            emitChildren(node);
            out.append(close);
        }

        @Override protected void emitCode(Code code) {
            out.append("<code>");
            out.append(escapeHtml(code.getText().toString()));
            out.append("</code>");
        }

        @Override protected void emitIndentedCodeBlock(IndentedCodeBlock icb) {
            out.append(PRE_CODE_OPEN);
            out.append(escapeHtml(icb.getContentChars().toString()));
            out.append(PRE_CODE_CLOSE);
            out.append("\n\n");
        }

        @Override protected void emitBlockQuote(BlockQuote node) {
            out.append("<blockquote>");
            emitChildren(node);
            trimTrailingNewlines();
            out.append("</blockquote>\n\n");
        }

        @Override protected void emitLink(Link link) {
            out.append("<a href=\"");
            out.append(escapeAttr(link.getUrl().toString()));
            out.append("\">");
            emitChildren(link);
            out.append("</a>");
        }

        @Override protected void emitAutoLink(AutoLink al) {
            out.append("<a href=\"");
            out.append(escapeAttr(al.getUrl().toString()));
            out.append("\">");
            out.append(escapeHtml(al.getUrl().toString()));
            out.append("</a>");
        }

        @Override protected void emitText(Text t) {
            out.append(escapeHtml(t.getChars().toString()));
        }

        @Override protected void emitSoftLineBreak() { out.append("\n"); }

        @Override protected void emitHardLineBreak() { out.append("\n"); }

        // Telegram has no <hr>; emit a visual separator.
        @Override protected void emitThematicBreak() { out.append("—\n\n"); }

        /** Typographic decorations and raw HTML — the node types flexmark's
         *  Typographic / (no-)HTML handling produces that the shared switch doesn't
         *  enumerate. */
        @Override protected void emitFallback(Node node) {
            if (node instanceof TypographicSmarts ts) {
                // --, ---, ..., etc. Flexmark stores replacements as HTML entity
                // strings (e.g. "&ndash;"); we decode to the actual Unicode
                // character so the emitted HTML reads cleanly and doesn't need
                // entity-escaping at the chunk-boundary level.
                out.append(decodeTypographicEntities(ts.getTypographicText()));
            } else if (node instanceof TypographicQuotes tq) {
                out.append(decodeTypographicEntities(tq.getTypographicOpening()));
                emitChildren(tq);
                out.append(decodeTypographicEntities(tq.getTypographicClosing()));
            } else if (node instanceof HtmlInline || node instanceof HtmlBlock
                    || node instanceof HtmlCommentBlock || node instanceof HtmlInlineComment) {
                emitRawHtml(node);
            } else {
                super.emitFallback(node);
            }
        }

        // Agents occasionally emit raw HTML instead of markdown — usually
        // simple emphasis tags (e.g. <b>Lazada</b>) that the LLM picked
        // because the system prompt mentioned Telegram. Telegram's HTML
        // parse mode supports a small no-attribute tag subset; pass those
        // through verbatim so the user sees actual bold/italic instead of
        // literal "<b>...</b>". Anything else (script, attributes, unknown
        // tags) still goes through escapeHtml so we can't unintentionally
        // ship dangerous markup.
        private void emitRawHtml(Node node) {
            String chars = node.getChars().toString();
            if (SAFE_TG_TAG.matcher(chars).matches()) {
                out.append(chars);
            } else {
                out.append(escapeHtml(chars));
            }
        }

        @Override protected void emitFencedCodeBlock(FencedCodeBlock fcb) {
            String lang = fcb.getInfo().toString().trim();
            out.append(PRE_OPEN);
            if (!lang.isEmpty()) {
                out.append("<code class=\"language-").append(escapeAttr(lang)).append("\">");
            } else {
                out.append("<code>");
            }
            out.append(escapeHtml(fcb.getContentChars().toString()));
            out.append(PRE_CODE_CLOSE);
            out.append("\n\n");
        }

        @Override protected void emitBulletList(BulletList list) {
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

        @Override protected void emitOrderedList(OrderedList list) {
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

        @Override protected void emitTable(TableBlock table) {
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
                if (row instanceof TableRow) emitTableBodyRow(row, headerLabels);
            }
            out.append("\n");
        }

        private void emitTableBodyRow(Node row, List<String> headerLabels) {
            List<TableCell> cells = new ArrayList<>();
            for (Node c = row.getFirstChild(); c != null; c = c.getNext()) {
                if (c instanceof TableCell cell) cells.add(cell);
            }
            if (cells.isEmpty()) return;
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

        private List<String> collectHeaderLabels(TableHead head) {
            List<String> labels = new ArrayList<>();
            if (head == null) return labels;
            // Only the first TableRow matters.
            for (Node row = head.getFirstChild(); row != null; row = row.getNext()) {
                if (row instanceof TableRow) {
                    for (Node cell = row.getFirstChild(); cell != null; cell = cell.getNext()) {
                        if (cell instanceof TableCell tc) {
                            labels.add(collectInlineText(tc));
                        }
                    }
                    return labels;
                }
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
        List<String> blocks = splitBlocks(html);
        StringBuilder current = new StringBuilder();

        for (String block : blocks) {
            if (block.isEmpty()) continue;
            appendBlock(block, maxLen, current, chunks);
        }
        if (!current.isEmpty()) chunks.add(current.toString());
        return chunks;
    }

    /**
     * Split {@code html} into blocks at double-newline boundaries, but treat a
     * {@code <pre>…</pre>} fence as a single indivisible block. Flexmark preserves
     * literal blank lines inside a fenced code block, so a naive
     * {@code split("\n\n")} would cut a fence in half and leave the two halves in
     * different chunks with unbalanced {@code <pre>}/{@code <code>} tags. Tracking
     * {@code <pre>} open/close depth keeps every fence intact; an oversized fence
     * still flows through to {@link #splitOversizedBlock}, which re-wraps it.
     */
    private static List<String> splitBlocks(String html) {
        List<String> blocks = new ArrayList<>();
        StringBuilder block = new StringBuilder();
        int preDepth = 0;
        int i = 0;
        while (i < html.length()) {
            if (html.startsWith(PRE_OPEN, i)) {
                preDepth++;
                block.append(PRE_OPEN);
                i += PRE_OPEN.length();
            } else if (preDepth > 0 && html.startsWith(PRE_CLOSE, i)) {
                preDepth--;
                block.append(PRE_CLOSE);
                i += PRE_CLOSE.length();
            } else if (preDepth == 0 && html.startsWith("\n\n", i)) {
                blocks.add(block.toString());
                block.setLength(0);
                i += 2;
            } else {
                block.append(html.charAt(i));
                i++;
            }
        }
        blocks.add(block.toString());
        return blocks;
    }

    /**
     * Append {@code block} to {@code current}, flushing into {@code chunks} when
     * the addition would exceed {@code maxLen}. Oversized blocks (a single block
     * already longer than {@code maxLen}) are flushed and then split via
     * {@link #splitOversizedBlock}.
     */
    private static void appendBlock(String block, int maxLen, StringBuilder current, List<String> chunks) {
        if (block.length() > maxLen) {
            if (!current.isEmpty()) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            chunks.addAll(splitOversizedBlock(block, maxLen));
            return;
        }
        int added = current.isEmpty() ? block.length() : current.length() + 2 + block.length();
        if (added > maxLen) {
            chunks.add(current.toString());
            current.setLength(0);
        }
        if (!current.isEmpty()) current.append("\n\n");
        current.append(block);
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
        if (!block.startsWith(PRE_OPEN)) return -1;
        int i = PRE_OPEN.length();
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
            appendLine(line, maxLen, current, chunks);
        }
        if (!current.isEmpty()) chunks.add(current.toString());
        return chunks;
    }

    /** Append {@code line} to {@code current}, flushing when over {@code maxLen}; hard-cut single lines longer than {@code maxLen}. */
    private static void appendLine(String line, int maxLen, StringBuilder current, List<String> chunks) {
        if (line.length() > maxLen) {
            if (!current.isEmpty()) {
                chunks.add(current.toString());
                current.setLength(0);
            }
            int off = 0;
            while (off < line.length()) {
                int end = safeCutIndex(line, off, Math.min(line.length(), off + maxLen));
                chunks.add(line.substring(off, end));
                off = end;
            }
            return;
        }
        int added = current.isEmpty() ? line.length() : current.length() + 1 + line.length();
        if (added > maxLen) {
            chunks.add(current.toString());
            current.setLength(0);
        }
        if (!current.isEmpty()) current.append("\n");
        current.append(line);
    }

    /** Longest HTML entity ({@code &amp;}, {@code &hellip;}, …) we'd ever back a cut up
     *  to clear. Bounds the backward scan so a stray {@code &} in plain text can't drag
     *  the cut arbitrarily far left. Telegram-safe tags are short too, but {@code <a
     *  href="…">} can be long, so tags get a separate, generous bound below. */
    private static final int MAX_ENTITY_LEN = 10;

    /**
     * Given a hard-cut at {@code end} on the substring {@code [from, line.length())},
     * back the cut up so it never lands inside an HTML entity ({@code &}…{@code ;}) or
     * a tag ({@code <}…{@code >}). A naive fixed-stride length cut can slice {@code &amp;}
     * into {@code …&am} + {@code p;…} or {@code <b>} into {@code …<b} + {@code >…},
     * producing markup Telegram rejects.
     *
     * <p>The cut is at the natural {@code end} unless an unterminated {@code &} or
     * {@code <} opens at or before {@code end} and its closing {@code ;} / {@code >}
     * lands at or after {@code end} — then we retreat the cut to just before that
     * opener so the whole entity/tag rides into the next chunk. If retreating would
     * leave nothing for this chunk (the opener sits at {@code from}, i.e. the entity
     * or tag is itself longer than {@code maxLen}), we keep the natural cut: a broken
     * fragment is unavoidable in that pathological case and progress must be made.
     */
    private static int safeCutIndex(String line, int from, int end) {
        if (end >= line.length()) return end; // natural end-of-line; nothing to split
        // An entity is short and self-delimiting; scan a bounded window left of the cut.
        int entityStart = lastUnterminated(line, from, end, '&', ';', MAX_ENTITY_LEN);
        if (entityStart < end && entityStart > from) return entityStart;
        // A tag can be longer (e.g. an <a href="…"> open tag); allow a window up to maxLen.
        int tagStart = lastUnterminated(line, from, end, '<', '>', end - from);
        if (tagStart < end && tagStart > from) return tagStart;
        return end;
    }

    /**
     * If a cut at {@code end} would fall strictly inside an {@code open}…{@code close}
     * run (e.g. {@code &}…{@code ;} or {@code <}…{@code >}), return the index of that
     * {@code open} char so the caller can cut just before it; otherwise return
     * {@code end} unchanged. Only opens within {@code window} chars of {@code end}
     * (and not before {@code from}) are considered, so an unmatched delimiter in plain
     * text can't drag the cut arbitrarily far.
     */
    private static int lastUnterminated(String line, int from, int end, char open, char close, int window) {
        int lowerBound = Math.max(from, end - window);
        for (int j = end - 1; j >= lowerBound; j--) {
            char c = line.charAt(j);
            if (c == close) return end; // closed before the cut — not inside a run
            if (c == open) {
                // Open with no intervening close: the cut at `end` is inside this run.
                // Confirm the run actually extends across `end` (its close is at/after end).
                int closeIdx = line.indexOf(close, j + 1);
                return (closeIdx >= end) ? j : end;
            }
        }
        return end;
    }
}
