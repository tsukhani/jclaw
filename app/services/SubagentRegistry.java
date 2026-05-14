package services;

import models.Agent;
import models.SubagentRun;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCLAW-271: in-memory mapping from {@link SubagentRun} id to the
 * {@link Future} carrying the child run's virtual thread, so the operator-
 * facing {@code /subagent kill} slash command (and the SubagentRuns admin
 * page's kill button) can flip a cooperative-cancellation flag the running
 * {@link agents.AgentRunner} checks at safe points, then transition the
 * audit row to {@link SubagentRun.Status#KILLED}.
 *
 * <p>Both spawn paths in {@link tools.SpawnSubagentTool} register their
 * Future here at start and unregister when the run terminates (success,
 * failure, timeout, or kill). The registry is JVM-local — a restart loses
 * every entry, which is fine because the still-RUNNING DB rows are recovered
 * by {@link jobs.SubagentOrphanRecoveryJob} and the cancelled VTs are gone
 * anyway. No persistence required.
 *
 * <p><b>Cooperative cancellation (JCLAW-291).</b> {@link #kill(Long, String)}
 * does NOT call {@link Thread#interrupt()} on the running VT. Doing so
 * brought down the entire JVM in production: the JDK's
 * {@code java.nio.channels.FileChannel} contract treats a thread interrupt
 * during a blocked I/O as "close the channel," and H2's MVStore was mid-
 * write on that channel — every subsequent JPA call then threw
 * {@code The database has been closed [90098-232]} until restart. Instead,
 * we flip a {@code volatile} flag on the entry and let
 * {@link agents.AgentRunner}'s checkpoints throw
 * {@link agents.RunCancelledException} at the next safe point (between LLM
 * rounds, between tool calls, at the top of a queue drain). The cost is
 * losing the "instant break a hung HTTP read" property — the
 * {@code runTimeoutSeconds} budget remains the ceiling for that pathological
 * case.
 *
 * <p>{@code kill} is idempotent: calling it on an id that's already terminal
 * (or never registered) returns a {@link KillResult} carrying a clear
 * "nothing to do" reason rather than throwing.
 *
 * <p><b>Test gap (intentional).</b> The original H2-corruption bug cannot be
 * reproduced under {@code play autotest}: the test profile uses H2's in-memory
 * mode, which has no FileChannel, so {@code Thread.interrupt()} on the
 * carrier thread is harmless there. The flag-based design is what we ship;
 * future maintainers MUST NOT reintroduce {@code Thread.interrupt()} on the
 * carrier thread — it works fine in tests and detonates in production.
 */
public final class SubagentRegistry {

    private SubagentRegistry() {}

    /** Per-run handle. {@link #cancelRequested} is the cooperative
     *  cancellation flag {@link agents.AgentRunner}'s checkpoints poll;
     *  {@link #future} is kept around so awaiting callers (the spawn-tool's
     *  {@code future.get(timeout)}) see CANCELLED on kill rather than blocking
     *  to the full timeout. We deliberately do NOT capture the carrier
     *  Thread — see the class-level comment for why interrupts are forbidden. */
    private record Entry(Future<?> future, AtomicBoolean cancelRequested) {}

    private static final Map<Long, Entry> ACTIVE = new ConcurrentHashMap<>();

    /** Register the VT-bearing Future under {@code runId}. Called by the
     *  spawn path right after dispatching the VT. The cancellation flag is
     *  allocated here so {@link #isCancelled} works the instant the entry
     *  is in the map, even before the VT body has done any work. */
    public static void register(Long runId, Future<?> future) {
        if (runId == null || future == null) return;
        ACTIVE.put(runId, new Entry(future, new AtomicBoolean(false)));
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

    /**
     * JCLAW-291: cooperative-cancellation check used by
     * {@link agents.AgentRunner}'s checkpoints. Returns {@code true} iff a
     * registry entry exists for {@code runId} and its cancel flag has been
     * flipped by {@link #kill}. Cheap — single hash lookup, single volatile
     * read.
     */
    public static boolean isCancelled(Long runId) {
        if (runId == null) return false;
        var entry = ACTIVE.get(runId);
        return entry != null && entry.cancelRequested().get();
    }

    /** Outcome of a {@link #kill(Long, String)} call. */
    public record KillResult(boolean killed, SubagentRun.Status finalStatus, String message) {}

    /**
     * Flip the cooperative-cancellation flag for {@code runId}, cancel the
     * registered Future (so awaiting callers unblock with a CancellationException),
     * transition the audit row to {@link SubagentRun.Status#KILLED} with the
     * given {@code reason} as the outcome, and emit
     * {@link EventLogger#recordSubagentKill}.
     *
     * <p>Idempotent: if the run is already terminal (any status other than
     * RUNNING), this is a no-op aside from returning a {@code KillResult}
     * carrying the existing status. If the row doesn't exist at all, returns
     * a {@code KillResult} with {@code killed=false} and a not-found message.
     *
     * <p>JCLAW-291: does NOT call {@link Thread#interrupt()}. See the class
     * comment for why — interrupting the carrier thread closed H2's FileChannel
     * mid-write and brought down the JVM.
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
            // Idempotent terminal case: still flip the flag + cancel the
            // Future in case unregister hasn't fired yet, but DO NOT remove
            // the entry — the running VT's own finally block calls
            // unregister(), which is the canonical cleanup path. Removing
            // here would race the AgentRunner checkpoint into reading
            // isCancelled()=false and continuing the round.
            var stale = ACTIVE.get(runId);
            if (stale != null) {
                stale.cancelRequested().set(true);
                // mayInterruptIfRunning=false: the cooperative flag is the
                // signal; we never want the JDK to interrupt the carrier
                // thread (see class comment for the H2 corruption story).
                stale.future().cancel(false);
            }
            return new KillResult(false, existing.status,
                    "Run " + runId + " is already " + existing.status.name().toLowerCase() + ".");
        }

        // JCLAW-291: flip the cooperative-cancellation flag BEFORE the DB
        // write so AgentRunner's next checkpoint observes
        // isCancelled(runId)=true and throws RunCancelledException. The
        // spawn-tool's catch then leaves our KILLED stamp untouched. We
        // also cancel the Future so any awaiting caller (sync spawn's
        // future.get(timeout)) unblocks with CancellationException rather
        // than waiting to the full timeout.
        //
        // We deliberately DO NOT remove the entry from the active map here.
        // The VT body's finally block (unregister()) is the canonical
        // cleanup path; removing here would let the runner's checkpoint
        // miss the flag (it would see no entry → isCancelled returns false
        // → the round continues to LLM call).
        var entry = ACTIVE.get(runId);
        if (entry != null) {
            entry.cancelRequested().set(true);
            entry.future().cancel(false);
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
