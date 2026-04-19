import channels.TelegramMarkdownFormatter;
import channels.TelegramMarkdownFormatter.TableMode;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Coverage for the JCLAW-91 markdown → Telegram-safe HTML converter. Tests are
 * organized by construct (bold, italic, link, table, etc.) plus a chunker
 * section at the end. Each test asserts on an exact-or-contains substring so
 * the test stays robust against flexmark's internal whitespace choices.
 */
public class TelegramMarkdownFormatterTest extends UnitTest {

    // ── Inline formatting ──

    @Test
    public void boldGeneratesBTags() {
        var html = TelegramMarkdownFormatter.toHtml("This is **bold** text.");
        assertTrue(html.contains("<b>bold</b>"),
                () -> "expected bold HTML, got: " + html);
    }

    @Test
    public void italicGeneratesITags() {
        var starForm = TelegramMarkdownFormatter.toHtml("An *italic* word.");
        assertTrue(starForm.contains("<i>italic</i>"),
                () -> "expected italic HTML from *x* syntax, got: " + starForm);

        var underscoreForm = TelegramMarkdownFormatter.toHtml("An _italic_ word.");
        assertTrue(underscoreForm.contains("<i>italic</i>"),
                () -> "expected italic HTML from _x_ syntax, got: " + underscoreForm);
    }

    @Test
    public void strikethroughGeneratesSTags() {
        var html = TelegramMarkdownFormatter.toHtml("This is ~~removed~~ now.");
        assertTrue(html.contains("<s>removed</s>"),
                () -> "expected strikethrough HTML, got: " + html);
    }

    @Test
    public void inlineCodeGeneratesCodeTags() {
        var html = TelegramMarkdownFormatter.toHtml("Call `foo.bar()` on it.");
        assertTrue(html.contains("<code>foo.bar()</code>"),
                () -> "expected inline code HTML, got: " + html);
    }

    @Test
    public void inlineCodeEscapesHtmlSpecials() {
        var html = TelegramMarkdownFormatter.toHtml("Run `<script>alert(1)</script>` never.");
        assertTrue(html.contains("<code>&lt;script&gt;alert(1)&lt;/script&gt;</code>"),
                () -> "inline code must escape &, <, >: " + html);
    }

    // ── Block formatting ──

    @Test
    public void headingsFlattenToBold() {
        var html = TelegramMarkdownFormatter.toHtml("# H1\n\n## H2\n\n### H3");
        assertTrue(html.contains("<b>H1</b>"),
                () -> "h1 → <b>: " + html);
        assertTrue(html.contains("<b>H2</b>"),
                () -> "h2 → <b>: " + html);
        assertTrue(html.contains("<b>H3</b>"),
                () -> "h3 → <b>: " + html);
        // No actual <h1>/<h2>/<h3> tags — Telegram would reject them.
        assertFalse(html.contains("<h1"), "headings must not pass through as <h*> tags");
    }

    @Test
    public void fencedCodeBlockGeneratesPreCode() {
        var md = """
                ```
                hello world
                ```
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertTrue(html.contains("<pre><code>"),
                () -> "fenced code opens <pre><code>: " + html);
        assertTrue(html.contains("hello world"),
                () -> "fenced code preserves content: " + html);
        assertTrue(html.contains("</code></pre>"),
                () -> "fenced code closes </code></pre>: " + html);
    }

    @Test
    public void fencedCodeBlockPreservesLanguageHint() {
        var md = """
                ```python
                print(1)
                ```
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertTrue(html.contains("class=\"language-python\""),
                () -> "language hint becomes class attr: " + html);
    }

