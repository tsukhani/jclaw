package agents;

import io.opentelemetry.context.Context;
import llm.FallbackTurn;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolDef;
import llm.TokenUsageEstimator;
import llm.routing.RoutedTurn;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.CompactionProgress;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.SessionCompactor.CompactionResult;
import services.Tx;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * JCLAW-38 / JCLAW-268 compaction trigger. Extracted from
 * {@link AgentRunner} as part of JCLAW-299. {@link #maybeCompactAndRebuild} runs between the prep Tx and the LLM
 * call, deciding whether the conversation's rows since its floor have passed the history limit, or the assembled
 * message list has reached the token trigger or crossed the per-model window budget. A request the model's window
 * still takes goes as it is, and {@link SessionCompactor#compact} runs on the turn's own request on another thread,
 * storing the summary for the next turn; only a request the window does not take waits, compacting inline and
 * re-hydrating the message list with the stored summary injected (JF-367). {@link #afterReply} starts the same
 * work after a reply whose next turn comes near the trigger, so that turn usually finds its summary stored.
 *
 * <p>A conversation runs one compaction at a time: the background start skips one that is running, the inline path
 * and {@code /compact} wait for it ({@link #runExclusive}). The summarizer call MUST run outside any JPA
 * transaction because it is LLM-bound and may take tens of seconds; holding a JDBC connection through it would
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

    private record CompactionDecision(Conversation conversation, @Nullable ModelInfo modelInfo,
                                      @Nullable String modelId, @Nullable String channelType,
                                      boolean pastHistoryLimit) {
    }

    /** A request's prompt as the trigger counts it, and the system prompt and tools within it no summary shrinks. */
    private record Measure(int estimatedTokens, int fixedTokens) {
    }

    /** The compaction running for each conversation. */
    private static final Map<Long, CompletableFuture<CompactionResult>> RUNNING = new ConcurrentHashMap<>();

    private static final CompactionResult FAILED = CompactionResult.skipped("failed");
    private static final CompactionResult NOT_NEAR = CompactionResult.skipped("not near the trigger");

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
     * the list they were actually computed against (JCLAW-1232). When no compaction fires inline,
     * {@code current} is returned unchanged.
     *
     * @param reservedTokens tokens the turn sends after this list, counted toward the trigger: its
     *                       recalled memories ({@link ContextWindowManager#reservedTokens})
     */
    public static MessageHydrator.Hydration maybeCompactAndRebuild(
            Agent agent, Long conversationId,
            @Nullable Set<String> disabledTools, LlmProvider primary,
            MessageHydrator.Hydration current, List<ToolDef> tools, int reservedTokens) {
        var snapshot = snapshot(agent, conversationId, primary);
        // A model missing from the provider's list has no known window, so the token cap and the row limit still apply.
        if (snapshot == null || snapshot.modelId() == null) return current;
        // No-op when the caller has no provider wired up — without one we
        // can't even make the summarization LLM call. Bail out instead of
        // letting providerName and the summary label NPE later.
        if (primary == null || primary.config() == null) return current;
        final var providerName = primary.config().name();
        final var modelId = snapshot.modelId();
        var measure = measure(providerName, modelId, current.messages(), tools);
        final int estimatedTokens = measure.estimatedTokens() + reservedTokens;
        if (!snapshot.pastHistoryLimit()
                && !SessionCompactor.shouldCompact(estimatedTokens, measure.fixedTokens(), snapshot.modelInfo())) {
            return current;
        }

        // Copied: the turn goes on with its own list while the summary call reads this one.
        var turn = new SessionCompactor.TurnRequest(List.copyOf(current.messages()), List.copyOf(tools));
        if (!SessionCompactor.shouldCompact(estimatedTokens, snapshot.modelInfo(), 0, 0)) {
            startInBackground(conversationId, onTheTurnsModel(agent, snapshot.conversation(),
                    () -> compactAndLog(agent, snapshot, modelId, primary, turn, estimatedTokens, true)));
            return current;
        }

        long startNs = System.nanoTime();
        var progress = CompactionProgress.open(estimatedTokens);
        var running = RUNNING.get(conversationId);
        var result = running != null ? running.join() : FAILED;
        if (!result.compacted()) {
            result = progress.around(() -> runExclusive(conversationId,
                    () -> compactAndLog(agent, snapshot, modelId, primary, turn, estimatedTokens, true)));
        }
        if (!result.compacted()) {
            progress.skipped();
            return current;
        }

        // Rebuild messages: fresh read picks up the bumped compactionSince,
        // appendSummaryToPrompt re-injects the (now stored) segments. No user message, so recall
        // does not run again: the caller's trailing message carries the prologue's (JF-152).
        var rebuilt = Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return current;
            var promptDisabled = disabledTools != null ? disabledTools : AgentPromptPreparer.turnDisabled(agent, conv,
                    DangerousActionGate.ownerReadsAlone(conv.id, conv.channelType, conv.peerId));
            var assembled = SystemPromptAssembler.assemble(agent, null, promptDisabled, conv.channelType, null, conv);
            return MessageHydrator.buildMessages(AgentPromptPreparer.turnSystemPrompt(assembled.systemPrompt(), conv), conv);
        });
        progress.done(ContextWindowManager.adjustedPromptTokens(providerName, modelId,
                TokenUsageEstimator.estimateChatRequest(modelId, rebuilt.messages(), tools)) + reservedTokens,
                (System.nanoTime() - startNs) / 1_000_000L);
        return rebuilt;
    }

    /**
     * After a reply is stored (JF-367): when {@code lastRequestTokens}, its last call's prompt and completion, comes
     * within {@link SessionCompactor#BACKGROUND_MARGIN_PERCENT} of the trigger, measures the request the next turn
     * starts from on another thread and compacts it there when that comes near too, so the next turn finds the summary
     * stored. The reply's own size overstates the next request, whose earlier tool results are pruned and compressed,
     * so only the measurement decides. Nothing starts while a compaction of the conversation runs.
     */
    public static void afterReply(Agent agent, Conversation conversation, LlmProvider primary, int lastRequestTokens) {
        var conversationId = conversation.id;
        if (conversationId == null || lastRequestTokens <= 0) return;
        var modelInfo = ModelResolver.resolveModelInfo(agent, conversation, primary).orElse(null);
        if (!SessionCompactor.nearCompaction(lastRequestTokens, 0, modelInfo)) return;
        startInBackground(conversationId, onTheTurnsModel(agent, conversation,
                () -> compactNextTurnIfNear(agent, conversationId, primary)));
    }

    private static CompactionResult compactNextTurnIfNear(Agent agent, Long conversationId, LlmProvider primary) {
        var turn = nextTurnRequest(agent, conversationId);
        var snapshot = snapshot(agent, conversationId, primary);
        if (turn == null || snapshot == null || snapshot.modelId() == null || primary.config() == null) return NOT_NEAR;
        var modelId = snapshot.modelId();
        var measure = measure(primary.config().name(), modelId, turn.messages(), turn.tools());
        if (!snapshot.pastHistoryLimit()
                && !SessionCompactor.nearCompaction(measure.estimatedTokens(), measure.fixedTokens(), snapshot.modelInfo())) {
            return NOT_NEAR;
        }
        boolean pastTrigger = snapshot.pastHistoryLimit()
                || SessionCompactor.shouldCompact(measure.estimatedTokens(), measure.fixedTokens(), snapshot.modelInfo());
        return compactAndLog(agent, snapshot, modelId, primary, turn, measure.estimatedTokens(), pastTrigger);
    }

    // Cheap snapshot: model info + effective model id + channel type, and one COUNT of the rows since the floor.
    // resolveModelInfo reads only in-memory provider config.
    private static @Nullable CompactionDecision snapshot(Agent agent, Long conversationId, LlmProvider primary) {
        return Tx.run(() -> {
            var conv = ConversationService.findById(conversationId);
            if (conv == null) return null;
            var mi = ModelResolver.resolveModelInfo(agent, conv, primary).orElse(null);
            var modelId = ModelResolver.effectiveModelId(agent, conv);
            return new CompactionDecision(conv, mi, modelId, conv.channelType, SessionCompactor.pastHistoryLimit(conv));
        });
    }

    private static Measure measure(String providerName, String modelId, List<ChatMessage> messages,
                                   List<ToolDef> tools) {
        var estimate = TokenUsageEstimator.estimateChatRequest(modelId, messages, tools);
        var system = messages.stream().takeWhile(m -> MessageRole.SYSTEM.value.equals(m.role())).toList();
        return new Measure(ContextWindowManager.adjustedPromptTokens(providerName, modelId, estimate),
                ContextWindowManager.adjustedPromptTokens(providerName, modelId,
                        TokenUsageEstimator.estimateChatRequest(modelId, system, tools)));
    }

    /** One automatic compaction on {@code turn}, logged; {@code pastTrigger} when the request reached the trigger rather than its margin. */
    private static CompactionResult compactAndLog(Agent agent, CompactionDecision snapshot, String modelId,
                                                  LlmProvider primary, SessionCompactor.TurnRequest turn,
                                                  int estimatedTokens, boolean pastTrigger) {
        var conversationId = snapshot.conversation().id;
        // The trigger measures the turn's model; a router agent's summary goes to its summarize class (JF-160).
        var turnModel = new SummaryModels.Choice(primary, modelId);
        var summary = SummaryModels.resolve(agent, snapshot.conversation(), () -> turnModel, snapshot.channelType());
        long startNs = System.nanoTime();
        var result = SessionCompactor.compact(conversationId, summary.first().label(), turn, summary, false, null,
                summary.callTokens(), SessionCompactor.MAX_SUMMARY_CALLS);
        if (result.compacted()) {
            logCompacted(agent.name, snapshot.channelType(), summary, result, (System.nanoTime() - startNs) / 1_000_000L);
        } else if (SessionCompactor.shouldCompact(estimatedTokens, snapshot.modelInfo(), 0, 0)) {
            EventLogger.info("compaction", agent.name, snapshot.channelType(),
                    "Compaction skipped (%s); the context-window trim is left to fit the prompt"
                            .formatted(result.skipReason()));
        } else if (pastTrigger && SessionCompactor.NO_CUT_REASON.equals(result.skipReason())
                && firstNoCutOnThisFloor(conversationId, snapshot.conversation().compactionSince)) {
            // Under the token trigger or the row limit alone the window is far off, and this would repeat every turn.
            EventLogger.info("compaction", agent.name, snapshot.channelType(),
                    "Compaction skipped (%s) at an estimated %d tokens, past the token trigger; logged once until the next compaction"
                            .formatted(result.skipReason(), estimatedTokens));
        }
        return result;
    }

    /**
     * Runs {@code body} as the conversation's one compaction once any running one has ended; the gate and
     * {@code /compact} hold the conversation through it, and a compaction started meanwhile waits or skips.
     */
    public static CompactionResult runExclusive(Long conversationId, Supplier<CompactionResult> body) {
        var mine = new CompletableFuture<CompactionResult>();
        for (var running = RUNNING.putIfAbsent(conversationId, mine); running != null;
             running = RUNNING.putIfAbsent(conversationId, mine)) {
            running.join();
        }
        var result = FAILED;
        try {
            result = body.get();
            return result;
        } finally {
            RUNNING.remove(conversationId, mine);
            mine.complete(result);
        }
    }

    // Never cancelled or interrupted: the summary is stored through H2 (AGENTS.md, Known pitfalls), and every wait
    // in it ends with its own call's timeout.
    private static void startInBackground(Long conversationId, Supplier<CompactionResult> body) {
        var mine = new CompletableFuture<CompactionResult>();
        if (RUNNING.putIfAbsent(conversationId, mine) != null) return;
        Thread.ofVirtual().name("compaction").start(Context.current().wrap(() -> {
            var result = FAILED;
            try {
                result = body.get();
            } catch (RuntimeException e) {
                EventLogger.warn("compaction", null, null, "Compaction of conversation %d in the background failed: %s"
                        .formatted(conversationId, e.getMessage()));
            } finally {
                RUNNING.remove(conversationId, mine);
                mine.complete(result);
            }
        }));
    }

    /**
     * {@code body} for another thread, on the model the calling turn runs on: a {@link RoutedTurn} or
     * {@link FallbackTurn} binding does not cross a thread, and without it the summary would be measured and written
     * for the agent's configured model.
     */
    private static Supplier<CompactionResult> onTheTurnsModel(Agent agent, Conversation conversation,
                                                              Supplier<CompactionResult> body) {
        var routed = RoutedTurn.current(conversation);
        if (routed == null) return () -> FallbackTurn.run(agent, conversation, body::get);
        var decision = routed.decision();
        boolean failedOver = routed.failedOver();
        return () -> RoutedTurn.callWith(decision, conversation, () -> {
            if (failedOver) Objects.requireNonNull(RoutedTurn.current(conversation)).promote();
            return body.get();
        });
    }

    /** The compaction running for {@code conversationId}, completing when it ends; null when none is. */
    public static @Nullable CompletableFuture<CompactionResult> runningForTest(Long conversationId) {
        var running = RUNNING.get(conversationId);
        return running != null ? running.copy() : null;
    }

    /** The success line of a compaction, automatic or {@code /compact}: what it did, on which model, and how long it took. */
    public static void logCompacted(@Nullable String agentName, @Nullable String channelType,
                                    SummaryModels summary, SessionCompactor.CompactionResult result, long tookMs) {
        EventLogger.info("compaction", agentName, channelType,
                "Compacted %d turns (%d chars) via %s in %d ms over %d summary call%s".formatted(result.turnsCompacted(),
                        result.summaryChars(), Objects.requireNonNullElse(summary.answeredBy(), summary.first().label()),
                        tookMs, result.calls(), result.calls() == 1 ? "" : "s"));
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

    /** The floor each conversation last logged a no-cut skip on, so the token trigger's skip is not repeated every turn. */
    private static final Map<Long, String> NO_CUT_LOGGED_ON = new ConcurrentHashMap<>();

    private static boolean firstNoCutOnThisFloor(Long conversationId, @Nullable Instant floor) {
        var key = String.valueOf(floor);
        return !key.equals(NO_CUT_LOGGED_ON.put(conversationId, key));
    }

    private record NextTurn(Conversation conversation, List<ChatMessage> messages, List<ToolDef> tools) {
    }
}
