package llm;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmTypes.ChatCompletionChunk;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.Choice;
import llm.LlmTypes.ChunkChoice;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.EmbeddingRequest;
import llm.LlmTypes.EmbeddingResponse;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolCallChunk;
import llm.LlmTypes.ToolDef;
import llm.LlmTypes.Usage;
import services.EventLogger;
import utils.HttpKeys;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract base for LLM provider integrations. Implements shared OpenAI-compatible
 * HTTP, retry, streaming, and serialization logic. Subclasses override template methods
 * to handle provider-specific differences (reasoning params, response parsing, etc.).
 *
 * <p>Outbound HTTP runs through {@link OkHttpLlmHttpDriver} (OkHttp 5.x +
 * {@code okhttp-sse} for streaming). The previous JDK alternative and the
 * {@code play.llm.client} flag that toggled between them were deleted in
 * JCLAW-187 once cloud benchmarks confirmed parity (median 0.94x avg
 * across 7 cloud runs, 0.85x local with NUM_PARALLEL=8). Validation for
 * the streaming SSE path lives in {@code test/ChatStreamSseTest}.
 */
public abstract sealed class LlmProvider permits OpenAiProvider, OllamaProvider, OpenRouterProvider, TogetherAiProvider {

    protected static final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};

    // OpenAI-compatible JSON field names used across request/response (de)serialization
    // and chunk-usage augmentation. Centralised so a typo can't drift one call site
    // off the wire shape without the compiler catching it.
    private static final String JSON_USAGE = "usage";
    private static final String JSON_MODEL = "model";
    private static final String JSON_CONTENT = "content";
    private static final String JSON_TOOL_CALLS = "tool_calls";
    private static final String JSON_TOOL_CALL_ID = "tool_call_id";
    private static final String JSON_FINISH_REASON = "finish_reason";
    // OpenAI tool-call type — the only value the spec defines today.
    private static final String TYPE_FUNCTION = "function";

    // Why: park retry waits on a platform-thread scheduler so a burst of 429s doesn't
    // wedge the LLM virtual-thread dispatcher under JDK-8373224 (Thread.sleep on many
    // concurrent VTs starves the FJP work queue and inflates tail latency).
    private static final ScheduledExecutorService RETRY_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "llm-retry-scheduler");
                t.setDaemon(true);
                return t;
            });

    protected final ProviderConfig config;

    /**
     * Declarative mapping from provider-name substring to constructor.
     * Adding a new provider is a single-line entry in {@link FactoryHolder}.
     *
     * <p>Held in a nested class (initialization-on-demand idiom) so the
     * subclass-constructor references don't fire during {@link LlmProvider}'s
     * own class initialization — which Sonar flags as S2390 ("Classes should
     * not access their own subclasses during class initialization") and the
     * JVM is technically free to order in ways that produce surprising NPEs.
     * {@code FactoryHolder} only loads when {@link #forConfig} first reads
     * its {@code MAP}, by which time {@link LlmProvider} is fully initialized.
     */
    private static final class FactoryHolder {
        static final Map<String, Function<ProviderConfig, LlmProvider>> MAP = Map.of(
                "openrouter", OpenRouterProvider::new,
                "ollama", OllamaProvider::new,
                "together", TogetherAiProvider::new,
                "openai", OpenAiProvider::new
        );

        private FactoryHolder() {}
    }

    protected LlmProvider(ProviderConfig config) {
        this.config = config;
    }

    public ProviderConfig config() { return config; }

    /**
     * Factory method: creates the right {@link LlmProvider} subclass based on
     * the provider name in the config. Matches against known substrings
     * declaratively via {@link FactoryHolder}; falls back to
     * {@link OpenAiProvider} for unknown/standard OpenAI-compatible providers.
     *
     * @param config the provider configuration to instantiate against
     * @return the most specific {@link LlmProvider} subclass for
     *         {@code config.name()}
     */
    public static LlmProvider forConfig(ProviderConfig config) {
        var lowerName = config.name().toLowerCase();
        for (var entry : FactoryHolder.MAP.entrySet()) {
            if (lowerName.contains(entry.getKey())) {
                return entry.getValue().apply(config);
            }
        }
        return new OpenAiProvider(config);
    }

    // ─── Template methods (override in subclasses) ───────────────────────

    /**
     * Add provider-specific reasoning/thinking parameters to the request JSON.
     *
     * @param request      the outgoing request body the subclass may mutate
     * @param thinkingMode operator-selected reasoning effort
     *                     ({@code "low"}/{@code "medium"}/{@code "high"} or
     *                     provider-specific extensions)
     */
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        // Default: no reasoning support
    }

    /**
     * Explicitly disable reasoning for models that think by default. Called
     * when thinkingMode is off.
     *
     * @param request the outgoing request body the subclass may mutate
     */
    protected void disableReasoning(JsonObject request) {
        // Default: no action (most providers don't need explicit disable)
    }

    /**
     * Extract reasoning text from a streaming chunk delta.
     * Called for each chunk during streaming.
     *
     * @param delta the streaming chunk delta object
     * @return reasoning text fragment, or {@code null} if no reasoning in
     *         this chunk
     */
    @SuppressWarnings("java:S1172") // template method — subclasses use the delta
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        return null;
    }

    /**
     * Read an integer field from a usage JSON object, returning 0 when the
     * field is missing or JSON null. Top-level form — {@code usage.field}.
     *
     * <p>Companion to the nested overload below; together they collapse the
     * "{@code if (obj.has(f) && !obj.get(f).isJsonNull()) return obj.get(f).getAsInt();}"
     * idiom that every provider's usage-extraction methods used to open-code.
     * Callers that need a fallback chain (top-level, then nested) can compose
     * the two: {@code int top = readUsageInt(usage, "x"); return top > 0 ? top : readUsageInt(usage, "details", "x");}.
     *
     * <p>The "missing-or-null returns 0" semantic is correct for token-count
     * fields specifically — providers either omit the field or report 0 when
     * a category didn't apply, and both should be treated equivalently by
     * downstream cost/usage aggregation.
     *
     * @param usageObj the provider's {@code usage} JSON object
     * @param field    the top-level field name to read
     * @return the field's int value, or {@code 0} when missing or null
     */
    protected static int readUsageInt(JsonObject usageObj, String field) {
        if (usageObj == null || !usageObj.has(field) || usageObj.get(field).isJsonNull()) return 0;
        return usageObj.get(field).getAsInt();
    }

    /**
     * Read an integer field nested under one wrapping object — {@code usage.nestedObj.field}.
     *
     * @param usageObj  the provider's {@code usage} JSON object
     * @param nestedObj the wrapping object's field name
     * @param field     the inner field name
     * @return the field's int value, or {@code 0} when any path component is
     *         missing or null
     */
    protected static int readUsageInt(JsonObject usageObj, String nestedObj, String field) {
        if (usageObj == null || !usageObj.has(nestedObj) || usageObj.get(nestedObj).isJsonNull()) return 0;
        return readUsageInt(usageObj.getAsJsonObject(nestedObj), field);
    }

    /**
     * Extract reasoning token count from a usage JSON object.
     * Called when parsing the usage block in responses.
     *
     * @param usageObj the provider's {@code usage} JSON object
     * @return the reasoning-token count, or {@code 0} when the provider
     *         doesn't expose one
     */
    @SuppressWarnings("java:S1172") // template method — subclasses use the usage object
    protected int extractReasoningTokens(JsonObject usageObj) {
        return 0;
    }

    /**
     * Shared "top-level then OpenAI-nested" reasoning-token read: prefer a
     * top-level {@code usage.reasoning_tokens}, else fall back to
     * {@code usage.completion_tokens_details.reasoning_tokens}. OpenRouter,
     * Together, and OpenAI-routed usage all fit this chain — OpenAI never emits
     * the top-level field, so it resolves to the nested path. Mirrors
     * {@link #extractCacheCreationTokens}'s top-then-nested shape.
     */
    protected int readReasoningTokens(JsonObject usageObj) {
        int top = readUsageInt(usageObj, "reasoning_tokens");
        return top > 0 ? top : readUsageInt(usageObj, "completion_tokens_details", "reasoning_tokens");
    }

    /**
     * Extract the count of prompt tokens that were served from a provider-side
     * prompt cache (cache <em>reads</em>). Defaults to the OpenAI-compat path
     * ({@code usage.prompt_tokens_details.cached_tokens}) which is also what
     * OpenRouter emits (with {@code usage: {include: true}}). Providers that
     * report differently — or not at all — override this.
     *
     * @param usageObj the provider's {@code usage} JSON object
     * @return cache-read tokens, or {@code 0} when none reported
     */
    protected int extractCachedTokens(JsonObject usageObj) {
        return readUsageInt(usageObj, "prompt_tokens_details", "cached_tokens");
    }

    /**
     * Extract the count of prompt tokens written to the provider-side prompt cache on
     * this turn (cache <em>writes</em>). Anthropic routes expose this as
     * {@code usage.cache_creation_input_tokens} (top-level) and OpenRouter normalizes
     * the same field through. OpenAI routes have no write concept — cache seeding is
     * implicit and not billed — so the field is absent and this returns 0.
     *
     * <p>Cache writes are a disjoint subset of {@code prompt_tokens}, alongside cache
     * reads. They are priced at a premium (Anthropic: 1.25× base for 5-min TTL).
     *
     * @param usageObj the provider's {@code usage} JSON object
     * @return cache-write tokens, or {@code 0} when none reported
     */
    protected int extractCacheCreationTokens(JsonObject usageObj) {
        // Anthropic/OpenRouter: top-level cache_creation_input_tokens.
        // Some normalizations nest it under prompt_tokens_details.cache_creation_tokens.
        int top = readUsageInt(usageObj, "cache_creation_input_tokens");
        return top > 0 ? top : readUsageInt(usageObj, "prompt_tokens_details", "cache_creation_tokens");
    }

    /**
     * Add provider-specific prompt-caching directives to the outgoing request JSON.
     * Called at the end of serializeRequest, after messages and reasoning have been
     * attached. Subclasses add things like Anthropic's {@code cache_control}
     * breakpoints (via OpenRouter) or Ollama's {@code keep_alive}. Default is no-op
     * because OpenAI and most OpenAI-compat providers cache automatically.
     *
     * @param request     the outgoing request body the subclass may mutate
     * @param chatRequest the higher-level request the JSON was built from
     *                    (gives subclasses access to message metadata for
     *                    deciding where to drop cache breakpoints)
     */
    protected void applyCacheDirectives(JsonObject request, ChatRequest chatRequest) {
        // Default: no-op
    }

    /**
     * Streaming chunks are Gson-deserialized field-by-field, which only catches
     * top-level usage fields. Providers report reasoning and cached tokens under
     * nested paths ({@code completion_tokens_details.reasoning_tokens},
     * {@code prompt_tokens_details.cached_tokens}), so re-scan the raw chunk JSON
     * via the template methods and replace Usage if we found more. Cheap — only
     * runs on the final chunk that carries the usage block.
     *
     * <p>Takes the already-parsed {@link JsonObject} instead of the raw string so
     * we don't pay {@link JsonParser#parseString} twice per chunk (once
     * implicitly inside {@code gson.fromJson} and once here). Caller in
     * {@link #chatStream} parses the SSE data field once and reuses the tree
     * for both the field-mapping and the usage augmentation pass.
     */
    private ChatCompletionChunk augmentChunkUsage(ChatCompletionChunk chunk, JsonObject root) {
        if (chunk.usage() == null) return chunk;
        try {
            if (!root.has(JSON_USAGE) || root.get(JSON_USAGE).isJsonNull()) return chunk;
            var usageObj = root.getAsJsonObject(JSON_USAGE);
            int reasoning = Math.max(chunk.usage().reasoningTokens(), extractReasoningTokens(usageObj));
            int cached = Math.max(chunk.usage().cachedTokens(), extractCachedTokens(usageObj));
            int cacheCreation = Math.max(chunk.usage().cacheCreationTokens(), extractCacheCreationTokens(usageObj));
            if (reasoning == chunk.usage().reasoningTokens()
                    && cached == chunk.usage().cachedTokens()
                    && cacheCreation == chunk.usage().cacheCreationTokens()) {
                return chunk;
            }
            var augmented = new Usage(
                    chunk.usage().promptTokens(),
                    chunk.usage().completionTokens(),
                    chunk.usage().totalTokens(),
                    reasoning,
                    cached,
                    cacheCreation);
            return new ChatCompletionChunk(chunk.id(), chunk.model(), chunk.choices(), augmented);
        } catch (Exception _) {
            return chunk;
        }
    }

    // ─── Synchronous chat ────────────────────────────────────────────────

    public ChatResponse chat(String model, List<ChatMessage> messages, List<ToolDef> tools,
                             Integer maxTokens, String thinkingMode, String channel) {
        return chat(model, messages, tools, maxTokens, thinkingMode, null, channel);
    }

    /**
     * Synchronous chat with an optional custom timeout (seconds).
     *
     * @param model          model id to request
     * @param messages       conversation messages in chronological order
     * @param tools          tool definitions exposed to the model; may be null
     * @param maxTokens      completion-token cap, or null for provider default
     * @param thinkingMode   reasoning-effort level, or null when off / N/A
     * @param timeoutSeconds custom HTTP timeout in seconds, or null for the
     *                       default 180s timeout
     * @param channel        inbound chat channel (web, telegram, slack, …)
     *                       that originated the call; the OkHttp
     *                       dispatcher_wait metric (recorded by
     *                       {@link utils.LlmCallEventListener}) is
     *                       partitioned by it so each channel's dashboard
     *                       view shows the dispatcher cost its chats
     *                       actually paid. Pass {@code null} for callers
     *                       without a chat-channel context (skill promotion,
     *                       slash commands, scheduled summarization).
     * @return the parsed chat completion response
     */
    public ChatResponse chat(String model, List<ChatMessage> messages, List<ToolDef> tools,
                             Integer maxTokens, String thinkingMode, Integer timeoutSeconds,
                             String channel) {
        var request = new ChatRequest(model, messages, tools, false, maxTokens, thinkingMode);
        var json = serializeRequest(request);
        var responseBody = executeWithRetry("/chat/completions", json, timeoutSeconds, channel);
        return deserializeResponse(responseBody);
    }

    // ─── Streaming chat ──────────────────────────────────────────────────

    // S107: streaming chat needs the conversation shape (model, messages, tools,
    // tuning) AND a triplet of callback consumers — the chunk/complete/error
    // split mirrors the SSE event surface and reactive callers want them
    // independent. Bundling into a Callbacks DTO would lose the lambda-literal
    // call-site ergonomics every caller depends on.
    @SuppressWarnings("java:S107")
    public void chatStream(String model, List<ChatMessage> messages, List<ToolDef> tools,
                           Consumer<ChatCompletionChunk> onChunk,
                           Runnable onComplete, Consumer<Exception> onError,
                           Integer maxTokens, String thinkingMode, String channel) {
        Thread.ofVirtual().name("llm-stream").start(() -> {
            try {
                var request = new ChatRequest(model, messages, tools, true, maxTokens, thinkingMode);
                var json = serializeRequest(request);
                OkHttpLlmHttpDriver.streamSse(buildUri("/chat/completions"),
                        HttpKeys.BEARER_PREFIX + config.apiKey(), json,
                        data -> {
                            // The server closes the stream right after the [DONE]
                            // sentinel, so we skip parsing it here.
                            if ("[DONE]".equals(data)) return;
                            // Parse once, reuse for both deserialization and usage
                            // augmentation. The previous implementation parsed
                            // twice on every final-usage chunk: once implicitly
                            // inside gson.fromJson(String, Class), once explicitly
                            // inside augmentChunkUsage via JsonParser.parseString.
                            try {
                                var root = JsonParser.parseString(data).getAsJsonObject();
                                var chunk = gson.fromJson(root, ChatCompletionChunk.class);
                                if (chunk != null) onChunk.accept(augmentChunkUsage(chunk, root));
                            } catch (Exception _) {
                                // Skip malformed chunks
                            }
                        },
                        onComplete,
                        t -> onError.accept(t instanceof Exception ex
                                ? ex : new LlmException("Stream error", t)),
                        channel);
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    // ─── Streaming with accumulation ─────────────────────────────────────

    // S107: same shape as chatStream, minus chunk/complete/error consumers
    // (accumulator owns those), plus onToken/onReasoning split because the
    // frontend renders thinking and content tokens through different paths.
    @SuppressWarnings("java:S107")
    public StreamAccumulator chatStreamAccumulate(String model, List<ChatMessage> messages,
                                                   List<ToolDef> tools, Consumer<String> onToken,
                                                   Consumer<String> onReasoning,
                                                   Integer maxTokens, String thinkingMode,
                                                   String channel) {
        var accumulator = new StreamAccumulator();
        accumulator.promptTokenEstimate = TokenUsageEstimator.estimateChatRequest(model, messages, tools);
        var contentBuilder = new StringBuilder();
        var toolCallAccumulator = new HashMap<Integer, ToolCallBuilder>();

        chatStream(model, messages, tools,
                chunk -> accumulateChunk(chunk, accumulator, contentBuilder, toolCallAccumulator,
                        onToken, onReasoning),
                () -> {
                    accumulator.content = contentBuilder.toString();
                    accumulator.toolCalls = toolCallAccumulator.values().stream()
                            .map(ToolCallBuilder::build).toList();
                    accumulator.completionTokenEstimate = TokenUsageEstimator.estimateCompletion(
                            model, accumulator.content, accumulator.toolCalls, accumulator.reasoningText());
                    accumulator.reasoningTokenEstimate = TokenUsageEstimator.estimateReasoning(
                            model, accumulator.reasoningText());
                    accumulator.markComplete();
                },
                e -> {
                    accumulator.error = e;
                    accumulator.markComplete();
                },
                maxTokens, thinkingMode, channel);

        return accumulator;
    }

    private void accumulateChunk(ChatCompletionChunk chunk,
                                 StreamAccumulator accumulator,
                                 StringBuilder contentBuilder,
                                 Map<Integer, ToolCallBuilder> toolCallAccumulator,
                                 Consumer<String> onToken,
                                 Consumer<String> onReasoning) {
        if (chunk.usage() != null) {
            accumulator.usage = chunk.usage();
            if (chunk.usage().reasoningTokens() > 0) {
                accumulator.reasoningDetected = true;
                accumulator.reasoningTokens = chunk.usage().reasoningTokens();
            }
        }
        for (var choice : chunk.choices()) {
            applyChoiceDelta(choice, accumulator, contentBuilder, toolCallAccumulator,
                    onToken, onReasoning);
        }
    }

    private void applyChoiceDelta(ChunkChoice choice,
                                  StreamAccumulator accumulator,
                                  StringBuilder contentBuilder,
                                  Map<Integer, ToolCallBuilder> toolCallAccumulator,
                                  Consumer<String> onToken,
                                  Consumer<String> onReasoning) {
        var delta = choice.delta();
        // Skip empty-content chunks: OpenAI-compatible providers
        // (e.g. OpenRouter routing Gemini-3-flash-preview, Kimi K2.5)
        // emit `content: ""` interleaved with every reasoning chunk
        // because the schema requires the field. Counting these as
        // real content stamps firstContentNanos at the same instant
        // as reasoningStartNanos → reasoningDurationMs collapses to 0
        // → the frontend renders the generic "Thinking" label after
        // reload. Mirrors the frontend's `if (!event.content) continue`
        // guard at chat.vue:1116.
        if (delta.content() != null && !delta.content().isEmpty()) {
            accumulator.noteFirstContentChunk();
            contentBuilder.append(delta.content());
            onToken.accept(delta.content());
        }
        // Delegate reasoning extraction to the provider subclass.
        // We buffer the text even when the consumer doesn't supply an
        // onReasoning callback, because the length feeds the token-count
        // estimate for providers (e.g. Ollama Cloud on glm-5.1) that
        // stream reasoning but omit reasoning_tokens from usage.
        var reasoningText = extractReasoningFromDelta(delta);
        if (reasoningText != null) {
            accumulator.reasoningDetected = true;
            accumulator.appendReasoningText(reasoningText);
            if (onReasoning != null) onReasoning.accept(reasoningText);
        }
        // JCLAW-120: Gemini-via-Ollama-Cloud streams parallel
        // tool calls all at the same index. mergeToolCallChunks
        // detects id / function-name mismatches and allocates
        // fresh slots so parallel calls stay distinct.
        mergeToolCallChunks(delta.toolCalls(), toolCallAccumulator);
        if (choice.finishReason() != null) {
            accumulator.finishReason = choice.finishReason();
        }
    }

    // ─── Embeddings ──────────────────────────────────────────────────────

    public float[] embeddings(String model, String input, String channel) {
        var request = new EmbeddingRequest(model, input);
        var json = gson.toJson(request);
        var responseBody = executeWithRetry("/embeddings", json, null, channel);
        var response = gson.fromJson(responseBody, EmbeddingResponse.class);
        if (response.data() == null || response.data().isEmpty()) {
            throw new LlmException("Empty embedding response");
        }
        return response.data().getFirst().embedding();
    }

    // ─── Failover (static utility) ───────────────────────────────────────

    // S107: failover wraps two providers around the standard 6-arg chat call;
    // pushing the chat tuple into a DTO would force every caller of the
    // primary {@link #chat} path to pre-build one, which they don't.
    @SuppressWarnings("java:S107")
    public static ChatResponse chatWithFailover(LlmProvider primary, LlmProvider secondary,
                                                 String model, List<ChatMessage> messages,
                                                 List<ToolDef> tools, Integer maxTokens,
                                                 String thinkingMode, String channel) {
        try {
            return primary.chat(model, messages, tools, maxTokens, thinkingMode, channel);
        } catch (LlmException e) {
            if (secondary != null) {
                EventLogger.warn("llm", "Failing over from %s to %s: %s"
                        .formatted(primary.config().name(), secondary.config().name(), e.getMessage()));
                return secondary.chat(model, messages, tools, maxTokens, thinkingMode, channel);
            }
            throw e;
        }
    }

    // ─── Shared internals ────────────────────────────────────────────────

    protected String serializeRequest(ChatRequest request) {
        var obj = new JsonObject();
        obj.addProperty(JSON_MODEL, request.model());
        obj.add("messages", serializeMessages(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            obj.add("tools", gson.toJsonTree(request.tools()));
        }
        if (request.stream()) {
            obj.addProperty("stream", true);
            var streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            obj.add("stream_options", streamOptions);
        }
        if (request.maxTokens() != null) {
            obj.addProperty("max_tokens", request.maxTokens());
        }
        if (request.thinkingMode() != null && !request.thinkingMode().isBlank()) {
            addReasoningParams(obj, request.thinkingMode());
        } else {
            disableReasoning(obj);
        }
        applyCacheDirectives(obj, request);
        return gson.toJson(obj);
    }

    private JsonArray serializeMessages(List<ChatMessage> messages) {
        var array = new JsonArray();
        for (var msg : messages) {
            var obj = new JsonObject();
            obj.addProperty("role", msg.role());
            if (msg.content() instanceof String s) {
                obj.addProperty(JSON_CONTENT, s);
            } else if (msg.content() != null) {
                obj.add(JSON_CONTENT, gson.toJsonTree(msg.content()));
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                obj.add(JSON_TOOL_CALLS, gson.toJsonTree(msg.toolCalls()));
            }
            if (msg.toolCallId() != null) {
                obj.addProperty(JSON_TOOL_CALL_ID, msg.toolCallId());
            }
            if (msg.toolName() != null) {
                obj.addProperty("name", msg.toolName());
            }
            array.add(obj);
        }
        return array;
    }

    protected ChatResponse deserializeResponse(String json) {
        var obj = JsonParser.parseString(json).getAsJsonObject();
        var id = obj.has("id") ? obj.get("id").getAsString() : null;
        var model = obj.has(JSON_MODEL) ? obj.get(JSON_MODEL).getAsString() : null;

        var choices = new ArrayList<Choice>();
        if (obj.has("choices")) {
            for (var choiceEl : obj.getAsJsonArray("choices")) {
                var choiceObj = choiceEl.getAsJsonObject();
                var index = choiceObj.get("index").getAsInt();
                var finishReason = choiceObj.has(JSON_FINISH_REASON) && !choiceObj.get(JSON_FINISH_REASON).isJsonNull()
                        ? choiceObj.get(JSON_FINISH_REASON).getAsString() : null;
                var msgObj = choiceObj.getAsJsonObject("message");
                var message = deserializeMessage(msgObj);
                choices.add(new Choice(index, message, finishReason));
            }
        }

        Usage usage = null;
        if (obj.has(JSON_USAGE) && !obj.get(JSON_USAGE).isJsonNull()) {
            usage = parseUsage(obj.getAsJsonObject(JSON_USAGE));
        }

        return new ChatResponse(id, model, choices, usage);
    }

    private ChatMessage deserializeMessage(JsonObject msgObj) {
        var role = msgObj.get("role").getAsString();
        String content = null;
        if (msgObj.has(JSON_CONTENT) && !msgObj.get(JSON_CONTENT).isJsonNull()) {
            content = msgObj.get(JSON_CONTENT).getAsString();
        }

        List<ToolCall> toolCalls = null;
        if (msgObj.has(JSON_TOOL_CALLS) && !msgObj.get(JSON_TOOL_CALLS).isJsonNull()) {
            toolCalls = new ArrayList<>();
            for (var tcEl : msgObj.getAsJsonArray(JSON_TOOL_CALLS)) {
                var tcObj = tcEl.getAsJsonObject();
                var tcId = tcObj.get("id").getAsString();
                var tcType = tcObj.has("type") ? tcObj.get("type").getAsString() : TYPE_FUNCTION;
                var fnObj = tcObj.getAsJsonObject(TYPE_FUNCTION);
                var fnName = fnObj.get("name").getAsString();
                var fnArgs = fnObj.get("arguments").getAsString();
                toolCalls.add(new ToolCall(tcId, tcType, new FunctionCall(fnName, fnArgs)));
            }
        }

        String toolCallId = null;
        if (msgObj.has(JSON_TOOL_CALL_ID) && !msgObj.get(JSON_TOOL_CALL_ID).isJsonNull()) {
            toolCallId = msgObj.get(JSON_TOOL_CALL_ID).getAsString();
        }

        return new ChatMessage(role, content, toolCalls, toolCallId, null);
    }

    /**
     * Build the absolute request URL by joining {@link ProviderConfig#baseUrl}
     * with {@code path}. Tolerates either a trailing slash on baseUrl or a
     * leading slash on path; emits exactly one slash between them.
     *
     * @param path API path beneath the provider's base URL (e.g.
     *             {@code "/chat/completions"})
     * @return the absolute URI for the request
     */
    protected URI buildUri(String path) {
        var url = config.baseUrl().endsWith("/")
                ? config.baseUrl() + path.substring(1)
                : config.baseUrl() + path;
        return URI.create(url);
    }

    protected String executeWithRetry(String path, String json, Integer timeoutSeconds, String channel) {
        var uri = buildUri(path);
        var auth = HttpKeys.BEARER_PREFIX + config.apiKey();
        var timeout = Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 180);
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                var outcome = attemptRequest(uri, auth, json, timeout, channel, attempt);
                if (outcome.body() != null) return outcome.body();
                if (outcome.error() != null) lastException = outcome.error();
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted during request to " + config.name(), ie);
            } catch (Exception e) {
                lastException = e;
            }

            if (attempt < MAX_RETRIES) {
                backoffBeforeRetry(attempt);
            }
        }

        throw new LlmException("All retries exhausted for " + config.name(), lastException);
    }

    /** Outcome of a single attempt: either {@code body} is a success body, or {@code error} carries a retryable error. */
    private record AttemptOutcome(String body, Exception error) {}

    /**
     * Execute one request attempt. Returns a body on 200, parks for retry-after on 429
     * (and returns an empty outcome so the caller advances), throws on 4xx, or returns
     * an error outcome on 5xx for the caller to retry.
     */
    private AttemptOutcome attemptRequest(URI uri, String auth, String json, Duration timeout,
                                          String channel, int attempt) throws InterruptedException, IOException {
        var reply = OkHttpLlmHttpDriver.send(uri, auth, json, timeout, channel);

        if (reply.statusCode() == 200) return new AttemptOutcome(reply.body(), null);

        if (reply.statusCode() == 429) {
            var defaultBackoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)] / 1000;
            var retryAfter = reply.retryAfterSeconds().orElse(defaultBackoff);
            EventLogger.warn("llm", "Rate limited by %s, retrying after %ds".formatted(config.name(), retryAfter));
            parkForMillis(retryAfter * 1000);
            return new AttemptOutcome(null, null);
        }

        if (reply.statusCode() >= 400 && reply.statusCode() < 500) {
            throw new LlmException("HTTP %d from %s: %s".formatted(
                    reply.statusCode(), config.name(), reply.body()));
        }

        return new AttemptOutcome(null, new LlmException("HTTP %d from %s: %s".formatted(
                reply.statusCode(), config.name(), reply.body())));
    }

    private void backoffBeforeRetry(int attempt) {
        try {
            var backoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
            EventLogger.warn("llm", "Retry %d/%d for %s after %dms"
                    .formatted(attempt + 1, MAX_RETRIES, config.name(), backoff));
            parkForMillis(backoff);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new LlmException("Interrupted during retry", ie);
        }
    }

    private static void parkForMillis(long delayMs) throws InterruptedException {
        try {
            RETRY_SCHEDULER.schedule(() -> null, delayMs, TimeUnit.MILLISECONDS).get();
        } catch (ExecutionException e) {
            throw new LlmException("Retry scheduler failed", e.getCause() != null ? e.getCause() : e);
        }
    }

    // ─── Usage parsing ────────────────────────────────────────────────────

    /**
     * Instance method: parse a usage JSON object using this provider's template
     * methods ({@link #extractReasoningTokens}, {@link #extractCachedTokens},
     * {@link #extractCacheCreationTokens}). Subclass overrides are honoured,
     * so provider-specific JSON paths are handled correctly.
     *
     * @param usageObj the provider's {@code usage} JSON object
     * @return the parsed {@link Usage} record with all token-count categories
     *         populated
     */
    public Usage parseUsage(JsonObject usageObj) {
        return new Usage(
                usageObj.has("prompt_tokens") ? usageObj.get("prompt_tokens").getAsInt() : 0,
                usageObj.has("completion_tokens") ? usageObj.get("completion_tokens").getAsInt() : 0,
                usageObj.has("total_tokens") ? usageObj.get("total_tokens").getAsInt() : 0,
                extractReasoningTokens(usageObj),
                extractCachedTokens(usageObj),
                extractCacheCreationTokens(usageObj));
    }

    // ─── Shared helper classes ───────────────────────────────────────────

    // S1104: package-internal streaming accumulator with public mutable fields by design.
    // Volatile field access from multiple agent/channel files is hotter than the
    // accessor route would be; converting every read site across app/agents,
    // app/channels, and the test suite would be churn far out of proportion to
    // the warning's severity (LOW). Treat this class as a value carrier.
    @SuppressWarnings("java:S1104")
    public static class StreamAccumulator {
        public volatile String content = "";
        public volatile List<ToolCall> toolCalls = List.of();
        public volatile String finishReason;
        public volatile boolean complete = false;
        public volatile Exception error;
        public volatile boolean reasoningDetected = false;
        public volatile int reasoningTokens = 0;
        /**
         * Accumulated reasoning text across all streamed deltas. Populated even
         * when the provider doesn't report {@code reasoning_tokens} in the usage
         * block, so callers can estimate a token count from the text length when
         * needed (see {@code AgentRunner.emitUsageAndComplete}).
         *
         * <p>Guarded by {@link #reasoningLock} (a {@link java.util.concurrent.locks.ReentrantLock}
         * rather than {@code synchronized}). The streaming callback fires on
         * an OkHttp virtual thread; under JEP-444 a {@code synchronized} block
         * pins the carrier for the duration of the lock — directly contradicting
         * the architecture rationale ("zero Thread is pinned events"). A
         * {@code ReentrantLock} parks the virtual thread instead, allowing
         * the carrier to be reused for other work.
         */
        private final StringBuilder reasoningTextBuffer = new StringBuilder();
        private final ReentrantLock reasoningLock =
                new ReentrantLock();
        public volatile Usage usage;
        /** JTokkit-measured prompt tokens for this provider request, available even when provider usage is absent. */
        public volatile TokenUsageEstimator.ChatRequestTokens promptTokenEstimate;
        /** JTokkit-measured completion tokens for streamed content/tool calls/reasoning. */
        public volatile TokenUsageEstimator.TokenCount completionTokenEstimate;
        /** JTokkit-measured reasoning-token subset for streamed reasoning text. */
        public volatile TokenUsageEstimator.TokenCount reasoningTokenEstimate;
        // Wall-clock nanoTime at first and latest reasoning chunk. Both remain 0
        // when the model emitted no reasoning. reasoningEndNanos is updated on
        // every append so it naturally captures "end of reasoning phase" — the
        // gap between the last reasoning chunk and the first content chunk is
        // within one provider tick, accurate enough for a user-visible seconds
        // label. See AgentRunner.buildUsageJson for the ms conversion.
        public volatile long reasoningStartNanos = 0L;
        public volatile long reasoningEndNanos = 0L;
        /**
         * Wall-clock nanoTime at the first content chunk of THIS round, or 0
         * if the round produced no content (tool-only round, or reasoning-only
         * response). {@link TurnUsage#addRound} aggregates the earliest
         * non-zero value across rounds so the persisted "Thought for X
         * seconds" matches what the user saw live: from first reasoning
         * event to first content event of the entire turn (including any
         * tool-execution gap between rounds).
         */
        public volatile long firstContentNanos = 0L;
        private final CountDownLatch latch = new CountDownLatch(1);

        public void appendReasoningText(String text) {
            if (text == null) return;
            reasoningLock.lock();
            try {
                reasoningTextBuffer.append(text);
                var now = System.nanoTime();
                if (reasoningStartNanos == 0L) reasoningStartNanos = now;
                reasoningEndNanos = now;
            } finally {
                reasoningLock.unlock();
            }
        }

        /**
         * Record the timestamp of the first content chunk in this round.
         * {@link TurnUsage#addRound} reads {@link #firstContentNanos} to
         * compute turn-level reasoning duration (first reasoning event of the
         * turn → first content event of the turn). Idempotent: only the first
         * call records.
         *
         * <p>Also bookends {@link #reasoningEndNanos} for the single-chunk
         * reasoning case, so the round-local {@link #reasoningDurationMs}
         * still computes a non-zero value for diagnostic / per-round needs.
         * Multi-chunk reasoning is unaffected (reasoningEndNanos was already
         * advanced past reasoningStartNanos via prior appends).
         */
        public void noteFirstContentChunk() {
            reasoningLock.lock();
            try {
                if (firstContentNanos != 0L) return;
                firstContentNanos = System.nanoTime();
                if (reasoningStartNanos != 0L && reasoningEndNanos == reasoningStartNanos) {
                    reasoningEndNanos = firstContentNanos;
                }
            } finally {
                reasoningLock.unlock();
            }
        }

        /** Character count of accumulated reasoning text. Used for token estimation. */
        public int reasoningChars() {
            reasoningLock.lock();
            try {
                return reasoningTextBuffer.length();
            } finally {
                reasoningLock.unlock();
            }
        }

        /**
         * Full streamed reasoning text for this round. Returned as a plain
         * {@link String} (buffer is copied) so callers can hand it to JPA /
         * downstream consumers without racing against concurrent appends on
         * the streaming thread.
         */
        public String reasoningText() {
            reasoningLock.lock();
            try {
                return reasoningTextBuffer.toString();
            } finally {
                reasoningLock.unlock();
            }
        }

        /**
         * Milliseconds spent in the reasoning phase, or 0 when no reasoning was
         * streamed. Computed lazily so callers get a stable snapshot even if the
         * stream is still in flight.
         */
        public long reasoningDurationMs() {
            if (reasoningStartNanos == 0L) return 0L;
            return (reasoningEndNanos - reasoningStartNanos) / 1_000_000L;
        }

        void markComplete() { complete = true; latch.countDown(); }
        public void awaitCompletion() throws InterruptedException { latch.await(); }
        public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Cumulative token usage across every LLM round in a single user turn.
     * A "turn" is one user message → one final assistant message, which for
     * tool-using models can span many LLM API calls (each round has its own
     * {@link StreamAccumulator}). Token counts get folded in via
     * {@link #addRound(StreamAccumulator)} after each round's stream completes.
     *
     * <p>Summing per-round usage is the billing-correct behaviour because each
     * round is a separate API call; it also matches user intuition for
     * reasoning/completion counts (the user sees all reasoning and all
     * synthesis output across rounds, not just round 1). Reasoning-phase
     * <em>timing</em> is also turn-level (see {@link #turnReasoningStartNanos}
     * and {@link #turnFirstContentNanos}): the persisted "Thought for X
     * seconds" matches what the user saw live — from first reasoning event
     * of the turn to first content event of the turn, including any tool-
     * execution gap between rounds. Anchoring it to round 1 only (the
     * pre-fix behaviour) made reloaded turns show e.g. 1.23s while the
     * live UI showed 9.60s for the same turn.
     *
     * <p>See JCLAW-76 for the accounting defect this class fixes.
     */
    // S1104: cumulative-usage value carrier; see StreamAccumulator note above.
    @SuppressWarnings("java:S1104")
    public static class TurnUsage {
        public int promptTokens;
        public int completionTokens;
        public int totalTokens;
        /** Sum of provider-reported {@code reasoning_tokens} across all rounds. */
        public int reasoningTokens;
        public int cachedTokens;
        public int cacheCreationTokens;
        /** Sum of streamed reasoning-text chars, used as a token fallback when the provider returns 0 reasoning_tokens. */
        public int reasoningChars;
        /** True once any round has detected reasoning, used to gate the fallback estimate. */
        public boolean reasoningDetected;
        /** True once any round returned a non-null {@link Usage}. Gates the zero-usage JSON path. */
        public boolean hasProviderUsage;
        /** True once any round has a JTokkit request/response measurement. */
        public boolean hasJtokkitUsage;
        public int jtokkitPromptTokens;
        public int jtokkitCompletionTokens;
        public int jtokkitReasoningTokens;
        public int jtokkitTotalTokens;
        public String jtokkitEncoding;
        public boolean jtokkitModelMatched = true;
        /**
         * Wall-clock nanoTime at the first reasoning chunk anywhere in this
         * turn (any round). Set on the first {@code addRound} that sees a
         * non-zero {@link StreamAccumulator#reasoningStartNanos}; never
         * overwritten — matches the frontend's "stamp once" semantics.
         */
        public volatile long turnReasoningStartNanos = 0L;
        /**
         * Wall-clock nanoTime at the first content chunk anywhere in this
         * turn (any round). Same first-non-zero-wins propagation rule as
         * {@link #turnReasoningStartNanos}. Stays 0 if the turn was
         * reasoning-only (no content streamed); see
         * {@link #reasoningDurationMs} for that fallback.
         */
        public volatile long turnFirstContentNanos = 0L;
        /**
         * Concatenated reasoning text across every LLM round in the turn.
         * Matches what the frontend bubble displays live (reasoning SSE
         * events stream in across all rounds before the first content byte)
         * so persisting this and rendering it on conversation reload keeps
         * historical bubbles consistent with how they first appeared.
         */
        private final StringBuilder reasoningText = new StringBuilder();

        public synchronized void addRound(StreamAccumulator acc) {
            if (acc == null) return;
            var u = acc.usage;
            if (u != null) {
                hasProviderUsage = true;
                promptTokens += u.promptTokens();
                completionTokens += u.completionTokens();
                totalTokens += u.totalTokens();
                reasoningTokens += u.reasoningTokens();
                cachedTokens += u.cachedTokens();
                cacheCreationTokens += u.cacheCreationTokens();
            }
            addJtokkitRound(acc);
            if (acc.reasoningDetected) reasoningDetected = true;
            reasoningChars += acc.reasoningChars();
            reasoningText.append(acc.reasoningText());
            if (acc.reasoningStartNanos != 0L && turnReasoningStartNanos == 0L) {
                turnReasoningStartNanos = acc.reasoningStartNanos;
            }
            if (acc.firstContentNanos != 0L && turnFirstContentNanos == 0L) {
                turnFirstContentNanos = acc.firstContentNanos;
            }
        }

        private void addJtokkitRound(StreamAccumulator acc) {
            if (acc.promptTokenEstimate == null && acc.completionTokenEstimate == null) return;
            hasJtokkitUsage = true;
            if (acc.promptTokenEstimate != null) {
                jtokkitPromptTokens += acc.promptTokenEstimate.promptTokens();
                noteJtokkitEncoding(acc.promptTokenEstimate.encodingName(), acc.promptTokenEstimate.modelMatched());
            }
            if (acc.completionTokenEstimate != null) {
                jtokkitCompletionTokens += acc.completionTokenEstimate.tokens();
                noteJtokkitEncoding(acc.completionTokenEstimate.encodingName(), acc.completionTokenEstimate.modelMatched());
            }
            if (acc.reasoningTokenEstimate != null) {
                jtokkitReasoningTokens += acc.reasoningTokenEstimate.tokens();
                noteJtokkitEncoding(acc.reasoningTokenEstimate.encodingName(), acc.reasoningTokenEstimate.modelMatched());
            }
            jtokkitTotalTokens = jtokkitPromptTokens + jtokkitCompletionTokens;
        }

        private void noteJtokkitEncoding(String encoding, boolean modelMatched) {
            if (encoding != null && jtokkitEncoding == null) jtokkitEncoding = encoding;
            jtokkitModelMatched &= modelMatched;
        }

        /** Returns the aggregated reasoning text, or {@code null} if nothing was streamed. */
        public synchronized String reasoningText() {
            return reasoningText.isEmpty() ? null : reasoningText.toString();
        }

        /**
         * Milliseconds from the first reasoning chunk of this turn to the
         * first content chunk of this turn. Matches the frontend's live
         * {@code _thinkingDurationMs} measurement so a turn shows the same
         * "Thought for X seconds" value during streaming and after reload.
         *
         * <p>Reasoning-only turns (no content ever streamed) fall back to
         * {@code turnEndNanos} — pass {@code System.nanoTime()} from the
         * caller at end-of-turn. Returns 0 when no reasoning was detected
         * (so the persistence layer can omit the field, matching the
         * pre-feature historical-message rendering).
         */
        public synchronized long reasoningDurationMs(long turnEndNanos) {
            if (turnReasoningStartNanos == 0L) return 0L;
            long endNanos = turnFirstContentNanos != 0L ? turnFirstContentNanos : turnEndNanos;
            long diffNanos = endNanos - turnReasoningStartNanos;
            return diffNanos > 0L ? diffNanos / 1_000_000L : 0L;
        }
    }

    // S1104: tool-call builder; public mutable fields by design (see StreamAccumulator note).
    @SuppressWarnings("java:S1104")
    public static class ToolCallBuilder {
        public String id;
        public String type = TYPE_FUNCTION;
        public String functionName;
        public StringBuilder arguments = new StringBuilder();

        public ToolCall build() {
            return new ToolCall(id, type, new FunctionCall(functionName, arguments.toString()));
        }
    }

    /**
     * Route the {@code chunks} from a single streaming delta into slots of
     * {@code accumulator} (JCLAW-120). OpenAI's spec says each parallel
     * tool_call gets its own {@code index}; chunks of the same call share
     * an index. Some providers — observed with gemini-3-flash-preview via
     * ollama-cloud — emit every parallel call at {@code index=0} (or omit
     * {@code index} entirely, which the primitive-int field defaults to 0).
     * Without correction, the accumulator would merge all five parallel
     * calls into slot 0: concatenated arguments, last-seen function name,
     * one final ToolCall instead of five.
     *
     * <p>Detection signals (any triggers a fresh slot allocation):
     * <ul>
     *   <li>The same index appears twice within this one delta's chunk
     *       list (defensive — covers providers that bundle fully-formed
     *       parallel calls into a single delta).</li>
     *   <li>The incoming chunk's non-null {@code id} differs from the
     *       existing slot's id.</li>
     *   <li>The incoming chunk's non-null {@code function.name} differs
     *       from the existing slot's functionName.</li>
     * </ul>
     * A fresh slot is numbered {@code max(existing) + 1}. Well-behaved
     * providers (OpenRouter, Anthropic) whose chunks share id and name
     * across the call's streaming lifetime stay on the original slot.
     *
     * @param chunks      tool-call delta chunks emitted on the current SSE
     *                    frame
     * @param accumulator mutable per-call accumulator, keyed by slot
     *                    number; mutated in place as chunks are folded in
     */
    public static void mergeToolCallChunks(
            List<ToolCallChunk> chunks,
            Map<Integer, ToolCallBuilder> accumulator) {
        if (chunks == null || chunks.isEmpty()) return;
        var seenInDelta = new HashSet<Integer>();
        for (var tc : chunks) {
            int slot = pickSlotForToolCall(tc, accumulator, seenInDelta);
            seenInDelta.add(slot);
            var builder = accumulator.computeIfAbsent(slot, _ -> new ToolCallBuilder());
            if (tc.id() != null) builder.id = tc.id();
            if (tc.type() != null) builder.type = tc.type();
            if (tc.function() != null) {
                if (tc.function().name() != null) builder.functionName = tc.function().name();
                if (tc.function().arguments() != null) builder.arguments.append(tc.function().arguments());
            }
        }
    }

    /**
     * Pick the destination slot for {@code chunk}. See
     * {@link #mergeToolCallChunks} for the detection rules. Package-visible
     * so unit tests can exercise the decision in isolation without driving
     * a full streaming call.
     */
    static int pickSlotForToolCall(
            ToolCallChunk chunk,
            Map<Integer, ToolCallBuilder> accumulator,
            Set<Integer> seenInDelta) {
        int slot = chunk.index();
        if (seenInDelta.contains(slot)) return nextSlot(accumulator);
        var existing = accumulator.get(slot);
        if (existing != null) {
            if (chunk.id() != null && existing.id != null
                    && !chunk.id().equals(existing.id)) {
                return nextSlot(accumulator);
            }
            if (chunk.function() != null && chunk.function().name() != null
                    && existing.functionName != null
                    && !chunk.function().name().equals(existing.functionName)) {
                return nextSlot(accumulator);
            }
        }
        return slot;
    }

    private static int nextSlot(Map<Integer, ToolCallBuilder> accumulator) {
        return accumulator.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
