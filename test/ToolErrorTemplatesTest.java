import agents.ToolRegistry;
import agents.ToolResultVerifier;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.ErrorTemplate;
import utils.ToolErrorTemplates;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;

/**
 * JCLAW-1132: the tool-error seam, and the two couplings a converted tool inherits from it —
 * the {@code "Error…"} prose convention {@code ToolResultVerifier} scores on, and the
 * {@code structuredJson} slot the template rides in.
 */
class ToolErrorTemplatesTest extends UnitTest {

    // ── rendering ─────────────────────────────────────────────────────────────

    @Test
    void theRenderedTextKeepsTheErrorPrefixTheVerifierScoresOn() {
        var result = ToolRegistry.ToolResult.error(ToolErrorTemplates.fsNotFound("notes.md"));

        var verdict = ToolResultVerifier.check("filesystem", result);

        assertEquals(ToolResultVerifier.Verdict.ERROR_REPORTED, verdict.verdict(),
                "rendering without the Error: head would score every converted failure a clean pass");
        assertTrue(result.text().startsWith("Error: File not found: notes.md"),
                "the head line must still carry today's message: " + result.text());
    }

    @Test
    void aTemplateWithNoRetryRendersTwoSectionsRatherThanAnEmptyThird() {
        var withRetry = ToolErrorTemplates.render(ToolErrorTemplates.fsNotFound("notes.md"));
        var withoutRetry = ToolErrorTemplates.render(ToolErrorTemplates.fsReadOnly("research"));

        assertTrue(withRetry.contains("How to retry: "));
        assertFalse(withoutRetry.contains("How to retry"),
                "a refusal with no retry path must not emit an empty section: " + withoutRetry);
        assertTrue(withoutRetry.contains("What to check: "));
    }

    @Test
    void theStructuredPayloadParsesAndCarriesTheCode() {
        var json = ToolErrorTemplates.structuredJson(ToolErrorTemplates.webTimedOut("https://x.test/a", 30));

        var error = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("error");
        assertEquals(ToolErrorTemplates.WEB_TIMED_OUT, error.get("code").getAsString());
        assertTrue(error.has("whatToCheck"));
        assertTrue(error.has("howToRetry"));
    }

    @Test
    void aTemplateWithoutARetryOmitsTheFieldRatherThanWritingNull() {
        var json = ToolErrorTemplates.structuredJson(ToolErrorTemplates.webBlocked("loopback address"));

        var error = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("error");
        assertFalse(error.has("howToRetry"),
                "a null retry must be absent, not a JSON null a reader has to special-case");
    }

    /**
     * The story's "no tool returns an empty or null error" clause, checked at the source
     * rather than by driving every tool: a factory that shipped with a blank section would
     * hand the model a heading with nothing under it.
     */
    @Test
    void everyFactoryProducesAFullyPopulatedTemplate() throws Exception {
        var checked = new ArrayList<String>();
        for (var m : ToolErrorTemplates.class.getDeclaredMethods()) {
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != ErrorTemplate.class) continue;

            var t = (ErrorTemplate) m.invoke(null, (Object[]) sampleArgs(m));
            assertFalse(t.code().isBlank(), m.getName() + " produced a blank code");
            assertFalse(t.whatBroke().isBlank(), m.getName() + " produced a blank whatBroke");
            assertFalse(t.whatToCheck().isBlank(), m.getName() + " produced a blank whatToCheck");
            assertFalse(ToolErrorTemplates.render(t).isBlank(), m.getName() + " rendered blank");
            checked.add(m.getName());
        }
        assertTrue(checked.size() >= 20,
                "the sweep found only " + checked.size() + " factories — it has stopped matching");
    }

    private static Object[] sampleArgs(Method m) {
        var types = m.getParameterTypes();
        var args = new Object[types.length];
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == int.class ? 1 : "sample";
        }
        return args;
    }

    // ── shell (AC: command, exit status, what to check) ───────────────────────

    @Test
    void aShellFailureNamesTheCommandTheStatusAndWhatToCheck() {
        var text = ToolErrorTemplates.render(ToolErrorTemplates.shellExitNonZero("frobnicate --all", 127));

        assertTrue(text.contains("frobnicate --all"), text);
        assertTrue(text.contains("127"), text);
        assertTrue(text.contains("not found on PATH"),
                "status 127 has a fixed meaning worth stating: " + text);
    }

    @Test
    void aTimeoutIsADifferentFailureFromANonZeroExit() {
        var timedOut = ToolErrorTemplates.shellTimedOut("sleep 600", 30);
        var exited = ToolErrorTemplates.shellExitNonZero("sleep 600", 1);

        assertNotEquals(timedOut.code(), exited.code());
        assertTrue(ToolErrorTemplates.render(timedOut).contains("larger 'timeout'"),
                "the remedy for a timeout is the timeout, not the command");
    }

    // ── web (AC: unreachable vs timed out) ────────────────────────────────────

    @Test
    void hostUnreachableAndTimedOutAreDistinctWithDistinctRemedies() {
        var unresolved = ToolErrorTemplates.webHostUnresolved("https://nope.test/a", "nodename nor servname");
        var unreachable = ToolErrorTemplates.webHostUnreachable("https://x.test:81/a", "Connection refused");
        var timedOut = ToolErrorTemplates.webTimedOut("https://x.test/a", 30);

        assertEquals(3, java.util.Set.of(unresolved.code(), unreachable.code(), timedOut.code()).size(),
                "three failures whose remedies differ must not share a code");
        assertTrue(ToolErrorTemplates.render(unresolved).contains("Do not retry the same URL"));
        assertTrue(ToolErrorTemplates.render(timedOut).contains("Retry once"));
        assertTrue(ToolErrorTemplates.render(unreachable).contains("scheme and port"));
    }

    // ── backward compatibility ───────────────────────────────────────────────

    /**
     * A transcript recorded before this story has null in {@code tool_result_structured}.
     * Nothing about reading it back may depend on the new payload being there.
     */
    @Test
    void aFetchFailureDescribesTheFallbacksWithoutTheCodebasesOwnVocabulary() {
        var t = ToolErrorTemplates.webFetchFailed("https://example.com/", "HTTP 403");
        assertFalse(t.whatToCheck().contains("ladder"), t.whatToCheck());
        assertTrue(t.whatToCheck().contains("fallback"), t.whatToCheck());
    }

    @Test
    void aResultWithNoStructuredPayloadStillRendersAndVerifiesAsBefore() {
        var legacySuccess = ToolRegistry.ToolResult.text("File written successfully: notes.md");
        var legacyFailure = ToolRegistry.ToolResult.text("Error: File not found: notes.md");

        assertNull(legacySuccess.structuredJson());
        assertNull(legacyFailure.structuredJson());
        assertEquals(ToolResultVerifier.Verdict.OK,
                ToolResultVerifier.check("filesystem", legacySuccess).verdict());
        assertEquals(ToolResultVerifier.Verdict.ERROR_REPORTED,
                ToolResultVerifier.check("filesystem", legacyFailure).verdict());
    }

    @Test
    void aConvertedFailureIsStillDispatchedRatherThanARefusal() {
        var result = ToolRegistry.ToolResult.error(ToolErrorTemplates.shellEmptyCommand());

        assertTrue(result.dispatched(),
                "the tool ran and reported a problem — JCLAW-883's distinction must not move");
        assertTrue(result.attachments().isEmpty());
        assertNull(result.videoJob());
    }
}
