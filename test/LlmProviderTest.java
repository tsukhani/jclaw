import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolDef;
import llm.OllamaProvider;
import llm.OpenAiProvider;
import llm.OpenRouterProvider;
import llm.TogetherAiProvider;
import llm.ToolCallChunkMerger;
import llm.ToolCapabilityMemo;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

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
class LlmProviderTest extends UnitTest {

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

    private static OpenRouterProvider openRouter() {
        return new OpenRouterProvider(new ProviderConfig(
                "openrouter", "https://openrouter.ai/api/v1", "sk-test", List.of()));
    }

    private static OllamaProvider ollama() {
        return new OllamaProvider(new ProviderConfig(
                "ollama-local", "http://localhost:11434/v1", "", List.of()));
    }

    private static TogetherAiProvider togetherAi() {
        return new TogetherAiProvider(new ProviderConfig(
                "together", "https://api.together.xyz/v1", "sk-test", List.of()));
    }

    // =====================
    // forConfig — factory routing
    // =====================

    @Test
    void forConfigRoutesOpenrouterNameToOpenRouterProvider() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "openrouter", "https://openrouter.ai/api/v1", "sk", List.of()));
        assertInstanceOf(OpenRouterProvider.class, p);
    }

    @Test
    void forConfigRoutesOllamaSubstringToOllamaProvider() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "ollama-cloud", "https://ollama.com/v1", "k", List.of()));
        assertInstanceOf(OllamaProvider.class, p);
    }

    @Test
    void forConfigRoutesOllamaLocalToOllamaProvider() {
        // JCLAW-178 AC #2: ollama-local must route through OllamaProvider via
        // the substring match on "ollama" — no new provider class.
        var p = LlmProvider.forConfig(new ProviderConfig(
                "ollama-local", "http://localhost:11434/v1", "ollama-local", List.of()));
        assertInstanceOf(OllamaProvider.class, p);
    }

    @Test
    void forConfigDefaultsToOpenAiForUnknownNames() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "lambda-labs", "https://api.lambdalabs.com/v1", "k", List.of()));
        assertInstanceOf(OpenAiProvider.class, p,
                "unknown provider names must default to OpenAiProvider");
    }

    @Test
    void forConfigRoutesOpenaiNameToOpenAiProvider() {
        // JCLAW-160 AC #1: openai is now an explicit factory entry rather
        // than relying on the unknown-name fallback. Pin so a future map
        // reshuffle can't accidentally route the canonical name elsewhere.
        var p = LlmProvider.forConfig(new ProviderConfig(
                "openai", "https://api.openai.com/v1", "sk-test", List.of()));
        assertInstanceOf(OpenAiProvider.class, p);
    }

    @Test
    void forConfigRoutesLmStudioToOpenAiProviderViaFallback() {
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
    void forConfigMatchesNameCaseInsensitively() {
        var p = LlmProvider.forConfig(new ProviderConfig(
                "OpenRouter-Mirror", "https://example.com", "k", List.of()));
        assertInstanceOf(OpenRouterProvider.class, p,
                "factory must lower-case the provider name before matching");
    }

    // =====================
    // parseUsage — instance method honors subclass overrides
    // =====================

    @Test
    void parseUsageInstanceMethodUsesOpenAiNestedReasoning() {
        var p = openAi();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150,
                 "completion_tokens_details": {"reasoning_tokens": 25}}
                """).getAsJsonObject());
        assertEquals(25, usage.reasoningTokens(),
                "OpenAiProvider.parseUsage must read nested completion_tokens_details");
    }

    @Test
    void parseUsageInstanceMethodUsesOllamaTopLevelReasoning() {
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
    void parseUsageInstancePicksUpCachedTokensFromBaseClass() {
        var p = openAi();
        var usage = p.parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150,
                 "prompt_tokens_details": {"cached_tokens": 80}}
                """).getAsJsonObject());
        assertEquals(80, usage.cachedTokens());
    }

    // =====================
    // JCLAW-901 — cache writes and provider-reported cost
    // =====================

    @Test
    void cacheWritesAreReadFromOpenRoutersActualFieldName() {
        // The payload below is what OpenRouter returned on a live probe (2026-08-02,
        // anthropic/claude-haiku-4.5, 4432-token prompt with cache_control) on the COLD
        // call. Only cache_creation_input_tokens and cache_creation_tokens were read
        // before, so every OpenRouter cache write scored 0 — which was then recorded as
        // "the provider doesn't report writes" when in fact the key differs.
        var usage = openRouter().parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 4432, "completion_tokens": 4, "total_tokens": 4436,
                 "cost": 0.00555725,
                 "prompt_tokens_details": {"cached_tokens": 0, "cache_write_tokens": 4421}}
                """).getAsJsonObject());
        assertEquals(4421, usage.cacheCreationTokens(), "cache_write_tokens must be read");
        assertEquals(0, usage.cachedTokens());
    }

    @Test
    void theWarmCallReportsReadsNotWrites() {
        // Same probe, the WARM call — the pair is what proves the two keys are distinct
        // rather than aliases, and that an 11.7x cost drop rides on them.
        var usage = openRouter().parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 4432, "completion_tokens": 4, "total_tokens": 4436,
                 "cost": 0.0004731,
                 "prompt_tokens_details": {"cached_tokens": 4421, "cache_write_tokens": 0}}
                """).getAsJsonObject());
        assertEquals(4421, usage.cachedTokens());
        assertEquals(0, usage.cacheCreationTokens());
        assertEquals(0.0004731, usage.costUsd(), 1e-9);
    }

    @Test
    void anthropicsOwnSpellingStillWinsOverTheOpenRouterOne() {
        // Precedence matters: a route that emits both must not double-count or pick the
        // wrong one. Native Anthropic's top-level key is checked first.
        var usage = openRouter().parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11,
                 "cache_creation_input_tokens": 700,
                 "prompt_tokens_details": {"cache_write_tokens": 42}}
                """).getAsJsonObject());
        assertEquals(700, usage.cacheCreationTokens());
    }

    @Test
    void costIsZeroWhenTheProviderReportsNone() {
        // Absent must read as 0 here and be OMITTED downstream, so "free" and "not
        // reported" stay distinguishable in the persisted usage JSON.
        var usage = openAi().parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11}
                """).getAsJsonObject());
        assertEquals(0d, usage.costUsd(), 1e-9);
    }

    @Test
    void aMalformedCostDoesNotBreakUsageParsing() {
        var usage = openRouter().parseUsage(JsonParser.parseString("""
                {"prompt_tokens": 10, "completion_tokens": 1, "total_tokens": 11,
                 "cost": "not-a-number"}
                """).getAsJsonObject());
        assertEquals(0d, usage.costUsd(), 1e-9);
        assertEquals(10, usage.promptTokens(), "a bad cost must not lose the token counts");
    }

    // =====================
    // serializeRequest — shape contract
    // =====================

    @Test
    void serializeRequestEmitsModelAndMessages() throws Exception {
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
    void serializeRequestEmitsToolsArrayWhenPresent() throws Exception {
        var tool = ToolDef.of("ping", "ping",
                Map.of("type", "object", "properties", Map.of()));
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                List.of(tool), false, null, null);
        var body = serialize(openAi(), req);
        assertTrue(body.has("tools"));
        assertEquals(1, body.getAsJsonArray("tools").size());
    }

    @Test
    void serializeRequestOmitsToolsArrayWhenNullOrEmpty() throws Exception {
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
    void serializeRequestEmitsStreamAndStreamOptionsWhenStreaming() throws Exception {
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
    void serializeRequestOmitsStreamFieldsWhenNotStreaming() throws Exception {
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        assertFalse(body.has("stream"), "non-streaming requests must omit stream key");
        assertFalse(body.has("stream_options"),
                "non-streaming requests must omit stream_options");
    }

    @Test
    void serializeRequestEmitsMaxTokensWhenSet() throws Exception {
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, 2048, null);
        var body = serialize(openAi(), req);
        assertEquals(2048, body.get("max_tokens").getAsInt());
    }

    @Test
    void serializeRequestOmitsMaxTokensWhenNull() throws Exception {
        var req = new ChatRequest("gpt-4o", List.of(ChatMessage.user("hi")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        assertFalse(body.has("max_tokens"));
    }

    @Test
    void serializeMessagesPreservesToolCallId() throws Exception {
        var req = new ChatRequest("gpt-4o",
                List.of(ChatMessage.toolResult("call-99", "web_fetch", "result text")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        var msg = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("tool", msg.get("role").getAsString());
        assertEquals("call-99", msg.get("tool_call_id").getAsString());
        assertEquals("web_fetch", msg.get("name").getAsString());
        assertEquals("result text", msg.get("content").getAsString());
    }

    @Test
    void serializeMessagesOmitsNameWhenToolNameUnknown() throws Exception {
        // Historical messages predating JCLAW-193 won't carry the name in DB.
        // We must not emit "name": null or empty — Ollama Cloud's Gemini bridge
        // rejects empty names with HTTP 400.
        var req = new ChatRequest("gpt-4o",
                List.of(ChatMessage.toolResult("call-99", null, "result text")),
                null, false, null, null);
        var body = serialize(openAi(), req);
        var msg = body.getAsJsonArray("messages").get(0).getAsJsonObject();
        assertEquals("tool", msg.get("role").getAsString());
        assertEquals("call-99", msg.get("tool_call_id").getAsString());
        assertFalse(msg.has("name"));
    }

    // =====================
    // serializeRequest — cache-boundary marker never reaches the wire
    // =====================

    @Test
    void everyProviderStripsTheCacheBoundaryMarker() throws Exception {
        // The marker is JClaw's own protocol, meaningless to a model. Only the
        // OpenRouter Anthropic route consumes it (by splitting on it); every other
        // route used to ship the literal HTML comment because the strip lived in
        // OpenRouterProvider. The base class now scrubs it for all four.
        var marker = agents.SystemPromptAssembler.CACHE_BOUNDARY_MARKER;
        var systemText = "stable prefix\n" + marker + "\nvariable suffix";
        List<LlmProvider> providers = List.of(openAi(), openRouter(), ollama(), togetherAi());

        for (var p : providers) {
            var req = new ChatRequest("gpt-4o",
                    List.of(ChatMessage.system(systemText), ChatMessage.user("hi")),
                    null, false, null, null);
            var json = serialize(p, req).toString();
            assertFalse(json.contains(marker),
                    "marker must not reach the wire for " + p.getClass().getSimpleName() + ": " + json);
            assertTrue(json.contains("stable prefix") && json.contains("variable suffix"),
                    "stripping must keep the surrounding text for " + p.getClass().getSimpleName());
        }
    }

    @Test
    void aForgedMarkerInAUserMessageNeverReachesTheWire() throws Exception {
        // JCLAW-976: the strip covered only the FIRST SYSTEM message, so a user turn — or a
        // replayed history row — carrying the marker shipped the literal HTML comment, which
        // is exactly what this scrub exists to prevent, and handed the model a JClaw-looking
        // sentinel to quote back when asked about its instructions.
        var marker = agents.SystemPromptAssembler.CACHE_BOUNDARY_MARKER;
        List<LlmProvider> providers = List.of(openAi(), openRouter(), ollama(), togetherAi());

        for (var p : providers) {
            var req = new ChatRequest("gpt-4o",
                    List.of(ChatMessage.system("stable prefix"),
                            ChatMessage.user("here you go " + marker + " and the rest")),
                    null, false, null, null);
            var json = serialize(p, req).toString();

            assertFalse(json.contains(marker),
                    "a forged marker in a user message must not reach the wire for "
                            + p.getClass().getSimpleName() + ": " + json);
            assertTrue(json.contains("here you go") && json.contains("and the rest"),
                    "the user's own words must survive the scrub for " + p.getClass().getSimpleName());
        }
    }

    @Test
    void anthropicRouteStillConsumesTheMarkerBySplitting() throws Exception {
        // The base-class strip must not pre-empt the split: on the Anthropic route the
        // marker still has to become a block boundary carrying cache_control, not just
        // vanish from a single block.
        var marker = agents.SystemPromptAssembler.CACHE_BOUNDARY_MARKER;
        var req = new ChatRequest("anthropic/claude-3-7-sonnet",
                List.of(ChatMessage.system("stable prefix\n" + marker + "\nvariable suffix"),
                        ChatMessage.user("hi")),
                null, false, null, null);

        var blocks = serialize(openRouter(), req)
                .getAsJsonArray("messages").get(0).getAsJsonObject()
                .getAsJsonArray("content");
        assertEquals(2, blocks.size(), "marker must still split the system message in two");
        assertTrue(blocks.get(0).getAsJsonObject().has("cache_control"),
                "the stable prefix block keeps its breakpoint");
    }

    // =====================
    // ToolCallBuilder
    // =====================

    @Test
    void toolCallBuilderProducesToolCallWithAccumulatedArgs() {
        var b = new ToolCallChunkMerger.ToolCallBuilder();
        b.id("call-1")
                .functionName("web_fetch")
                .appendArguments("{\"url\":")
                .appendArguments("\"https://x\"}");
        var tc = b.build();
        assertEquals("call-1", tc.id());
        assertEquals("function", tc.type(),
                "default type stays 'function' when not overridden");
        assertEquals("web_fetch", tc.function().name());
        assertEquals("{\"url\":\"https://x\"}", tc.function().arguments());
    }

    @Test
    void toolCallBuilderRespectsExplicitType() {
        var b = new ToolCallChunkMerger.ToolCallBuilder();
        b.id("x")
                .type("custom-type")
                .functionName("fn");
        var tc = b.build();
        assertEquals("custom-type", tc.type());
    }

    // --- JCLAW-929: which 429s are permanent and must not be retried ---

    private static boolean permanentQuota(String body) throws Exception {
        var m = LlmProvider.class.getDeclaredMethod("isPermanentQuotaError", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, body);
    }

    @Test
    void exhaustedCreditBalanceIsPermanent() throws Exception {
        // The live body observed from OpenAI on 2026-08-03, which the retry loop
        // spent four attempts per call on before this fix.
        assertTrue(permanentQuota("""
                {"error":{"message":"You have no credits remaining. Add credits to continue using the API.",
                "type":"insufficient_quota","param":null,"code":"credit_balance_exhausted"}}"""));
    }

    @Test
    void insufficientQuotaTypeAloneIsPermanent() throws Exception {
        assertTrue(permanentQuota(
                "{\"error\":{\"message\":\"You exceeded your current quota\",\"type\":\"insufficient_quota\"}}"));
    }

    @Test
    void ordinaryRateLimitStaysRetryable() throws Exception {
        // Must stay retryable: classifying a transient limit as permanent turns a
        // recoverable call into a hard failure.
        assertFalse(permanentQuota("""
                {"error":{"message":"Rate limit reached for gpt-4o in organization org-x on requests per min.",
                "type":"requests","code":"rate_limit_exceeded"}}"""));
    }

    @Test
    void quotaWordInProseDoesNotMakeARateLimitPermanent() throws Exception {
        // The word "quota" appears in rate-limit copy; only the code position counts.
        assertFalse(permanentQuota(
                "{\"error\":{\"message\":\"You have exceeded your quota for this minute\",\"code\":\"rate_limit_exceeded\"}}"));
    }

    @Test
    void unparseableOrEmptyBodyStaysRetryable() throws Exception {
        assertFalse(permanentQuota(null));
        assertFalse(permanentQuota(""));
        assertFalse(permanentQuota("<html>502 Bad Gateway</html>"));
        assertFalse(permanentQuota("{\"error\":\"just a string\"}"));
    }

    // =====================
    // JCLAW-1074 — tools are withheld from chat-only models
    // =====================

    private static final List<ToolDef> ONE_TOOL = List.of(
            ToolDef.of("do_thing", "does a thing", Map.of()));

    /** An Ollama provider whose catalog declares one model's tool capability. */
    private static OllamaProvider ollamaWith(String modelId, Boolean supportsTools) {
        var info = new ModelInfo(modelId, modelId, 8192, 0, false, false, false, false,
                supportsTools, -1, -1, -1, -1, null, false);
        return new OllamaProvider(new ProviderConfig(
                "ollama-local", "http://localhost:11434/v1", "", List.of(info)));
    }

    private static ChatRequest requestWithTools(String modelId) {
        return new ChatRequest(modelId, List.of(ChatMessage.user("Hi")), ONE_TOOL, false, null, null);
    }

    @Test
    void toolsAreOmittedForAModelDeclaredToolIncapable() throws Exception {
        // The 400 this closes: "dolphin3:8b does not support tools".
        var json = serialize(ollamaWith("dolphin3:8b", false), requestWithTools("dolphin3:8b"));
        assertFalse(json.has("tools"),
                "tools must be absent for a chat-only model: " + json);
        // The turn still happens — withholding tools, not the request.
        assertTrue(json.has("messages"), "messages still sent: " + json);
    }

    @Test
    void toolsAreSentForAModelDeclaredToolCapable() throws Exception {
        var json = serialize(ollamaWith("qwen3:8b", true), requestWithTools("qwen3:8b"));
        assertTrue(json.has("tools"), "tools sent for a tool-capable model: " + json);
    }

    @Test
    void toolsAreSentWhenCapabilityIsUnrecorded() throws Exception {
        // null is what every model discovered before JCLAW-1074 deserializes to.
        // Defaulting these off would silently disarm every agent on restart.
        var json = serialize(ollamaWith("legacy-model", null), requestWithTools("legacy-model"));
        assertTrue(json.has("tools"), "unknown capability still sends tools: " + json);
    }

    @Test
    void toolsAreSentWhenTheModelIsNotInTheCatalogAtAll() throws Exception {
        // Empty catalog: a provider that has never been discovered must not have
        // its agents disarmed as a side effect.
        var json = serialize(ollama(), requestWithTools("never-discovered"));
        assertTrue(json.has("tools"), "unlisted model still sends tools: " + json);
    }

    // =====================
    // JCLAW-1076 — learning tool-incapability from the provider's own error
    // =====================

    @Test
    void recognisesTheProviderWordingsThatMeanNoToolSupport() {
        // The exact message Ollama returned for dolphin3:8b.
        assertTrue(ToolCapabilityMemo.isToolsUnsupported(
                "registry.ollama.ai/library/dolphin3:8b does not support tools"));
        assertTrue(ToolCapabilityMemo.isToolsUnsupported("Tools are not supported by this model"));
        assertTrue(ToolCapabilityMemo.isToolsUnsupported("Unsupported parameter: 'tools'"));
        assertTrue(ToolCapabilityMemo.isToolsUnsupported(
                new RuntimeException("wrapped", new RuntimeException("does not support tools"))));
    }

    @Test
    void leavesUnrelatedFailuresAlone() {
        // Retrying these without tools would silently drop the caller's tools
        // and hide a real bug, so the match has to stay narrow.
        assertFalse(ToolCapabilityMemo.isToolsUnsupported("context length exceeded"));
        assertFalse(ToolCapabilityMemo.isToolsUnsupported("invalid api key"));
        assertFalse(ToolCapabilityMemo.isToolsUnsupported("tool call arguments were malformed"));
        assertFalse(ToolCapabilityMemo.isToolsUnsupported("no tools were provided for this request"));
        assertFalse(ToolCapabilityMemo.isToolsUnsupported((String) null));
        assertFalse(ToolCapabilityMemo.isToolsUnsupported(new RuntimeException((String) null)));
    }

    @Test
    void aLearnedModelIsGatedUpFrontSoTheWastedCallHappensOnce() throws Exception {
        ToolCapabilityMemo.clearForTest();
        var provider = ollamaWith("chatonly:8b", null); // unknown — tools would be sent
        assertTrue(serialize(provider, requestWithTools("chatonly:8b")).has("tools"),
                "unknown capability sends tools on the first call");

        ToolCapabilityMemo.record("ollama-local", "chatonly:8b");

        assertFalse(serialize(provider, requestWithTools("chatonly:8b")).has("tools"),
                "after learning, tools are withheld without another failure");
        // Scoped to the model that actually failed.
        assertTrue(serialize(provider, requestWithTools("other:8b")).has("tools"),
                "a sibling model is unaffected");
        ToolCapabilityMemo.clearForTest();
    }

    @Test
    void modelInfoResolvesUnknownToolSupportAsSupported() {
        assertTrue(new ModelInfo("m", "m", 1, 1, false).toolCallingSupported(),
                "convenience constructor defaults to tool-capable");
        assertFalse(new ModelInfo("m", "m", 1, 1, false, false, false, false,
                false, -1, -1, -1, -1, null, false).toolCallingSupported());
    }

    // =====================
    // alwaysThinks models: never "off", never the vendor's expensive default
    // =====================

    /** An Ollama catalog declaring one thinking-capable model's architecture lock. */
    private static OllamaProvider ollamaThinking(String modelId, boolean alwaysThinks) {
        return ollamaThinking(modelId, alwaysThinks, null);
    }

    private static OllamaProvider ollamaThinking(String modelId, boolean alwaysThinks,
                                                 List<String> levels) {
        var info = new ModelInfo(modelId, modelId, 8192, 0, true, false, false, false,
                true, -1, -1, -1, -1, levels, alwaysThinks);
        return new OllamaProvider(new ProviderConfig(
                "ollama-cloud", "https://ollama.com/v1", "k", List.of(info)));
    }

    private static ChatRequest requestWithThinking(String modelId, String thinkingMode) {
        return new ChatRequest(modelId, List.of(ChatMessage.user("hi")),
                null, false, null, thinkingMode);
    }

    @Test
    void anAlwaysThinksModelGetsItsLowestRungRatherThanAnOffSignal() throws Exception {
        // "none" collapses the reasoning channel into content on Ollama, and omitting
        // the field inherits the vendor default — max on this ladder. The lowest rung
        // is the only option that both keeps the channels split and stays cheap.
        var json = serialize(ollamaThinking("glm-5.3-flash", true, List.of("low", "high", "max")),
                requestWithThinking("glm-5.3-flash", null));
        assertEquals("low", json.get("reasoning_effort").getAsString(),
                "an always-thinking model must be pinned to its cheapest rung: " + json);
        assertFalse(json.has("think"),
                "the native think:false fallback must not ride along: " + json);
    }

    @Test
    void theRungComesFromTheModelsOwnLadderNotAHardcodedLow() throws Exception {
        // Ladders differ per model — OpenRouter-routed effort models advertise
        // "minimal" below "low". Sending a level the model never advertised is the
        // failure this guards.
        var json = serialize(ollamaThinking("effort-model", true, List.of("minimal", "xhigh")),
                requestWithThinking("effort-model", null));
        assertEquals("minimal", json.get("reasoning_effort").getAsString());
    }

    @Test
    void anExplicitEffortLevelStillReachesAnAlwaysThinksModel() throws Exception {
        // The fallback covers an unset preference only — an operator-chosen level is
        // still the model's to honor, including the top rung.
        var json = serialize(ollamaThinking("glm-5.3-flash", true, List.of("low", "high", "max")),
                requestWithThinking("glm-5.3-flash", "max"));
        assertEquals("max", json.get("reasoning_effort").getAsString());
    }

    @Test
    void reasoningIsStillDisabledForAModelThatCanActuallyStop() throws Exception {
        // The disable path is load-bearing for every ordinary thinking model —
        // narrowing it to alwaysThinks must not turn reasoning on for the rest.
        var json = serialize(ollamaThinking("kimi-k2.5", false),
                requestWithThinking("kimi-k2.5", null));
        assertEquals("none", json.get("reasoning_effort").getAsString());
        assertFalse(json.get("think").getAsBoolean());
    }

    @Test
    void reasoningIsStillDisabledForAModelMissingFromTheCatalog() throws Exception {
        // No metadata means no lock: an undiscovered model keeps the normal path.
        var json = serialize(ollama(), requestWithThinking("never-discovered", null));
        assertEquals("none", json.get("reasoning_effort").getAsString());
    }

    @Test
    void anAlwaysThinksModelWithNoDeclaredLadderFallsBackToTheDefaultRungs() throws Exception {
        // thinkingLevels is optional metadata; effectiveThinkingLevels() supplies
        // low/medium/high, so the pin still resolves rather than silently reverting
        // to the disable path.
        var json = serialize(ollamaThinking("mystery-reasoner", true),
                requestWithThinking("mystery-reasoner", null));
        assertEquals("low", json.get("reasoning_effort").getAsString());
    }
}
