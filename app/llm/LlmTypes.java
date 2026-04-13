package llm;

import models.MessageRole;

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
            String toolCallId
    ) {
        public static ChatMessage system(String text) {
            return new ChatMessage(MessageRole.SYSTEM.value, text, null, null);
        }

        public static ChatMessage user(String text) {
            return new ChatMessage(MessageRole.USER.value, text, null, null);
        }

        public static ChatMessage assistant(String text) {
            return new ChatMessage(MessageRole.ASSISTANT.value, text, null, null);
        }

        public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
            return new ChatMessage(MessageRole.ASSISTANT.value, text, toolCalls, null);
        }

        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage(MessageRole.TOOL.value, content, null, toolCallId);
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
     */
    public record Usage(
            int promptTokens,
            int completionTokens,
            int totalTokens,
            int reasoningTokens,
            int cachedTokens,
            int cacheCreationTokens
    ) {
        /** Backwards-compatible factory for callers that don't have cache-creation data. */
        public static Usage of(int promptTokens, int completionTokens, int totalTokens,
                               int reasoningTokens, int cachedTokens) {
            return new Usage(promptTokens, completionTokens, totalTokens, reasoningTokens, cachedTokens, 0);
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
            List<ModelInfo> models
    ) {}

    public record ModelInfo(
            String id,
            String name,
            int contextWindow,
            int maxTokens,
            boolean supportsThinking
    ) {}
}
