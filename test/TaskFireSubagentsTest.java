import agents.AgentRunner;
import models.Agent;
import models.SubagentRun;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConversationService;
import services.SubagentRegistry;
import services.TaskFireDeadline;
import services.TaskRunRegistry;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;

/**
 * JCLAW-1567: a task fire and the subagent runs it started. A cancel stops them, and their activity
 * keeps the fire within its idle limit. No {@code Fixtures.deleteDatabase}: every row here is named
 * for this class and test.
 */
class TaskFireSubagentsTest extends UnitTest {

    private Long taskRunId;
    private Long childRunId;

    @BeforeEach
    void seed() {
        var suffix = String.valueOf(System.nanoTime() % 1_000_000_000L);
        var agent = agent("tfs-parent-" + suffix, null);
        var task = new Task();
        task.agent = agent;
        task.name = "tfs-task-" + suffix;
        task.type = Task.Type.CRON;
        task.cronExpression = "*/30 * * * *";
        task.status = Task.Status.RUNNING;
        task.save();
        var fire = new TaskRun();
        fire.task = task;
        fire.startedAt = Instant.now();
        fire.status = TaskRun.Status.RUNNING;
        fire.save();
        var child = agent("tfs-child-" + suffix, agent);
        var run = new SubagentRun();
        run.parentAgent = agent;
        run.childAgent = child;
        run.parentTaskRun = fire;
        run.childConversation = ConversationService.create(child, "web", null);
        run.status = SubagentRun.Status.RUNNING;
        run.save();
        taskRunId = fire.id;
        childRunId = run.id;
        // The cancel and the watchdog read these rows on threads of their own.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    @Test
    void cancellingATaskRunKillsTheSubagentsItStarted() throws Exception {
        var childFuture = new CompletableFuture<AgentRunner.RunResult>();
        TaskRunRegistry.register(taskRunId);
        SubagentRegistry.register(childRunId, childFuture);
        try (var _ = SubagentRegistry.stopWhenTaskRunCancelled(taskRunId)) {
            assertTrue(TaskRunRegistry.requestCancel(taskRunId));

            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!childFuture.isCancelled() && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(childFuture.isCancelled(), "the subagent the fire was waiting on is stopped");
            // kill cancels the future before it writes the row, so the row can lag.
            SubagentRun row = null;
            while (System.nanoTime() < deadline) {
                JPA.em().clear();
                row = SubagentRun.findById(childRunId);
                if (row.status == SubagentRun.Status.KILLED) break;
                Thread.sleep(20);
            }
            assertEquals(SubagentRun.Status.KILLED, row.status);
            assertTrue(row.outcome.contains("task run that started it was cancelled"), row.outcome);
        } finally {
            SubagentRegistry.unregister(childRunId);
            TaskRunRegistry.unregister(taskRunId);
        }
    }

    @Test
    void aFireWhoseSubagentKeepsWorkingIsNotIdle() throws Exception {
        TaskRunRegistry.register(taskRunId);
        SubagentRegistry.register(childRunId, new CompletableFuture<Void>());
        ScheduledFuture<?> watchdog = null;
        try {
            watchdog = arm(taskRunId, Duration.ofMillis(400), Duration.ZERO);
            // The fire itself records nothing while it waits; only its subagent works, for three idle limits.
            for (int i = 0; i < 24; i++) {
                SubagentRegistry.touch(childRunId);
                Thread.sleep(50);
            }
            assertFalse(TaskRunRegistry.isCancelled(taskRunId), "a working subagent keeps its fire alive");

            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!TaskRunRegistry.isCancelled(taskRunId) && System.nanoTime() < deadline) Thread.sleep(20);
            assertTrue(TaskRunRegistry.isCancelled(taskRunId), "once the subagent falls silent, the idle limit cuts the fire");
        } finally {
            TaskFireDeadline.disarm(watchdog, taskRunId);
            SubagentRegistry.unregister(childRunId);
            TaskRunRegistry.unregister(taskRunId);
        }
    }

    private static Agent agent(String name, Agent parent) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.parentAgent = parent;
        agent.save();
        return agent;
    }

    private static ScheduledFuture<?> arm(Long runId, Duration idle, Duration ceiling) throws Exception {
        Method arm = TaskFireDeadline.class.getDeclaredMethod(
                "arm", Long.class, Duration.class, Duration.class, Duration.class);
        arm.setAccessible(true);
        return (ScheduledFuture<?>) arm.invoke(null, runId, idle, ceiling, Duration.ofMillis(25));
    }
}
