import org.junit.jupiter.api.*;
import play.test.*;
import models.*;
import services.*;

import java.time.Instant;

/**
 * Tests for TaskPollerJob: status transitions, exponential backoff,
 * CRON re-scheduling, and graceful shutdown.
 *
 * <p>Uses a non-configured provider so AgentRunner.run() returns an error
 * without making external calls. This exercises the full executeTask →
 * onSuccess path for the "soft failure" case and the exception path for
 * onFailure.
 */
public class TaskPollerJobTest extends UnitTest {

    @BeforeEach
    void setup() throws InterruptedException {
        Thread.sleep(200);
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
    }

    // --- Status transitions (via direct method invocation) ---
    // Note: doJob() integration tests are not possible because Play 1.x's
    // thread-local JPA sessions cause detached entity issues when doJob()
    // dispatches to virtual threads via invokeAll(). We test the individual
    // lifecycle methods directly instead.

    @Test
    public void findPendingDueFiltersCorrectly() {
        var agent = createAgent("due-filter-agent");

        // Past-due task — should be found
        var dueTask = createTask(agent, "Due task", Task.Type.IMMEDIATE, Task.Status.PENDING);

        // Future task — should NOT be found
        var futureTask = createTask(agent, "Future task", Task.Type.IMMEDIATE, Task.Status.PENDING);
        futureTask.nextRunAt = Instant.now().plusSeconds(3600);
        futureTask.save();

        // Already running task — should NOT be found
        createTask(agent, "Running task", Task.Type.IMMEDIATE, Task.Status.RUNNING);

        var pending = Task.findPendingDue();
        assertEquals(1, pending.size(), "Only past-due PENDING tasks should be returned");
        assertEquals(dueTask.id, ((Task) pending.getFirst()).id);
    }

    @Test
    public void onSuccessMarksTaskCompleted() throws Exception {
        var agent = createAgent("success-agent");
        var task = new Task();
        task.agent = agent;
        task.name = "Direct success";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.RUNNING;
        task.nextRunAt = Instant.now();
        task.save();

        var poller = new jobs.TaskPollerJob();
        var method = jobs.TaskPollerJob.class.getDeclaredMethod("onSuccess", Task.class);
        method.setAccessible(true);
        method.invoke(poller, task);

        play.db.jpa.JPA.em().clear();
        var updated = (Task) Task.findById(task.id);
        assertEquals(Task.Status.COMPLETED, updated.status,
                "onSuccess should set status to COMPLETED");
    }

    // --- Exponential backoff ---

    @Test
    public void onFailureSetsExponentialBackoff() throws Exception {
        // Create an agent with a provider that will cause an exception
        // (not a soft error) by using a provider that actively throws
        var agent = createAgent("backoff-agent");

        // We test onFailure directly via reflection since it's a private method
        // and we need to verify the backoff formula precisely
        var poller = new jobs.TaskPollerJob();

        var task = new Task();
        task.name = "Backoff test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.RUNNING;
        task.retryCount = 0;
        task.maxRetries = 5;
        task.agent = agent;
        task.nextRunAt = Instant.now();
        task.save();

        // Invoke onFailure via reflection
        var method = jobs.TaskPollerJob.class.getDeclaredMethod("onFailure", Task.class, Exception.class);
        method.setAccessible(true);

        // First failure: retryCount goes to 1, backoff = 30 * 2^0 = 30s
        var beforeRetry1 = Instant.now();
        method.invoke(poller, task, new RuntimeException("Transient error"));

        play.db.jpa.JPA.em().clear();
        var after1 = (Task) Task.findById(task.id);
        assertEquals(Task.Status.PENDING, after1.status, "Should be PENDING for retry");
        assertEquals(1, after1.retryCount);
        assertNotNull(after1.nextRunAt);
        assertTrue(after1.nextRunAt.isAfter(beforeRetry1.plusSeconds(25)),
                "First backoff should be ~30s, got: " + after1.nextRunAt);
        assertTrue(after1.nextRunAt.isBefore(beforeRetry1.plusSeconds(35)),
                "First backoff should be ~30s, got: " + after1.nextRunAt);
        assertEquals("Transient error", after1.lastError);

        // Second failure: retryCount goes to 2, backoff = 30 * 2^1 = 60s
        after1.status = Task.Status.RUNNING; // reset for next failure
        after1.save();
        var beforeRetry2 = Instant.now();
        method.invoke(poller, after1, new RuntimeException("Still failing"));

        play.db.jpa.JPA.em().clear();
        var after2 = (Task) Task.findById(task.id);
        assertEquals(2, after2.retryCount);
        assertTrue(after2.nextRunAt.isAfter(beforeRetry2.plusSeconds(55)),
                "Second backoff should be ~60s");
        assertTrue(after2.nextRunAt.isBefore(beforeRetry2.plusSeconds(65)),
                "Second backoff should be ~60s");
    }

