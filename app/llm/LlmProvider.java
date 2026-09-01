package llm;

import agents.SystemPromptAssembler;
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
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import llm.LlmTypes.Usage;
import llm.ToolCallChunkMerger.ToolCallBuilder;
import models.MessageRole;
import services.EventLogger;
import utils.HttpKeys;
import utils.LatencyTrace;
import utils.PlayConfig;
import utils.Strings;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
public abstract sealed class LlmProvider implements LlmStreamCarriers
        permits OpenAiProvider, OllamaProvider, OpenRouterProvider, TogetherAiProvider {

    protected static final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};
    // Upper bound on a server-supplied Retry-After. The header is
    // externally controlled, so a hostile or misconfigured upstream can park
    // the calling (virtual) thread for an unbounded interval — cap it so a
    // rogue value can't wedge a request for minutes.
    private static final long RETRY_AFTER_MAX_SECONDS = 60;

    // OpenAI-compatible JSON field names used across request/response (de)serialization
    // and chunk-usage augmentation. Centralised so a typo can't drift one call site
    // off the wire shape without the compiler catching it.
    private static final String JSON_USAGE = "usage";
    private static final String JSON_MODEL = "model";
    private static final String JSON_MESSAGES = "messages";
    private static final String JSON_CONTENT = "content";
    private static final String JSON_ROLE = "role";
    private static final String JSON_TOOL_CALLS = "tool_calls";
    private static final String JSON_TOOL_CALL_ID = "tool_call_id";
    private static final String JSON_FINISH_REASON = "finish_reason";
    private static final String JSON_PROMPT_TOKENS_DETAILS = "prompt_tokens_details";
    private static final String JSON_COMPLETION_TOKENS_DETAILS = "completion_tokens_details";
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
        return top > 0 ? top : readUsageInt(usageObj, JSON_COMPLETION_TOKENS_DETAILS, "reasoning_tokens");
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
        return readUsageInt(usageObj, JSON_PROMPT_TOKENS_DETAILS, "cached_tokens");
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
        // Three spellings, because providers disagree and OpenRouter changed theirs.
        // Anthropic native: top-level cache_creation_input_tokens. Some normalizations
        // nest it as prompt_tokens_details.cache_creation_tokens. OpenRouter today
        // emits prompt_tokens_details.cache_write_tokens.
        //
        // JCLAW-901: only the first two were read, so cache WRITES came back 0 on every
        // OpenRouter call. That is not a provider gap, which is what it was previously
        // recorded as — a live probe on 2026-08-02 (anthropic/claude-haiku-4.5, a 4432-
        // token prompt with cache_control) returned cache_write_tokens=4421 on the cold
        // call and cached_tokens=4421 on the warm one. We were reading the wrong key.
        int top = readUsageInt(usageObj, "cache_creation_input_tokens");
        if (top > 0) return top;
        int nested = readUsageInt(usageObj, JSON_PROMPT_TOKENS_DETAILS, "cache_creation_tokens");
        return nested > 0 ? nested : readUsageInt(usageObj, JSON_PROMPT_TOKENS_DETAILS, "cache_write_tokens");
    }

    /**
     * Extract what the provider says this call actually cost, in USD.
     *
     * <p>Defaults to OpenRouter's top-level {@code usage.cost}, which it returns for
     * every request once {@code usage: {include: true}} is set — that opt-in is
     * unconditional, so this covers OpenAI-, Anthropic-, Gemini- and DeepSeek-routed
     * traffic through OpenRouter alike. Providers that report differently, or not at
     * all, override this.
     *
     * <p>Why measure rather than compute: JClaw already derives cost from token counts
     * times {@code ModelInfo} prices, which is inference about someone else's pricing.
     * The provider's own figure survives price changes, promotional rates and BYOK, and
     * it is what makes a cache saving quotable as money. The same probe above measured
     * $0.00555725 cold against $0.00047310 warm on an identical prompt — an 11.7x
     * difference the token counts alone cannot express without a pricing table.
     *
     * @param usageObj the provider's {@code usage} JSON object
     * @return reported cost in USD, or {@code 0} when the provider reports none
     */
    protected double extractCostUsd(JsonObject usageObj) {
        if (usageObj == null || !usageObj.has("cost") || usageObj.get("cost").isJsonNull()) return 0d;
        try {
            return usageObj.get("cost").getAsDouble();
        } catch (Exception _) {
            return 0d;
        }
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
     * Scrub {@link SystemPromptAssembler#CACHE_BOUNDARY_MARKER} from the first system
     * message. Runs on every outbound request after {@link #applyCacheDirectives}, so a
     * provider whose cache protocol already consumed the marker by splitting on it sees
     * a no-op; every other route would otherwise ship the literal HTML comment, which
     * some models echo back when asked to quote their instructions.
     *
     * <p>Only string content is handled — a block array means a subclass structured the
     * system message itself and owns what the blocks contain.
     */
    private static void stripCacheBoundaryMarker(JsonObject request) {
        if (!request.has(JSON_MESSAGES) || !request.get(JSON_MESSAGES).isJsonArray()) return;
        // JCLAW-976: every message, not only the first system one. The markers are provider
        // protocol; a user turn or a replayed history row carrying one would otherwise ship
        // the literal HTML comment — exactly what this scrub exists to prevent — and give the
        // model a JClaw-looking sentinel to quote back.
        for (var el : request.getAsJsonArray(JSON_MESSAGES)) {
            if (!el.isJsonObject()) continue;
            var msg = el.getAsJsonObject();
            var content = msg.get(JSON_CONTENT);
            if (content == null || !content.isJsonPrimitive()) continue;
            var text = content.getAsString();
            var scrubbed = text
                    .replace(SystemPromptAssembler.CORE_MEMORY_BOUNDARY_MARKER, "")
                    .replace(SystemPromptAssembler.CACHE_BOUNDARY_MARKER, "");
            if (!scrubbed.equals(text)) {
                msg.addProperty(JSON_CONTENT, scrubbed);
            }
        }
    }

    /** The first {@code role=system} message in a serialized request, or null when there is none. */
    protected static JsonObject findFirstSystemMessage(JsonObject request) {
        if (!request.has(JSON_MESSAGES) || !request.get(JSON_MESSAGES).isJsonArray()) return null;
        for (var el : request.getAsJsonArray(JSON_MESSAGES)) {
            if (!el.isJsonObject()) continue;
            var msg = el.getAsJsonObject();
            if (msg.has(JSON_ROLE) && MessageRole.SYSTEM.value.equals(msg.get(JSON_ROLE).getAsString())) {
                return msg;
            }
        }
        return null;
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
            double cost = Math.max(chunk.usage().costUsd(), extractCostUsd(usageObj));
            if (reasoning == chunk.usage().reasoningTokens()
                    && cached == chunk.usage().cachedTokens()
                    && cacheCreation == chunk.usage().cacheCreationTokens()
                    && cost == chunk.usage().costUsd()) {
                return chunk;
            }
            var augmented = new Usage(
                    chunk.usage().promptTokens(),
                    chunk.usage().completionTokens(),
                    chunk.usage().totalTokens(),
                    reasoning,
                    cached,
                    cacheCreation,
                    cost);
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
        // JCLAW-882: the sync dispatch point. Counted before the wire call so a
        // request that ends in an exception still shows as a call the harness
        // decided to make — the NFR is about decisions, not successes.
        LatencyTrace.countLlmCall();
        String responseBody;
        try {
            responseBody = executeWithRetry(HttpKeys.CHAT_COMPLETIONS_PATH, json, timeoutSeconds, channel);
        } catch (RuntimeException e) {
            // JCLAW-1076: the provider has just told us this model can't use
            // tools. Retry once without them rather than failing the turn. The
            // retry carries no tools, so it cannot raise this error again.
            if (!sentTools(request) || !ToolCapabilityMemo.isToolsUnsupported(e)) throw e;
            ToolCapabilityMemo.record(config.name(), model);
            var retry = new ChatRequest(model, messages, List.of(), false, maxTokens, thinkingMode);
            responseBody = executeWithRetry(HttpKeys.CHAT_COMPLETIONS_PATH,
                    serializeRequest(retry), timeoutSeconds, channel);
        }
        // A provider can return a 200 whose body is garbage (truncated JSON, an
        // HTML error page, a missing "choices" array). deserializeResponse then
        // throws a raw JsonSyntaxException / IllegalStateException — which
        // chatWithFailover doesn't catch, so the failover never fires. Wrap it as
        // an LlmException so provider-side garbage-with-200 is a failover trigger.
        ChatResponse response;
        try {
            response = deserializeResponse(responseBody);
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Malformed 200 response from " + config.name(), e);
        }
        noteCachedCall(LatencyTrace.current(), response.usage());
        return response;
    }

    /**
     * Companion half of the JCLAW-882 call counter: a call whose prompt came out
     * of the provider's cache costs a fraction of an uncached one, so the
     * efficiency NFR needs the split rather than the total alone. No-op outside a
     * turn or when the provider reports no cache reads.
     */
    private static void noteCachedCall(LatencyTrace trace, Usage usage) {
        if (trace != null && usage != null && usage.cachedTokens() > 0) {
            trace.noteCachedLlmCall();
        }
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
        // JCLAW-882: the streaming dispatch point. Counted here rather than inside
        // the virtual thread below, because the turn binding lives on the calling
        // thread — the stream thread and the provider's IO thread carry none.
        LatencyTrace.countLlmCall();
        Thread.ofVirtual().name("llm-stream").start(() ->
                streamOnce(model, messages, tools, onChunk, onComplete, onError,
                        maxTokens, thinkingMode, channel, true));
    }

    /**
     * One streaming attempt. {@code mayRetryWithoutTools} is false on the retry,
     * so a tools-unsupported error can be handled at most once (JCLAW-1076).
     */
    @SuppressWarnings("java:S107") // same shape as chatStream, plus the retry latch
    private void streamOnce(String model, List<ChatMessage> messages, List<ToolDef> tools,
                            Consumer<ChatCompletionChunk> onChunk,
                            Runnable onComplete, Consumer<Exception> onError,
                            Integer maxTokens, String thinkingMode, String channel,
                            boolean mayRetryWithoutTools) {
        // Retrying after tokens have reached the user would replay them. A
        // tools-unsupported 400 is a request rejection so nothing has streamed
        // yet, but the latch makes that a guarantee rather than an assumption.
        var emitted = new AtomicBoolean(false);
        Consumer<Throwable> handleFailure = t -> {
            var retryable = mayRetryWithoutTools && !emitted.get()
                    && tools != null && !tools.isEmpty()
                    && ToolCapabilityMemo.isToolsUnsupported(t);
            if (retryable) {
                ToolCapabilityMemo.record(config.name(), model);
                streamOnce(model, messages, List.of(), onChunk, onComplete, onError,
                        maxTokens, thinkingMode, channel, false);
                return;
            }
            onError.accept(t instanceof Exception ex ? ex : new LlmException("Stream error", t));
        };
        try {
            var request = new ChatRequest(model, messages, tools, true, maxTokens, thinkingMode);
            var json = serializeRequest(request);
            OkHttpLlmHttpDriver.streamSse(buildUri(HttpKeys.CHAT_COMPLETIONS_PATH),
                    HttpKeys.BEARER_PREFIX + config.apiKey(), json,
                    data -> {
                        emitted.set(true);
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
                    handleFailure,
                    channel);
        } catch (Exception e) {
            handleFailure.accept(e);
        }
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
        // JCLAW-882: the completion callback below fires on the provider's IO
        // thread, which has no turn binding — capture the dispatching thread's
        // trace so the cache-served half of the counter stays attributable.
        var trace = LatencyTrace.current();

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
                    noteCachedCall(trace, accumulator.usage);
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
        ToolCallChunkMerger.mergeToolCallChunks(delta.toolCalls(), toolCallAccumulator);
        if (choice.finishReason() != null) {
            accumulator.finishReason = choice.finishReason();
        }
    }

    // ─── Embeddings ──────────────────────────────────────────────────────

    /** An embedding plus the model the provider reports having served it with. */
    public record EmbeddingResult(float[] vector, String servedModel) {}

    public float[] embeddings(String model, String input, String channel) {
        return embeddingsDetailed(model, input, channel).vector();
    }

    /**
     * As {@link #embeddings}, but also surfacing the model named in the response
     * (JCLAW-931). Only the probe needs it: a provider that silently substitutes a
     * different embedding model answers 200 with a perfectly good vector, so the vector
     * alone cannot tell an operator whether the model they picked is the one being used.
     */
    public EmbeddingResult embeddingsDetailed(String model, String input, String channel) {
        var request = new EmbeddingRequest(model, input);
        var json = gson.toJson(request);
        var responseBody = executeWithRetry("/embeddings", json, null, channel);
        EmbeddingResponse response;
        try {
            response = gson.fromJson(responseBody, EmbeddingResponse.class);
        } catch (RuntimeException e) {
            // Same garbage-with-200 concern as chat(): a malformed body must
            // surface as an LlmException, not a raw JsonSyntaxException.
            throw new LlmException("Malformed embeddings response from " + config.name(), e);
        }
        if (response == null || response.data() == null || response.data().isEmpty()) {
            throw new LlmException("Empty embedding response");
        }
        return new EmbeddingResult(response.data().getFirst().embedding(), response.model());
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

    /** Whether this request actually put a tools array on the wire — the precondition for the JCLAW-1076 retry. */
    private boolean sentTools(ChatRequest request) {
        return request.tools() != null && !request.tools().isEmpty()
                && modelSupportsTools(request.model());
    }

    /**
     * True unless the model is configured as unable to call tools (JCLAW-1074).
     *
     * <p>Sending a {@code tools} array to a chat-only model is a hard failure,
     * not a degradation — Ollama answers {@code 400 "<model> does not support
     * tools"} and the turn produces nothing. Withholding the array instead lets
     * the model answer normally without them.
     *
     * <p>Unknown resolves to true, which is the opposite of
     * {@code modelSupportsThinking}'s default and deliberate: an unlisted model
     * here means "we have no capability data", and assuming no tools would
     * silently disarm every agent on a provider that publishes none.
     */
    protected boolean modelSupportsTools(String modelId) {
        if (modelId == null) return true;
        // A model that already rejected tools this run is skipped up front, so
        // the wasted first call happens once rather than every turn (JCLAW-1076).
        if (ToolCapabilityMemo.isKnownIncapable(config().name(), modelId)) return false;
        var models = config().models();
        if (models == null) return true;
        return models.stream()
                .filter(m -> modelId.equals(m.id()))
                .findFirst()
                .map(ModelInfo::toolCallingSupported)
                .orElse(true);
    }

    protected String serializeRequest(ChatRequest request) {
        var obj = new JsonObject();
        obj.addProperty(JSON_MODEL, request.model());
        obj.add(JSON_MESSAGES, serializeMessages(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty() && modelSupportsTools(request.model())) {
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
        stripCacheBoundaryMarker(obj);
        return gson.toJson(obj);
    }

    private JsonArray serializeMessages(List<ChatMessage> messages) {
        var array = new JsonArray();
        for (var msg : messages) {
            var obj = new JsonObject();
            obj.addProperty(JSON_ROLE, msg.role());
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
        var role = msgObj.get(JSON_ROLE).getAsString();
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
            boolean alreadyBackedOff = false;
            try {
                var outcome = attemptRequest(uri, auth, json, timeout, channel, attempt);
                if (outcome.body() != null) return outcome.body();
                if (outcome.error() != null) lastException = outcome.error();
                alreadyBackedOff = outcome.alreadyBackedOff();
            } catch (LlmException e) {
                throw e;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new LlmException("Interrupted during request to " + config.name(), ie);
            } catch (Exception e) {
                lastException = e;
            }

            // The 429 path already parked for the (clamped) Retry-After, so
            // don't stack the standard backoff on top of it.
            if (attempt < MAX_RETRIES && !alreadyBackedOff) {
                backoffBeforeRetry(attempt);
            }
        }

        throw new LlmException("All retries exhausted for " + config.name(), lastException);
    }

    /**
     * Outcome of a single attempt: either {@code body} is a success body, or {@code error}
     * carries a retryable error. {@code alreadyBackedOff} is true when the attempt already
     * waited (the 429 path parks for Retry-After itself), so {@code executeWithRetry} must
     * not stack the standard backoff on top.
     */
    private record AttemptOutcome(String body, Exception error, boolean alreadyBackedOff) {}

    /**
     * Error codes on a 429 that mean the balance is gone rather than the rate is too
     * high (JCLAW-929). Waiting never clears these, so retrying only burns the
     * remaining attempts and hammers the provider — one exhausted-balance backfill
     * of 616 rows spent four attempts each, about 11 seconds per row, embedding none.
     *
     * <p>Matched against the error {@code code}/{@code type} field, never free text:
     * OpenAI separates {@code rate_limit_exceeded} from {@code insufficient_quota} by
     * code alone, and its rate-limit copy has itself used the word "quota". Misreading
     * a transient limit as permanent turns a recoverable call into a hard failure, so
     * this list stays narrow and additions need the same evidence.
     */
    private static final List<String> PERMANENT_QUOTA_CODES =
            List.of("insufficient_quota", "credit_balance_exhausted");

    /**
     * Whether a 429 body identifies a permanently exhausted balance. Parsed rather
     * than substring-matched so a code only counts in the {@code code}/{@code type}
     * position; an unparseable body is treated as retryable, preserving today's
     * behavior when a provider returns something unexpected.
     */
    private static boolean isPermanentQuotaError(String body) {
        if (body == null || body.isBlank()) return false;
        try {
            var root = JsonParser.parseString(body);
            if (!root.isJsonObject()) return false;
            var error = root.getAsJsonObject().getAsJsonObject("error");
            if (error == null) return false;
            for (var field : List.of("code", "type")) {
                var el = error.get(field);
                if (el != null && el.isJsonPrimitive()
                        && PERMANENT_QUOTA_CODES.contains(el.getAsString())) {
                    return true;
                }
            }
        } catch (Exception _) {
            return false;
        }
        return false;
    }

    /**
     * Execute one request attempt. Returns a body on 200; on 429 parks for the
     * (clamped) Retry-After and returns an error outcome flagged already-backed-off so the
     * caller records a 429 {@link LlmException} without double-waiting; throws on 4xx; or
     * returns an error outcome on 5xx for the caller to retry.
     */
    private AttemptOutcome attemptRequest(URI uri, String auth, String json, Duration timeout,
                                          String channel, int attempt) throws InterruptedException, IOException {
        var reply = OkHttpLlmHttpDriver.send(uri, auth, json, timeout, channel);

        if (reply.statusCode() == 200) return new AttemptOutcome(reply.body(), null, false);

        if (reply.statusCode() == 429) {
            if (isPermanentQuotaError(reply.body())) {
                throw new LlmException("HTTP 429 from %s (permanent, not retried): %s".formatted(
                        config.name(), sanitizeErrorBody(reply.body(), config.apiKey())));
            }
            var defaultBackoff = backoffMsFor(attempt) / 1000;
            var requested = reply.retryAfterSeconds().orElse(defaultBackoff);
            var retryAfter = Math.min(requested, RETRY_AFTER_MAX_SECONDS);
            if (retryAfter < requested) {
                EventLogger.warn("llm", "Retry-After from %s clamped %ds → %ds".formatted(
                        config.name(), requested, retryAfter));
            }
            EventLogger.warn("llm", "Rate limited by %s, retrying after %ds".formatted(config.name(), retryAfter));
            parkForMillis(retryAfter * 1000);
            return new AttemptOutcome(null,
                    new LlmException("HTTP 429 from %s: rate limited (retry-after %ds)".formatted(
                            config.name(), retryAfter)),
                    true);
        }

        if (reply.statusCode() >= 400 && reply.statusCode() < 500) {
            throw new LlmException("HTTP %d from %s: %s".formatted(
                    reply.statusCode(), config.name(), sanitizeErrorBody(reply.body(), config.apiKey())));
        }

        return new AttemptOutcome(null, new LlmException("HTTP %d from %s: %s".formatted(
                reply.statusCode(), config.name(), sanitizeErrorBody(reply.body(), config.apiKey()))), false);
    }

    /**
     * JCLAW-730: scrub the provider secret and cap the length of an upstream
     * error body before it enters an {@link LlmException} message — which flows
     * on into event logs and the UI. Provider 4xx/5xx bodies are
     * attacker-influenceable and can be large or echo the request (including the
     * API key), so truncating and redacting the key keeps them from flooding the
     * logs or leaking the credential downstream.
     */
    private static String sanitizeErrorBody(String body, String secret) {
        return Strings.redactAndTruncate(body, secret);
    }

    /** Retry backoff for {@code attempt} in ms — the {@link #BACKOFF_MS} schedule
     *  scaled by {@code llm.retry.backoff-percent} (default 100 = the full 1s/2s/4s
     *  waits). {@code %test} sets it to 0 so functional tests that hit an unreachable
     *  provider don't sleep through the backoff: the retry COUNT is unchanged
     *  ({@link #MAX_RETRIES}), only the delay collapses. */
    private static long backoffMsFor(int attempt) {
        var base = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
        return base * PlayConfig.intOr("llm.retry.backoff-percent", 100) / 100;
    }

    private void backoffBeforeRetry(int attempt) {
        try {
            var backoff = backoffMsFor(attempt);
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
     * {@link #extractCacheCreationTokens}, {@link #extractCostUsd}). Subclass
     * overrides are honored, so provider-specific JSON paths are handled correctly.
     *
     * @param usageObj the provider's {@code usage} JSON object
     * @return the parsed {@link Usage} record with all token-count categories and
     *         the provider-reported cost populated
     */
    public Usage parseUsage(JsonObject usageObj) {
        return new Usage(
                readUsageInt(usageObj, "prompt_tokens"),
                readUsageInt(usageObj, "completion_tokens"),
                readUsageInt(usageObj, "total_tokens"),
                extractReasoningTokens(usageObj),
                extractCachedTokens(usageObj),
                extractCacheCreationTokens(usageObj),
                extractCostUsd(usageObj));
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
