import jobs.TaskCleanupJob;
import models.Agent;
import models.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * JCLAW-259 coverage for {@link TaskCleanupJob}: the retention TTL
 * sweep that hard-deletes terminal tasks past their retention window.
 *
 * <p>Each scenario backdates {@code updatedAt} via a JPQL UPDATE after
 * save — the @PreUpdate hook on the entity would otherwise stamp
 * {@code Instant.now()} on every {@code task.save()} call and we'd
 * never get a row "old enough" to be eligible.
 */
class TaskCleanupJobTest extends UnitTest {

    private static final String CONFIG_KEY = "tasks.retentionDays";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        // Reset to a known state — explicit delete in case a prior test
        // left a key in the Config table (deleteDatabase truncates but
        // ConfigService caches).
        ConfigService.delete(CONFIG_KEY);
    }

    @Test
    void deletesTerminalTasksOlderThanRetentionDays() {
        var agent = AgentService.create("svc-cleanup-terminal", "openrouter", "gpt-4.1");
        var oldCompleted = seedTask(agent, "old-completed", Task.Status.COMPLETED);
        var oldFailed = seedTask(agent, "old-failed", Task.Status.FAILED);
        var oldCancelled = seedTask(agent, "old-cancelled", Task.Status.CANCELLED);
        var oldLost = seedTask(agent, "old-lost", Task.Status.LOST);

        ConfigService.set(CONFIG_KEY, "30");
        backdate(oldCompleted.id, 31);
        backdate(oldFailed.id, 60);
        backdate(oldCancelled.id, 90);
        backdate(oldLost.id, 45);

        new TaskCleanupJob().doJob();

        // All four terminal states past 30 days must be gone.
        assertNull(Task.findById(oldCompleted.id), "COMPLETED past TTL must be deleted");
        assertNull(Task.findById(oldFailed.id), "FAILED past TTL must be deleted");
        assertNull(Task.findById(oldCancelled.id), "CANCELLED past TTL must be deleted");
        assertNull(Task.findById(oldLost.id), "LOST past TTL must be deleted");
    }

    @Test
    void leavesFreshTerminalTasksAlone() {
        // Tasks in terminal status but younger than the cutoff must stay.
        // The retention boundary is exclusive: updatedAt < cutoff is
        // deleted, equality and above is kept.
        var agent = AgentService.create("svc-cleanup-fresh", "openrouter", "gpt-4.1");
        var freshCompleted = seedTask(agent, "fresh-completed", Task.Status.COMPLETED);

        ConfigService.set(CONFIG_KEY, "30");
        backdate(freshCompleted.id, 5);  // well inside the 30-day window

        new TaskCleanupJob().doJob();

        assertNotNull(Task.findById(freshCompleted.id),
                "terminal task within retention window must be preserved");
    }

    @Test
    void leavesActiveTasksAloneRegardlessOfAge() {
        // PENDING / ACTIVE / RUNNING tasks are never touched even if
        // they've been sitting around longer than the retention period
        // — the cleanup is for terminal states only.
        var agent = AgentService.create("svc-cleanup-active", "openrouter", "gpt-4.1");
        var oldPending = seedTask(agent, "old-pending", Task.Status.PENDING);
        var oldActive = seedTask(agent, "old-active", Task.Status.ACTIVE);
        var oldRunning = seedTask(agent, "old-running", Task.Status.RUNNING);

        ConfigService.set(CONFIG_KEY, "30");
        backdate(oldPending.id, 365);
        backdate(oldActive.id, 365);
        backdate(oldRunning.id, 365);

        new TaskCleanupJob().doJob();

        assertNotNull(Task.findById(oldPending.id), "PENDING never deleted");
        assertNotNull(Task.findById(oldActive.id), "ACTIVE never deleted");
        assertNotNull(Task.findById(oldRunning.id), "RUNNING never deleted");
    }

    @Test
    void retentionZeroDisablesCleanup() {
        // tasks.retentionDays=0 is the operator-facing "never auto-delete"
        // switch. The job runs but no rows go.
        var agent = AgentService.create("svc-cleanup-disabled", "openrouter", "gpt-4.1");
        var ancient = seedTask(agent, "ancient", Task.Status.COMPLETED);

        ConfigService.set(CONFIG_KEY, "0");
        backdate(ancient.id, 999);

        new TaskCleanupJob().doJob();

        assertNotNull(Task.findById(ancient.id),
                "retentionDays=0 must short-circuit the entire job");
    }

    @Test
    void resolveRetentionDaysHandlesAllConfigStates() {
        // Direct coverage of the config-parsing branches independent
        // of the doJob path: missing, blank, valid, zero, negative,
        // above-cap, and non-numeric.
        // Missing config returns the default.
        ConfigService.delete(CONFIG_KEY);
        assertEquals(TaskCleanupJob.DEFAULT_RETENTION_DAYS,
                TaskCleanupJob.resolveRetentionDays());

        // Blank config returns the default.
        ConfigService.set(CONFIG_KEY, "");
        assertEquals(TaskCleanupJob.DEFAULT_RETENTION_DAYS,
                TaskCleanupJob.resolveRetentionDays());

        // Valid positive integer returns itself.
        ConfigService.set(CONFIG_KEY, "7");
        assertEquals(7, TaskCleanupJob.resolveRetentionDays());

        // Zero is the disabled sentinel.
        ConfigService.set(CONFIG_KEY, "0");
        assertEquals(TaskCleanupJob.RETENTION_DISABLED,
                TaskCleanupJob.resolveRetentionDays());

        // Negative falls back to default (with a warn the test doesn't assert).
        ConfigService.set(CONFIG_KEY, "-5");
        assertEquals(TaskCleanupJob.DEFAULT_RETENTION_DAYS,
                TaskCleanupJob.resolveRetentionDays());

        // Above the 3650-day cap falls back to default.
        ConfigService.set(CONFIG_KEY, "10000");
        assertEquals(TaskCleanupJob.DEFAULT_RETENTION_DAYS,
                TaskCleanupJob.resolveRetentionDays());

        // Non-numeric falls back to default (typo protection).
        ConfigService.set(CONFIG_KEY, "thirty");
        assertEquals(TaskCleanupJob.DEFAULT_RETENTION_DAYS,
                TaskCleanupJob.resolveRetentionDays());
    }

    @Test
    void mixedBatchDeletesEligibleAndPreservesIneligible() {
        // One terminal+stale, one terminal+fresh, one active+stale.
        // Only the first should disappear after a single job pass.
        var agent = AgentService.create("svc-cleanup-mixed", "openrouter", "gpt-4.1");
        var staleTerminal = seedTask(agent, "stale-terminal", Task.Status.COMPLETED);
        var freshTerminal = seedTask(agent, "fresh-terminal", Task.Status.COMPLETED);
        var staleActive = seedTask(agent, "stale-active", Task.Status.RUNNING);

        ConfigService.set(CONFIG_KEY, "30");
        backdate(staleTerminal.id, 100);
        backdate(freshTerminal.id, 1);
        backdate(staleActive.id, 100);

        new TaskCleanupJob().doJob();

        assertNull(Task.findById(staleTerminal.id), "eligible row must be deleted");
        assertNotNull(Task.findById(freshTerminal.id), "fresh terminal must be preserved");
        assertNotNull(Task.findById(staleActive.id), "active state must be preserved");
    }

    // ────────── helpers ──────────

    /** Insert a Task in the requested status. @PrePersist stamps updatedAt
     *  to Instant.now(); use {@link #backdate} to shift it backward. */
    private static Task seedTask(Agent agent, String name, Task.Status status) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.type = Task.Type.IMMEDIATE;
        t.status = status;
        t.scheduledAt = Instant.now();
        t.save();
        return t;
    }

    /** Move {@code updatedAt} back by {@code days} via JPQL UPDATE — the
     *  entity's @PreUpdate hook would clobber a direct {@code t.updatedAt =}
     *  assignment on save, so we go around it. */
    private static void backdate(Long taskId, int days) {
        var past = Instant.now().minus(days, ChronoUnit.DAYS);
        JPA.em().createQuery("UPDATE Task t SET t.updatedAt = :past WHERE t.id = :id")
                .setParameter("past", past)
                .setParameter("id", taskId)
                .executeUpdate();
        JPA.em().flush();
        // Clear so subsequent findById sees the backdated value rather
        // than the cached @PrePersist stamp.
        JPA.em().clear();
    }
}