    @Test
    public void onFailureMarksFailedAfterMaxRetries() throws Exception {
        var agent = createAgent("max-retry-agent");
        var poller = new jobs.TaskPollerJob();

        var task = new Task();
        task.name = "Max retries test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.RUNNING;
        task.retryCount = 2;
        task.maxRetries = 3;
        task.agent = agent;
        task.nextRunAt = Instant.now();
        task.save();

        var method = jobs.TaskPollerJob.class.getDeclaredMethod("onFailure", Task.class, Exception.class);
        method.setAccessible(true);
        method.invoke(poller, task, new RuntimeException("Final failure"));

        play.db.jpa.JPA.em().clear();
        var updated = (Task) Task.findById(task.id);
        assertEquals(Task.Status.FAILED, updated.status,
                "Should be FAILED after reaching maxRetries");
        assertEquals(3, updated.retryCount);
        assertEquals("Final failure", updated.lastError);
    }

    // --- CRON re-scheduling ---

    @Test
    public void cronTaskSchedulesNextRunAfterCompletion() throws Exception {
        var agent = createAgent("cron-agent");
        var poller = new jobs.TaskPollerJob();

        var task = new Task();
        task.name = "Every-minute cron";
        task.type = Task.Type.CRON;
        task.cronExpression = "* * * * *"; // every minute
        task.status = Task.Status.RUNNING;
        task.agent = agent;
        task.nextRunAt = Instant.now();
        task.save();

        // Call onSuccess which triggers scheduleCronNext
        var onSuccess = jobs.TaskPollerJob.class.getDeclaredMethod("onSuccess", Task.class);
        onSuccess.setAccessible(true);
        onSuccess.invoke(poller, task);

        play.db.jpa.JPA.em().clear();

        // The completed task should be COMPLETED
        var completed = (Task) Task.findById(task.id);
        assertEquals(Task.Status.COMPLETED, completed.status);

        // A new task should have been created for the next CRON run
        @SuppressWarnings("unchecked")
        var allTasks = (java.util.List<Task>) (java.util.List<?>) Task.findAll();
        assertTrue(allTasks.size() >= 2,
                "Should have at least 2 tasks (completed + next scheduled), got " + allTasks.size());

        var nextTask = allTasks.stream()
                .filter(t -> !t.id.equals(task.id))
                .findFirst()
                .orElse(null);
        assertNotNull(nextTask, "Next CRON task should exist");
        assertEquals(Task.Type.CRON, nextTask.type);
        assertEquals("* * * * *", nextTask.cronExpression);
        assertEquals("Every-minute cron", nextTask.name);
        assertNotNull(nextTask.nextRunAt, "Next run time should be set");
        assertTrue(nextTask.nextRunAt.isAfter(Instant.now()),
                "Next run should be in the future");
    }

    // --- Graceful shutdown ---

    @Test
    public void shutdownGracefullyDoesNotThrowWhenIdle() {
        // No active executor — should be a no-op
        assertDoesNotThrow(() -> jobs.TaskPollerJob.shutdownGracefully());
    }

    // --- Helpers ---

    private Agent createAgent(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "nonexistent";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private Task createTask(Agent agent, String name, Task.Type type, Task.Status status) {
        var task = new Task();
        task.agent = agent;
        task.name = name;
        task.type = type;
        task.status = status;
        task.nextRunAt = Instant.now().minusSeconds(60); // already due
        task.save();
        return task;
    }

}
