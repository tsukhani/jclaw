import org.junit.jupiter.api.*;
import play.test.*;
import llm.LlmTypes.*;
import llm.LlmProvider;
import llm.ProviderRegistry;
import services.ConfigService;
import com.google.gson.JsonParser;

import java.util.List;
import java.util.Map;

public class LlmClientTest extends UnitTest {

    @Test
    public void chatMessageFactoryMethods() {
        var sys = ChatMessage.system("You are helpful");
        assertEquals("system", sys.role());
        assertEquals("You are helpful", sys.content());

        var user = ChatMessage.user("Hello");
        assertEquals("user", user.role());

        var asst = ChatMessage.assistant("Hi there");
        assertEquals("assistant", asst.role());

        var tool = ChatMessage.toolResult("call-1", "result data");
        assertEquals("tool", tool.role());
        assertEquals("call-1", tool.toolCallId());
    }

    @Test
    public void toolDefCreation() {
        var tool = ToolDef.of("web_fetch", "Fetch a URL",
                Map.of("type", "object",
                        "properties", Map.of(
                                "url", Map.of("type", "string", "description", "The URL to fetch")
                        ),
                        "required", List.of("url")));

        assertEquals("function", tool.type());
        assertEquals("web_fetch", tool.function().name());
        assertEquals("Fetch a URL", tool.function().description());
    }

    @Test
    public void providerConfigRecord() {
        var config = new ProviderConfig(
                "openrouter",
                "https://openrouter.ai/api/v1",
                "sk-test-key",
                List.of(
                        new ModelInfo("openai/gpt-4.1", "GPT-4.1", 1047576, 32768, false),
                        new ModelInfo("anthropic/claude-sonnet-4-6", "Claude Sonnet 4.6", 200000, 8192, false)
                ));

        assertEquals("openrouter", config.name());
        assertEquals(2, config.models().size());
        assertEquals("GPT-4.1", config.models().getFirst().name());
    }

    @Test
    public void assistantMessageWithToolCalls() {
        var toolCalls = List.of(
                new ToolCall("call-1", "function", new FunctionCall("web_fetch", "{\"url\":\"https://example.com\"}"))
        );
        var msg = ChatMessage.assistant(null, toolCalls);
        assertEquals("assistant", msg.role());
        assertNull(msg.content());
        assertEquals(1, msg.toolCalls().size());
        assertEquals("web_fetch", msg.toolCalls().getFirst().function().name());
    }

    // --- Usage parser: verifies every field cache-related field flows from provider JSON ---

    @Test
    public void parseUsageOpenAiShape() {
        // Plain OpenAI response: no caching fields at all.
        var usageObj = JsonParser.parseString("""
                {"prompt_tokens": 100, "completion_tokens": 50, "total_tokens": 150}
                """).getAsJsonObject();
        var usage = LlmProvider.parseUsageBlock(usageObj);
        assertEquals(100, usage.promptTokens());
        assertEquals(50, usage.completionTokens());
        assertEquals(150, usage.totalTokens());
        assertEquals(0, usage.cachedTokens());
        assertEquals(0, usage.cacheCreationTokens());
        assertEquals(0, usage.reasoningTokens());
    }

    @Test
    public void parseUsageOpenAiCacheReadHit() {
        // OpenAI shape with a 95% cache hit: cached_tokens nested under prompt_tokens_details.
        var usageObj = JsonParser.parseString("""
                {"prompt_tokens": 4891, "completion_tokens": 112, "total_tokens": 5003,
                 "prompt_tokens_details": {"cached_tokens": 4670},
                 "completion_tokens_details": {"reasoning_tokens": 76}}
                """).getAsJsonObject();
        var usage = LlmProvider.parseUsageBlock(usageObj);
        assertEquals(4891, usage.promptTokens());
        assertEquals(112, usage.completionTokens());
        assertEquals(4670, usage.cachedTokens(), "cached reads must flow from prompt_tokens_details");
        assertEquals(0, usage.cacheCreationTokens(), "OpenAI routes never populate cache_creation");
        assertEquals(76, usage.reasoningTokens(), "nested reasoning tokens must flow");
    }

