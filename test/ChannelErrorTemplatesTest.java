import llm.LlmProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ChannelErrorTemplates;
import utils.ErrorRendering;
import utils.ErrorTemplate;
import utils.LlmErrorTemplates;

/**
 * JCLAW-1133: a failed turn reaches the reader as three actionable parts, rendered for what the
 * channel can display, and never loses the retry instruction to a length cap.
 *
 * <p>Every sink previously ended a failed turn with a fixed sentence naming nothing. The remedy is
 * usually knowable — JCLAW-1134 attaches a classified {@code Failure} to the provider exception —
 * so the tests below pin that the classification is reused rather than the message re-derived from
 * prose, which is the mistake this epic keeps finding.
 */
class ChannelErrorTemplatesTest extends UnitTest {

    private static final int TELEGRAM_CAP = 4096;

    @Test
    void aClassifiedProviderFailureKeepsItsRemedyRatherThanFallingBackToGeneric() {
        var failure = new LlmErrorTemplates.Failure(
                LlmErrorTemplates.Remedy.INVALID_KEY, "openrouter", "gpt-4.1", null, null);
        var t = ChannelErrorTemplates.forTurnFailure(
                new LlmProvider.LlmException.ClientError("HTTP 401 from openrouter", failure));
        assertTrue(t.whatBroke().contains("openrouter"), t.whatBroke());
        assertNotEquals(ChannelErrorTemplates.TURN_FAILED, t.code(),
                "a classified failure must not collapse into the generic turn template");
    }

    /** The runner wraps provider failures before they reach a sink, so the cause chain matters. */
    @Test
    void aClassifiedFailureIsFoundThroughAWrappingException() {
        var failure = new LlmErrorTemplates.Failure(
                LlmErrorTemplates.Remedy.QUOTA_EXHAUSTED, "together", null, null, null);
        var wrapped = new RuntimeException("turn failed",
                new LlmProvider.LlmException.ClientError("HTTP 429", failure));
        assertTrue(ChannelErrorTemplates.forTurnFailure(wrapped).whatBroke().contains("together"));
    }

    @Test
    void anUnclassifiedFailureStillGetsThreeParts() {
        var t = ChannelErrorTemplates.forTurnFailure(new IllegalStateException("boom"));
        assertEquals(ChannelErrorTemplates.TURN_FAILED, t.code());
        assertNotNull(t.howToRetry());
        assertFalse(t.whatBroke().isBlank());
    }

    /** The epic's rule: technical detail goes to the log, never to the reader. */
    @Test
    void theGenericTemplateNeverLeaksTheExceptionText() {
        var t = ChannelErrorTemplates.forTurnFailure(
                new IllegalStateException("NullPointerException at com.foo.Bar.baz(Bar.java:42)"));
        var rendered = ErrorRendering.PLAIN.render(t);
        assertFalse(rendered.contains("Bar.java"), "stack detail must not reach the reader: " + rendered);
        assertFalse(rendered.contains("NullPointerException"), rendered);
    }

    @Test
    void plainRenderingCarriesNoMarkupForTelegramAndWhatsApp() {
        var t = ChannelErrorTemplates.forTurnFailure(null);
        var out = ChannelErrorTemplates.render(t, ErrorRendering.PLAIN, TELEGRAM_CAP);
        assertFalse(out.contains("**"), "no markdown emphasis on a plain channel: " + out);
        assertFalse(out.contains("__"), out);
    }

    /**
     * The AC's hard case: markup-hostile content in the failure text must not make the message
     * unsendable. A backtick or asterisk arriving from a provider's error body is data, and on the
     * plain path nothing may turn it into formatting.
     */
    @Test
    void markupHostileFailureTextSurvivesThePlainPath() {
        var failure = new LlmErrorTemplates.Failure(
                LlmErrorTemplates.Remedy.UNCLASSIFIED, "acme`*_~[]", null, null, null);
        var t = ChannelErrorTemplates.forTurnFailure(
                new LlmProvider.LlmException.ClientError("boom", failure));
        var out = ChannelErrorTemplates.render(t, ErrorRendering.PLAIN, TELEGRAM_CAP);
        assertTrue(out.contains("acme`*_~[]"), "the text passes through verbatim: " + out);
        assertFalse(out.contains("**"), out);
    }

    /**
     * Truncation takes from the middle. Cutting the tail would drop the retry instruction, which
     * is the only part the reader acts on — the exact dead end this epic exists to remove.
     */
    @Test
    void truncationKeepsTheRetryInstructionAndCutsTheCheckSection() {
        var t = new ErrorTemplate("x", "It broke.", "A ".repeat(4000).trim(), "Send it again.");
        var out = ChannelErrorTemplates.render(t, ErrorRendering.PLAIN, 200);
        assertTrue(out.length() <= 200, "respects the cap: " + out.length());
        assertTrue(out.contains("Send it again."), "the retry survives truncation: " + out);
        assertTrue(out.contains("It broke."), out);
        assertTrue(out.contains("…"), "the cut is marked: " + out);
    }

    @Test
    void aShortMessageIsNotTruncatedAtAll() {
        var t = new ErrorTemplate("x", "It broke.", "Check the log.", "Send it again.");
        var out = ChannelErrorTemplates.render(t, ErrorRendering.PLAIN, 4096);
        assertEquals(ErrorRendering.PLAIN.render(t), out);
        assertFalse(out.contains("…"));
    }

    /** A template with no retry renders two sections rather than an empty third, at any cap. */
    @Test
    void aTemplateWithNoRetryStillRendersCleanly() {
        var t = new ErrorTemplate("x", "It broke.", "Nothing to check.", null);
        var out = ChannelErrorTemplates.render(t, ErrorRendering.PLAIN, 4096);
        assertFalse(out.contains("How to retry"), out);
    }
}
