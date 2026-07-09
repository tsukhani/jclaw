package tools;

import agents.AgentRunner;
import agents.ToolContext;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import services.ConfigService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool.SyncRunOutcome;
import utils.GsonHolder;

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

    private static final String FIELD_ERROR = "error";

    /** JCLAW-497/498: detached async subagent OUTCOMES, keyed by runId, handed off
     *  from a spawn (single async-in-task or a batch fan-out) to the inline
     *  block-await in subagent_yield. These children have no persistent
     *  conversation to resume into, so the chat announce/resume path doesn't
     *  apply; the parent collects results via a blocking yield. */
    private static final ConcurrentHashMap<Long, CompletableFuture<SyncRunOutcome>>
            ASYNC_OUTCOMES = new ConcurrentHashMap<>();

    /** JCLAW-498: outstanding async-child runIds grouped by the spawning parent's
     *  scope ({@code task:<id>} or {@code conv:<id>}), so subagent_yield all=true
     *  can collect every child this parent fanned out without re-listing run ids. */
    private static final ConcurrentHashMap<String, Set<Long>>
            OUTSTANDING_BY_SCOPE = new ConcurrentHashMap<>();

    private SubagentAsyncRunner() {}

    /** Launch the async-spawn background VT and return the immediate JSON
     *  acknowledgement payload. */
    static String launchAsyncSpawn(Long runId, Long childAgentId, Long childConvId,
                                   Long parentConvId, String parentAgentName,
                                   SubagentSpawnArgs parsed, String runIdStr) {
        Thread.ofVirtual().name("subagent-async-" + runId).start(() ->
                runAsyncAndAnnounce(runId, childAgentId, childConvId, parentConvId,
                        parentAgentName, parsed.mode(), parsed.context(), parsed.label(),
                        parsed.timeoutSeconds(), parsed.task()));
        var asyncPayload = new LinkedHashMap<String, Object>();
        asyncPayload.put(SubagentSpawnTool.FIELD_RUN_ID, runIdStr);
        asyncPayload.put(SubagentSpawnTool.FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        asyncPayload.put(SubagentSpawnTool.FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        return GsonHolder.INSTANCE.toJson(asyncPayload, Map.class);
    }

    /** JCLAW-498: the current tool-dispatch scope key — {@code task:<id>} in a task
     *  fire, {@code conv:<id>} in a chat turn, or null when neither is bound. */
    static String currentScopeKey() {
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
        var set = OUTSTANDING_BY_SCOPE.get(key);
        return set == null ? List.of() : new ArrayList<>(set);
    }

    private static void forgetOutstanding(Long runId) {
        for (var set : OUTSTANDING_BY_SCOPE.values()) set.remove(runId);
    }

    /**
     * JCLAW-497/498: dispatch a child on a VT (parallel) and stash a future that
     * completes with its terminal outcome for subagent_yield to block-await inline.
     * No announce / parent-resume — the parent collects via a blocking yield. Used
     * by the single async-in-task path and by batch fan-out (chat or task).
     */
    static void dispatchDetachedAsync(Long runId, Long childAgentId, Long childConvId,
                                      String parentAgentName, String mode, String context,
                                      int timeoutSeconds, String task, String scopeKey) {
        var outcomeFuture = new CompletableFuture<SyncRunOutcome>();
        ASYNC_OUTCOMES.put(runId, outcomeFuture);
        if (scopeKey != null) {
            OUTSTANDING_BY_SCOPE.computeIfAbsent(scopeKey, _ -> ConcurrentHashMap.newKeySet())
                    .add(runId);
        }
        Thread.ofVirtual().name("subagent-async-task-" + runId).start(() ->
                runAsyncDetached(runId, childAgentId, childConvId, parentAgentName,
                        mode, context, timeoutSeconds, task, outcomeFuture));
    }

    /** JCLAW-497: single async spawn inside a task fire. Returns run_id immediately. */
    static String launchAsyncSpawnForTask(Long runId, Long childAgentId, Long childConvId,
                                          String parentAgentName, SubagentSpawnArgs parsed, String runIdStr) {
        dispatchDetachedAsync(runId, childAgentId, childConvId, parentAgentName,
                parsed.mode(), parsed.context(), parsed.timeoutSeconds(), parsed.task(), currentScopeKey());
        var payload = new LinkedHashMap<String, Object>();
        payload.put(SubagentSpawnTool.FIELD_RUN_ID, runIdStr);
        payload.put(SubagentSpawnTool.FIELD_CONVERSATION_ID, String.valueOf(childConvId));
        payload.put(SubagentSpawnTool.FIELD_STATUS, SubagentRun.Status.RUNNING.name());
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /**
     * JCLAW-497/498: run a detached async child to completion and hand its terminal
     * outcome to the awaiting subagent_yield. Mirrors {@link #runAsyncAndAnnounce}
     * minus the announce + parent-resume: run the child, do the terminal audit +
     * lifecycle bookkeeping, then complete {@code outcomeFuture}.
     */
    @SuppressWarnings("java:S1181")
    private static void runAsyncDetached(Long runId, Long childAgentId, Long childConvId,
                                         String parentAgentName, String mode, String context,
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
     *  it can't hang even if completion signalling misbehaves. */
    private static SyncRunOutcome awaitOutcomeFuture(CompletableFuture<SyncRunOutcome> f)
            throws InterruptedException, ExecutionException, TimeoutException {
        int ceiling = ConfigService.getInt(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS);
        return ceiling <= 0 ? f.get() : f.get(ceiling + 30L, TimeUnit.SECONDS);
    }

    private static LinkedHashMap<String, Object> outcomeMap(Long runId, SyncRunOutcome outcome) {
        var m = new LinkedHashMap<String, Object>();
        m.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
        m.put(SubagentSpawnTool.FIELD_REPLY, outcome.reply());
        m.put(SubagentSpawnTool.FIELD_STATUS, outcome.terminalStatus().name());
        if (outcome.replyTruncated()) m.put(SubagentSpawnTool.FIELD_TRUNCATED, Boolean.TRUE);
        return m;
    }

    /**
     * JCLAW-497: block-await ONE async subagent's outcome (subagent_yield with a
     * single runId) and return it as the same JSON a synchronous spawn returns.
     */
    static String awaitAsyncOutcome(Long runId) {
        var f = ASYNC_OUTCOMES.remove(runId);
        forgetOutstanding(runId);
        if (f == null) {
            return "Error: no pending async subagent for runId " + runId
                    + " (already collected, or not an async spawn).";
        }
        try {
            return GsonHolder.INSTANCE.toJson(outcomeMap(runId, awaitOutcomeFuture(f)), Map.class);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return "Error: interrupted while awaiting async subagent " + runId + ".";
        } catch (Exception e) {
            return "Error: failed awaiting async subagent " + runId + ": " + e.getMessage();
        }
    }

    /**
     * JCLAW-498: block-await MANY async subagents (subagent_yield runIds[] / all)
     * and return {@code {results:[...], count:n}}. Each entry mirrors a sync spawn's
     * shape; a missing/already-collected runId is reported in-line rather than
     * failing the whole batch. The children ran concurrently, so the wall-clock of
     * this collect ≈ the slowest still-running child.
     */
    static String awaitAsyncOutcomes(List<Long> runIds) {
        var results = new ArrayList<Object>();
        for (var runId : runIds) {
            var f = ASYNC_OUTCOMES.remove(runId);
            forgetOutstanding(runId);
            if (f == null) {
                var miss = new LinkedHashMap<String, Object>();
                miss.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
                miss.put(SubagentSpawnTool.FIELD_STATUS, "UNKNOWN");
                miss.put(FIELD_ERROR, "no pending async subagent (already collected, or not an async spawn)");
                results.add(miss);
                continue;
            }
            try {
                results.add(outcomeMap(runId, awaitOutcomeFuture(f)));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                var err = new LinkedHashMap<String, Object>();
                err.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
                err.put(SubagentSpawnTool.FIELD_STATUS, "FAILED");
                err.put(FIELD_ERROR, "interrupted while awaiting");
                results.add(err);
            } catch (Exception e) {
                var err = new LinkedHashMap<String, Object>();
                err.put(SubagentSpawnTool.FIELD_RUN_ID, String.valueOf(runId));
                err.put(SubagentSpawnTool.FIELD_STATUS, "FAILED");
                err.put(FIELD_ERROR, e.getMessage());
                results.add(err);
            }
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("results", results);
        payload.put("count", results.size());
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
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
     * @param context         optional context string included in the
     *                        announce Message body
     * @param label           short label for the announce card
     * @param timeoutSeconds  wall-clock cap; on expiry the SubagentRun is
     *                        marked TIMED_OUT
     * @param task            the user-prompt-equivalent string the child
     *                        agent processes
     */
    @SuppressWarnings("java:S1181")
    static void runAsyncAndAnnounce(Long runId, Long childAgentId, Long childConvId,
                                    Long parentConvId, String parentAgentName,
                                    String mode, String context, String label,
                                    int timeoutSeconds, String task) {
        var future = startAsyncChild(runId, childAgentId, childConvId, task);
        SyncRunOutcome outcome;
        try {
            outcome = awaitAsyncFuture(future, timeoutSeconds,
                    ConfigService.getInt(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                            SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS), runId);
        } finally {
            SubagentRegistry.unregister(runId);
        }

        // JCLAW-291: kill primitive owns the terminal state on this path —
        // skip every downstream side effect (audit write, announce post,
        // lifecycle event, yield resume). The operator's slash-command
        // response is the user-visible kill confirmation.
        if (outcome.killedByOperator()) {
            EventLogger.flush();
            return;
        }

        // Update the SubagentRun audit row (terminal status + outcome).
        // JCLAW-271: skip the write when /subagent kill already stamped
        // KILLED — the operator's reason is the source of truth, and
        // overwriting it here would replace "Killed by operator" with the
        // TIMEOUT / FAILED message we synthesized when the cancelled
        // Future threw.
        SubagentRunStore.persistAsyncTerminalRun(runId, outcome);

        // Build + post the announce Message into the parent Conversation.
        var yieldedFlag = SubagentResponses.postAnnounceAndReadYieldFlag(runId, childConvId, parentConvId,
                label, outcome);

        SubagentResponses.emitTerminalEvent(parentAgentName, SubagentRunStore.lookupAgentName(childAgentId),
                String.valueOf(runId), mode, context, outcome.terminalStatus(), outcome.errorReason());
        EventLogger.flush();

        // JCLAW-273: resume the parent agent's logical turn for a yielded
        // caller. The announce Message we just posted is the parent's next
        // user input; calling AgentRunner.run kicks off a fresh turn that
        // picks up the announce via ConversationService.loadRecentMessages
        // (which keeps USER-role announce rows visible to the LLM) and
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
            Long runId, Long childAgentId, Long childConvId, String task) {
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
                future.complete(SubagentAcpRunner.executeChildRun(runId, childAgent, childConv, task, false));
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
