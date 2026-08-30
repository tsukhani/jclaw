import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ApiResponses;
import utils.ErrorRendering;
import utils.ErrorTemplate;
import utils.ErrorTemplates;

import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * JCLAW-1130: the error-template mechanism, before any surface depends on it.
 *
 * <p>What is pinned here is the behaviour the ~800 call sites in the rest of the epic will
 * assume: that a lookup always yields a usable template, that a template with no retry path
 * renders two sections rather than an empty third, and that a newly added error code on
 * {@link ApiResponses} cannot ship without a remedy.
 */
class ErrorTemplateTest extends UnitTest {

    private static final ErrorTemplate WITH_RETRY = new ErrorTemplate(
            "sample_code", "It broke.", "Check the thing.", "Try again.");

    private static final ErrorTemplate WITHOUT_RETRY = new ErrorTemplate(
            "terminal_code", "It broke.", "Check the thing.", null);

    // ── lookup ────────────────────────────────────────────────────────────────

    @Test
    void anUnknownCodeFallsBackInsteadOfReturningNullOrThrowing() {
        var t = ErrorTemplates.forCode("a_code_nobody_registered");

        assertNotNull(t, "the failure path must not have its own failure mode");
        assertEquals("a_code_nobody_registered", t.code(),
                "the fallback keeps the code so the log still identifies the failure");
        assertFalse(ErrorTemplates.isRegistered("a_code_nobody_registered"),
                "the fallback must be distinguishable from a registered template");
        assertFalse(t.whatBroke().isBlank());
        assertFalse(t.whatToCheck().isBlank());
    }

    @Test
    void everyCentralisedApiResponsesCodeHasItsOwnTemplate() throws Exception {
        var missing = new ArrayList<String>();
        for (var f : ApiResponses.class.getDeclaredFields()) {
            boolean isPublicConstant = Modifier.isPublic(f.getModifiers())
                    && Modifier.isStatic(f.getModifiers())
                    && Modifier.isFinal(f.getModifiers())
                    && f.getType() == String.class;
            if (!isPublicConstant) continue;
            var code = (String) f.get(null);
            if (!ErrorTemplates.isRegistered(code)) missing.add(f.getName() + " (" + code + ")");
        }
        // Reflection rather than a hardcoded list so a code added to ApiResponses tomorrow
        // fails here until someone writes its remedy, instead of silently taking the fallback.
        assertTrue(missing.isEmpty(),
                "every centralised error code needs a template; missing: " + missing);
    }

    // ── rendering ─────────────────────────────────────────────────────────────

    @Test
    void bothModesOmitTheRetrySectionEntirelyWhenThereIsNoRetry() {
        for (var mode : ErrorRendering.values()) {
            var rendered = mode.render(WITHOUT_RETRY);

            assertFalse(rendered.contains("retry"),
                    mode + " must omit the retry section, not render it empty: " + rendered);
            assertEquals(2, rendered.split("\n\n").length,
                    mode + " must emit exactly two sections when there is no retry path");
            assertFalse(rendered.endsWith("\n") || rendered.endsWith(" "),
                    mode + " must not leave the trailing separator of an omitted section");
        }
    }

    @Test
    void bothModesEmitAllThreeSectionsWhenThereIsARetry() {
        for (var mode : ErrorRendering.values()) {
            var rendered = mode.render(WITH_RETRY);

            assertEquals(3, rendered.split("\n\n").length, mode + " must emit three sections");
            assertTrue(rendered.contains("It broke."), mode.toString());
            assertTrue(rendered.contains("Check the thing."), mode.toString());
            assertTrue(rendered.contains("Try again."), mode.toString());
        }
    }

    @Test
    void plainModeEmitsNoMarkupBecauseSomeChannelsRejectIt() {
        var rendered = ErrorRendering.PLAIN.render(WITH_RETRY);

        // Telegram and WhatsApp reject or mangle stray markup, and a failed error delivery is
        // the worst outcome on this path — so PLAIN carries none of the usual offenders.
        for (var markup : new String[] {"*", "_", "`", "#", "[", "]"}) {
            assertFalse(rendered.contains(markup),
                    "PLAIN must not emit " + markup + ", got: " + rendered);
        }
    }

    @Test
    void richModeMarksUpTheLabelsAndNotTheContent() {
        var rendered = ErrorRendering.RICH.render(WITH_RETRY);

        assertTrue(rendered.contains("**What broke**"), rendered);
        assertTrue(rendered.contains("**What to check**"), rendered);
        assertTrue(rendered.contains("**How to retry**"), rendered);
        assertTrue(rendered.contains("— It broke."),
                "content follows the label unmarked so a failure message containing markup "
                        + "cannot break the surrounding emphasis: " + rendered);
    }

    // ── localization keys ─────────────────────────────────────────────────────

    @Test
    void keysDeriveFromTheCodeSoTheyCannotDisagreeWithIt() {
        assertEquals("error.sample_code.broke", WITH_RETRY.brokeKey());
        assertEquals("error.sample_code.check", WITH_RETRY.checkKey());
        assertEquals("error.sample_code.retry", WITH_RETRY.retryKey());
    }

    @Test
    void everyRegisteredTemplateHasDistinctDerivedKeys() {
        // A duplicated code would silently collapse two remedies onto one message-bundle key.
        var t = ErrorTemplates.forCode(ApiResponses.NOT_FOUND);
        assertEquals("error.not_found.broke", t.brokeKey());
        assertNotEquals(t.brokeKey(), t.checkKey());
        assertNotEquals(t.checkKey(), t.retryKey());
    }

    // ── construction ──────────────────────────────────────────────────────────

    @Test
    void aBlankRetryIsRejectedRatherThanTreatedAsAbsent() {
        // Otherwise "no retry" would have two representations and rendering would have to
        // test for both — the exact bug the omit-the-section behaviour above exists to avoid.
        assertThrows(IllegalArgumentException.class,
                () -> new ErrorTemplate("c", "broke", "check", "   "));
    }

    @Test
    void theRequiredPartsCannotBeBlank() {
        assertThrows(IllegalArgumentException.class, () -> new ErrorTemplate("", "b", "c", null));
        assertThrows(IllegalArgumentException.class, () -> new ErrorTemplate("c", "", "c", null));
        assertThrows(IllegalArgumentException.class, () -> new ErrorTemplate("c", "b", "", null));
    }

    @Test
    void hasRetryReportsWhetherThereIsARetryPath() {
        assertTrue(WITH_RETRY.hasRetry());
        assertFalse(WITHOUT_RETRY.hasRetry());
    }
}
