package llm;

import com.google.gson.*;
import llm.LlmTypes.*;
import services.EventLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Abstract base for LLM provider integrations. Implements shared OpenAI-compatible
 * HTTP, retry, streaming, and serialization logic. Subclasses override template methods
 * to handle provider-specific differences (reasoning params, response parsing, etc.).
 */
public abstract sealed class LlmProvider permits OpenAiProvider, OllamaProvider, OpenRouterProvider {

    protected static final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};

    protected final ProviderConfig config;

    /**
     * Declarative mapping from provider-name substring to constructor.
     * Adding a new provider is a single-line entry here instead of
     * modifying a conditional chain in {@code ProviderRegistry}.
     */
    private static final Map<String, Function<ProviderConfig, LlmProvider>> PROVIDER_FACTORIES = Map.of(
            "openrouter", OpenRouterProvider::new,
            "ollama", OllamaProvider::new
    );

    protected LlmProvider(ProviderConfig config) {
        this.config = config;
    }

    public ProviderConfig config() { return config; }

    /**
     * Factory method: creates the right {@link LlmProvider} subclass based on
     * the provider name in the config. Matches against known substrings
     * declaratively via {@link #PROVIDER_FACTORIES}; falls back to
     * {@link OpenAiProvider} for unknown/standard OpenAI-compatible providers.
     */
    public static LlmProvider forConfig(ProviderConfig config) {
        var lowerName = config.name().toLowerCase();
        for (var entry : PROVIDER_FACTORIES.entrySet()) {
            if (lowerName.contains(entry.getKey())) {
                return entry.getValue().apply(config);
            }
        }
        return new OpenAiProvider(config);
    }

    // ─── Template methods (override in subclasses) ───────────────────────

    /** Add provider-specific reasoning/thinking parameters to the request JSON. */
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        // Default: no reasoning support
    }

    /** Explicitly disable reasoning for models that think by default. Called when thinkingMode is off. */
    protected void disableReasoning(JsonObject request) {
        // Default: no action (most providers don't need explicit disable)
    }

    /**
     * Extract reasoning text from a streaming chunk delta.
     * Called for each chunk during streaming. Return null if no reasoning in this chunk.
     */
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        return null;
    }

    /**
     * Extract reasoning token count from a usage JSON object.
     * Called when parsing the usage block in responses.
     */
    protected int extractReasoningTokens(JsonObject usageObj) {
        return 0;
    }

    /**
     * Extract the count of prompt tokens that were served from a provider-side
     * prompt cache (cache <em>reads</em>). Defaults to the OpenAI-compat path
     * ({@code usage.prompt_tokens_details.cached_tokens}) which is also what
     * OpenRouter emits (with {@code usage: {include: true}}). Providers that
     * report differently — or not at all — override this.
     */
    protected int extractCachedTokens(JsonObject usageObj) {
        if (usageObj.has("prompt_tokens_details")
                && !usageObj.get("prompt_tokens_details").isJsonNull()) {
            var details = usageObj.getAsJsonObject("prompt_tokens_details");
            if (details.has("cached_tokens") && !details.get("cached_tokens").isJsonNull()) {
                return details.get("cached_tokens").getAsInt();
            }
        }
        return 0;
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
     */
    protected int extractCacheCreationTokens(JsonObject usageObj) {
        // Anthropic/OpenRouter: top-level cache_creation_input_tokens.
        if (usageObj.has("cache_creation_input_tokens")
                && !usageObj.get("cache_creation_input_tokens").isJsonNull()) {
            return usageObj.get("cache_creation_input_tokens").getAsInt();
        }
        // Some normalizations nest it under prompt_tokens_details.
        if (usageObj.has("prompt_tokens_details")
                && !usageObj.get("prompt_tokens_details").isJsonNull()) {
            var details = usageObj.getAsJsonObject("prompt_tokens_details");
            if (details.has("cache_creation_tokens") && !details.get("cache_creation_tokens").isJsonNull()) {
                return details.get("cache_creation_tokens").getAsInt();
            }
        }
        return 0;
    }

    /**
     * Add provider-specific prompt-caching directives to the outgoing request JSON.
     * Called at the end of serializeRequest, after messages and reasoning have been
     * attached. Subclasses add things like Anthropic's {@code cache_control}
     * breakpoints (via OpenRouter) or Ollama's {@code keep_alive}. Default is no-op
     * because OpenAI and most OpenAI-compat providers cache automatically.
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
     */
    private ChatCompletionChunk augmentChunkUsage(ChatCompletionChunk chunk, String rawData) {
        if (chunk.usage() == null) return chunk;
        try {
            var root = JsonParser.parseString(rawData).getAsJsonObject();
            if (!root.has("usage") || root.get("usage").isJsonNull()) return chunk;
            var usageObj = root.getAsJsonObject("usage");
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
                             Integer maxTokens, String thinkingMode) {
        return chat(model, messages, tools, maxTokens, thinkingMode, null);
    }

    /**
     * Synchronous chat with an optional custom timeout (seconds).
     * Pass null for the default 180s timeout.
     */
    public ChatResponse chat(String model, List<ChatMessage> messages, List<ToolDef> tools,
                             Integer maxTokens, String thinkingMode, Integer timeoutSeconds) {
        var request = new ChatRequest(model, messages, tools, false, maxTokens, thinkingMode);
        var json = serializeRequest(request);
        var responseBody = executeWithRetry("/chat/completions", json, timeoutSeconds);
        return deserializeResponse(responseBody);
    }

    // ─── Streaming chat ──────────────────────────────────────────────────

    public void chatStream(String model, List<ChatMessage> messages, List<ToolDef> tools,
                           Consumer<ChatCompletionChunk> onChunk,
                           Runnable onComplete, Consumer<Exception> onError,
                           Integer maxTokens, String thinkingMode) {
        Thread.ofVirtual().start(() -> {
            try {
                var request = new ChatRequest(model, messages, tools, true, maxTokens, thinkingMode);
                var json = serializeRequest(request);
                var httpReq = buildRequest("/chat/completions", json);

                var response = utils.HttpClients.LLM.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    var body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    onError.accept(new LlmException("HTTP %d: %s".formatted(response.statusCode(), body)));
                    return;
                }

                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            var data = line.substring(6).strip();
                            if ("[DONE]".equals(data)) break;
                            try {
                                var chunk = gson.fromJson(data, ChatCompletionChunk.class);
                                if (chunk != null) onChunk.accept(augmentChunkUsage(chunk, data));
                            } catch (JsonSyntaxException _) {
                                // Skip malformed chunks
                            }
                        }
                    }
                }
                onComplete.run();
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    // ─── Streaming with accumulation ─────────────────────────────────────

    public StreamAccumulator chatStreamAccumulate(String model, List<ChatMessage> messages,
                                                   List<ToolDef> tools, Consumer<String> onToken,
                                                   Consumer<String> onReasoning,
                                                   Integer maxTokens, String thinkingMode) {
        var accumulator = new StreamAccumulator();
        var contentBuilder = new StringBuilder();
        var toolCallAccumulator = new java.util.HashMap<Integer, ToolCallBuilder>();

        chatStream(model, messages, tools,
                chunk -> {
                    if (chunk.usage() != null) {
                        accumulator.usage = chunk.usage();
                        if (chunk.usage().reasoningTokens() > 0) {
                            accumulator.reasoningDetected = true;
                            accumulator.reasoningTokens = chunk.usage().reasoningTokens();
                        }
                    }
                    for (var choice : chunk.choices()) {
                        var delta = choice.delta();
                        if (delta.content() != null) {
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
                        if (delta.toolCalls() != null) {
                            for (var tc : delta.toolCalls()) {
                                var builder = toolCallAccumulator.computeIfAbsent(
                                        tc.index(), _ -> new ToolCallBuilder());
                                if (tc.id() != null) builder.id = tc.id();
                                if (tc.type() != null) builder.type = tc.type();
                                if (tc.function() != null) {
                                    if (tc.function().name() != null) builder.functionName = tc.function().name();
                                    if (tc.function().arguments() != null) builder.arguments.append(tc.function().arguments());
                                }
                            }
                        }
                        if (choice.finishReason() != null) {
                            accumulator.finishReason = choice.finishReason();
                        }
                    }
                },
                () -> {
                    accumulator.content = contentBuilder.toString();
                    accumulator.toolCalls = toolCallAccumulator.values().stream()
                            .map(ToolCallBuilder::build).toList();
                    accumulator.markComplete();
                },
                e -> {
                    accumulator.error = e;
                    accumulator.markComplete();
                },
                maxTokens, thinkingMode);

        return accumulator;
    }

    // ─── Embeddings ──────────────────────────────────────────────────────

    public float[] embeddings(String model, String input) {
        var request = new EmbeddingRequest(model, input);
        var json = gson.toJson(request);
        var responseBody = executeWithRetry("/embeddings", json);
        var response = gson.fromJson(responseBody, EmbeddingResponse.class);
        if (response.data() == null || response.data().isEmpty()) {
            throw new LlmException("Empty embedding response");
        }
        return response.data().getFirst().embedding();
    }

    // ─── Failover (static utility) ───────────────────────────────────────

    public static ChatResponse chatWithFailover(LlmProvider primary, LlmProvider secondary,
                                                 String model, List<ChatMessage> messages,
                                                 List<ToolDef> tools, Integer maxTokens,
                                                 String thinkingMode) {
        try {
            return primary.chat(model, messages, tools, maxTokens, thinkingMode);
        } catch (LlmException e) {
            if (secondary != null) {
                EventLogger.warn("llm", "Failing over from %s to %s: %s"
                        .formatted(primary.config().name(), secondary.config().name(), e.getMessage()));
                return secondary.chat(model, messages, tools, maxTokens, thinkingMode);
            }
            throw e;
        }
    }

    // ─── Shared internals ────────────────────────────────────────────────

    protected String serializeRequest(ChatRequest request) {
        var obj = new JsonObject();
        obj.addProperty("model", request.model());
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
                obj.addProperty("content", s);
            } else if (msg.content() != null) {
                obj.add("content", gson.toJsonTree(msg.content()));
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                obj.add("tool_calls", gson.toJsonTree(msg.toolCalls()));
            }
            if (msg.toolCallId() != null) {
                obj.addProperty("tool_call_id", msg.toolCallId());
            }
            array.add(obj);
        }
        return array;
    }

    protected ChatResponse deserializeResponse(String json) {
        var obj = JsonParser.parseString(json).getAsJsonObject();
        var id = obj.has("id") ? obj.get("id").getAsString() : null;
        var model = obj.has("model") ? obj.get("model").getAsString() : null;

        var choices = new ArrayList<Choice>();
        if (obj.has("choices")) {
            for (var choiceEl : obj.getAsJsonArray("choices")) {
                var choiceObj = choiceEl.getAsJsonObject();
                var index = choiceObj.get("index").getAsInt();
                var finishReason = choiceObj.has("finish_reason") && !choiceObj.get("finish_reason").isJsonNull()
                        ? choiceObj.get("finish_reason").getAsString() : null;
                var msgObj = choiceObj.getAsJsonObject("message");
                var message = deserializeMessage(msgObj);
                choices.add(new Choice(index, message, finishReason));
            }
        }

        Usage usage = null;
        if (obj.has("usage") && !obj.get("usage").isJsonNull()) {
            usage = parseUsage(obj.getAsJsonObject("usage"));
        }

        return new ChatResponse(id, model, choices, usage);
    }

    private ChatMessage deserializeMessage(JsonObject msgObj) {
        var role = msgObj.get("role").getAsString();
        String content = null;
        if (msgObj.has("content") && !msgObj.get("content").isJsonNull()) {
            content = msgObj.get("content").getAsString();
        }

        List<ToolCall> toolCalls = null;
        if (msgObj.has("tool_calls") && !msgObj.get("tool_calls").isJsonNull()) {
            toolCalls = new ArrayList<>();
            for (var tcEl : msgObj.getAsJsonArray("tool_calls")) {
                var tcObj = tcEl.getAsJsonObject();
                var tcId = tcObj.get("id").getAsString();
                var tcType = tcObj.has("type") ? tcObj.get("type").getAsString() : "function";
                var fnObj = tcObj.getAsJsonObject("function");
                var fnName = fnObj.get("name").getAsString();
                var fnArgs = fnObj.get("arguments").getAsString();
                toolCalls.add(new ToolCall(tcId, tcType, new FunctionCall(fnName, fnArgs)));
            }
        }

        String toolCallId = null;
        if (msgObj.has("tool_call_id") && !msgObj.get("tool_call_id").isJsonNull()) {
            toolCallId = msgObj.get("tool_call_id").getAsString();
        }

        return new ChatMessage(role, content, toolCalls, toolCallId);
    }

    protected HttpRequest buildRequest(String path, String json) {
        return buildRequest(path, json, null);
    }

    protected HttpRequest buildRequest(String path, String json, Integer timeoutSeconds) {
        var url = config.baseUrl().endsWith("/")
                ? config.baseUrl() + path.substring(1)
                : config.baseUrl() + path;

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofSeconds(timeoutSeconds != null ? timeoutSeconds : 180))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    protected String executeWithRetry(String path, String json) {
        return executeWithRetry(path, json, null);
    }

    protected String executeWithRetry(String path, String json, Integer timeoutSeconds) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                var httpReq = buildRequest(path, json, timeoutSeconds);
                var response = utils.HttpClients.LLM.send(httpReq, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) return response.body();

                if (response.statusCode() == 429) {
                    var defaultBackoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)] / 1000;
                    var retryAfter = response.headers().firstValue("Retry-After")
                            .map(v -> { try { return Long.parseLong(v); } catch (NumberFormatException _) { return defaultBackoff; } })
                            .orElse(defaultBackoff);
                    EventLogger.warn("llm", "Rate limited by %s, retrying after %ds".formatted(config.name(), retryAfter));
                    Thread.sleep(retryAfter * 1000);
                    continue;
                }

                if (response.statusCode() >= 400 && response.statusCode() < 500) {
                    throw new LlmException("HTTP %d from %s: %s".formatted(
                            response.statusCode(), config.name(), response.body()));
                }

                lastException = new LlmException("HTTP %d from %s: %s".formatted(
                        response.statusCode(), config.name(), response.body()));

            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
            }

            if (attempt < MAX_RETRIES) {
                try {
                    var backoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
                    EventLogger.warn("llm", "Retry %d/%d for %s after %dms"
                            .formatted(attempt + 1, MAX_RETRIES, config.name(), backoff));
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted during retry", ie);
                }
            }
        }

        throw new LlmException("All retries exhausted for " + config.name(), lastException);
    }

    // ─── Usage parsing ────────────────────────────────────────────────────

    /**
     * Instance method: parse a usage JSON object using this provider's template
     * methods ({@link #extractReasoningTokens}, {@link #extractCachedTokens},
     * {@link #extractCacheCreationTokens}). Subclass overrides are honoured,
     * so provider-specific JSON paths are handled correctly.
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

    /**
     * Static convenience method for callers that don't have a provider instance
     * (e.g. tests). Uses the base-class extraction logic — equivalent to
     * calling {@link #parseUsage} on a vanilla {@link OpenAiProvider}.
     *
     * <p>Handles all known provider shapes:
     * <ul>
     *   <li>OpenAI: {@code prompt_tokens_details.cached_tokens},
     *       {@code completion_tokens_details.reasoning_tokens}</li>
     *   <li>Anthropic/OpenRouter: {@code cache_creation_input_tokens} (top-level),
     *       {@code prompt_tokens_details.cache_creation_tokens} (nested fallback)</li>
     * </ul>
     */
    public static Usage parseUsageBlock(JsonObject usageObj) {
        // Base-class extractReasoningTokens returns 0, so replicate the extended
        // static extraction that also checks completion_tokens_details. This keeps
        // the static path backward-compatible with tests while the instance path
        // is the preferred entry point for production code.
        int reasoningTokens = 0;
        if (usageObj.has("reasoning_tokens") && !usageObj.get("reasoning_tokens").isJsonNull()) {
            reasoningTokens = usageObj.get("reasoning_tokens").getAsInt();
        }
        if (reasoningTokens == 0 && usageObj.has("completion_tokens_details")
                && !usageObj.get("completion_tokens_details").isJsonNull()) {
            var details = usageObj.getAsJsonObject("completion_tokens_details");
            if (details.has("reasoning_tokens") && !details.get("reasoning_tokens").isJsonNull()) {
                reasoningTokens = details.get("reasoning_tokens").getAsInt();
            }
        }

        // For cached and cache-creation tokens, instantiate a temporary base provider
        // to reuse the template methods without duplicating their logic.
        var baseConfig = new ProviderConfig("_static", "", "", List.of());
        var base = new OpenAiProvider(baseConfig);
        int cachedTokens = base.extractCachedTokens(usageObj);
        int cacheCreationTokens = base.extractCacheCreationTokens(usageObj);

        return new Usage(
                usageObj.has("prompt_tokens") ? usageObj.get("prompt_tokens").getAsInt() : 0,
                usageObj.has("completion_tokens") ? usageObj.get("completion_tokens").getAsInt() : 0,
                usageObj.has("total_tokens") ? usageObj.get("total_tokens").getAsInt() : 0,
                reasoningTokens, cachedTokens, cacheCreationTokens);
    }

    // ─── Shared helper classes ───────────────────────────────────────────

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
         * needed (see {@code AgentRunner.emitUsageAndComplete}). Appending is
         * synchronized because the streaming callback fires on a virtual thread
         * distinct from the consumer reading {@link #reasoningChars()}.
         */
        private final StringBuilder reasoningTextBuffer = new StringBuilder();
        public volatile Usage usage;
        // Wall-clock nanoTime at first and latest reasoning chunk. Both remain 0
        // when the model emitted no reasoning. reasoningEndNanos is updated on
        // every append so it naturally captures "end of reasoning phase" — the
        // gap between the last reasoning chunk and the first content chunk is
        // within one provider tick, accurate enough for a user-visible seconds
        // label. See AgentRunner.buildUsageJson for the ms conversion.
        public volatile long reasoningStartNanos = 0L;
        public volatile long reasoningEndNanos = 0L;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        public synchronized void appendReasoningText(String text) {
            if (text == null) return;
            reasoningTextBuffer.append(text);
            var now = System.nanoTime();
            if (reasoningStartNanos == 0L) reasoningStartNanos = now;
            reasoningEndNanos = now;
        }

        /** Character count of accumulated reasoning text. Used for token estimation. */
        public synchronized int reasoningChars() { return reasoningTextBuffer.length(); }

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
            return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
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
     * <em>timing</em> remains a per-round concern captured on the first
     * round's {@link StreamAccumulator}; see {@code AgentRunner.buildUsageJson}
     * for how the two dimensions combine in the emitted usage JSON.
     *
     * <p>See JCLAW-76 for the accounting defect this class fixes.
     */
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

        public void addRound(StreamAccumulator acc) {
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
            if (acc.reasoningDetected) reasoningDetected = true;
            reasoningChars += acc.reasoningChars();
        }
    }

    protected static class ToolCallBuilder {
        String id;
        String type = "function";
        String functionName;
        StringBuilder arguments = new StringBuilder();

        ToolCall build() {
            return new ToolCall(id, type, new FunctionCall(functionName, arguments.toString()));
        }
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
