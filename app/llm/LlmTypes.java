package llm;

import models.MessageRole;

import java.math.BigDecimal;
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
     */
    public record Usage(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int reasoningTokens,
            int cachedTokens,
            int cacheCreationTokens
    ) {}

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

    public record ChunkDelta(
            String role,
            String content,
            List<ToolCallChunk> toolCalls,
            String reasoning,
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

    public record EmbeddingResponse(
            List<EmbeddingData> data,
            Usage usage
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
     *                         DeepSeek-R1, Qwen QwQ) whose architecture has no
     *                         non-thinking mode — the provider API accepts a
     *                         "reasoning off" value but the model thinks
     *                         anyway. The UI surfaces these as a locked-on
     *                         pill so the operator isn't misled into believing
     *                         their off preference was honored. Implies
     *                         {@code supportsThinking == true}; meaningless
     *                         otherwise.
     */
    public record ModelInfo(
            String id,
            String name,
            int contextWindow,
            int maxTokens,
            boolean supportsThinking,
            boolean supportsVision,
            boolean supportsAudio,
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
            this(id, name, contextWindow, maxTokens, supportsThinking, false, false, -1, -1, -1, -1, null, false);
        }

        /** Convenience constructor — capabilities plus the four pricing fields;
         *  defaults vision/audio off and leaves thinking levels unset. */
        public ModelInfo(String id, String name, int contextWindow, int maxTokens, boolean supportsThinking,
                         double promptPrice, double completionPrice,
                         double cachedReadPrice, double cacheWritePrice) {
            this(id, name, contextWindow, maxTokens, supportsThinking, false, false,
                    promptPrice, completionPrice, cachedReadPrice, cacheWritePrice, null, false);
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
