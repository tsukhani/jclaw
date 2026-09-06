package jobs;

import models.Agent;
import play.Play;
import play.db.jpa.JPA;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.EventLogger;
import utils.AppClock;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * JCLAW-1066: clears subagent {@link Agent} rows left behind before conversation
 * deletion learned to remove them. Their runs and conversations are already gone,
 * so nothing reaches them again — 582 such rows on the instance where this was
 * found, carrying 129,828 tool-config rows between them.
 *
 * <p>Idempotent: once swept there is nothing left to match, so it costs one
 * indexed query per boot.
 */
@OnApplicationStart(async = true)
public class OrphanSubagentSweepJob extends Job<Void> {

    /**
     * A spawn writes the child Agent and its SubagentRun in two separate
     * transactions ({@code SubagentRunStore.insertSubagentRun}), so a child
     * legitimately has no run row for a moment. Without this window the sweep
     * would race a spawn in progress and delete a live agent.
     */
    static final Duration SPAWN_GRACE = Duration.ofHours(1);

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        int swept = sweep(AppClock.now().minus(SPAWN_GRACE));
        if (swept > 0) {
            EventLogger.info("system",
                    "Swept %d orphaned subagent(s) whose runs no longer exist".formatted(swept));
        }
    }

    /**
     * Delete every subagent older than {@code createdBefore} that no SubagentRun
     * references. Public so the default-package tests can drive it without a boot.
     *
     * @return how many agents were deleted
     */
    public static int sweep(Instant createdBefore) {
        List<Long> ids = JPA.em().createQuery(
                "SELECT a.id FROM Agent a "
                        + "WHERE a.parentAgent IS NOT NULL AND a.createdAt < :cutoff "
                        + "  AND NOT EXISTS (SELECT 1 FROM SubagentRun r WHERE r.childAgent = a)",
                Long.class).setParameter("cutoff", createdBefore).getResultList();
        int deleted = 0;
        for (var id : ids) {
            // Null when an earlier iteration already removed it as a nested descendant.
            Agent agent = Agent.findById(id);
            if (agent != null) {
                AgentService.delete(agent);
                deleted++;
            }
        }
        return deleted;
    }
}
