package llm;

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
            Integer maxTokens
    ) {}

    public record ChatMessage(
            String role,
            Object content,
            List<ToolCall> toolCalls,
            String toolCallId
    ) {
        public static ChatMessage system(String text) {
            return new ChatMessage("system", text, null, null);
        }

        public static ChatMessage user(String text) {
            return new ChatMessage("user", text, null, null);
        }

        public static ChatMessage assistant(String text) {
            return new ChatMessage("assistant", text, null, null);
        }

        public static ChatMessage assistant(String text, List<ToolCall> toolCalls) {
            return new ChatMessage("assistant", text, toolCalls, null);
        }

        public static ChatMessage toolResult(String toolCallId, String content) {
            return new ChatMessage("tool", content, null, toolCallId);
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

    public record Usage(
            int promptTokens,
            int completionTokens,
            int totalTokens
    ) {}

    // --- Streaming types ---

    public record ChatCompletionChunk(
            String id,
            String model,
            List<ChunkChoice> choices
    ) {}

    public record ChunkChoice(
            int index,
            ChunkDelta delta,
            String finishReason
    ) {}

    public record ChunkDelta(
            String role,
            String content,
            List<ToolCallChunk> toolCalls
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
            int maxTokens
    ) {}
}
