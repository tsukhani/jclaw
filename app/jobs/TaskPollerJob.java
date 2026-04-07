package jobs;

import agents.AgentRunner;
import models.Task;
import play.jobs.Every;
import play.jobs.Job;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Every("30s")
public class TaskPollerJob extends Job<Void> {

    private static final long BASE_BACKOFF_SECONDS = 30;

    @Override
    public void doJob() {
        var pendingTasks = Task.findPendingDue();
        if (pendingTasks.isEmpty()) return;

        for (var task : pendingTasks) {
            Thread.ofVirtual().start(() -> executeTask(task));
        }
    }

    private void executeTask(Task task) {
        try {
            Tx.run(() -> {
                task.status = Task.Status.RUNNING;
                task.save();
            });

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
        task.status = Task.Status.COMPLETED;
        task.save();
        EventLogger.info("task", task.agent != null ? task.agent.name : null, null,
                "Task completed: %s".formatted(task.name));

        // CRON re-scheduling
        if (task.type == Task.Type.CRON && task.cronExpression != null) {
            scheduleCronNext(task);
        }
    }

    private void onFailure(Task task, Exception e) {
        task.retryCount++;
        task.lastError = e.getMessage();

        if (task.retryCount >= task.maxRetries) {
            task.status = Task.Status.FAILED;
            EventLogger.error("task", task.agent != null ? task.agent.name : null, null,
                    "Task permanently failed after %d retries: %s — %s"
                            .formatted(task.retryCount, task.name, e.getMessage()));
        } else {
            task.status = Task.Status.PENDING;
            var backoffSeconds = BASE_BACKOFF_SECONDS * (1L << (task.retryCount - 1));
            task.nextRunAt = Instant.now().plusSeconds(backoffSeconds);
            EventLogger.warn("task", task.agent != null ? task.agent.name : null, null,
                    "Task retry %d/%d for '%s' in %ds"
                            .formatted(task.retryCount, task.maxRetries, task.name, backoffSeconds));
        }
        task.save();
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
