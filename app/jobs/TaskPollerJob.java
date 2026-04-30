package jobs;

import agents.AgentRunner;
import models.Task;
import play.db.jpa.JPA;
import play.jobs.Every;
import play.jobs.Job;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Every("30s")
public class TaskPollerJob extends Job<Void> {

    private static final long BASE_BACKOFF_SECONDS = 30;

    /**
     * The currently active executor, if any. Exposed so {@link ShutdownJob} can
     * call {@link #shutdownGracefully()} to interrupt in-flight tasks and wait
     * for them to finish before the JVM exits.
     */
    private static volatile ExecutorService activeExecutor;

    @Override
    public void doJob() {
        var pendingTasks = Task.findPendingDue();
        if (pendingTasks.isEmpty()) return;

        var callables = pendingTasks.stream()
                .<Callable<Void>>map(task -> () -> {
                    executeTask(task);
                    return null;
                })
                .toList();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            activeExecutor = executor;
            executor.invokeAll(callables);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            EventLogger.warn("task", "Task poller interrupted during execution");
        } finally {
            activeExecutor = null;
        }
    }

    /**
     * Interrupt all in-flight tasks and wait briefly for them to finish.
     * Called by {@link ShutdownJob} during application shutdown.
     *
     * <p>JCLAW-191: 5s timeout (was 30s). The previous 30s alone consumed
     * the framework's entire scheduler-shutdown budget, triggering the
     * "Jobs scheduler did not terminate within 30000 ms" warn on every
     * restart. Tasks that don't respect interrupt within 5s are abandoned
     * — they may leave their {@link models.Task} row in {@code RUNNING}
     * state until a recovery sweep, but losing 5s of work on a forced
     * restart is acceptable; losing 30s of clean-shutdown budget on every
     * restart was not.
     */
    public static void shutdownGracefully() {
        var executor = activeExecutor;
        if (executor != null) {
            executor.shutdownNow();
            try {
                executor.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void executeTask(Task task) {
        try {
            // Atomic CAS: only proceed if status is still PENDING.
            // Prevents double-execution when two poller cycles overlap.
            boolean claimed = Tx.run(() -> {
                int updated = JPA.em().createQuery(
                        "UPDATE Task SET status = :running WHERE id = :id AND status = :pending")
                        .setParameter("running", Task.Status.RUNNING)
                        .setParameter("id", task.id)
                        .setParameter("pending", Task.Status.PENDING)
                        .executeUpdate();
                return updated == 1;
            });
            if (!claimed) return; // another thread already claimed it

            EventLogger.info("task", task.agent != null ? task.agent.name : null, null,
                    "Executing task: %s".formatted(task.name));

            if (task.agent != null) {
                var conversation = Tx.run(() -> ConversationService.findOrCreate(
                        task.agent, "task", "task-%d".formatted(task.id)));
                var prompt = "Execute the following task:\n\n**%s**\n\n%s"
                        .formatted(task.name, task.description != null ? task.description : "");
                AgentRunner.run(task.agent, conversation, prompt);
            }

            Tx.run(() -> onSuccess(task));

        } catch (Exception e) {
            Tx.run(() -> onFailure(task, e));
        }
    }

    private void onSuccess(Task task) {
        // Re-fetch by ID to get a managed entity — the passed-in task may be
        // detached after AgentRunner.run() opened and committed its own Tx.run() blocks.
        var fresh = Task.<Task>findById(task.id);
        if (fresh == null) return;
        fresh.status = Task.Status.COMPLETED;
        fresh.save();
        EventLogger.info("task", fresh.agent != null ? fresh.agent.name : null, null,
                "Task completed: %s".formatted(fresh.name));

        // CRON re-scheduling
        if (fresh.type == Task.Type.CRON && fresh.cronExpression != null) {
            scheduleCronNext(fresh);
        }
    }

    /** Maximum bit-shift for exponential backoff — caps at ~12 days (30 * 2^20 seconds). */
    private static final int MAX_BACKOFF_SHIFT = 20;

    private void onFailure(Task task, Exception e) {
        // Re-fetch by ID to get a managed entity with the current DB state.
        var fresh = Task.<Task>findById(task.id);
        if (fresh == null) return;
        fresh.retryCount++;
        fresh.lastError = e.getMessage();

        if (fresh.retryCount >= fresh.maxRetries) {
            fresh.status = Task.Status.FAILED;
            EventLogger.error("task", fresh.agent != null ? fresh.agent.name : null, null,
                    "Task permanently failed after %d retries: %s — %s"
                            .formatted(fresh.retryCount, fresh.name, e.getMessage()));
        } else {
            fresh.status = Task.Status.PENDING;
            var shift = Math.min(fresh.retryCount - 1, MAX_BACKOFF_SHIFT);
            var backoffSeconds = BASE_BACKOFF_SECONDS * (1L << shift);
            fresh.nextRunAt = Instant.now().plusSeconds(backoffSeconds);
            EventLogger.warn("task", fresh.agent != null ? fresh.agent.name : null, null,
                    "Task retry %d/%d for '%s' in %ds"
                            .formatted(fresh.retryCount, fresh.maxRetries, fresh.name, backoffSeconds));
        }
        fresh.save();
    }

    private void scheduleCronNext(Task completedTask) {
        try {
            var nextRun = CronParser.nextExecution(completedTask.cronExpression);
            if (nextRun != null) {
                Tx.run(() -> {
                    var next = new Task();
                    next.agent = completedTask.agent;
                    next.name = completedTask.name;
                    next.description = completedTask.description;
                    next.type = Task.Type.CRON;
                    next.cronExpression = completedTask.cronExpression;
                    next.nextRunAt = nextRun;
                    next.save();
                    EventLogger.info("task", null, null,
                            "Next run of '%s' scheduled for %s".formatted(next.name, nextRun));
                });
            }
        } catch (Exception e) {
            EventLogger.error("task", "Failed to schedule next CRON run for '%s': %s"
                    .formatted(completedTask.name, e.getMessage()));
        }
    }
}
