package agents;

import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolDef;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.Tx;

import java.util.List;
import java.util.Set;

/**
 * JCLAW-38 / JCLAW-268 compaction trigger. Extracted from
 * {@link AgentRunner} as part of JCLAW-299. The single entry point
 * {@link #maybeCompactAndRebuild} runs between the prep Tx and the
 * LLM call, deciding whether the assembled message list has reached
 * {@code chat.compaction.triggerTokens} or crossed the per-model window
 * budget; if so it invokes {@link SessionCompactor#compact} on the turn's
 * own request and re-hydrates the message list with the freshly stored
 * summary injected.
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

    private CompactionGate() {
    }

    private record CompactionDecision(@Nullable ModelInfo modelInfo, @Nullable String modelId,
                                      @Nullable String channelType) {
    }

    /**
     * If {@code current} exceeds the compaction budget for the
     * effective model, run {@link SessionCompactor#compact} and
     * return a freshly rebuilt message list (with the new summary
     * injected into the system prompt and the older turns dropped).
     * Otherwise, returns {@code current} unchanged (JCLAW-38).
     *
     * <p>Called from both {@link AgentPromptPreparer} rewrite paths
     * after the initial prep Tx closes, because the
     * summarization call itself is LLM-bound and must not hold a JDBC
     * connection.
     */
    public static List<ChatMessage> maybeCompactAndRebuild(
            Agent agent, Long conversationId,
            @Nullable Set<String> disabledTools, LlmProvider primary,
            List<ChatMessage> current) {
        return maybeCompactAndRebuild(agent, conversationId, disabledTools,
                primary, current, List.of());
    }

    /**
     * Overload taking the request's tool schemas so the compaction trigger
     * measures the same payload {@link ContextWindowManager#trimToContextWindow}
     * sees — tool JSON contributes to the provider's prompt_tokens count and
     * was previously absent from the chars/4 estimate, letting large
     * tool-heavy turns slip past the compaction threshold and get truncated
     * by the trim drop-oldest fallback instead of summarized. The summary
     * request sends the same schemas, so its prefix matches the turn's.
     */
    public static List<ChatMessage> maybeCompactAndRebuild(
            Agent agent, Long conversationId,
            @Nullable Set<String> disabledTools, LlmProvider primary,
            List<ChatMessage> current, List<ToolDef> tools) {
        return maybeCompactAndRebuild(agent, conversationId, disabledTools, primary,
                new MessageHydrator.Hydration(current, List.of(), List.of(), List.of()), tools, 0).messages();
    }

    /**
     * Bearer-aware overload. A rebuild replaces the message list, so the media bearers whose
     * {@code chatMessageIndex} addressed the pre-compaction list stop being valid; this returns the
     * rebuild's own bearers beside its messages so the capability rewrites downstream write into
     * the list they were actually computed against (JCLAW-1232). When no compaction fires,
     * {@code current} is returned unchanged.
     *
     * @param reservedTokens tokens the turn sends after this list, counted toward the trigger: its
     *                       recalled memories ({@link ContextWindowManager#reservedTokens})
     */
    public static MessageHydrator.Hydration maybeCompactAndRebuild(
            Agent agent, Long conversationId,
            @Nullable Set<String> disabledTools, LlmProvider primary,
            MessageHydrator.Hydration current, List<ToolDef> tools, int reservedTokens) {
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
        // No-op when the caller has no provider wired up — without one we
        // can't even make the summarization LLM call. Bail out instead of
        // letting providerName/modelLabel construction NPE later.
        if (primary == null || primary.config() == null) return current;
        final var providerName = primary.config().name();
        var estimate = TokenUsageEstimator.estimateChatRequest(snapshot.modelId(), current.messages(), tools);
        int estimatedTokens = ContextWindowManager.adjustedPromptTokens(providerName, snapshot.modelId(), estimate)
                + reservedTokens;
        if (!SessionCompactor.shouldCompact(estimatedTokens, snapshot.modelInfo())) return current;

        final var modelId = snapshot.modelId();
        final var compactionChannel = snapshot.channelType();
        final var maxOutput = ConfigService.getInt("chat.compactionMaxTokens", 8192);
        final var modelLabel = providerName + "/" + modelId;

        SessionCompactor.ModelCall call = (messages, defs) ->
                primary.chat(modelId, messages, defs, maxOutput, null, compactionChannel);
        var result = SessionCompactor.compact(conversationId, modelLabel,
                new SessionCompactor.TurnRequest(current.messages(), tools), call);
        if (!result.compacted()) {
            // Under the token trigger alone the window is far off and this would repeat every turn of a long chat.
            if (SessionCompactor.shouldCompact(estimatedTokens, snapshot.modelInfo(), 0)) {
                EventLogger.info("compaction", agent.name, snapshot.channelType(),
                        "Compaction skipped (%s); the context-window trim is left to fit the prompt"
                                .formatted(result.skipReason()));
            }
            return current;
        }
        EventLogger.info("compaction", agent.name, snapshot.channelType(),
                "Compacted %d turns (%d chars) via %s".formatted(
                        result.turnsCompacted(), result.summaryChars(), modelLabel));

        // Rebuild messages: fresh read picks up the bumped compactionSince,
        // appendSummaryToPrompt re-injects the (now stored) summary. No user message, so recall
        // does not run again: the caller's trailing message carries the prologue's (JF-152).
        return Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return current;
            var promptDisabled = disabledTools != null ? disabledTools : AgentPromptPreparer.turnDisabled(agent, conv,
                    DangerousActionGate.ownerReadsAlone(conv.id, conv.channelType, conv.peerId));
            var assembled = SystemPromptAssembler.assemble(agent, null, promptDisabled, conv.channelType, null, conv);
            return MessageHydrator.buildMessages(AgentPromptPreparer.turnSystemPrompt(assembled.systemPrompt(), conv), conv);
        });
    }

    /**
     * What {@code conversationId}'s next turn would send ahead of its own message, as the turn builds it: the system
     * prompt and tools for the conversation's channel and fence, and the history pruned and compressed. {@code /compact}
     * summarizes on it, so its request is the one this gate sends. The next turn's own message, and so its recall and
     * clock, do not exist yet and are absent here as they are from the list the gate is handed. Null when the
     * conversation is gone.
     */
    public static SessionCompactor.@Nullable TurnRequest nextTurnRequest(Agent agent, Long conversationId) {
        var built = Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return null;
            var ownerReadsAlone = DangerousActionGate.ownerReadsAlone(conv.id, conv.channelType, conv.peerId);
            var assembled = SystemPromptAssembler.assemble(agent, null,
                    AgentPromptPreparer.turnDisabled(agent, conv, ownerReadsAlone), conv.channelType, null, conv);
            var messages = MessageHydrator.buildMessages(
                    AgentPromptPreparer.turnSystemPrompt(assembled.systemPrompt(), conv), conv).messages();
            return new NextTurn(conv, messages, AgentPromptPreparer.turnTools(agent, conv, ownerReadsAlone));
        });
        if (built == null) return null;
        return new SessionCompactor.TurnRequest(
                AgentPromptPreparer.prunedAndCompressed(built.messages(), agent, built.conversation()), built.tools());
    }

    private record NextTurn(Conversation conversation, List<ChatMessage> messages, List<ToolDef> tools) {
    }
}
