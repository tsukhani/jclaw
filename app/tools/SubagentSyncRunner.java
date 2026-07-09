package tools;

import agents.AgentRunner;
import agents.RunCancelledException;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import services.ConfigService;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool.SyncRunOutcome;

import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * JCLAW-677: synchronous child dispatch and the timeout/cancel policy,
 * extracted from {@link SubagentSpawnTool}. Dispatches the child run to a
 * virtual thread and enforces the idle/ceiling budgets via {@link #awaitFuture}.
 */
final class SubagentSyncRunner {

    /** JCLAW-424: idle-await poll cadence — how often the await loop wakes to
     *  re-check the inactivity and ceiling budgets while the child runs. */
    private static final long IDLE_POLL_INTERVAL_MS = 1000;

    /** JCLAW-424: bounded grace after a timeout flips the cooperative-stop flag,
     *  giving the child's next checkpoint a chance to observe it and stop cleanly
     *  before the spawn path unregisters the run. */
    private static final long STOP_GRACE_MS = 3000;

    private SubagentSyncRunner() {}

    /**
     * Step 4: dispatch the child run to a virtual thread, enforce the
     * wall-clock budget via Future.get(timeout), and translate the await
     * outcome into a {@link SyncRunOutcome}. The child Agent + Conversation
     * are re-fetched inside the VT so they're managed in a fresh persistence
     * context.
     *
     * <p>JCLAW-267: inline mode wraps the runner in
     * {@link services.ConversationService#withSubagentRunIdMarker} so every Message
     * AgentRunner persists during the child run carries the SubagentRun id
     * for the chat UI's nested-turn folding. Session mode passes the null
     * marker through.
     *
     * <p>JCLAW-291: cooperative-cancel registration via
     * {@link services.SubagentRegistry}. We deliberately do NOT capture the
     * carrier Thread — see services.SubagentRegistry's class comment for
     * the H2 FileChannel post-mortem.
     */
    @SuppressWarnings("java:S1181")
    static SyncRunOutcome runChildSynchronously(Long runId, Long childAgentId,
                                                Long childConvId, String task,
                                                int timeoutSeconds, boolean inlineMode) {
        var future = new CompletableFuture<AgentRunner.RunResult>();
        SubagentRegistry.register(runId, future);
        Thread.ofVirtual().name("subagent-" + runId).start(() -> {
            try {
                var childAgent = Tx.run(() -> (Agent) Agent.findById(childAgentId));
                var childConv = Tx.run(() -> (Conversation) Conversation.findById(childConvId));
                if (childAgent == null || childConv == null) {
                    throw new IllegalStateException(
                            "Subagent rows vanished before AgentRunner.run");
                }
                future.complete(SubagentAcpRunner.executeChildRun(runId, childAgent, childConv, task, inlineMode));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });

        try {
            int ceilingSeconds = ConfigService.getInt(SubagentSpawnTool.MAX_WALLCLOCK_KEY,
                    SubagentSpawnTool.DEFAULT_MAX_WALLCLOCK_SECONDS);
            return awaitFuture(future, timeoutSeconds, ceilingSeconds, runId);
        } finally {
            SubagentRegistry.unregister(runId);
        }
    }

    /**
     * JCLAW-424: await the child future on an IDLE-timeout model. The future is
     * polled every {@link #IDLE_POLL_INTERVAL_MS}; the run times out only when
     * it has been INACTIVE (no {@link services.SubagentRegistry#touch}) for
     * longer than {@code idleBudgetSeconds}, or when its total runtime exceeds
     * the operator-configured {@link SubagentSpawnTool#MAX_WALLCLOCK_KEY} ceiling.
     * An actively-working child resets the idle clock at every LLM round / tool
     * call (see {@link agents.AgentRunner#checkSubagentCancel}) and so never trips
     * the idle budget — fixing the prior failure mode where a child still producing
     * output (e.g. a long report-generation turn) was killed at a fixed
     * wall-clock deadline. Every other terminal path (success, kill,
     * yield-watchdog timeout, runner exception) is translated to a uniform
     * {@link SyncRunOutcome} exactly as before.
     *
     * <p>Public for {@code SubagentSpawnToolTest} (default package).
     */
    static SyncRunOutcome awaitFuture(
            CompletableFuture<AgentRunner.RunResult> future,
            int idleBudgetSeconds, int ceilingSeconds, Long runId) {
        long idleBudgetNanos = TimeUnit.SECONDS.toNanos(Math.max(1, idleBudgetSeconds));
        long ceilingNanos = ceilingSeconds <= 0 ? Long.MAX_VALUE : TimeUnit.SECONDS.toNanos(ceilingSeconds);
        long startNanos = System.nanoTime();
        while (true) {
            try {
                var result = future.get(IDLE_POLL_INTERVAL_MS, TimeUnit.MILLISECONDS);
                var reply = result == null ? "" : result.response();
                var truncated = result != null && result.truncated();
                return new SyncRunOutcome(reply, SubagentRun.Status.COMPLETED,
                        reply, null, false, truncated);
            } catch (TimeoutException _) {
                // Poll tick — the child is still running. Check the two budgets.
                long idleNanos = SubagentRegistry.nanosSinceActivity(runId);
                if (idleNanos < 0) idleNanos = System.nanoTime() - startNanos; // unregistered fallback
                if (idleNanos > idleBudgetNanos) {
                    return stopChildOnTimeout(future, runId,
                            "Subagent run exceeded its %d-second idle budget (no activity)".formatted(idleBudgetSeconds));
                }
                if (System.nanoTime() - startNanos > ceilingNanos) {
                    return stopChildOnTimeout(future, runId,
                            "Subagent run exceeded the absolute %d-second ceiling".formatted(ceilingSeconds));
                }
                // budgets intact — keep polling
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                var reason = "Parent thread interrupted while awaiting subagent";
                return new SyncRunOutcome("", SubagentRun.Status.FAILED, reason, reason, false, false);
            } catch (CancellationException _) {
                // JCLAW-291: kill primitive cancelled our Future. Registry already KILLED.
                return new SyncRunOutcome("", SubagentRun.Status.KILLED,
                        "Killed by operator", null, true, false);
            } catch (ExecutionException ee) {
                return fromExecutionException(ee);
            }
        }
    }

    /**
     * JCLAW-424: on idle/ceiling timeout, flip the cooperative-stop flag and
     * give the child a bounded grace ({@link #STOP_GRACE_MS}) to observe it at
     * its next checkpoint and unwind cleanly — so the absolute ceiling actually
     * halts a runaway rather than orphaning its VT — then return the TIMEOUT
     * outcome.
     */
    private static SyncRunOutcome stopChildOnTimeout(
            CompletableFuture<AgentRunner.RunResult> future, Long runId, String reason) {
        SubagentRegistry.requestStop(runId);
        long graceDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(STOP_GRACE_MS);
        while (!future.isDone() && System.nanoTime() < graceDeadline) {
            try {
                future.get(200, TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
                // still unwinding — keep waiting within grace
            } catch (InterruptedException _) {
                // Restore the interrupt flag and stop waiting — don't swallow it
                // (java:S2142); we then finalize the TIMEOUT outcome below.
                Thread.currentThread().interrupt();
                break;
            } catch (Exception _) {
                break; // child stopped (RunCancelled / cancellation / completion)
            }
        }
        future.cancel(false);
        // JCLAW-424 (AC5): surface the child's partial output so the parent can
        // salvage progress rather than receiving an empty reply. The audit row's
        // outcome still records the timeout reason (terminalOutcome == reason).
        var partial = SubagentRunStore.capturePartialReply(runId);
        return new SyncRunOutcome(partial, SubagentRun.Status.TIMEOUT, reason, reason, false, false);
    }

    /**
     * Translate an {@link ExecutionException} from the child future into a
     * {@link SyncRunOutcome}, preserving the JCLAW-291 (kill) and JCLAW-326
     * (yield-watchdog timeout) terminal semantics.
     */
    private static SyncRunOutcome fromExecutionException(ExecutionException ee) {
        var cause = ee.getCause() != null ? ee.getCause() : ee;
        if (cause instanceof RunCancelledException) {
            // JCLAW-291: runner observed the cancel flag at a checkpoint and threw.
            return new SyncRunOutcome("", SubagentRun.Status.KILLED,
                    "Killed by operator", null, true, false);
        }
        if (cause instanceof TimeoutException) {
            // JCLAW-326: yield watchdog completed the future exceptionally.
            var reason = cause.getMessage() != null ? cause.getMessage() : "yield timeout";
            return new SyncRunOutcome("", SubagentRun.Status.TIMEOUT, reason, reason, false, false);
        }
        var reason = cause.getMessage() != null ? cause.getMessage() : cause.toString();
        return new SyncRunOutcome("", SubagentRun.Status.FAILED, reason, reason, false, false);
    }
}
