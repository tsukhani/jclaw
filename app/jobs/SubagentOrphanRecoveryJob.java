package jobs;

import models.SubagentRun;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.util.List;

/**
 * JCLAW-270: boot-time sweep for orphaned async-spawn rows.
 *
 * <p>Async-spawn child runs (JCLAW-270) execute on dedicated virtual threads —
 * they do not survive a JVM restart. Any {@link SubagentRun} row left in
 * {@link SubagentRun.Status#RUNNING} when this job fires therefore belongs to
 * either a freshly-issued spawn that hasn't begun yet (raced this job), or a
 * pre-restart orphan whose VT vanished. The threshold {@link #ORPHAN_AGE_SECONDS}
 * separates the two — rows younger than the threshold are left alone; rows
 * older are stamped {@code FAILED} with a clear {@code outcome} so the audit
 * log reflects truth.
 *
 * <p><b>No completion announce.</b> The orphaned run's parent Conversation
 * may have moved on (the operator could have closed it, kept chatting, or
 * deleted it); surfacing a stale "your subagent failed" card hours after the
 * fact would be more disruptive than helpful. The audit row + the WARN-level
 * EventLog row are enough — operators inspecting the events page can pivot to
 * the child conversation directly.
 *
 * <p>Synchronous-spawn rows can also be orphaned by a JVM kill mid-run; this
 * job handles them identically (same RUNNING state in the DB, same lack of
 * an in-process Future to resume). The sweep is a one-shot at boot, not a
 * recurring job — there is no in-flight detection during steady state, only
 * "leftovers from the prior JVM."
 */
@OnApplicationStart
public class SubagentOrphanRecoveryJob extends Job<Void> {

    /**
     * Minimum age before a {@code RUNNING} row is considered orphaned.
     * Larger than the typical bootstrap-to-startup window for fresh spawns,
     * small enough that a real orphan doesn't sit RUNNING for hours.
     */
    static final long ORPHAN_AGE_SECONDS = 60;

    @Override
    public void doJob() {
        var cutoff = Instant.now().minusSeconds(ORPHAN_AGE_SECONDS);
        List<SubagentRun> orphans;
        try {
            orphans = Tx.run(() -> SubagentRun.<SubagentRun>find(
                    "status = ?1 AND startedAt < ?2",
                    SubagentRun.Status.RUNNING, cutoff).fetch());
        } catch (RuntimeException e) {
            EventLogger.warn("subagent",
                    "SubagentOrphanRecoveryJob query failed: " + e.getMessage());
            return;
        }
        if (orphans.isEmpty()) return;

        for (var orphan : orphans) {
            var orphanId = orphan.id;
            try {
                Tx.run(() -> {
                    var fresh = (SubagentRun) SubagentRun.findById(orphanId);
                    if (fresh == null || fresh.status != SubagentRun.Status.RUNNING) return;
                    fresh.status = SubagentRun.Status.FAILED;
                    fresh.endedAt = Instant.now();
                    fresh.outcome = "Subagent VT did not survive JVM restart";
                    fresh.save();
                });
                EventLogger.warn("subagent",
                        "Marked orphaned SubagentRun " + orphanId + " FAILED at boot");
            } catch (RuntimeException e) {
                EventLogger.warn("subagent",
                        "Failed to recover orphaned SubagentRun " + orphanId
                                + ": " + e.getMessage());
            }
        }
        EventLogger.flush();
    }
}
