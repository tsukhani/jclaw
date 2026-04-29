import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.*;
import llm.OllamaProvider;
import llm.OpenAiProvider;
import llm.OpenRouterProvider;
import org.junit.jupiter.api.*;
import play.test.*;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Tests for the abstract {@link LlmProvider} base — the factory routing,
 * the instance-vs-static usage-parsing distinction, the request-serialization
 * shape, and the {@code ToolCallBuilder} accumulator.
 *
 * <p>Behavior already exercised by {@code LlmClientTest} (the static
 * {@code parseUsageBlock}, OpenRouter cache breakpoints, the mergeToolCallChunks
 * variants, the StreamAccumulator) is not duplicated here.
 */
public class LlmProviderTest extends UnitTest {

    private static JsonObject serialize(LlmProvider p, ChatRequest req) throws Exception {
        Method m = LlmProvider.class.getDeclaredMethod("serializeRequest", ChatRequest.class);
        m.setAccessible(true);
        var json = (String) m.invoke(p, req);
        return JsonParser.parseString(json).getAsJsonObject();
    }

    private static OpenAiProvider openAi() {
        return new OpenAiProvider(new ProviderConfig(
                "openai", "https://api.openai.com/v1", "sk-test", List.of()));
    }

    // =====================
    // forConfig — factory routing
    // =====================