    @Test
    public void parseUsageAnthropicCacheWriteSeed() {
        // First turn of a new conversation on an Anthropic route: cache_creation_input_tokens
        // at the top level of usage; no cached reads yet (cache is being seeded).
        var usageObj = JsonParser.parseString("""
                {"prompt_tokens": 5000, "completion_tokens": 200, "total_tokens": 5200,
                 "cache_creation_input_tokens": 4800,
                 "prompt_tokens_details": {"cached_tokens": 0}}
                """).getAsJsonObject();
        var usage = LlmProvider.parseUsageBlock(usageObj);
        assertEquals(5000, usage.promptTokens());
        assertEquals(0, usage.cachedTokens(), "no cache reads on the seeding turn");
        assertEquals(4800, usage.cacheCreationTokens(), "cache writes must flow from top-level field");
    }

    @Test
    public void parseUsageAnthropicCacheMixedReadAndWrite() {
        // A later turn where some of the prefix is already cached (reads) AND a new
        // breakpoint was added (writes). Both subsets are disjoint and both count.
        var usageObj = JsonParser.parseString("""
                {"prompt_tokens": 6000, "completion_tokens": 150, "total_tokens": 6150,
                 "cache_creation_input_tokens": 500,
                 "prompt_tokens_details": {"cached_tokens": 5000}}
                """).getAsJsonObject();
        var usage = LlmProvider.parseUsageBlock(usageObj);
        assertEquals(6000, usage.promptTokens());
        assertEquals(5000, usage.cachedTokens());
        assertEquals(500, usage.cacheCreationTokens());
        // Implicit uncached input = 6000 - 5000 - 500 = 500; the consumer of Usage
        // computes this (not the parser), so we just sanity-check the invariant.
        assertTrue(usage.cachedTokens() + usage.cacheCreationTokens() <= usage.promptTokens());
    }

    @Test
    public void parseUsageHandlesMissingFields() {
        // Robustness: empty usage object, shouldn't NPE.
        var usage = LlmProvider.parseUsageBlock(JsonParser.parseString("{}").getAsJsonObject());
        assertEquals(0, usage.promptTokens());
        assertEquals(0, usage.completionTokens());
        assertEquals(0, usage.totalTokens());
        assertEquals(0, usage.cachedTokens());
        assertEquals(0, usage.cacheCreationTokens());
    }

    @Test
    public void streamAccumulatorStartsEmpty() {
        var acc = new LlmProvider.StreamAccumulator();
        assertFalse(acc.complete);
        assertEquals("", acc.content);
        assertTrue(acc.toolCalls.isEmpty());
        assertNull(acc.error);
    }

    @Test
    public void streamAccumulatorReasoningDurationIsZeroWhenNoReasoning() {
        var acc = new LlmProvider.StreamAccumulator();
        assertEquals(0L, acc.reasoningDurationMs());
        assertEquals(0L, acc.reasoningStartNanos);
        assertEquals(0L, acc.reasoningEndNanos);
    }

    @Test
    public void streamAccumulatorCapturesReasoningSpan() throws Exception {
        // Simulates JCLAW-70 timing capture: first reasoning chunk stamps the
        // start, the last chunk extends the end. Duration must be positive
        // and within a sane upper bound (the ~5ms sleep plus scheduler noise).
        var acc = new LlmProvider.StreamAccumulator();
        acc.appendReasoningText("Thinking about sky...");
        Thread.sleep(5);
        acc.appendReasoningText(" because of Rayleigh.");

        var firstStart = acc.reasoningStartNanos;
        var duration = acc.reasoningDurationMs();
        assertTrue(duration >= 1L, "duration must be >= 1ms");
        assertTrue(duration < 500L, "duration must be < 500ms (sleep was 5ms)");

        // Start is latched on first append — later appends extend end, not start.
        acc.appendReasoningText(" Done.");
        assertEquals(firstStart, acc.reasoningStartNanos);
        assertTrue(acc.reasoningDurationMs() >= duration,
                "end must advance past the third chunk");
    }

