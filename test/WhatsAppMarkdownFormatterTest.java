import channels.WhatsAppMarkdownFormatter;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ChannelErrorTemplates;
import utils.ErrorRendering;

/**
 * JCLAW-1409: CommonMark → WhatsApp formatting, one test per row of the ticket's
 * mapping plus tables, code protection and pass-through. Asserts on the string
 * JClaw produces, never on how a client draws it.
 */
class WhatsAppMarkdownFormatterTest extends UnitTest {

    private static String fmt(String md) {
        return WhatsAppMarkdownFormatter.format(md);
    }

    @Test
    void nullAndEmptyProduceEmpty() {
        assertEquals("", fmt(null));
        assertEquals("", fmt(""));
    }

    @Test
    void aPlainSentenceComesBackUnchanged() {
        assertEquals("The meeting moved to Tuesday, 3pm.", fmt("The meeting moved to Tuesday, 3pm."));
    }

    @Test
    void theChannelErrorFallbackComesBackUnchanged() {
        var plain = ChannelErrorTemplates.render(ChannelErrorTemplates.forChannelReader(),
                ErrorRendering.PLAIN, 4096);
        assertEquals(plain, fmt(plain));
    }

    @Test
    void boldBecomesSingleStar() {
        assertEquals("*bold*", fmt("**bold**"));
        assertEquals("*bold*", fmt("__bold__"));
    }

    @Test
    void italicBecomesUnderscore() {
        assertEquals("_it_", fmt("*it*"));
        assertEquals("_it_", fmt("_it_"));
        assertEquals("*b* and _i_", fmt("**b** and *i*"));
    }

    @Test
    void strikethroughBecomesSingleTilde() {
        assertEquals("~gone~", fmt("~~gone~~"));
    }

    @Test
    void inlineCodeIsKeptAndItsContentUntouched() {
        assertEquals("`**not bold** [a](b)`", fmt("`**not bold** [a](b)`"));
        assertEquals("run `a < b && c`", fmt("run `a < b && c`"));
    }

    @Test
    void fencedCodeDropsTheLanguageAndLeavesContentUntouched() {
        assertEquals("```x = a ** b  # **kept**\n[l](u) _y_```",
                fmt("```python\nx = a ** b  # **kept**\n[l](u) _y_\n```"));
    }

    @Test
    void headingBecomesBoldOnItsOwnLineThenABlankLine() {
        assertEquals("*Title*", fmt("# Title"));
        assertEquals("*Sub*\n\nbody", fmt("### Sub\nbody"));
    }

    @Test
    void everyBulletMarkerBecomesAHyphen() {
        assertEquals("- a\n- b", fmt("- a\n- b"));
        assertEquals("- a\n- b", fmt("* a\n* b"));
        assertEquals("- a\n- b", fmt("+ a\n+ b"));
    }

    @Test
    void numberedListKeepsItsNumbers() {
        assertEquals("1. a\n2. b", fmt("1. a\n2. b"));
        assertEquals("3. a\n4. b", fmt("3. a\n4. b"));
    }

    @Test
    void quotePrefixesEveryLine() {
        assertEquals("> one\n> *two*", fmt("> one\n> **two**"));
    }

    @Test
    void linkBecomesTextThenAddressInParentheses() {
        assertEquals("click (https://x.com)", fmt("[click](https://x.com)"));
        assertEquals("*click* (https://x.com)", fmt("[**click**](https://x.com)"));
    }

    @Test
    void linkWhoseTextIsItsOwnAddressBecomesTheAddress() {
        assertEquals("https://x.com", fmt("[https://x.com](https://x.com)"));
    }

    @Test
    void bareAndAngleBracketUrlsBecomeTheAddress() {
        assertEquals("see https://x.com/a now", fmt("see https://x.com/a now"));
        assertEquals("see https://x.com/a now", fmt("see <https://x.com/a> now"));
    }

    @Test
    void literalFormattingCharactersAreNotEscaped() {
        assertEquals("2 * 3 = 6 and snake_case ~ ok", fmt("2 * 3 = 6 and snake_case ~ ok"));
    }

    @Test
    void textWithNoChildNodeIsKeptRatherThanDropped() {
        assertEquals("x < y & z", fmt("x &lt; y &amp; z"));
        assertEquals("mail a@b.com or c@d.com", fmt("mail <a@b.com> or c@d.com"));
    }

    @Test
    void aBackslashEscapePrintsTheCharacterItProtects() {
        assertEquals("5 * 3 and a_b", fmt("5 \\* 3 and a\\_b"));
        assertEquals("C:\\Users\\me", fmt("C:\\Users\\me"), "a backslash before a letter is literal");
    }

    @Test
    void tableBecomesOneBulletLinePerBodyRow() {
        var md = """
                | Course | Level | Duration | Price |
                |---|---|---|---|
                | [Intro to Widgets](https://example.com/course/intro-to-widgets) | Beginner | 2 days | USD 500 |
                | [Advanced Widgets](https://example.com/course/advanced-widgets) | Intermediate | 2 days | USD 900 — 3 upcoming intakes |
                """;
        var out = fmt(md);
        var lines = out.split("\n");
        assertEquals(2, lines.length, out);
        assertEquals("- *Course*: Intro to Widgets (https://example.com/course/intro-to-widgets)"
                + " — *Level*: Beginner — *Duration*: 2 days — *Price*: USD 500", lines[0]);
        assertTrue(lines[1].startsWith("- *Course*: Advanced Widgets (https://example.com/course/advanced-widgets)"),
                out);
        assertFalse(out.contains("|"), "no pipe row may reach the output: " + out);
        assertFalse(out.contains("---"), "no separator row may reach the output: " + out);
    }

    @Test
    void boldInsideATableCellConverts() {
        assertEquals("- *Skill*: *bcr* — *Description*: Read cards",
                fmt("| Skill | Description |\n|---|---|\n| **bcr** | Read cards |"));
    }

    @Test
    void theTicketExampleConvertsEndToEnd() {
        var md = """
                - **"What courses do you have?"** — I can search our catalog

                | Course | Level |
                |---|---|
                | [Intro to Widgets](https://example.com/w) | Beginner |

                A few notes:
                - Want the flexible option? **Advanced Widgets** has the most intakes.
                """;
        assertEquals("""
                - *"What courses do you have?"* — I can search our catalog

                - *Course*: Intro to Widgets (https://example.com/w) — *Level*: Beginner

                A few notes:

                - Want the flexible option? *Advanced Widgets* has the most intakes.""", fmt(md));
    }
}
