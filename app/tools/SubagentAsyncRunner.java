package tools;

import agents.AgentRunner;
import agents.ToolContext;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool.SyncRunOutcome;
import utils.GsonHolder;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JCLAW-677: async and detached subagent execution plus the yield handoff,
 * extracted from {@link SubagentSpawnTool}. Owns the async-outcome futures and
 * the outstanding-by-scope registry the block-await in {@code subagent_yield}
 * collects against.
 */
final class SubagentAsyncRunner {

    /** JCLAW-497/498: async subagent OUTCOMES, keyed by runId, handed off from a
     *  spawn (single async, or a batch fan-out) to the inline block-await in
     *  subagent_yield. An entry's scope is what subagent_yield all=true collects
     *  by, so expiry and the size cap also bound that registry.
     *
     *  <p>Bounded so an uncollected outcome can't pin its future — and the full
     *  reply string it holds — for the JVM lifetime. subagent_yield removes an
     *  entry on collect, but a batch fan-out with no matching yield (or a partial
     *  collect) would otherwise leak the future forever. {@code expireAfterWrite}
     *  comfortably dominates the max child wall-clock ceiling
     *  ({@code subagent.maxWallClockSeconds}, default 1800s) plus generous
     *  collection headroom, so a legitimately-running-then-collected child is
     *  never evicted before its yield; {@code maximumSize} is the hard leak cap.
     *  Collect semantics are preserved: {@link Cache#asMap() asMap().remove}
     *  still retrieves any live outcome, and an expired/evicted one reads back as
     *  an already-collected miss instead of hanging. Mirrors the Caffeine memo in
     *  {@link llm.TokenUsageEstimator}. */
    private static final Cache<Long, Pending> ASYNC_OUTCOMES =
            Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofHours(2))
                    .maximumSize(4_096)
                    .build();

    /** An uncollected outcome and the spawning parent's scope ({@code task:<id>} or {@code conv:<id>}). */
    private record Pending(@Nullable String scopeKey, CompletableFuture<SyncRunOutcome> outcome) {}

    /** Runs a subagent_yield is block-awaiting; a chat child among them posts no announce, the yield returning its whole reply. */
    private static final Set<Long> AWAITED = ConcurrentHashMap.newKeySet();

    /** What a batch yield returns once its turn is stopped; the turn persists none of it. */
    private static final String STOPPED = "{\"results\":[],\"count\":0,\"stopped\":true,"
            + "\"note\":\"The turn was stopped while waiting; every run stays collectable by the next subagent_yield.\"}";

    private SubagentAsyncRunner() {}

    /** Launch the async-spawn background VT and return the immediate JSON
     *  acknowledgment payload. */
    static String launchAsyncSpawn(Long runId, @Nullable Long childAgentId, @Nullable Long childConvId,
                                   Long parentConvId, String parentAgentName,
                                   SubagentSpawnArgs parsed, String runIdStr) {
        var outcome = track(runId, currentScopeKey());
        Thread.ofVirtual().name("subagent-async-" + runId).start(() ->
                runAsyncAndAnnounce(runId, childAgentId, childConvId, parentConvId,
                        parentAgentName, parsed.mode(), parsed.context(), parsed.label(),
                        parsed.timeoutSeconds(), parsed.resolvedTask(), outcome));
        var asyncPayload = new LinkedHashMap<String, Object>();
        asyncPayload.put(SubagentSpawnTool.FIELD_RUN_ID, runIdStr);
        asyncPayload.put(SubagentSpawnTool.FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        asyncPayload.put(SubagentSpawnTool.FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        return GsonHolder.GSON.toJson(asyncPayload, Map.class);
    }

    /** JCLAW-498: the current tool-dispatch scope key — {@code task:<id>} in a task
     *  fire, {@code conv:<id>} in a chat turn, or null when neither is bound. */
    static @Nullable String currentScopeKey() {
        var t = ToolContext.taskRunId();
        if (t != null) return "task:" + t;
        var c = ToolContext.conversationId();
        return c != null ? "conv:" + c : null;
    }

    /** JCLAW-498: the outstanding async-child runIds for the current scope, for
     *  subagent_yield all=true. */
    static List<Long> outstandingForCurrentScope() {
        var key = currentScopeKey();
        if (key == null) return List.of();
        return ASYNC_OUTCOMES.asMap().entrySet().stream()
                .filter(e -> key.equals(e.getValue().scopeKey()))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    /** Stash a future for {@code runId}'s terminal outcome, collectable by a subagent_yield in {@code scopeKey}. */
    private static CompletableFuture<SyncRunOutcome> track(Long runId, @Nullable String scopeKey) {
        var outcome = new CompletableFuture<SyncRunOutcome>();
        ASYNC_OUTCOMES.put(runId, new Pending(scopeKey, outcome));
        return outcome;
    }

    /**
     * JCLAW-497/498: dispatch a child on a VT (parallel) and stash a future that
     * completes with its terminal outcome for subagent_yield to block-await inline.
     * No announce / parent-resume — the parent collects via a blocking yield. Used
     * by the single async-in-task path and by batch fan-out (chat or task).
     */
    static void dispatchDetachedAsync(Long runId, @Nullable Long childAgentId, @Nullable Long childConvId,
                                      String parentAgentName, @Nullable String mode, @Nullable String context,
                                      int timeoutSeconds, String task, @Nullable String scopeKey) {
        var outcomeFuture = track(runId, scopeKey);
        Thread.ofVirtual().name("subagent-async-task-" + runId).start(() ->
                runAsyncDetached(runId, childAgentId, childConvId, parentAgentName,
                        mode, context, timeoutSeconds, task, outcomeFuture));
    }

    /** JCLAW-497: single async spawn inside a task fire. Returns run_id immediately. */
    static String launchAsyncSpawnForTask(Long runId, @Nullable Long childAgentId, @Nullable Long childConvId,
                                          String parentAgentName, SubagentSpawnArgs parsed, String runIdStr) {
        dispatchDetachedAsync(runId, childAgentId, childConvId, parentAgentName,
                parsed.resolvedMode(), parsed.resolvedContext(), parsed.timeoutSeconds(), parsed.resolvedTask(), currentScopeKey());
        var payload = new LinkedHashMap<String, Object>();
        payload.put(SubagentSpawnTool.FIELD_RUN_ID, runIdStr);
        payload.put(SubagentSpawnTool.FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        payload.put(SubagentSpawnTool.FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        return GsonHolder.GSON.toJson(payload, Map.class);
    }

    /**
     * JCLAW-497/498: run a detached async child to completion and hand its terminal
     * outcome to the awaiting subagent_yield. Mirrors {@link #runAsyncAndAnnounce}
     * minus the announce + parent-resume: run the child, do the terminal audit +
     * lifecycle bookkeeping, then complete {@code outcomeFuture}.
     */
    @SuppressWarnings("java:S1181")
    private static void runAsyncDetached(Long runId, @Nullable Long childAgentId, @Nullable Long childConvId,
                                         String parentAgentName, @Nullable String mode, @Nullable String context,
                                         int timeoutSeconds, String task,
                                         CompletableFuture<SyncRunOutcome> outcomeFuture) {
        try {
            var future = startAsyncChild(runId, childAgentId, childConvId, task);
            SyncRunOutcome outcome;
            try {
                outcome = awaitAsyncFuture(future, timeoutSeconds,
                        ConfigService.getInt(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                                SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS), runId);
            } finally {
                SubagentRegistry.unregister(runId);
            }
            if (!outcome.killedByOperator()) {
                SubagentRunStore.persistAsyncTerminalRun(runId, outcome);
                SubagentResponses.emitTerminalEvent(parentAgentName, SubagentRunStore.lookupAgentName(childAgentId),
                        String.valueOf(runId), mode, context, outcome.terminalStatus(), outcome.errorReason());
            }
            EventLogger.flush();
            outcomeFuture.complete(outcome);
        } catch (Throwable t) {
            outcomeFuture.completeExceptionally(t);
        }
    }

    /** Block-await one detached async outcome, bounded a little past the ceiling so
     *  it can't hang even if completion signaling misbehaves; null when {@code stop} completes first. */
    private static @Nullable SyncRunOutcome awaitOutcomeFuture(CompletableFuture<SyncRunOutcome> f,
                                                               CompletableFuture<?> stop)
            throws InterruptedException, ExecutionException, TimeoutException {
        int ceiling = ConfigService.getInt(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS);
        var first = CompletableFuture.anyOf(f, stop);
        if (ceiling <= 0) first.get();
        else first.get(ceiling + 30L, TimeUnit.SECONDS);
        return f.isDone() ? f.get() : null;
    }

    private static LinkedHashMap<String, Object> outcomeMap(Long runId, SyncRunOutcome outcome) {
        var m = new LinkedHashMap<String, Object>();
        m.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
        m.put(SubagentSpawnTool.FIELD_REPLY, outcome.reply());
        m.put(SubagentSpawnTool.FIELD_STATUS, outcome.terminalStatus().name());
        var failure = outcome.failure();
        if (failure != null) m.put(SubagentSpawnTool.FIELD_ERROR, failure);
        if (outcome.replyTruncated()) m.put(SubagentSpawnTool.FIELD_TRUNCATED, Boolean.TRUE);
        return m;
    }

    /**
     * JCLAW-497: block-await ONE async subagent's outcome (subagent_yield with a
     * single runId) and return it as the same JSON a synchronous spawn returns.
     */
    static String awaitAsyncOutcome(Long runId) {
        var pending = ASYNC_OUTCOMES.asMap().remove(runId);
        if (pending == null) {
            return "Error: no pending async subagent for runId " + runId
                    + " (already collected, or not an async spawn).";
        }
        var stop = new CompletableFuture<Void>();
        var relay = SubagentChatBridge.relayActivity(List.of(runId), SubagentChatBridge.ACTIVITY_RELAY_INTERVAL);
        try (var _ = SubagentRegistry.waitingOn(List.of(runId)); var _ = ToolContext.onCancel(() -> stop.complete(null))) {
            var outcome = awaitOutcomeFuture(pending.outcome(), stop);
            if (outcome == null || ToolContext.cancelled()) {
                ASYNC_OUTCOMES.put(runId, pending);
                return "Error: the turn was stopped while awaiting async subagent " + runId
                        + "; it stays collectable by the next subagent_yield.";
            }
            return GsonHolder.GSON.toJson(outcomeMap(runId, outcome), Map.class);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: interrupted while awaiting async subagent " + runId + ".";
        } catch (Exception e) {
            return "Error: failed awaiting async subagent " + runId + ": " + e.getMessage();
        } finally {
            relay.cancel(false);
        }
    }

    /**
     * JCLAW-498: block-await MANY async subagents (subagent_yield runIds[] / all)
     * and return {@code {results:[...], count:n}}. Each entry mirrors a sync spawn's
     * shape; a run no longer held here returns its saved result when it finished in
     * this conversation or task, and is otherwise reported in-line rather than failing
     * the whole batch. The children ran concurrently, so the wall-clock of this
     * collect ≈ the slowest still-running child. A stopped turn ends the wait and
     * hands every claimed outcome back, since it persists nothing it returns.
     */
    static String awaitAsyncOutcomes(List<Long> runIds) {
        var results = new ArrayList<Object>();
        var claimed = new LinkedHashMap<Long, Pending>();
        for (var runId : runIds) {
            // Marked before the claim, so a child that ends in between still finds this yield waiting.
            AWAITED.add(runId);
            var pending = ASYNC_OUTCOMES.asMap().remove(runId);
            if (pending != null) claimed.put(runId, pending);
        }
        var stop = new CompletableFuture<Void>();
        var stopped = false;
        var relay = SubagentChatBridge.relayActivity(runIds, SubagentChatBridge.ACTIVITY_RELAY_INTERVAL);
        try (var _ = SubagentRegistry.waitingOn(runIds); var _ = ToolContext.onCancel(() -> stop.complete(null))) {
            for (var runId : runIds) {
                var pending = claimed.get(runId);
                if (pending == null) {
                    results.add(savedOrMissing(runId));
                    continue;
                }
                try {
                    var outcome = awaitOutcomeFuture(pending.outcome(), stop);
                    if (outcome == null) {
                        stopped = true;
                        break;
                    }
                    results.add(outcomeMap(runId, outcome));
                } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    var err = new LinkedHashMap<String, Object>();
                    err.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
                    err.put(SubagentSpawnTool.FIELD_STATUS, "FAILED");
                    err.put(SubagentSpawnTool.FIELD_ERROR, "interrupted while awaiting");
                    results.add(err);
                } catch (Exception e) {
                    var err = new LinkedHashMap<String, Object>();
                    err.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
                    err.put(SubagentSpawnTool.FIELD_STATUS, "FAILED");
                    err.put(SubagentSpawnTool.FIELD_ERROR, e.getMessage());
                    results.add(err);
                }
            }
            if (stopped || ToolContext.cancelled()) {
                claimed.forEach(ASYNC_OUTCOMES::put);
                return STOPPED;
            }
        } finally {
            AWAITED.removeAll(runIds);
            relay.cancel(false);
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("results", results);
        payload.put("count", results.size());
        return GsonHolder.GSON.toJson(payload, Map.class);
    }

    /** A run this yield holds no outcome for: its saved result when it finished in this scope, else a miss. */
    private static Map<String, Object> savedOrMissing(Long runId) {
        var saved = SubagentRunStore.savedOutcome(runId);
        if (saved != null) return saved;
        var miss = new LinkedHashMap<String, Object>();
        miss.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
        miss.put(SubagentSpawnTool.FIELD_STATUS, "UNKNOWN");
        miss.put(SubagentSpawnTool.FIELD_ERROR, "no pending async subagent (already collected, or not an async spawn)");
        return miss;
    }

    /**
     * JCLAW-270: async-spawn body. Runs on a dedicated virtual thread
     * (name {@code subagent-async-<runId>}) so the parent's tool-call return
     * doesn't wait on the child's wall clock. The shape mirrors the
     * synchronous flow (AgentRunner.run with a Future.get timeout) — when it
     * terminates, this method writes the announce Message into the parent
     * Conversation, updates the SubagentRun audit row, and fires the
     * terminal lifecycle event (SUBAGENT_COMPLETE / SUBAGENT_ERROR /
     * SUBAGENT_TIMEOUT).
     *
     * <p>Restart resilience: in-flight VTs do not survive a JVM restart.
     * {@link jobs.SubagentOrphanRecoveryJob} marks any RUNNING SubagentRun
     * older than a small window FAILED at boot so the audit log reflects
     * truth; no announce Message is posted for orphans (the parent
     * conversation may have moved on, surfacing a stale completion card
     * would be more disruptive than helpful).
     *
     * @param runId           the {@link models.SubagentRun} audit-row id
     * @param childAgentId    id of the child agent to run
     * @param childConvId     the child Conversation id (inline reuses the
     *                        parent; session mode has a separate child)
     * @param parentConvId    the parent Conversation id where the announce
     *                        Message will be written
     * @param parentAgentName parent agent's name (used in announce metadata
     *                        and lifecycle events)
     * @param mode            {@code "inline"} or {@code "session"}
     * @param context         the spawn's context mode, for the lifecycle
     *                        event
     * @param label           short label for the announce card
     * @param timeoutSeconds  idle budget; on expiry the SubagentRun is
     *                        marked TIMEOUT
     * @param task            the user-prompt-equivalent string the child
     *                        agent processes
     */
    static void runAsyncAndAnnounce(Long runId, @Nullable Long childAgentId, @Nullable Long childConvId,
                                    Long parentConvId, String parentAgentName,
                                    @Nullable String mode, @Nullable String context, @Nullable String label,
                                    int timeoutSeconds, String task) {
        runAsyncAndAnnounce(runId, childAgentId, childConvId, parentConvId, parentAgentName,
                mode, context, label, timeoutSeconds, task, null);
    }

    /** {@link #runAsyncAndAnnounce(Long, Long, Long, Long, String, String, String, String, int, String)}
     *  that also completes {@code collected}, the outcome a subagent_yield runIds/all collects. */
    @SuppressWarnings("java:S1181")
    private static void runAsyncAndAnnounce(Long runId, @Nullable Long childAgentId, @Nullable Long childConvId,
                                            Long parentConvId, String parentAgentName,
                                            @Nullable String mode, @Nullable String context, @Nullable String label,
                                            int timeoutSeconds, String task,
                                            @Nullable CompletableFuture<SyncRunOutcome> collected) {
        var future = startAsyncChild(runId, childAgentId, childConvId, task);
        SyncRunOutcome outcome;
        try {
            outcome = awaitAsyncFuture(future, timeoutSeconds,
                    ConfigService.getInt(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                            SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS), runId);
        } finally {
            SubagentRegistry.unregister(runId);
        }

        try {
            // JCLAW-291: kill primitive owns the terminal state on this path —
            // skip every downstream side effect (audit write, announce post,
            // lifecycle event, yield resume). The operator's slash-command
            // response is the user-visible kill confirmation.
            if (outcome.killedByOperator()) {
                EventLogger.flush();
                return;
            }

            // JCLAW-271: persistAsyncTerminalRun skips the write when /subagent kill already
            // stamped KILLED — the operator's reason is the source of truth, and overwriting
            // it would replace "Killed by operator" with the TIMEOUT / FAILED message we
            // synthesized when the canceled Future threw.
            SubagentRunStore.persistAsyncTerminalRun(runId, outcome);

            // Checked before collected completes: the yield it wakes unmarks the run.
            var yieldedFlag = AWAITED.contains(runId) ? Boolean.FALSE
                    : SubagentResponses.postAnnounceAndReadYieldFlag(runId, childConvId, parentConvId,
                            label, outcome);

            SubagentResponses.emitTerminalEvent(parentAgentName, SubagentRunStore.lookupAgentName(childAgentId),
                    String.valueOf(runId), mode, context, outcome.terminalStatus(), outcome.errorReason());
            EventLogger.flush();
            if (collected != null) collected.complete(outcome);

            // JCLAW-273: resume the parent agent's logical turn for a yielded
            // caller. The announce Message we just posted is the parent's next
            // user input; calling AgentRunner.resumeAndDeliver kicks off a fresh turn that
            // picks up the announce via ConversationService.loadRecentMessages and
            // produces a final assistant reply.
            if (Boolean.TRUE.equals(yieldedFlag)) {
                try {
                    SubagentResponses.resumeParentAfterYield(parentConvId, runId);
                } catch (Throwable t) {
                    EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL,
                            "Failed to resume parent for yielded run " + runId
                                    + ": " + t.getMessage());
                }
            }
        } finally {
            // A kill or a throw above still ends a yield waiting on this run.
            if (collected != null) collected.complete(outcome);
        }
    }

    /**
     * JCLAW-270 / JCLAW-291: spin up the inner runner VT and register the
     * future with {@link services.SubagentRegistry} for cooperative cancel.
     * The carrier thread is intentionally NOT captured — see
     * {@code services.SubagentRegistry}'s class doc for the H2 FileChannel
     * post-mortem.
     */
    @SuppressWarnings("java:S1181")
    private static CompletableFuture<AgentRunner.RunResult> startAsyncChild(
            Long runId, @Nullable Long childAgentId, @Nullable Long childConvId, String task) {
        var future = new CompletableFuture<AgentRunner.RunResult>();
        SubagentRegistry.register(runId, future);
        Thread.ofVirtual().name("subagent-async-runner-" + runId).start(() -> {
            try {
                var childAgent = Tx.run(() -> (Agent) Agent.findById(childAgentId));
                var childConv = Tx.run(() -> (Conversation) Conversation.findById(childConvId));
                if (childAgent == null || childConv == null) {
                    throw new IllegalStateException(
                            "Subagent rows vanished before AgentRunner.run");
                }
                future.complete(SubagentRegistry.callAsRun(runId,
                        () -> SubagentAcpRunner.executeChildRun(runId, childAgent, childConv, task, false)));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * Async variant of {@link SubagentSyncRunner#awaitFuture}: identical catch
     * ladder plus a top-level {@link Throwable} guard so this background VT never
     * leaks an unchecked failure (which would lose the announce + terminal event
     * entirely).
     */
    @SuppressWarnings("java:S1181")
    private static SyncRunOutcome awaitAsyncFuture(
            CompletableFuture<AgentRunner.RunResult> future, int timeoutSeconds, int ceilingSeconds, Long runId) {
        try {
            return SubagentSyncRunner.awaitFuture(future, timeoutSeconds, ceilingSeconds, runId);
        } catch (Throwable t) {
            // Top-level guard: see method javadoc.
            var reason = t.getMessage() != null ? t.getMessage() : t.toString();
            return new SyncRunOutcome("", SubagentRun.Status.FAILED,
                    reason, reason, false, false);
        }
    }
}