    @Test
    public void streamAccumulatorIgnoresNullReasoningText() {
        var acc = new LlmProvider.StreamAccumulator();
        acc.appendReasoningText(null);
        assertEquals(0L, acc.reasoningStartNanos);
        assertEquals(0L, acc.reasoningDurationMs());
    }

    @Test
    public void llmExceptionPreservesMessage() {
        var ex = new LlmProvider.LlmException("Provider down");
        assertEquals("Provider down", ex.getMessage());

        var caused = new LlmProvider.LlmException("Retry failed", new RuntimeException("network"));
        assertEquals("Retry failed", caused.getMessage());
        assertNotNull(caused.getCause());
    }

    @Test
    public void providerRegistryLoadsFromConfig() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();

        ConfigService.set("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
        ConfigService.set("provider.openrouter.apiKey", "sk-test");
        ConfigService.set("provider.openrouter.models", """
                [{"id":"openai/gpt-4.1","name":"GPT-4.1","contextWindow":1000000,"maxTokens":32768}]
                """);

        ConfigService.set("provider.ollama-cloud.baseUrl", "https://ollama.com/v1");
        ConfigService.set("provider.ollama-cloud.apiKey", "ollama-key");

        ProviderRegistry.refresh();

        var providers = ProviderRegistry.listAll();
        assertEquals(2, providers.size());

        var openrouter = ProviderRegistry.get("openrouter");
        assertNotNull(openrouter);
        assertEquals("https://openrouter.ai/api/v1", openrouter.config().baseUrl());
        assertEquals(1, openrouter.config().models().size());

        var ollama = ProviderRegistry.get("ollama-cloud");
        assertNotNull(ollama);
        assertEquals("https://ollama.com/v1", ollama.config().baseUrl());
    }

    @Test
    public void providerRegistryPrimaryAndSecondary() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();

        ConfigService.set("provider.primary.baseUrl", "https://primary.ai/v1");
        ConfigService.set("provider.primary.apiKey", "pk-1");
        ConfigService.set("provider.secondary.baseUrl", "https://secondary.ai/v1");
        ConfigService.set("provider.secondary.apiKey", "pk-2");

        ProviderRegistry.refresh();

