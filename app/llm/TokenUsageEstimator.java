package llm;

import com.google.gson.Gson;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;
import com.knuddels.jtokkit.api.EncodingType;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static utils.GsonHolder.INSTANCE;

/**
 * Provider-facing token measurement backed by JTokkit.
 *
 * <p>Provider {@code usage} blocks remain authoritative whenever they are
 * returned. This class gives JClaw a local tokenizer measurement for two
 * gaps: preflight context-window arithmetic, and fallback usage accounting
 * for providers/routes that omit final usage metadata. It intentionally uses
 * {@link Encoding#countTokensOrdinary(String)} so prompt text that happens to
 * contain tiktoken special-token sentinels is counted as literal user text
 * instead of throwing.
 */
public final class TokenUsageEstimator {

    private static final Gson gson = INSTANCE;
    private static final EncodingRegistry REGISTRY = Encodings.newLazyEncodingRegistry();

    private static final int TOKENS_PER_MESSAGE = 3;
    private static final int TOKENS_PER_NAME = 1;
    private static final int ASSISTANT_REPLY_PRIMER = 3;

    public record TokenCount(int tokens, String encodingName, boolean modelMatched) {
        public static TokenCount zero(String encodingName, boolean modelMatched) {
            return new TokenCount(0, encodingName, modelMatched);
        }
    }

    public record ChatRequestTokens(int messageTokens, int toolTokens, int promptTokens,
                                    String encodingName, boolean modelMatched) {}

    private record ResolvedEncoding(Encoding encoding, String name, boolean modelMatched) {}

    private TokenUsageEstimator() {}

    /**
     * Estimate the prompt tokens a chat-completions request will consume:
     * messages plus separately shipped tool schemas plus the assistant reply
     * primer. This mirrors OpenAI's chat-token counting formula closely enough
     * for provider-facing headroom math, while keeping provider usage as the
     * persisted source of truth when available.
     */
    public static ChatRequestTokens estimateChatRequest(String model,
                                                         List<ChatMessage> messages,
                                                         List<ToolDef> tools) {
        var resolved = resolveEncoding(model);
        int messageTokens = ASSISTANT_REPLY_PRIMER;
        if (messages != null) {
            for (var message : messages) {
                messageTokens += estimateMessage(resolved.encoding(), message);
            }
        }
        int toolTokens = estimateTools(resolved.encoding(), tools);
        return new ChatRequestTokens(
                messageTokens,
                toolTokens,
                messageTokens + toolTokens,
                resolved.name(),
                resolved.modelMatched());
    }

    /** Estimate one chat message without the assistant reply primer. */
    public static TokenCount estimateMessage(String model, ChatMessage message) {
        var resolved = resolveEncoding(model);
        return new TokenCount(estimateMessage(resolved.encoding(), message),
                resolved.name(), resolved.modelMatched());
    }

    /**
     * Estimate output-side tokens for a streamed assistant round. Reasoning
     * text is included in completion tokens because OpenAI-compatible provider
     * usage generally treats reasoning tokens as a subset of completion tokens.
     */
    public static TokenCount estimateCompletion(String model, String content,
                                                 List<ToolCall> toolCalls,
                                                 String reasoningText) {
        var resolved = resolveEncoding(model);
        int tokens = count(resolved.encoding(), content) + count(resolved.encoding(), reasoningText);
        if (toolCalls != null && !toolCalls.isEmpty()) {
            tokens += count(resolved.encoding(), gson.toJson(toolCalls));
        }
        return new TokenCount(tokens, resolved.name(), resolved.modelMatched());
    }

    /** Estimate streamed reasoning text by itself for UI/reporting fallback fields. */
    public static TokenCount estimateReasoning(String model, String reasoningText) {
        var resolved = resolveEncoding(model);
        return new TokenCount(count(resolved.encoding(), reasoningText),
                resolved.name(), resolved.modelMatched());
    }

    private static int estimateMessage(Encoding encoding, ChatMessage message) {
        if (message == null) return 0;
        int tokens = TOKENS_PER_MESSAGE;
        tokens += count(encoding, message.role());
        tokens += countContent(encoding, message.content());
        tokens += countToolCalls(encoding, message.toolCalls());
        if (message.toolCallId() != null) tokens += count(encoding, message.toolCallId());
        if (message.toolName() != null) {
            tokens += TOKENS_PER_NAME;
            tokens += count(encoding, message.toolName());
        }
        return tokens;
    }

    private static int estimateTools(Encoding encoding, List<ToolDef> tools) {
        if (tools == null || tools.isEmpty()) return 0;
        return count(encoding, gson.toJson(tools));
    }

    private static int countContent(Encoding encoding, Object content) {
        if (content instanceof String s) return count(encoding, s);
        if (!(content instanceof List<?> parts)) return 0;
        int tokens = 0;
        for (var part : parts) {
            if (part instanceof Map<?,?> m && m.get("text") instanceof String t) {
                tokens += count(encoding, t);
            }
        }
        return tokens;
    }

    private static int countToolCalls(Encoding encoding, List<ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) return 0;
        return count(encoding, gson.toJson(toolCalls));
    }

    private static int count(Encoding encoding, String text) {
        if (text == null || text.isEmpty()) return 0;
        return encoding.countTokensOrdinary(text);
    }

    private static ResolvedEncoding resolveEncoding(String model) {
        var modelKey = canonicalModelName(model);
        if (!modelKey.isEmpty()) {
            var direct = REGISTRY.getEncodingForModel(modelKey);
            if (direct.isPresent()) {
                return new ResolvedEncoding(direct.get(), direct.get().getName(), true);
            }
        }

        var encodingName = fallbackEncodingName(modelKey);
        var encoding = REGISTRY.getEncoding(encodingName)
                .orElseGet(() -> REGISTRY.getEncoding(EncodingType.CL100K_BASE));
        return new ResolvedEncoding(encoding, encodingName, false);
    }

    private static String canonicalModelName(String model) {
        if (model == null) return "";
        var trimmed = model.strip();
        var slash = trimmed.lastIndexOf('/');
        return slash >= 0 && slash < trimmed.length() - 1
                ? trimmed.substring(slash + 1)
                : trimmed;
    }

    private static String fallbackEncodingName(String model) {
        var lower = model == null ? "" : model.toLowerCase(Locale.ROOT);
        if (lower.startsWith("gpt-4o")
                || lower.startsWith("gpt-4.1")
                || lower.startsWith("gpt-5")
                || lower.startsWith("o1")
                || lower.startsWith("o3")
                || lower.startsWith("o4")) {
            return "o200k_base";
        }
        return "cl100k_base";
    }
}
