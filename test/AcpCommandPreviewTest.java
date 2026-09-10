import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.AcpCommandPreview;
import tools.HarnessModel;

import java.util.List;

/**
 * The Coding panel's read-only preview of what a {@code runtime="acp"} spawn launches.
 * Drives {@link AcpCommandPreview#of} rather than {@code current()} so the assertions
 * don't depend on which harness adapters happen to be on the host's PATH.
 */
class AcpCommandPreviewTest extends UnitTest {

    private static final List<String> CLAUDE = List.of("claude", "-p");
    private static final HarnessModel OLLAMA =
            new HarnessModel("ollama", "http://localhost:11434/v1", "k", "qwen3.5:9b");

    @Test
    void withNoOverrideTheLaunchIsTheConfiguredCommand() {
        var p = AcpCommandPreview.of("claude", CLAUDE, null, null);
        assertEquals("claude -p", p.command());
        assertEquals("claude -p", p.effective());
        assertEquals(List.of(), p.env());
        assertNull(p.rejection());
        assertFalse(p.acpAdapter());
    }

    @Test
    void anUnsetCommandPreviewsNothing() {
        var p = AcpCommandPreview.of("claude", List.of(), null, null);
        assertEquals("", p.command());
        assertEquals("", p.effective());
    }

    @Test
    void claudeTakesTheModelFlagOnTheWrapperAndTheEndpointInEnv() {
        var p = AcpCommandPreview.of("claude", CLAUDE, OLLAMA, null);
        assertEquals("claude -p --model qwen3.5:9b", p.effective());
        assertEquals(List.of("ANTHROPIC_MODEL", "ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN"), p.env());
        assertNull(p.rejection());
    }

    @Test
    void theAcpAdapterReplacesTheCommandAndCarriesTheModelInEnvInstead() {
        var p = AcpCommandPreview.of("claude", CLAUDE, OLLAMA, "claude-agent-acp");
        assertEquals("claude-agent-acp", p.effective(),
                "claude-agent-acp has no model flag — the env carries the override");
        assertTrue(p.acpAdapter());
        assertTrue(p.env().contains("ANTHROPIC_MODEL"));
        // The configured command is still reported so the panel can show both.
        assertEquals("claude -p", p.command());
    }

    @Test
    void codexCarriesItsProviderAsAnInlineTomlBlock() {
        var p = AcpCommandPreview.of("codex", List.of("codex", "exec"), OLLAMA, null);
        assertTrue(p.effective().startsWith("codex exec -m qwen3.5:9b -c model_provider=jclaw"),
                "effective was: " + p.effective());
        assertTrue(p.effective().contains("model_providers.jclaw.wire_api=\"responses\""),
                "effective was: " + p.effective());
    }

    @Test
    void aModelOnlyOverrideLeavesTheHarnessOnItsOwnEndpoint() {
        var p = AcpCommandPreview.of("gemini", List.of("gemini", "-p"),
                new HarnessModel(null, null, null, "gemini-3-flash"), null);
        assertEquals("gemini -p -m gemini-3-flash", p.effective());
        assertEquals(List.of(), p.env());
    }

    @Test
    void aHarnessThatTakesNoOverrideIsRejectedAndLaunchesUnchanged() {
        var p = AcpCommandPreview.of("opencode", List.of("opencode", "run"),
                new HarnessModel(null, null, null, "qwen3.5:9b"), null);
        assertNotNull(p.rejection());
        assertTrue(p.rejection().contains("takes no model override"), "rejection was: " + p.rejection());
        assertEquals("opencode run", p.effective());
        assertEquals(List.of(), p.env());
    }

    @Test
    void geminiRefusesAnEndpointOverrideRatherThanMistranslateIt() {
        var p = AcpCommandPreview.of("gemini", List.of("gemini", "-p"), OLLAMA, null);
        assertNotNull(p.rejection());
        assertEquals("gemini -p", p.effective());
    }
}