    @Test
    public void fencedCodeBlockEscapesHtmlSpecials() {
        var md = """
                ```
                a < b && c > d
                ```
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertTrue(html.contains("a &lt; b &amp;&amp; c &gt; d"),
                () -> "code content must be HTML-escaped: " + html);
    }

    @Test
    public void blockQuoteGeneratesBlockquoteTag() {
        var html = TelegramMarkdownFormatter.toHtml("> A famous saying.");
        assertTrue(html.contains("<blockquote>") && html.contains("</blockquote>"),
                () -> "blockquote renders wrapped: " + html);
        assertTrue(html.contains("A famous saying."),
                () -> "blockquote content preserved: " + html);
    }

    @Test
    public void thematicBreakRendersAsEmDash() {
        var html = TelegramMarkdownFormatter.toHtml("Before\n\n---\n\nAfter");
        // Telegram has no <hr>; we emit an em-dash visual separator.
        assertTrue(html.contains("—"),
                () -> "thematic break becomes em-dash: " + html);
        assertFalse(html.contains("<hr"),
                () -> "no <hr> tag (Telegram rejects it): " + html);
    }

    // ── Lists ──

    @Test
    public void unorderedListRendersWithBullets() {
        var md = """
                - alpha
                - beta
                - gamma
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertTrue(html.contains("• alpha"),
                () -> "bullet prefix expected: " + html);
        assertTrue(html.contains("• beta"),
                () -> "bullet prefix expected: " + html);
        assertTrue(html.contains("• gamma"),
                () -> "bullet prefix expected: " + html);
        assertFalse(html.contains("<ul"),
                () -> "no <ul> tag (Telegram rejects it): " + html);
    }

    @Test
    public void orderedListRendersWithNumberedPrefix() {
        var md = """
                1. first
                2. second
                3. third
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertTrue(html.contains("1. first"),
                () -> "numeric prefix expected: " + html);
        assertTrue(html.contains("2. second"),
                () -> "numeric prefix expected: " + html);
        assertTrue(html.contains("3. third"),
                () -> "numeric prefix expected: " + html);
        assertFalse(html.contains("<ol"),
                () -> "no <ol> tag: " + html);
    }

    // ── Links ──

    @Test
    public void linkGeneratesATagWithHref() {
        var html = TelegramMarkdownFormatter.toHtml("See [docs](https://example.com/docs).");
        assertTrue(html.contains("<a href=\"https://example.com/docs\">docs</a>"),
                () -> "link renders with href + text: " + html);
    }

    @Test
    public void linkEscapesAttributeQuotesAndAmpersands() {
        var html = TelegramMarkdownFormatter.toHtml("[q](https://example.com/search?a=1&b=\"2\")");
        // The href value must not contain raw " or unescaped & — both would break
        // the attribute or the parseMode check.
        assertTrue(html.contains("&amp;"),
                () -> "ampersand must be entity-escaped inside href: " + html);
        assertTrue(html.contains("&quot;"),
                () -> "double-quote must be entity-escaped inside href: " + html);
    }

    // ── Tables ──

    @Test
    public void tableRendersAsBulletsByDefault() {
        var md = """
                | Name | Status |
                | --- | --- |
                | foo | active |
                | bar | disabled |
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertTrue(html.contains("• <b>Name</b>: foo — <b>Status</b>: active"),
                () -> "bullets mode labels each cell by its header: " + html);
        assertTrue(html.contains("• <b>Name</b>: bar — <b>Status</b>: disabled"),
                () -> "bullets mode renders every body row: " + html);
        assertFalse(html.contains("<table"),
                () -> "no <table> tag (Telegram rejects it): " + html);
    }

    @Test
    public void tableCodeModeWrapsInPreCode() {
        var md = """
                | A | B |
                | --- | --- |
                | 1 | 2 |
                """;
        var html = TelegramMarkdownFormatter.toHtml(md, TableMode.CODE);
        assertTrue(html.contains("<pre><code>"),
                () -> "CODE mode wraps in <pre><code>: " + html);
        assertTrue(html.contains("| A | B |") || html.contains("A"),
                () -> "CODE mode preserves table text: " + html);
    }

    @Test
    public void tableOffModeDropsTable() {
        var md = """
                before

                | A | B |
                | --- | --- |
                | 1 | 2 |

                after
                """;
        var html = TelegramMarkdownFormatter.toHtml(md, TableMode.OFF);
        assertTrue(html.contains("before"), () -> "surrounding text preserved: " + html);
        assertTrue(html.contains("after"), () -> "surrounding text preserved: " + html);
        assertFalse(html.contains("| A |"),
                () -> "OFF mode drops table content: " + html);
    }

