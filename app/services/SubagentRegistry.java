package services;

import models.Agent;
import models.SubagentRun;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;

/**
 * JCLAW-271: in-memory mapping from {@link SubagentRun} id to the
 * {@link Future} carrying the child run's virtual thread, so the operator-
 * facing {@code /subagent kill} slash command (and the SubagentRuns admin
 * page's kill button) can interrupt the in-flight VT and flip the audit row
 * to {@link SubagentRun.Status#KILLED}.
 *
 * <p>Both spawn paths in {@link tools.SpawnSubagentTool} register their
 * Future here at start and unregister when the run terminates (success,
 * failure, timeout, or kill). The registry is JVM-local — a restart loses
 * every entry, which is fine because the still-RUNNING DB rows are recovered
 * by {@link jobs.SubagentOrphanRecoveryJob} and the cancelled VTs are gone
 * anyway. No persistence required.
 *
 * <p>{@code kill} is idempotent: calling it on an id that's already terminal
 * (or never registered) returns a {@link KillResult} carrying a clear
 * "nothing to do" reason rather than throwing.
 */
public final class SubagentRegistry {

    private SubagentRegistry() {}

    /** Per-run handle paired with {@link #ACTIVE}. We track both the
     *  {@link Future} (so awaiting callers see CANCELLED on kill) and the
     *  carrier {@link Thread} (so {@link Thread#interrupt} actually
     *  propagates into the running task — JDK's CompletableFuture.cancel
     *  doesn't, by design). The thread is captured lazily on registration
     *  via {@link #registerWithThread} from inside the VT body. */
    private record Entry(Future<?> future, Thread thread) {}

    private static final Map<Long, Entry> ACTIVE = new ConcurrentHashMap<>();

    /** Register the VT-bearing Future under {@code runId}. Called by the
     *  spawn path right after dispatching the VT. Thread is captured lazily
     *  via {@link #registerWithThread} from inside the VT body. */
    public static void register(Long runId, Future<?> future) {
        if (runId == null || future == null) return;
        ACTIVE.put(runId, new Entry(future, null));
    }

    /** Like {@link #register} but also stores a reference to the carrier
     *  thread. The spawn path's outer await thread can't capture the
     *  inner VT's Thread object directly — only the inner VT body knows
     *  about itself. Called from inside the VT body before any blocking
     *  work begins; subsequent kills observe both the Future and the
     *  Thread, and call interrupt() on the thread to break out of
     *  socket reads / LLM HTTP calls in the runner. */
    public static void registerWithThread(Long runId, Future<?> future, Thread thread) {
        if (runId == null || future == null) return;
        ACTIVE.put(runId, new Entry(future, thread));
    }

    /** Unregister the entry for {@code runId}. Called from a {@code finally}
     *  block in the VT body so a normal completion clears the slot
     *  regardless of which terminal status it reaches. */
    public static void unregister(Long runId) {
        if (runId == null) return;
        ACTIVE.remove(runId);
    }

    /** Test-only: clear the entire registry. Production code must never
     *  call this — concurrent kills would race. */
    public static void clear() {
        ACTIVE.clear();
    }

    /** Snapshot of the currently-registered run ids. For tests + admin
     *  introspection only. */
    public static java.util.Set<Long> activeRunIds() {
        return java.util.Set.copyOf(ACTIVE.keySet());
    }

    /** Outcome of a {@link #kill(Long, String)} call. */
    public record KillResult(boolean killed, SubagentRun.Status finalStatus, String message) {}

