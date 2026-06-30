import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-534: per-agent memory auto-capture resolution. Capture is on by default;
 * the extractor model is the agent's default unless an explicit override is set.
 */
class AgentMemorySettingsTest extends UnitTest {

    private static Agent agent() {
        var a = new Agent();
        a.name = "mem-agent";
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        return a;
    }

    @Test
    void autocaptureEnabledByDefault() {
        assertTrue(agent().memoryAutocaptureEnabled, "auto-capture must be on by default");
    }

    @Test
    void modelInheritsAgentDefaultWhenNoOverride() {
        var a = agent();
        assertEquals("openrouter", a.autocaptureProviderEffective());
        assertEquals("gpt-4.1", a.autocaptureModelEffective());
    }

    @Test
    void explicitOverrideWins() {
        var a = agent();
        a.memoryAutocaptureProvider = "ollama-cloud";
        a.memoryAutocaptureModel = "deepseek-v4-flash";
        assertEquals("ollama-cloud", a.autocaptureProviderEffective());
        assertEquals("deepseek-v4-flash", a.autocaptureModelEffective());
    }

    @Test
    void blankOverrideFallsBackToAgentDefault() {
        var a = agent();
        a.memoryAutocaptureProvider = "";
        a.memoryAutocaptureModel = "  ";
        assertEquals("openrouter", a.autocaptureProviderEffective());
        assertEquals("gpt-4.1", a.autocaptureModelEffective());
    }
}
