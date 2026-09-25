import channels.WhatsAppChannel;
import llm.LlmProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;
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

    /**
     * A channel reader is not the operator — a group guest, a WhatsApp customer — so no
     * classification may put the provider, the model, the key or the balance in front of them.
     */
    @Test
    void theChannelReaderTemplateDisclosesNothingAboutTheDeployment() {
        var rendered = ErrorRendering.PLAIN.render(ChannelErrorTemplates.forChannelReader()).toLowerCase();
        for (var word : new String[]{"provider", "model", "key", "credit", "quota", "settings", "log entry"}) {
            assertFalse(rendered.contains(word), "'" + word + "' reached the reader: " + rendered);
        }
        assertNotNull(ChannelErrorTemplates.forChannelReader().howToRetry());
    }

    @Test
    void theOperatorDetailCarriesTheClassifiedRemedy() {
        var failure = new LlmErrorTemplates.Failure(
                LlmErrorTemplates.Remedy.QUOTA_EXHAUSTED, "together", "llama-4", null, null);
        var detail = ChannelErrorTemplates.operatorDetail(new RuntimeException("turn failed",
                new LlmProvider.LlmException.ClientError("HTTP 402", failure)));
        assertNotNull(detail);
        assertTrue(detail.contains("together") && detail.contains("credit"), detail);
    }

    /** Unclassified, the raw message already on the log line says more than the generic template. */
    @Test
    void anUnclassifiedFailureHasNoOperatorDetail() {
        assertNull(ChannelErrorTemplates.operatorDetail(new IllegalStateException("boom")));
        assertNull(ChannelErrorTemplates.operatorDetail(null));
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

    // --- JCLAW-1135: binding failures, read by an operator through /logs ---

    /**
     * Regression guard for a defect found while validating the secret-safety test: EventLog.message
     * is capped at 500 and EventLogger cuts from the tail, so the Slack template — 610 chars — lost
     * its retry instruction, the one part the operator acts on. Every binding template must keep
     * its full retry once rendered to the log cap. WhatsApp sat at 499, one character from failing.
     */
    @Test
    void everyBindingTemplateKeepsItsRetryInstructionWithinTheLogCap() {
        var cap = EventLogger.MESSAGE_MAX_CHARS;
        for (var t : new ErrorTemplate[]{
                ChannelErrorTemplates.slackSignatureMismatch(42L, "T0123ABCD"),
                ChannelErrorTemplates.telegramTokenRejected(42L),
                ChannelErrorTemplates.whatsAppOutsideWindow(42L, false),
                ChannelErrorTemplates.whatsAppOutsideWindow(42L, true)}) {
            var stored = ChannelErrorTemplates.render(t, ErrorRendering.PLAIN, cap);
            assertTrue(stored.length() <= cap, t.code() + " exceeds the log cap: " + stored.length());
            assertTrue(stored.contains(t.howToRetry()),
                    t.code() + " lost its retry instruction to truncation: " + stored);
        }
    }

    @Test
    void theSlackMismatchNamesTheBindingAndTheSecretItConcerns() {
        var t = ChannelErrorTemplates.slackSignatureMismatch(42L, "T0123ABCD");
        assertTrue(t.whatBroke().contains("42") && t.whatBroke().contains("T0123ABCD"), t.whatBroke());
        assertTrue(t.whatToCheck().contains("Signing Secret"), t.whatToCheck());
    }

    /** Verified against Slack's docs: a regenerated secret keeps the old one valid for 24 hours. */
    @Test
    void theSlackMismatchExplainsWhyItStartsADayAfterRegeneration() {
        assertTrue(ChannelErrorTemplates.slackSignatureMismatch(1L, null).whatToCheck()
                .contains("24 hours"));
    }

    @Test
    void theTelegramTemplateNamesTheBindingAndSaysItWasDisabled() {
        var t = ChannelErrorTemplates.telegramTokenRejected(42L);
        assertTrue(t.whatBroke().contains("42") && t.whatBroke().contains("disabled"), t.whatBroke());
        assertTrue(t.howToRetry().contains("re-enable"), t.howToRetry());
    }

    /** The story's AC: an out-of-window rejection reads as an expected constraint, not a fault. */
    @Test
    void theWhatsAppWindowRejectionReadsAsAConstraintNotAnError() {
        for (var templateConfigured : new boolean[]{false, true}) {
            var t = ChannelErrorTemplates.whatsAppOutsideWindow(42L, templateConfigured);
            var all = (t.whatBroke() + " " + t.whatToCheck()).toLowerCase();
            assertFalse(all.contains("error"), "must not be framed as an error: " + all);
            assertFalse(all.contains("failed"), all);
            assertTrue(t.whatToCheck().contains("Expected"), t.whatToCheck());
        }
    }

    /** Telling an operator who configured a template that there is none sends them the wrong way. */
    @Test
    void theWhatsAppWindowRejectionOnlyAsksForATemplateWhenThereIsNone() {
        assertTrue(ChannelErrorTemplates.whatsAppOutsideWindow(42L, false).whatToCheck()
                .contains("none configured"));
        var withTemplate = ChannelErrorTemplates.whatsAppOutsideWindow(42L, true);
        assertFalse(withTemplate.whatToCheck().contains("none configured"), withTemplate.whatToCheck());
        assertFalse(withTemplate.howToRetry().contains("Set an approved message template"),
                withTemplate.howToRetry());
    }

    @Test
    void metaTheOutOfWindowCodeIsRecognizedFromAGraphApiErrorBody() {
        var body = "{\"error\":{\"message\":\"Re-engagement message\",\"type\":\"OAuthException\","
                + "\"code\":131047,\"fbtrace_id\":\"AbC\"}}";
        assertEquals(ChannelErrorTemplates.META_OUTSIDE_WINDOW, WhatsAppChannel.metaErrorCode(body));
    }

    /** Runs on the failure path, so it must never throw on a body it cannot read. */
    @Test
    void metaErrorCodeIsTolerantOfBodiesItCannotParse() {
        for (var bad : new String[]{null, "", "   ", "not json", "[1,2]", "{}", "{\"error\":\"x\"}",
                "{\"error\":{\"code\":\"nan\"}}"}) {
            assertEquals(-1, WhatsAppChannel.metaErrorCode(bad), "for body: " + bad);
        }
    }
}
