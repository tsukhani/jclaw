package agents;

import llm.LlmTypes.ChatMessage;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import services.ConfigService;
import services.compression.ContentHash;
import services.compression.ContentType;
import services.compression.ContentTypeDetector;
import services.compression.JsonCompressor;

import java.util.ArrayList;
import java.util.List;

/**
 * Content-aware compression pipeline (JCLAW-465 core). Sits between
 * {@code MessageHydrator.buildMessages()} and {@code CompactionGate} in the
 * agent run loop: it shrinks individual TOOL-role message bodies (structured
 * tool outputs) BEFORE the existing compaction/trim stages, so the budget
 * check sees the smaller payload and compaction fires less often.
 *
 * <p><b>MVP scope:</b> JSON only — the highest-value, lowest-risk content type.
 * CODE / LOG / TEXT compressors (JCLAW-463/464) hook in at the type switch in
 * {@link #maybeCompress} as they land. Disabled by default
 * ({@code chat.compression.enabled=false}).
 *
 * <p><b>Safety:</b> only TOOL-role messages with plain String content are
 * touched — USER / ASSISTANT / SYSTEM turns (intent, instructions) are never
 * compressed. Token measurement is authoritative (jtokkit via
 * {@link TokenUsageEstimator}); a per-message token-level inflation guard keeps
 * the original whenever compression doesn't actually save tokens.
 */
public final class CompressionPipeline {

    private CompressionPipeline() {}

    private static final JsonCompressor JSON = new JsonCompressor();

    // ccr_retrieve exists to UN-compress; its output must never be recompressed.
    // Literal rather than tools.CcrRetrieveTool.TOOL_NAME to avoid an
    // agents <-> tools package cycle.
    private static final String CCR_RETRIEVE_TOOL = "ccr_retrieve";

    /**
     * Compress eligible messages for the agent's effective model. No-op (returns
     * the input list) when disabled, when the model can't be resolved, or when
     * nothing changed.
     */
    public static List<ChatMessage> compress(List<ChatMessage> messages, Agent agent, Conversation conversation) {
        if (messages == null || messages.isEmpty() || agent == null
                || !agent.compressionEffective() || !jsonEnabled()) return messages;
        var modelId = ModelResolver.effectiveModelId(agent, conversation);
        if (modelId == null || modelId.isBlank()) return messages;
        return compressMessages(messages, modelId, ConfigService.getInt("chat.compression.minTokens", 250));
    }

    /**
     * Pure compression core — public as a test seam so the routing and the
     * token-level inflation guard can be exercised without an {@link Agent} /
     * {@link Conversation} or global config. Returns the same list instance when
     * nothing was compressed, so callers can cheaply detect a no-op.
     */
    public static List<ChatMessage> compressMessages(List<ChatMessage> messages, String modelId, int minTokens) {
        var out = new ArrayList<ChatMessage>(messages.size());
        var anyChanged = false;
        for (var msg : messages) {
            var compressed = maybeCompress(msg, modelId, minTokens);
            anyChanged |= compressed != msg;
            out.add(compressed);
        }
        return anyChanged ? out : messages;
    }

    private static ChatMessage maybeCompress(ChatMessage msg, String modelId, int minTokens) {
        // Only TOOL-role messages with plain String content are eligible:
        // structured tool outputs are where the redundancy lives, and leaving
        // user/assistant/system turns untouched preserves intent + instructions.
        if (!MessageRole.TOOL.value.equals(msg.role())) return msg;
        // Recompressing ccr_retrieve's output would hand the LLM the same elided
        // view it called the tool to escape — a retrieval loop. Pass it through.
        if (CCR_RETRIEVE_TOOL.equals(msg.toolName())) return msg;
        if (!(msg.content() instanceof String content) || content.isBlank()) return msg;

        var before = TokenUsageEstimator.estimateMessage(modelId, msg).tokens();
        if (before < minTokens) return msg; // below the floor — not worth the work

        var type = ContentTypeDetector.detect(content);
        // MVP routes JSON only; CODE/LOG/TEXT join here as their compressors land.
        if (type != ContentType.JSON) return msg;

        var result = JSON.compress(content);
        if (!result.changed()) return msg;

        // JCLAW-462 (CCR): leave a retrieval handle so the LLM can pull the
        // full original via ccr_retrieve. The handle is the hash of the ORIGINAL
        // content, which ccr_retrieve recomputes over the durable Message row.
        var hinted = result.content()
                + "\n[compressed — call ccr_retrieve(\"" + ContentHash.handle(content)
                + "\") for the full original]";
        var candidate = new ChatMessage(msg.role(), hinted, msg.toolCalls(),
                msg.toolCallId(), msg.toolName());
        var after = TokenUsageEstimator.estimateMessage(modelId, candidate).tokens();
        if (after >= before) {
            // Char savings didn't translate to token savings — keep the original.
            play.Logger.debug("[compress] inflation guard %s/%s %d->%d tokens, kept original",
                    type, result.algorithm(), before, after);
            return msg;
        }
        play.Logger.debug("[compress] %s/%s %d->%d tokens (-%d)",
                type, result.algorithm(), before, after, before - after);
        return candidate;
    }

    private static boolean jsonEnabled() {
        return !"false".equalsIgnoreCase(ConfigService.get("chat.compression.json.enabled", "true"));
    }
}
