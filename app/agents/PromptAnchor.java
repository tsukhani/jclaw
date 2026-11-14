package agents;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import llm.TokenUsageEstimator;
import llm.TokenUsageEstimator.ChatRequestTokens;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * The provider's own count of a conversation's last request, which the compaction trigger counts on from (JF-420): a
 * later request measures as that call's prompt and completion plus the adjusted estimate of what it changes beside
 * them, so the local tokenizer's error is that of the change, not of the whole conversation.
 *
 * @param promptTokens     the reply's final call's prompt, as the provider counted it
 * @param completionTokens that call's completion, as the provider counted it
 * @param requestEstimate  the same prompt as the local tokenizer counted it, unadjusted
 * @param replyEstimate    the stored reply as the local tokenizer counts it in a later request, unadjusted
 * @param modelMatched     whether {@code requestEstimate} was counted with the model's own encoding
 */
public record PromptAnchor(String providerName, String modelId, int promptTokens, int completionTokens,
                           int requestEstimate, int replyEstimate, boolean modelMatched) {

    /** The usage record's field holding the final call's local estimate. */
    public static final String ESTIMATE_FIELD = "lastPromptEstimate";

    /**
     * The anchor {@code conversation}'s newest top-level reply gives a request on {@code providerName}/{@code modelId}.
     * Null, leaving the trigger to the adjusted estimate of the whole request, when the provider did not count that
     * reply's final call, another model answered it, a {@code /reset} followed it, or a summary was stored at or after
     * the reply before it: the request it answered may then carry history the next one does not. Call inside a Tx.
     */
    public static @Nullable PromptAnchor find(Conversation conversation, @Nullable String providerName,
                                              @Nullable String modelId) {
        if (conversation.id == null || providerName == null || modelId == null) return null;
        List<Message> replies = Message.find("conversation = ?1 AND role = ?2 AND usageJson IS NOT NULL"
                        + " AND subagentRunId IS NULL ORDER BY createdAt DESC, id DESC",
                conversation, MessageRole.ASSISTANT.value).fetch(2);
        if (replies.isEmpty()) return null;
        var reply = replies.getFirst();
        var floor = floor(conversation);
        if (floor != null && reply.createdAt.isBefore(floor)) return null;
        var previous = replies.size() > 1 ? replies.get(1).createdAt : null;
        long summaries = previous == null ? SessionCompaction.count("conversation = ?1", conversation)
                : SessionCompaction.count("conversation = ?1 AND createdAt >= ?2", conversation, previous);
        return summaries == 0 ? fromUsage(reply, providerName, modelId) : null;
    }

    private static @Nullable PromptAnchor fromUsage(Message reply, String providerName, String modelId) {
        JsonObject usage;
        try {
            usage = JsonParser.parseString(reply.usageJson).getAsJsonObject();
        } catch (JsonParseException | IllegalStateException _) {
            return null;
        }
        if (!"provider".equals(text(usage, "usageSource")) || !providerName.equals(text(usage, "modelProvider"))
                || !modelId.equals(text(usage, "modelId")) || !(usage.get(ESTIMATE_FIELD) instanceof JsonObject estimate)) {
            return null;
        }
        int prompt = number(usage, "lastPrompt");
        int requestEstimate = number(estimate, "prompt");
        if (prompt <= 0 || requestEstimate <= 0) return null;
        var replyTokens = TokenUsageEstimator.estimateMessage(modelId,
                ChatMessage.assistant(Objects.requireNonNullElse(reply.content, "")));
        return new PromptAnchor(providerName, modelId, prompt, Math.max(0, number(usage, "lastCompletion")),
                requestEstimate, replyTokens.tokens(), "true".equals(text(estimate, "modelMatched")));
    }

    /**
     * {@code next}, the local estimate of a later request on this anchor's model, as the trigger counts it: this
     * anchor's prompt and completion plus the adjusted estimate of all {@code next} holds beyond the anchored request
     * and its reply, which is below zero when pruning or compression shrank what that request carried.
     */
    public int measure(ChatRequestTokens next) {
        int change = next.promptTokens() - requestEstimate - replyEstimate;
        long measured = (long) promptTokens + completionTokens
                + ContextWindowManager.adjustedMessageTokens(change, next.modelMatched(), providerName, modelId);
        return Math.clamp(measured, 0, Integer.MAX_VALUE);
    }

    /** Whether {@code next} was counted with the encoding this anchor's estimate was, as {@link #measure} needs. */
    public boolean measures(ChatRequestTokens next) {
        return next.modelMatched() == modelMatched;
    }

    /**
     * An adjusted local estimate in the provider's count, by the ratio of this anchor's provider prompt to its adjusted
     * estimate: for a size with no anchored request of its own, such as the request a summary leaves.
     */
    public int toProviderUnits(int adjustedTokens) {
        int adjusted = ContextWindowManager.adjustedMessageTokens(requestEstimate, modelMatched, providerName, modelId);
        if (adjusted <= 0) return adjustedTokens;
        return (int) Math.clamp(Math.round((double) adjustedTokens * promptTokens / adjusted), 0L, Integer.MAX_VALUE);
    }

    private static @Nullable Instant floor(Conversation conversation) {
        var reset = conversation.contextSince;
        var compacted = conversation.compactionSince;
        if (reset == null) return compacted;
        if (compacted == null) return reset;
        return reset.isAfter(compacted) ? reset : compacted;
    }

    private static @Nullable String text(JsonObject json, String key) {
        var value = json.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }

    private static int number(JsonObject json, String key) {
        var value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber() ? value.getAsInt() : 0;
    }
}