        assertNotNull(ProviderRegistry.getPrimary());
        assertNotNull(ProviderRegistry.getSecondary());
    }

    // ─── mergeToolCallChunks (JCLAW-120) ─────────────────────────────

    @Test
    public void mergeToolCallChunks_wellBehavedStreamingKeepsOneSlot() {
        // Streaming lifecycle of a single call: first chunk carries id+name+"",
        // subsequent chunks carry just the arguments fragments. All share
        // index=0. Must collapse into exactly one ToolCall.
        var acc = new java.util.HashMap<Integer, LlmProvider.ToolCallBuilder>();
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, "call_a", "function", new FunctionCall("web_search", ""))
        ), acc);
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, null, null, new FunctionCall(null, "{\"query\":"))
        ), acc);
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, null, null, new FunctionCall(null, "\"x\"}"))
        ), acc);

        assertEquals(1, acc.size());
        var built = acc.get(0).build();
        assertEquals("call_a", built.id());
        assertEquals("web_search", built.function().name());
        assertEquals("{\"query\":\"x\"}", built.function().arguments());
    }

    @Test
    public void mergeToolCallChunks_parallelCallsWithDistinctIndicesStayDistinct() {
        // OpenAI-compliant: parallel calls get distinct index values.
        var acc = new java.util.HashMap<Integer, LlmProvider.ToolCallBuilder>();
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, "call_a", "function", new FunctionCall("web_search", "{\"q\":\"a\"}")),
                new ToolCallChunk(1, "call_b", "function", new FunctionCall("web_search", "{\"q\":\"b\"}"))
        ), acc);

        assertEquals(2, acc.size());
        assertEquals("call_a", acc.get(0).build().id());
        assertEquals("call_b", acc.get(1).build().id());
        assertEquals("{\"q\":\"a\"}", acc.get(0).build().function().arguments());
        assertEquals("{\"q\":\"b\"}", acc.get(1).build().function().arguments());
    }

    @Test
    public void mergeToolCallChunks_reusedIndexWithDistinctIdsSplitsSlots() {
        // JCLAW-120 production offender: Gemini-via-Ollama sends every parallel
        // call at index=0 but with distinct ids. Must split into separate slots.
        var acc = new java.util.HashMap<Integer, LlmProvider.ToolCallBuilder>();
        // Separate deltas simulate per-chunk streaming.
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, "functions.web_search:1", "function",
                        new FunctionCall("web_search", "{\"query\":\"tech\"}"))
        ), acc);
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, "functions.web_search:2", "function",
                        new FunctionCall("web_search", "{\"query\":\"biz\"}"))
        ), acc);
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, "functions.datetime:3", "function",
                        new FunctionCall("datetime", "{\"action\":\"now\"}"))
        ), acc);

        assertEquals(3, acc.size());
        // Each call's arguments and name must stay intact — the production bug
        // was concatenating args and overwriting name to "datetime".
        assertEquals("{\"query\":\"tech\"}", acc.get(0).build().function().arguments());
        assertEquals("web_search", acc.get(0).build().function().name());
        assertEquals("{\"query\":\"biz\"}", acc.get(1).build().function().arguments());
        assertEquals("web_search", acc.get(1).build().function().name());
        assertEquals("{\"action\":\"now\"}", acc.get(2).build().function().arguments());
        assertEquals("datetime", acc.get(2).build().function().name());
    }

    @Test
    public void mergeToolCallChunks_reusedIndexWithDifferentNamesSplitsSlots() {
        // Provider sends two calls at index=0, same (null) id, different names.
        // Name mismatch alone must force a new slot.
        var acc = new java.util.HashMap<Integer, LlmProvider.ToolCallBuilder>();
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, null, "function", new FunctionCall("web_search", "{\"q\":\"a\"}"))
        ), acc);
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, null, "function", new FunctionCall("datetime", "{\"action\":\"now\"}"))
        ), acc);

        assertEquals(2, acc.size());
        assertEquals("web_search", acc.get(0).build().function().name());
        assertEquals("datetime", acc.get(1).build().function().name());
    }

    @Test
    public void mergeToolCallChunks_sameDeltaIndexCollisionSplitsSlots() {
        // Defensive: provider bundles two parallel calls in one delta's
        // tool_calls list, both at index=0. Must split even if id/name
        // checks happen to pass (e.g., incomplete metadata).
        var acc = new java.util.HashMap<Integer, LlmProvider.ToolCallBuilder>();
        LlmProvider.mergeToolCallChunks(List.of(
                new ToolCallChunk(0, "a", "function", new FunctionCall("web_search", "{\"q\":\"x\"}")),
                new ToolCallChunk(0, "b", "function", new FunctionCall("web_search", "{\"q\":\"y\"}"))
        ), acc);

        assertEquals(2, acc.size());
        assertEquals("a", acc.get(0).build().id());
        assertEquals("b", acc.get(1).build().id());
    }

    @Test
    public void mergeToolCallChunks_nullInputIsNoOp() {
        var acc = new java.util.HashMap<Integer, LlmProvider.ToolCallBuilder>();
        LlmProvider.mergeToolCallChunks(null, acc);
        LlmProvider.mergeToolCallChunks(List.of(), acc);
        assertEquals(0, acc.size());
    }
}