    @Test
    public void forConfigRoutesOpenrouterNameToOpenRouterProvider() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "openrouter", "https://openrouter.ai/api/v1", "sk", List.of()));
        assertInstanceOf(OpenRouterProvider.class, p);
    }

    @Test
    public void forConfigRoutesOllamaSubstringToOllamaProvider() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "ollama-cloud", "https://ollama.com/v1", "k", List.of()));
        assertInstanceOf(OllamaProvider.class, p);
    }

    @Test
    public void forConfigRoutesOllamaLocalToOllamaProvider() {
        // JCLAW-178 AC #2: ollama-local must route through OllamaProvider via
        // the substring match on "ollama" — no new provider class.
        var p = LlmProvider.forConfig(new ProviderConfig(
                "ollama-local", "http://localhost:11434/v1", "ollama-local", List.of()));
        assertInstanceOf(OllamaProvider.class, p);
    }

    @Test
    public void forConfigDefaultsToOpenAiForUnknownNames() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "lambda-labs", "https://api.lambdalabs.com/v1", "k", List.of()));
        assertInstanceOf(OpenAiProvider.class, p,
                "unknown provider names must default to OpenAiProvider");
    }

    @Test
    public void forConfigRoutesLmStudioToOpenAiProviderViaFallback() {
        // JCLAW-182 AC #2: lm-studio doesn't match either Ollama or OpenRouter
        // substrings, so the factory falls through to OpenAiProvider — perfect
        // because LM Studio speaks OpenAI-compatible /v1/chat/completions
        // natively. Pin the fallback so a future name-matching tweak doesn't
        // accidentally route lm-studio elsewhere.
        var p = LlmProvider.forConfig(new ProviderConfig(
                "lm-studio", "http://localhost:1234/v1", "lm-studio", List.of()));
        assertInstanceOf(OpenAiProvider.class, p);
    }

    @Test
    public void forConfigMatchesNameCaseInsensitively() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "OpenRouter-Mirror", "https://example.com", "k", List.of()));
        assertInstanceOf(OpenRouterProvider.class, p,
                "factory must lower-case the provider name before matching");
    }

    // =====================
    // parseUsage — instance method honors subclass overrides
    // =====================

    @Test
    public void parseUsageInstanceMethodUsesOpenAiNestedReasoning() {
        var p = openAi();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150,
                 "completion_tokens_details": {"reasoning_tokens": 25}}
                """).getAsJsonObject());
        assertEquals(25, usage.reasoningTokens(),
                "OpenAiProvider.parseUsage must read nested completion_tokens_details");
    }

    @Test
    public void parseUsageInstanceMethodUsesOllamaTopLevelReasoning() {
        var ollama = new OllamaProvider(new ProviderConfig(
                "ollama-cloud", "https://ollama.com/v1", "k", List.of()));
        var usage = ollama.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150,
                 "reasoning_tokens": 17}
                """).getAsJsonObject());
        assertEquals(17, usage.reasoningTokens(),
                "OllamaProvider.parseUsage must read top-level reasoning_tokens");
    }

    @Test
    public void parseUsageInstancePicksUpCachedTokensFromBaseClass() {
        var p = openAi();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150,
                 "prompt_tokens_details": {"cached_tokens": 80}}
                """).getAsJsonObject());
        assertEquals(80, usage.cachedTokens());
    }

    // =====================
    // serializeRequest — shape contract
    // =====================

    @Test
    public void serializeRequestEmitsModelAndMessages() throws Exception {
        var req = new ChatRequest("gpt-4o",
                List.of(ChatMessage.system("sys"), ChatMessage.user("hi")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        assertEquals("gpt-4o", body.get("model").getAsString());
        var messages = body.getAsJsonArray("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).getAsJsonObject().get("role").getAsString());
        assertEquals("user", messages.get(1).getAsJsonObject().get("role").getAsString());
    }

    @Test
    public void serializeRequestEmitsToolsArrayWhenPresent() throws Exception {
        var tool = ToolDef.of("ping", "ping",
                Map.of("type", "object", "properties", Map.of()));
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                List.of(tool), false, null, null);
        var body = serialize(openAi(), req);
        assertTrue(body.has("tools"));
        assertEquals(1, body.getAsJsonArray("tools").size());
    }

    @Test
    public void serializeRequestOmitsToolsArrayWhenNullOrEmpty() throws Exception {
        var nullTools = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, null, null);
        assertFalse(serialize(openAi(), nullTools).has("tools"),
                "null tools must not produce a tools key");

        var emptyTools = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                List.of(), false, null, null);
        assertFalse(serialize(openAi(), emptyTools).has("tools"),
                "empty tools list must not produce a tools key");
    }

    @Test
    public void serializeRequestEmitsStreamAndStreamOptionsWhenStreaming() throws Exception {
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, true, null, null);
        var body = serialize(openAi(), req);
        assertTrue(body.has("stream"));
        assertTrue(body.get("stream").getAsBoolean());
        assertTrue(body.has("stream_options"),
                "streaming requests must include include_usage stream option");
        var opts = body.getAsJsonObject("stream_options");
        assertTrue(opts.has("include_usage"));
        assertTrue(opts.get("include_usage").getAsBoolean());
    }

    @Test
    public void serializeRequestOmitsStreamFieldsWhenNotStreaming() throws Exception {
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        assertFalse(body.has("stream"), "non-streaming requests must omit stream key");
        assertFalse(body.has("stream_options"),
                "non-streaming requests must omit stream_options");
    }

    @Test
    public void serializeRequestEmitsMaxTokensWhenSet() throws Exception {
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, 2048, null);
        var body = serialize(openAi(), req);
        assertEquals(2048, body.get("max_tokens").getAsInt());
    }

    @Test
    public void serializeRequestOmitsMaxTokensWhenNull() throws Exception {
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        assertFalse(body.has("max_tokens"));
    }

    @Test
    public void serializeMessagesPreservesToolCallId() throws Exception {
        var req = new ChatRequest("gpt-4o",
                List.of(ChatMessage.toolResult("call-99", "result text")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        var msg = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("tool", msg.get("role").getAsString());
        assertEquals("call-99", msg.get("tool_call_id").getAsString());
        assertEquals("result text", msg.get("content").getAsString());
    }

    // =====================
    // ToolCallBuilder
    // =====================

    @Test
    public void toolCallBuilderProducesToolCallWithAccumulatedArgs() {
        var b = new LlmProvider.ToolCallBuilder();
        b.id = "call-1";
        b.functionName = "web_fetch";
        b.arguments.append("{\"url\":");
        b.arguments.append("\"https://x\"}");
        var tc = b.build();
        assertEquals("call-1", tc.id());
        assertEquals("function", tc.type(),
                "default type stays 'function' when not overridden");
        assertEquals("web_fetch", tc.function().name());
        assertEquals("{\"url\":\"https://x\"}", tc.function().arguments());
    }

    @Test
    public void toolCallBuilderRespectsExplicitType() {
        var b = new LlmProvider.ToolCallBuilder();
        b.id = "x";
        b.type = "custom-type";
        b.functionName = "fn";
        var tc = b.build();
        assertEquals("custom-type", tc.type());
    }
}