    // ── Safety: raw HTML in input ──

    @Test
    public void rawHtmlInInputIsEscaped() {
        var html = TelegramMarkdownFormatter.toHtml("Plain <script>alert(1)</script> text.");
        assertFalse(html.contains("<script>"),
                () -> "raw <script> must never pass through: " + html);
        assertTrue(html.contains("&lt;script&gt;"),
                () -> "raw HTML must be entity-escaped: " + html);
    }

    @Test
    public void plainTextIsPassedThroughEscaped() {
        var html = TelegramMarkdownFormatter.toHtml("just some text");
        assertTrue(html.contains("just some text"),
                () -> "plain text preserved: " + html);
    }

    @Test
    public void nullAndEmptyInputReturnEmptyString() {
        assertEquals("", TelegramMarkdownFormatter.toHtml(null));
        assertEquals("", TelegramMarkdownFormatter.toHtml(""));
    }

    // ── Chunker ──

    @Test
    public void chunkHtmlReturnsSingleChunkWhenUnderLimit() {
        var chunks = TelegramMarkdownFormatter.chunkHtml("<b>short</b>", 4000);
        assertEquals(1, chunks.size());
        assertEquals("<b>short</b>", chunks.get(0));
    }

    @Test
    public void chunkHtmlSplitsAtBlockBoundaries() {
        // Three ~100-char blocks separated by \n\n; maxLen of 150 forces splits.
        var blockA = "<b>" + "A".repeat(90) + "</b>";
        var blockB = "<b>" + "B".repeat(90) + "</b>";
        var blockC = "<b>" + "C".repeat(90) + "</b>";
        var html = blockA + "\n\n" + blockB + "\n\n" + blockC;
        var chunks = TelegramMarkdownFormatter.chunkHtml(html, 150);
        assertTrue(chunks.size() >= 2,
                () -> "expected multi-chunk split, got " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= 150,
                    () -> "chunk exceeds maxLen: " + c.length());
        }
        // No chunk should have a dangling unclosed <b> (block boundaries split
        // at \n\n, and each block is individually balanced).
        for (String c : chunks) {
            int opens = countOccurrences(c, "<b>");
            int closes = countOccurrences(c, "</b>");
            assertEquals(opens, closes,
                    () -> "tag imbalance in chunk: " + c);
        }
    }

    @Test
    public void chunkHtmlRewrapsOversizedCodeFence() {
        // Build an HTML payload with a single oversized <pre><code> block.
        var inner = ("line " + "x".repeat(50) + "\n").repeat(40); // ~2000 chars inner
        var html = "<pre><code>" + inner + "</code></pre>";
        var maxLen = 500;
        var chunks = TelegramMarkdownFormatter.chunkHtml(html, maxLen);

        assertTrue(chunks.size() >= 2,
                () -> "oversized code should split, got " + chunks.size());
        for (String c : chunks) {
            assertTrue(c.length() <= maxLen,
                    () -> "chunk exceeds maxLen: " + c.length());
            // Every chunk must be individually well-formed: starts with <pre><code>,
            // ends with </code></pre>.
            assertTrue(c.startsWith("<pre><code>"),
                    () -> "chunk must open with <pre><code>: " + c.substring(0, Math.min(30, c.length())));
            assertTrue(c.endsWith("</code></pre>"),
                    () -> "chunk must close with </code></pre>: " + c.substring(Math.max(0, c.length() - 30)));
        }
    }

    @Test
    public void chunkHtmlHandlesLanguageHintedCodeFence() {
        var inner = ("a".repeat(200) + "\n").repeat(10);
        var html = "<pre><code class=\"language-python\">" + inner + "</code></pre>";
        var chunks = TelegramMarkdownFormatter.chunkHtml(html, 600);
        assertTrue(chunks.size() >= 2, "should split");
        for (String c : chunks) {
            assertTrue(c.startsWith("<pre><code class=\"language-python\">"),
                    () -> "open tag with language hint preserved on every chunk: "
                            + c.substring(0, Math.min(60, c.length())));
            assertTrue(c.endsWith("</code></pre>"),
                    () -> "close tag present: " + c.substring(Math.max(0, c.length() - 30)));
        }
    }

    @Test
    public void chunkHtmlEmptyInputReturnsEmptyList() {
        assertTrue(TelegramMarkdownFormatter.chunkHtml(null, 100).isEmpty());
        assertTrue(TelegramMarkdownFormatter.chunkHtml("", 100).isEmpty());
    }

    // ── JCLAW-92: autolink, typographic, tasklist extensions ──

    @Test
    public void autolinkPromotesBareUrlToAnchor() {
        var html = TelegramMarkdownFormatter.toHtml("Visit https://example.com now.");
        assertTrue(html.contains("<a href=\"https://example.com\">https://example.com</a>"),
                () -> "bare URL should be promoted to an anchor: " + html);
    }

    @Test
    public void autolinkLeavesFormattedLinksAlone() {
        // Explicit [text](url) links must still render with the author's text,
        // not get clobbered by the autolink extension.
        var html = TelegramMarkdownFormatter.toHtml("See [the docs](https://example.com).");
        assertTrue(html.contains("<a href=\"https://example.com\">the docs</a>"),
                () -> "explicit link text preserved: " + html);
    }

    @Test
    public void typographicReplacesEmAndEnDashes() {
        // Two hyphens → en dash; three → em dash.
        var enDash = TelegramMarkdownFormatter.toHtml("range 1--10");
        assertTrue(enDash.contains("\u20131"),
                () -> "-- should become en dash (U+2013): " + enDash);

        var emDash = TelegramMarkdownFormatter.toHtml("wait --- yes");
        assertTrue(emDash.contains("\u2014"),
                () -> "--- should become em dash (U+2014): " + emDash);
    }

    @Test
    public void typographicReplacesEllipsis() {
        var html = TelegramMarkdownFormatter.toHtml("and so on...");
        assertTrue(html.contains("\u2026"),
                () -> "... should become … (U+2026): " + html);
    }

    @Test
    public void tasklistPendingRendersEmptyBox() {
        var html = TelegramMarkdownFormatter.toHtml("- [ ] pending item");
        assertTrue(html.contains("\u2610 pending item"),
                () -> "empty checkbox (U+2610) expected: " + html);
        assertFalse(html.contains("[ ]"),
                () -> "raw brackets must not leak: " + html);
    }

    @Test
    public void tasklistDoneRendersCheckedBox() {
        var html = TelegramMarkdownFormatter.toHtml("- [x] done item");
        assertTrue(html.contains("\u2611 done item"),
                () -> "checked box (U+2611) expected: " + html);
        assertFalse(html.contains("[x]"),
                () -> "raw brackets must not leak: " + html);
    }

    @Test
    public void tasklistMixedStateRendersPerItem() {
        var md = """
                - [x] alpha
                - [ ] beta
                - [x] gamma
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertTrue(html.contains("\u2611 alpha"), () -> "alpha should be checked: " + html);
        assertTrue(html.contains("\u2610 beta"), () -> "beta should be empty: " + html);
        assertTrue(html.contains("\u2611 gamma"), () -> "gamma should be checked: " + html);
    }

    // ── End-to-end: the skills-table screenshot scenario ──

    @Test
    public void endToEndSkillsTableRendersAsBullets() {
        // The v0.9.24 screenshot: an agent emitted a skills table that rendered
        // as raw pipes in Telegram. After JCLAW-91 it should come out as bullets.
        var md = """
                Here are the skills I have available:

                | Skill | Description |
                | --- | --- |
                | codebase-to-course | Transform any codebase into a course. |
                | daily-briefing | Generate a daily briefing. |

                Would you like to use any of these?
                """;
        var html = TelegramMarkdownFormatter.toHtml(md);
        assertFalse(html.contains("| Skill |"),
                () -> "raw pipe header must not leak through: " + html);
        assertTrue(html.contains("• <b>Skill</b>: codebase-to-course — <b>Description</b>: Transform any codebase into a course."),
                () -> "first row should render as a keyed bullet: " + html);
        assertTrue(html.contains("Would you like to use any of these?"),
                () -> "trailing prose preserved: " + html);
    }

    // ── Helpers ──

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }
}