    /**
     * Cancel the registered Future (if any) for {@code runId}, transition the
     * audit row to {@link SubagentRun.Status#KILLED} with the given
     * {@code reason} as the outcome, and emit {@link EventLogger#recordSubagentKill}.
     *
     * <p>Idempotent: if the run is already terminal (any status other than
     * RUNNING), this is a no-op aside from returning a {@code KillResult}
     * carrying the existing status. If the row doesn't exist at all, returns
     * a {@code KillResult} with {@code killed=false} and a not-found message.
     *
     * @param runId the SubagentRun primary key
     * @param reason short human description recorded as the run's outcome and
     *               in the SUBAGENT_KILL event payload
     * @return outcome of the kill attempt
     */
    public static KillResult kill(Long runId, String reason) {
        if (runId == null) {
            return new KillResult(false, null, "runId is required.");
        }
        var existing = (SubagentRun) Tx.run(() -> SubagentRun.findById(runId));
        if (existing == null) {
            return new KillResult(false, null, "Run " + runId + " not found.");
        }
        // Idempotent terminal-state guard — read inside the same fetch to
        // dodge race with a near-simultaneous terminal write from the spawn
        // VT itself.
        if (existing.status != SubagentRun.Status.RUNNING) {
            // Still attempt to cancel + clean up the registry slot in case
            // unregister hasn't fired yet, but don't touch the DB row.
            var stale = ACTIVE.remove(runId);
            if (stale != null) {
                stale.future().cancel(true);
                if (stale.thread() != null) stale.thread().interrupt();
            }
            return new KillResult(false, existing.status,
                    "Run " + runId + " is already " + existing.status.name().toLowerCase() + ".");
        }

        // Cancel the Future + interrupt the carrier thread. CompletableFuture#cancel
        // alone doesn't propagate to the running task (the boolean is a no-op
        // per the JDK javadoc), so we hand-interrupt the captured Thread to
        // break out of socket reads + tool-execution loops inside AgentRunner.
        // The VT body's post-AgentRunner Tx will still attempt its terminal
        // write — but our KILLED stamp below races, and spawn-path commits
        // check `status != KILLED` before overwriting.
        var entry = ACTIVE.remove(runId);
        if (entry != null) {
            entry.future().cancel(true);
            if (entry.thread() != null) entry.thread().interrupt();
        }

        var updatedStatus = Tx.run(() -> {
            var fresh = (SubagentRun) SubagentRun.findById(runId);
            if (fresh == null) return null;
            if (fresh.status != SubagentRun.Status.RUNNING) {
                // Beat us to a terminal write between our first read and now.
                return fresh.status;
            }
            fresh.status = SubagentRun.Status.KILLED;
            fresh.endedAt = Instant.now();
            fresh.outcome = reason != null && !reason.isBlank() ? reason : "Killed by operator";
            fresh.save();
            return SubagentRun.Status.KILLED;
        });

        if (updatedStatus == SubagentRun.Status.KILLED) {
            // Read names + mode/context outside the previous Tx to avoid
            // dragging the event-emit into the critical path's transaction.
            var meta = Tx.run(() -> {
                var fresh = (SubagentRun) SubagentRun.findById(runId);
                if (fresh == null) return null;
                String parentName = fresh.parentAgent != null ? fresh.parentAgent.name : null;
                String childName = fresh.childAgent != null ? fresh.childAgent.name : null;
                return new String[]{parentName, childName};
            });
            String parentName = meta != null ? meta[0] : null;
            String childName = meta != null ? meta[1] : null;
            // mode/context not stored on SubagentRun — pass null. The typed
            // EventLogger helper tolerates null fields gracefully.
            EventLogger.recordSubagentKill(parentName, childName,
                    String.valueOf(runId), null, null,
                    reason != null && !reason.isBlank() ? reason : "Killed by operator");
            return new KillResult(true, SubagentRun.Status.KILLED,
                    "Run " + runId + " killed.");
        }

        return new KillResult(false, updatedStatus,
                "Run " + runId + " was already " + (updatedStatus != null ? updatedStatus.name().toLowerCase() : "gone")
                        + " before kill landed.");
    }

    /** Convenience for tests + callers that don't care about the parent agent
     *  for an access check (slash commands always run scoped to the parent
     *  conversation, so the caller has already confirmed scope). */
    @SuppressWarnings("unused")
    public static boolean isActive(Long runId) {
        return runId != null && ACTIVE.containsKey(runId);
    }

    /** Convenience for the {@code /subagent kill} return text: did the
     *  caller's runId match a row at all? Distinct from {@link #isActive}
     *  because a row may exist in the DB but not in the registry (terminal,
     *  or post-restart orphan recovery cleaned it up). */
    public static boolean rowExists(Long runId) {
        if (runId == null) return false;
        return Tx.run(() -> SubagentRun.findById(runId) != null);
    }

    // ---- Type-safe helper for callers that want to scope by parent agent ---

    /** Whether a row with the given {@code runId} is owned by {@code parent}.
     *  Slash commands use this to enforce per-conversation scoping. Returns
     *  false when the row is missing or the parent FK doesn't match. */
    @SuppressWarnings("unused")
    public static boolean isOwnedBy(Long runId, Agent parent) {
        if (runId == null || parent == null) return false;
        return Boolean.TRUE.equals(Tx.run(() -> {
            var run = (SubagentRun) SubagentRun.findById(runId);
            return run != null && run.parentAgent != null
                    && parent.id != null && parent.id.equals(run.parentAgent.id);
        }));
    }
}
