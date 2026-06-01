import channels.SlackMarkdownFormatter;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-341: CommonMark → Slack mrkdwn conversion. The interesting cases are the
 * single/double-star disambiguation (CommonMark *italic* vs mrkdwn *bold*) and
 * code protection (no inline transform leaks into spans/fences).
 */
class SlackMarkdownFormatterTest extends UnitTest {

    @Test
    void nullAndBlankProduceEmpty() {
        assertEquals("", SlackMarkdownFormatter.format(null));
        assertEquals("", SlackMarkdownFormatter.format(""));
    }

    @Test
    void boldBecomesSingleStar() {
        assertEquals("*bold*", SlackMarkdownFormatter.format("**bold**"));
        assertEquals("*bold*", SlackMarkdownFormatter.format("__bold__"));
    }

    @Test
    void italicBecomesUnderscore() {
        assertEquals("_it_", SlackMarkdownFormatter.format("*it*"));
        assertEquals("_it_", SlackMarkdownFormatter.format("_it_"));
    }

    @Test
    void boldAndItalicDisambiguated() {
        // CommonMark's *single*/**double** map to mrkdwn's _underscore_/*single*.
        assertEquals("*b* and _i_", SlackMarkdownFormatter.format("**b** and *i*"));
    }

    @Test
    void headingBecomesBold() {
        assertEquals("*Title*", SlackMarkdownFormatter.format("# Title"));
        assertEquals("*Sub*", SlackMarkdownFormatter.format("### Sub"));
    }

    @Test
    void strikethrough() {
        assertEquals("~gone~", SlackMarkdownFormatter.format("~~gone~~"));
    }

    @Test
    void inlineLinkBecomesAngleBracketPipe() {
        assertEquals("<https://x.com|click>", SlackMarkdownFormatter.format("[click](https://x.com)"));
    }

    @Test
    void bareUrlBecomesAngleBracket() {
        assertEquals("<https://x.com>", SlackMarkdownFormatter.format("https://x.com"));
    }

    @Test
    void unorderedListUsesBullets() {
        assertEquals("• a\n• b", SlackMarkdownFormatter.format("- a\n- b"));
    }

    @Test
    void orderedListKeepsNumbers() {
        assertEquals("1. a\n2. b", SlackMarkdownFormatter.format("1. a\n2. b"));
    }

    @Test
    void controlCharsEscaped() {
        assertEquals("a &lt; b &amp; c &gt; d", SlackMarkdownFormatter.format("a < b & c > d"));
    }

    @Test
    void inlineCodeIsProtectedFromInlineTransforms() {
        // The ** inside a code span must NOT become a bold marker.
        assertEquals("`**not bold**`", SlackMarkdownFormatter.format("`**not bold**`"));
        // Control chars inside code are still escaped (Slack displays them).
        assertEquals("`a &lt; b`", SlackMarkdownFormatter.format("`a < b`"));
    }

    @Test
    void fencedCodeStripsLanguageAndProtectsContent() {
        var out = SlackMarkdownFormatter.format("```python\nx = a ** b  # **kept**\n```");
        assertEquals("```\nx = a ** b  # **kept**\n```", out);
    }

    @Test
    void blockquotePrefixesLines() {
        assertEquals("> quoted", SlackMarkdownFormatter.format("> quoted"));
    }

    @Test
    void mixedDocument() {
        var md = "# Report\n\nStatus: **green**, see [docs](https://d.io).\n\n- one\n- two";
        var out = SlackMarkdownFormatter.format(md);
        assertEquals("*Report*\n\nStatus: *green*, see <https://d.io|docs>.\n\n• one\n• two", out);
    }
}
