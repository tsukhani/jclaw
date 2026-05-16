package agents;

import java.util.List;
import java.util.Set;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import models.Agent;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

/**
 * JCLAW-38 / JCLAW-268 compaction trigger. Extracted from
 * {@link AgentRunner} as part of JCLAW-299. The single entry point
 * {@link #maybeCompactAndRebuild} runs between the prep Tx and the
 * LLM call, deciding whether the assembled message list has crossed
 * the per-model compaction budget; if so it invokes
 * {@link SessionCompactor#compact} and re-hydrates the message list
 * with the freshly stored summary injected.
 *
 * <p>The decision is intentionally side-effect-free outside the
 * compactor: a snapshot read in a bounded Tx, a token-count check
 * against {@link ModelInfo}, and the compactor's own summarizer
 * lambda which makes its own LLM call. The summarizer call MUST run
 * outside any JPA transaction because it is LLM-bound and may take
 * tens of seconds — holding a JDBC connection through that would
 * starve the connection pool.
 *
 * <p>Even on success the caller should pass the rebuilt message list
 * through {@link ContextWindowManager#trimToContextWindow} as a
 * final safety net — if the summary plus retained tail somehow
 * still doesn't fit, drop-oldest guarantees we never ship an
 * over-budget context.
 */
public final class CompactionGate {

    private CompactionGate() {}

    private record CompactionDecision(ModelInfo modelInfo, String modelId, String channelType) {}

    /**
     * If {@code current} exceeds the compaction budget for the
     * effective model, run {@link SessionCompactor#compact} and
     * return a freshly rebuilt message list (with the new summary
     * injected into the system prompt and the older turns dropped).
     * Otherwise returns {@code current} unchanged (JCLAW-38).
     *
     * <p>Called from both {@link AgentRunner#run} and the streaming
     * loop after the initial prep Tx closes, because the
     * summarization call itself is LLM-bound and must not hold a JDBC
     * connection.
     */
    static List<ChatMessage> maybeCompactAndRebuild(
            Agent agent, Long conversationId, String userMessage,
            Set<String> disabledTools, LlmProvider primary,
            List<ChatMessage> current) {
        // Cheap snapshot: model info + effective model id + channel type.
        // resolveModelInfo reads only in-memory provider config, so this
        // Tx is bounded by one findById.
        var snapshot = Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return null;
            var mi = ModelResolver.resolveModelInfo(agent, conv, primary).orElse(null);
            var modelId = ModelResolver.effectiveModelId(agent, conv);
            return new CompactionDecision(mi, modelId, conv.channelType);
        });
        if (snapshot == null || snapshot.modelInfo() == null || snapshot.modelId() == null) return current;
        if (!SessionCompactor.shouldCompact(ContextWindowManager.estimateTokens(current), snapshot.modelInfo())) return current;

        final var modelId = snapshot.modelId();
        final var compactionChannel = snapshot.channelType();
        final var maxOutput = ConfigService.getInt("chat.compactionMaxTokens", 8192);
        final var modelLabel = primary.config().name() + "/" + modelId;

        SessionCompactor.Summarizer summarizer = sumMsgs -> {
            var resp = primary.chat(modelId, sumMsgs, List.of(), maxOutput, null, compactionChannel);
            return SessionCompactor.firstChoiceText(resp);
        };

        var result = SessionCompactor.compact(conversationId, modelLabel, summarizer);
        if (!result.compacted()) {
            EventLogger.info("compaction", agent.name, snapshot.channelType(),
                    "Compaction skipped (%s); falling back to drop-oldest".formatted(result.skipReason()));
            return current;
        }
        EventLogger.info("compaction", agent.name, snapshot.channelType(),
                "Compacted %d turns (%d chars) via %s".formatted(
                        result.turnsCompacted(), result.summaryChars(), modelLabel));

        // Rebuild messages: fresh read picks up the bumped compactionSince,
        // appendSummaryToPrompt re-injects the (now stored) summary.
        return Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return current;
            var assembled = SystemPromptAssembler.assemble(agent, userMessage, disabledTools, conv.channelType);
            var sysPrompt = SessionCompactor.appendSummaryToPrompt(assembled.systemPrompt(), conv);
            // JCLAW-268: re-inject spawn-time parent context for inherit-mode subagents.
            sysPrompt = SessionCompactor.appendParentContextToPrompt(sysPrompt, conv);
            return MessageHydrator.buildMessages(sysPrompt, conv);
        });
    }
}
