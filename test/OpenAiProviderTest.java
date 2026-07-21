import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Direct tests for {@link OpenAiProvider}: the standard OpenAI-compatible
 * implementation. The class is intentionally tiny — two template-method
 * overrides — so the test surface is correspondingly small.
 *
 * <p>The base-class {@code serializeRequest} machinery and prompt-cache
 * handling for OpenRouter are already exercised in {@code LlmClientTest};
 * this file pins the OpenAI-specific reasoning-param emission and the
 * OpenAI-shape reasoning-token extraction in isolation.
 */
class OpenAiProviderTest extends UnitTest {

    private static OpenAiProvider provider() {
        return new OpenAiProvider(new ProviderConfig(
                "openai", "https://api.openai.com/v1", "sk-test", List.of()));
    }

    private static JsonObject serialize(LlmProvider p, ChatRequest req) throws Exception {
        // Mirror the reflection trick from LlmClientTest. serializeRequest is
        // protected; we go through the declared method on the base class so the
        // concrete subclass's template overrides take effect.
        Method m = LlmProvider.class.getDeclaredMethod("serializeRequest", ChatRequest.class);
        m.setAccessible(true);
        var json = (String) m.invoke(p, req);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static ChatRequest withThinking(String thinkingMode) {
        return new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, null, thinkingMode);
    }

    // =====================
    // serializeRequest — reasoning params
    // =====================

    @Test
    void addReasoningParamsEmitsReasoningEffortField() throws Exception {
        var body = serialize(provider(), withThinking("medium"));
        assertTrue(body.has("reasoning_effort"),
                "reasoning_effort must be present when thinkingMode is set");
        assertEquals("medium", body.get("reasoning_effort").getAsString());
    }

    @Test
    void addReasoningParamsThreadsExactValueThrough() throws Exception {
        for (var level : List.of("low", "medium", "high")) {
            var body = serialize(provider(), withThinking(level));
            assertEquals(level, body.get("reasoning_effort").getAsString(),
                    "reasoning_effort value must round-trip verbatim for: " + level);
        }
    }

    @Test
    void noReasoningParamsWhenThinkingModeNull() throws Exception {
        var body = serialize(provider(), withThinking(null));
        assertFalse(body.has("reasoning_effort"),
                "no reasoning_effort key when thinkingMode is null");
    }

    @Test
    void noReasoningParamsWhenThinkingModeBlank() throws Exception {
        var body = serialize(provider(), withThinking("   "));
        assertFalse(body.has("reasoning_effort"),
                "no reasoning_effort key when thinkingMode is blank");
    }

    // =====================
    // extractReasoningTokens — OpenAI shape
    // =====================

    @Test
    void extractReasoningTokensReadsNestedDetailsField() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "completion_tokens_details": {"reasoning_tokens": 42}}
                """).getAsJsonObject());
        assertEquals(42, usage.reasoningTokens(),
                "OpenAI nests reasoning tokens under completion_tokens_details");
    }

    @Test
    void extractReasoningTokensReturnsZeroWhenDetailsAbsent() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15}
                """).getAsJsonObject());
        assertEquals(0, usage.reasoningTokens());
    }

    @Test
    void extractReasoningTokensReturnsZeroWhenDetailsNull() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "completion_tokens_details": null}
                """).getAsJsonObject());
        assertEquals(0, usage.reasoningTokens());
    }

    @Test
    void extractReasoningTokensReturnsZeroWhenDetailsHasNoReasoningField() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 5, "total_tokens": 15,
                 "completion_tokens_details": {"audio_tokens": 99}}
                """).getAsJsonObject());
        assertEquals(0, usage.reasoningTokens(),
                "non-reasoning fields under completion_tokens_details must not bleed in");
    }

    // =====================
    // parseUsage — JSON-null token fields (JCLAW-823)
    // =====================

    @Test
    void parseUsageTreatsJsonNullTokenFieldsAsZero() {
        var p = provider();
        // A provider that emits "prompt_tokens": null (etc.) used to crash the
        // whole 200-response parse via getAsInt() on JsonNull. parseUsage now
        // routes through readUsageInt, which treats null like missing → 0.
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": null, "completion_tokens": null, "total_tokens": null}
                """).getAsJsonObject());
        assertEquals(0, usage.promptTokens());
        assertEquals(0, usage.completionTokens());
        assertEquals(0, usage.totalTokens());
    }

    @Test
    void parseUsageStillReadsPresentTokenFields() {
        var p = provider();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18}
                """).getAsJsonObject());
        assertEquals(11, usage.promptTokens());
        assertEquals(7, usage.completionTokens());
        assertEquals(18, usage.totalTokens());
    }
}
