import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.*;
import llm.TogetherAiProvider;
import org.junit.jupiter.api.*;
import play.test.*;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Direct tests for {@link TogetherAiProvider}: pins the Together-shape
 * reasoning request, the explicit-disable contract, and the top-level
 * usage.reasoning_tokens read with OpenAI nested fallback.
 *
 * <p>Mirrors {@code OpenAiProviderTest} for consistency. The base-class
 * {@code serializeRequest} machinery is exercised in {@code LlmClientTest};
 * this file pins only the Together-specific overrides.
 */
public class TogetherAiProviderTest extends UnitTest {

    private static TogetherAiProvider provider() {
        return new TogetherAiProvider(new ProviderConfig(
                "together", "https://api.together.xyz/v1", "tg-test", List.of()));
    }

    private static JsonObject serialize(LlmProvider p, ChatRequest req) throws Exception {
        Method m = LlmProvider.class.getDeclaredMethod("serializeRequest", ChatRequest.class);
        m.setAccessible(true);
        var json = (String) m.invoke(p, req);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static ChatRequest withThinking(String thinkingMode) {
        return new ChatRequest("moonshotai/Kimi-K2.5", List.of(ChatMessage.user("hi")),
                null, false, null, thinkingMode);
    }

    // =====================
    // serializeRequest — reasoning params
    // =====================

    @Test
    public void addReasoningParamsEmitsReasoningEnabledTrue() throws Exception {
        var body = serialize(provider(), withThinking("medium"));
        assertTrue(body.has("reasoning"),
                "reasoning object must be present when thinkingMode is set");
        var reasoning = body.getAsJsonObject("reasoning");
        assertTrue(reasoning.has("enabled"), "reasoning.enabled must be set");
        assertTrue(reasoning.get("enabled").getAsBoolean(),
                "reasoning.enabled must be true when thinkingMode is non-blank");
    }

    @Test
    public void addReasoningParamsCollapsesEffortLevelsToOnSwitch() throws Exception {
        // Together has no per-effort gradient — any non-blank thinkingMode
        // collapses to enabled:true. The JClaw-side level string is intentionally
        // ignored; this test pins that behavior so a future change that starts
        // forwarding the level string would surface here.
        for (var level : List.of("low", "medium", "high")) {
            var body = serialize(provider(), withThinking(level));
            var reasoning = body.getAsJsonObject("reasoning");
            assertTrue(reasoning.get("enabled").getAsBoolean(),
                    "reasoning.enabled must be true regardless of level: " + level);
            assertFalse(reasoning.has("effort"),
                    "no effort key — Together uses a binary on/off switch, not a gradient");
        }
    }

    @Test
    public void disableReasoningEmitsReasoningEnabledFalseWhenThinkingNull() throws Exception {
        var body = serialize(provider(), withThinking(null));
        assertTrue(body.has("reasoning"),
                "reasoning object must be present even when thinking is off — explicit disable");
        assertFalse(body.getAsJsonObject("reasoning").get("enabled").getAsBoolean(),
                "reasoning.enabled must be false when thinkingMode is null");
    }

    @Test
    public void disableReasoningEmitsReasoningEnabledFalseWhenThinkingBlank() throws Exception {
        var body = serialize(provider(), withThinking("   "));
        assertFalse(body.getAsJsonObject("reasoning").get("enabled").getAsBoolean(),
                "blank thinkingMode must produce explicit reasoning.enabled=false");
    }

    // =====================
    // extractReasoningTokens — Together / OpenRouter shape with OpenAI fallback
    // =====================

    @Test
    public void extractReasoningTokensReadsTopLevelField() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "reasoning_tokens": 42}
                """).getAsJsonObject());
        assertEquals(42, usage.reasoningTokens(),
                "Together puts reasoning_tokens at the top level of usage");
    }

    @Test
    public void extractReasoningTokensFallsBackToOpenAiNestedField() {
        // Defensive: if Together starts proxying OpenAI-style usage shapes
        // for some models, we still surface the count rather than reporting 0.
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "completion_tokens_details": {"reasoning_tokens": 17}}
                """).getAsJsonObject());
        assertEquals(17, usage.reasoningTokens(),
                "OpenAI-nested fallback path must surface the count when top-level is absent");
    }

    @Test
    public void extractReasoningTokensReturnsZeroWhenBothFieldsAbsent() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                """).getAsJsonObject());
        assertEquals(0, usage.reasoningTokens());
    }

    @Test
    public void extractReasoningTokensReturnsZeroWhenTopLevelNull() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "reasoning_tokens": null}
                """).getAsJsonObject());
        assertEquals(0, usage.reasoningTokens());
    }
}
