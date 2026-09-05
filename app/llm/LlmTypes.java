package llm;

import models.MessageRole;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable record types for the OpenAI-compatible chat completions API.
 */
public final class LlmTypes {

    private LlmTypes() {}

    // --- Request types ---

    public record ChatRequest(
            String model,
            List<ChatMessage> messages,
            List<ToolDef> tools,
            boolean stream,
            Integer maxTokens,
            String thinkingMode
    ) {}

    public record ChatMessage(
            String role,
            Object content,
            List<ToolCall> toolCalls,
            String toolCallId,
            String toolName
    ) {
        public static ChatMessage system(String text) {
            return new ChatMessage(MessageRole.SYSTEM.value, text, null, null, null);
        }

        public static ChatMessage user(String text) {
            return new ChatMessage(MessageRole.USER.value, text, null, null, null);
        }

        public static ChatMessage assistant(String text) {
            return new ChatMessage(MessageRole.ASSISTANT.value, text, null, null, null);
        }

        public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
            return new ChatMessage(MessageRole.ASSISTANT.value, text, toolCalls, null, null);
        }

        // toolName is the function name from the matching tool_call. Some adapters
        // require it on the tool-result message — Ollama Cloud's Gemini bridge in
        // particular rejects with HTTP 400 "function_response.name: Name cannot
        // be empty" when it's missing. OpenAI's own API tolerates a "name" field
        // here, so emitting it unconditionally is safe across providers.
        public static ChatMessage toolResult(String toolCallId, String toolName, String content) {
            return new ChatMessage(MessageRole.TOOL.value, content, null, toolCallId, toolName);
        }
    }

    public record ToolDef(
            String type,
            FunctionDef function
    ) {
        public static ToolDef of(String name, String description, Map<String, Object> parameters) {
            return new ToolDef("function", new FunctionDef(name, description, parameters));
        }
    }

    public record FunctionDef(
            String name,
            String description,
            Map<String, Object> parameters
    ) {}

    public record ToolCall(
            String id,
            String type,
            FunctionCall function
    ) {}

    public record FunctionCall(
            String name,
            String arguments
    ) {}

    // --- Response types ---

    public record ChatResponse(
            String id,
            String model,
            List<Choice> choices,
            Usage usage
    ) {}

    public record Choice(
            int index,
            ChatMessage message,
            String finishReason
    ) {}

    /**
     * Token-count snapshot returned by the provider for a single completion.
     *
     * <p>Semantics across OpenAI-compat providers (OpenAI, OpenRouter):
     * <ul>
     *   <li>{@code promptTokens} is the <em>total</em> input count — uncached input
     *       <em>plus</em> cached reads <em>plus</em> cache writes.</li>
     *   <li>{@code cachedTokens} (cache <em>reads</em>) and {@code cacheCreationTokens}
     *       (cache <em>writes</em>) are disjoint subsets of {@code promptTokens}.</li>
     *   <li>{@code uncachedInput = promptTokens - cachedTokens - cacheCreationTokens}.</li>
     * </ul>
     *
     * <p>The three categories are priced differently: uncached input at base rate,
     * cache reads at ~0.1× (Anthropic) / ~0.5× (OpenAI), cache writes at ~1.25× (Anthropic
     * 5-min TTL). {@code cacheCreationTokens} is typically {@code 0} for OpenAI routes,
     * which cache implicitly and don't charge a write premium.
     *
     * @param promptTokens         total input tokens — uncached input plus cached
     *                             reads plus cache writes
     * @param completionTokens     tokens the model produced in its reply
     * @param totalTokens          {@code promptTokens + completionTokens}
     *                             (provider-reported, may not always exactly equal
     *                             the sum)
     * @param reasoningTokens      hidden chain-of-thought tokens billed separately
     *                             on thinking-capable models; {@code 0} otherwise
     * @param cachedTokens         cache <em>reads</em> — subset of
     *                             {@code promptTokens}
     * @param cacheCreationTokens  cache <em>writes</em> — subset of
     *                             {@code promptTokens}; typically {@code 0} on
     *                             OpenAI routes
     * @param costUsd              what the provider says the call cost, in USD, or
     *                             {@code 0} when it reports none (JCLAW-901). Measured
     *                             rather than derived from tokens times a price table,
     *                             so it survives pricing changes and BYOK
     */
    public record Usage(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int reasoningTokens,
            int cachedTokens,
            int cacheCreationTokens,
            double costUsd,
            ProviderMetrics providerMetrics
    ) {
        /** Normalise null to {@link ProviderMetrics#EMPTY} so readers never null-check. */
        public Usage {
            providerMetrics = providerMetrics == null ? ProviderMetrics.EMPTY : providerMetrics;
        }

        /** Back-compat for the call sites that predate JCLAW-1147's provider metrics. */
        public Usage(int promptTokens, int completionTokens, int totalTokens,
                     int reasoningTokens, int cachedTokens, int cacheCreationTokens, double costUsd) {
            this(promptTokens, completionTokens, totalTokens, reasoningTokens,
                    cachedTokens, cacheCreationTokens, costUsd, ProviderMetrics.EMPTY);
        }

        /**
         * Back-compat for the providers and tests that report no cost — same pattern as
         * {@code ConversationQueue.QueuedMessage}, so adding the component did not have
         * to touch twenty-odd existing construction sites.
         */
        public Usage(int promptTokens, int completionTokens, int totalTokens,
                     int reasoningTokens, int cachedTokens, int cacheCreationTokens) {
            this(promptTokens, completionTokens, totalTokens, reasoningTokens,
                    cachedTokens, cacheCreationTokens, 0d, ProviderMetrics.EMPTY);
        }
    }

    /**
     * Numeric telemetry a provider reports that the OpenAI usage schema has no slot for
     * (JCLAW-1147). Keys are the provider's own dotted JSON path — {@code
     * cost_details.upstream_inference_cost} — so two providers naming different things
     * the same never collide on one turn, since a turn only ever talks to one provider.
     *
     * <p>Values are {@code double} because the fields span both fractional costs and
     * integer counts/durations; every one observed so far is additive per round, which
     * is what makes {@link #plus} a sum rather than a merge policy per key. A
     * non-additive field (a flag, a ratio, a high-water mark) must not be collected
     * here — it would be silently wrong once a turn runs more than one round.
     */
    public record ProviderMetrics(Map<String, Double> values) {

        public static final ProviderMetrics EMPTY = new ProviderMetrics(Map.of());

        public ProviderMetrics {
            values = values == null || values.isEmpty() ? Map.of() : Map.copyOf(values);
        }

        public boolean isEmpty() {
            return values.isEmpty();
        }

        /** Key-wise sum, for folding each LLM round of a turn into the turn total. */
        public ProviderMetrics plus(ProviderMetrics other) {
            if (other == null || other.isEmpty()) return this;
            if (isEmpty()) return other;
            var merged = new LinkedHashMap<>(values);
            other.values().forEach((k, v) -> merged.merge(k, v, Double::sum));
            return new ProviderMetrics(merged);
        }
    }

    // --- Streaming types ---

    public record ChatCompletionChunk(
            String id,
            String model,
            List<ChunkChoice> choices,
            Usage usage
    ) {}

    public record ChunkChoice(
            int index,
            ChunkDelta delta,
            String finishReason
    ) {}

    /**
     * One streamed delta. Three different reasoning shapes exist in the wild and
     * each provider picks the one its server emits via
     * {@code LlmProvider.extractReasoningFromDelta}:
     *
     * <ul>
     *   <li>{@code reasoning} — plain string (Ollama, Together)</li>
     *   <li>{@code reasoningContent} — deserialized from {@code reasoning_content}
     *       by the LOWER_CASE_WITH_UNDERSCORES naming policy. What
     *       OpenAI-compatible servers emit: LM Studio, vLLM, SGLang (JCLAW-850)</li>
     *   <li>{@code reasoningDetails} — structured array (OpenRouter)</li>
     * </ul>
     */
    public record ChunkDelta(
            String role,
            String content,
            List<ToolCallChunk> toolCalls,
            String reasoning,
            String reasoningContent,
            List<ReasoningDetail> reasoningDetails
    ) {}

    public record ReasoningDetail(
            String type,
            String text
    ) {}

    public record ToolCallChunk(
            int index,
            String id,
            String type,
            FunctionCall function
    ) {}

    // --- Embedding types ---

    public record EmbeddingRequest(
            String model,
            Object input
    ) {}

    /**
     * @param model the model the provider says it actually served. Not always the one
     *              asked for: LM Studio ignores the requested model on /v1/embeddings and
     *              serves whichever embedding model is loaded, echoing that name back — so
     *              this field is what distinguishes "the model works" from "something
     *              answered" (JCLAW-931).
     */
    public record EmbeddingResponse(
            List<EmbeddingData> data,
            Usage usage,
            String model
    ) {}

    public record EmbeddingData(
            int index,
            float[] embedding
    ) {}

    // --- Provider config ---

    public record ProviderConfig(
            String name,
            String baseUrl,
            String apiKey,
            List<ModelInfo> models,
            PaymentModality paymentModality,
            BigDecimal subscriptionMonthlyUsd
    ) {
        /** Convenience constructor — defaults the modality to the provider's
         *  default ({@link PaymentModality#defaultFor}) and the subscription
         *  price to zero. For call sites that don't carry a billing shape. */
        public ProviderConfig(String name, String baseUrl, String apiKey, List<ModelInfo> models) {
            this(name, baseUrl, apiKey, models,
                    PaymentModality.defaultFor(name), BigDecimal.ZERO);
        }
    }

    /**
     * Default reasoning-effort levels assumed when a thinking-capable model does
     * not declare its own {@code thinkingLevels}. Matches the OpenAI/Ollama
     * {@code reasoning_effort} enum. OpenRouter-routed effort-style models
     * additionally accept {@code "minimal"} and {@code "xhigh"}; seed those in
     * {@code thinkingLevels} per-model when the provider supports them.
     */
    public static final List<String> DEFAULT_THINKING_LEVELS = List.of("low", "medium", "high");

    /**
     * Model metadata including pricing. Pricing fields use {@code double} defaults
     * of {@code -1} to distinguish "not provided" from "free" ({@code 0.0}).
     * Values are per-million tokens, matching the convention in provider config JSON.
     *
     * @param id               provider's canonical model identifier (the value
     *                         passed to the provider's API)
     * @param name             display name shown in the UI
     * @param contextWindow    maximum total tokens (input + output) the model
     *                         accepts in one request
     * @param maxTokens        maximum completion tokens the model will produce
     * @param supportsThinking true when the model can produce reasoning /
     *                         chain-of-thought tokens
     * @param supportsVision   true when the model accepts image inputs
     * @param supportsAudio    true when the model accepts audio inputs
     * @param supportsTools    whether the model can call tools, or {@code null}
     *                         when unknown — read {@link #toolCallingSupported()}
     *                         rather than this field. Boxed on purpose
     *                         (JCLAW-1074): provider model lists are Gson-parsed
     *                         from stored config, and a primitive would make
     *                         every model discovered before this field existed
     *                         deserialize as {@code false}, silently stripping
     *                         tools from every agent on the next restart. Null
     *                         distinguishes "not recorded" from "recorded as no".
     * @param promptPrice      USD per million prompt (uncached input) tokens,
     *                         {@code -1} when unknown
     * @param completionPrice  USD per million completion tokens, {@code -1}
     *                         when unknown
     * @param cachedReadPrice  USD per million cache-read tokens, {@code -1}
     *                         when unknown
     * @param cacheWritePrice  USD per million cache-write tokens, {@code -1}
     *                         when unknown
     * @param thinkingLevels   reasoning-effort values the model accepts (e.g.
     *                         {@code ["low","medium","high"]}, or the
     *                         OpenRouter-extended
     *                         {@code ["minimal","low","medium","high","xhigh"]}).
     *                         {@code null} or empty is equivalent to
     *                         {@link #DEFAULT_THINKING_LEVELS} when
     *                         {@code supportsThinking} is true, and
     *                         meaningless otherwise.
     * @param alwaysThinks     marks pure reasoning models (e.g. OpenAI o1/o3,
     *                         DeepSeek-R1, GLM-5.3) whose architecture has no
     *                         non-thinking mode — the provider API accepts a
     *                         "reasoning off" value but the model thinks
     *                         anyway. The UI surfaces these as a locked-on
     *                         pill so the operator isn't misled into believing
     *                         their off preference was honored, and
     *                         {@code serializeRequest} sends the first entry of
     *                         {@link ModelInfo#effectiveThinkingLevels()}
     *                         instead of an off signal. Does not imply the
     *                         effort is fixed: the ladder is still selectable.
     *                         Implies {@code supportsThinking == true};
     *                         meaningless otherwise.
     */
    public record ModelInfo(
            String id,
            String name,
            int contextWindow,
            int maxTokens,
            boolean supportsThinking,
            boolean supportsVision,
            boolean supportsAudio,
            boolean supportsVideo,
            Boolean supportsTools,
            double promptPrice,
            double completionPrice,
            double cachedReadPrice,
            double cacheWritePrice,
            List<String> thinkingLevels,
            boolean alwaysThinks
    ) {
        /** Convenience constructor — capabilities only; defaults all pricing to
         *  the {@code -1} unknown sentinel, vision/audio off, and no explicit
         *  thinking levels. */
        public ModelInfo(String id, String name, int contextWindow, int maxTokens, boolean supportsThinking) {
            this(id, name, contextWindow, maxTokens, supportsThinking, false, false, false, true,
                    -1, -1, -1, -1, null, false);
        }

        /** Convenience constructor — capabilities plus the four pricing fields;
         *  defaults vision/audio off and leaves thinking levels unset. */
        public ModelInfo(String id, String name, int contextWindow, int maxTokens, boolean supportsThinking,
                         double promptPrice, double completionPrice,
                         double cachedReadPrice, double cacheWritePrice) {
            this(id, name, contextWindow, maxTokens, supportsThinking, false, false, false, true,
                    promptPrice, completionPrice, cachedReadPrice, cacheWritePrice, null, false);
        }

        /**
         * Whether to send the {@code tools} array to this model. Unknown resolves
         * to {@code true}: sending tools to a model that turns out not to support
         * them fails one request loudly, while withholding them from a capable
         * model disarms the agent silently — so the recoverable error is the
         * better default. Only an authoritative {@code false} withholds.
         */
        public boolean toolCallingSupported() {
            return supportsTools == null || supportsTools;
        }

        /**
         * Resolve the effective list of reasoning-effort levels this model accepts.
         * Returns the model's explicit list when non-empty, otherwise
         * {@link #DEFAULT_THINKING_LEVELS} for thinking-capable models, otherwise
         * an empty list.
         */
        public List<String> effectiveThinkingLevels() {
            if (thinkingLevels != null && !thinkingLevels.isEmpty()) return thinkingLevels;
            return supportsThinking ? DEFAULT_THINKING_LEVELS : List.of();
        }
    }
}
