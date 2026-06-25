package agents;

import llm.LlmTypes.ChatMessage;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import services.CompressionMetrics;
import services.ConfigService;
import services.compression.CodeCompressor;
import services.compression.ContentCompressor;
import services.compression.ContentHash;
import services.compression.ContentType;
import services.compression.ContentTypeDetector;
import services.compression.JsonCompressor;
import services.compression.TextCompressor;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import play.Logger;

/**
 * Content-aware compression pipeline (JCLAW-465 core). Sits between
 * {@code MessageHydrator.buildMessages()} and {@code CompactionGate} in the
 * agent run loop: it shrinks individual TOOL-role message bodies (structured
 * tool outputs) BEFORE the existing compaction/trim stages, so the budget
 * check sees the smaller payload and compaction fires less often.
 *
 * <p><b>Routing (JCLAW-460):</b> each eligible body is classified by
 * {@link ContentTypeDetector} and sent to the matching {@link ContentCompressor}:
 * JSON (JCLAW-461), CODE (JCLAW-463), and TEXT/LOG (JCLAW-464, statistical).
 *
 * <p><b>Per-agent gating (JCLAW-463/464/465):</b> the master enable
 * ({@link Agent#compressionEffective()}) gates everything; under it, per-type
 * sub-toggles ({@code compression{Json,Code,Text}Effective()}) decide which
 * content types are compressed, and {@code compressionTargetRatio} tunes the
 * text compressor's aggressiveness. {@link #enabledTypesFor} and the agent's
 * ratio fold into the {@link CompressionSettings} passed to the router.
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
    private static final CodeCompressor CODE = new CodeCompressor();

    // ccr_retrieve exists to UN-compress; its output must never be recompressed.
    // Literal rather than tools.CcrRetrieveTool.TOOL_NAME to avoid an
    // agents <-> tools package cycle.
    private static final String CCR_RETRIEVE_TOOL = "ccr_retrieve";

    /** Enabled content types, the text compressor's aggressiveness, and the metrics agent id + channel. */
    private record CompressionSettings(Set<ContentType> types, double targetRatio, String agentId, String channel) {}

    /** The compressor for a content type. TEXT/LOG share the statistical compressor, tuned per agent. */
    private static ContentCompressor compressorFor(ContentType type, double targetRatio) {
        return switch (type) {
            case JSON -> JSON;
            case CODE -> CODE;
            case LOG, TEXT -> new TextCompressor(targetRatio);
        };
    }

    /**
     * Compress eligible messages for the agent's effective model. No-op (returns
     * the input list) when the master is off, no content type is enabled, the
     * model can't be resolved, or nothing changed.
     */
    public static List<ChatMessage> compress(List<ChatMessage> messages, Agent agent, Conversation conversation) {
        if (messages == null || messages.isEmpty() || agent == null || !agent.compressionEffective()) {
            return messages;
        }
        var types = enabledTypesFor(agent);
        if (types.isEmpty()) return messages;
        var modelId = ModelResolver.effectiveModelId(agent, conversation);
        if (modelId == null || modelId.isBlank()) return messages;
        var ratio = agent.compressionTargetRatio != null
                ? agent.compressionTargetRatio : TextCompressor.DEFAULT_TARGET_RATIO;
        var agentId = agent.id != null ? String.valueOf(agent.id) : null;
        var channel = conversation != null ? conversation.channelType : null;
        return compressMessages(messages, modelId,
                ConfigService.getInt("chat.compression.minTokens", 250),
                new CompressionSettings(types, ratio, agentId, channel));
    }

    /** Content types compressed for this agent: master on, per-type sub-toggle not opted out. */
    private static Set<ContentType> enabledTypesFor(Agent agent) {
        var set = EnumSet.noneOf(ContentType.class);
        if (agent.compressionJsonEffective()) set.add(ContentType.JSON);
        if (agent.compressionCodeEffective()) set.add(ContentType.CODE);
        if (agent.compressionTextEffective()) {
            set.add(ContentType.TEXT);
            set.add(ContentType.LOG);
        }
        return set;
    }

    /**
     * Pure compression core — a test seam that enables every content type at the
     * default target ratio, so routing and the token-level inflation guard can be
     * exercised without an {@link Agent} / {@link Conversation} or per-agent
     * config. Returns the same list instance when nothing was compressed, so
     * callers can cheaply detect a no-op.
     */
    public static List<ChatMessage> compressMessages(List<ChatMessage> messages, String modelId, int minTokens) {
        return compressMessages(messages, modelId, minTokens,
                new CompressionSettings(EnumSet.allOf(ContentType.class), TextCompressor.DEFAULT_TARGET_RATIO, null, null));
    }

    private static List<ChatMessage> compressMessages(List<ChatMessage> messages, String modelId, int minTokens,
                                                      CompressionSettings settings) {
        var out = new ArrayList<ChatMessage>(messages.size());
        var anyChanged = false;
        for (var msg : messages) {
            var compressed = maybeCompress(msg, modelId, minTokens, settings);
            anyChanged |= compressed != msg;
            out.add(compressed);
        }
        return anyChanged ? out : messages;
    }

    private static ChatMessage maybeCompress(ChatMessage msg, String modelId, int minTokens,
                                             CompressionSettings settings) {
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
        if (!settings.types().contains(type)) return msg; // type disabled for this agent

        var result = compressorFor(type, settings.targetRatio()).compress(content);
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
            Logger.debug("[compress] inflation guard %s/%s %d->%d tokens, kept original",
                    type, result.algorithm(), before, after);
            CompressionMetrics.recordInflationGuard(settings.agentId(), settings.channel(), modelId, type.name(), before, after);
            return msg;
        }
        Logger.debug("[compress] %s/%s %d->%d tokens (-%d)",
                type, result.algorithm(), before, after, before - after);
        CompressionMetrics.recordCompression(settings.agentId(), settings.channel(), modelId, type.name(), result.algorithm(), before, after);
        return candidate;
    }
}
