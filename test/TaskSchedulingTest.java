import org.junit.jupiter.api.*;
import play.test.*;
import jobs.CronParser;
import models.Agent;
import models.Task;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

class TaskSchedulingTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    // --- CronParser tests ---

    @Test
    void cronEveryMinute() {
        var next = CronParser.nextExecution("* * * * *");
        assertNotNull(next);
        assertTrue(next.isAfter(Instant.now()));
    }

    @Test
    void cronSpecificTime() {
        // Every day at noon
        var after = LocalDateTime.of(2026, 4, 7, 11, 0)
                .atZone(ZoneId.systemDefault()).toInstant();
        var next = CronParser.nextExecution("0 12 * * *", after);
        assertNotNull(next);
        var ldt = LocalDateTime.ofInstant(next, ZoneId.systemDefault());
        assertEquals(12, ldt.getHour());
        assertEquals(0, ldt.getMinute());
    }

    @Test
    void cronWithStep() {
        // Every 15 minutes
        var after = LocalDateTime.of(2026, 4, 7, 10, 0)
                .atZone(ZoneId.systemDefault()).toInstant();
        var next = CronParser.nextExecution("*/15 * * * *", after);
        assertNotNull(next);
        var ldt = LocalDateTime.ofInstant(next, ZoneId.systemDefault());
        assertEquals(0, ldt.getMinute() % 15);
    }

    @Test
    void cronInvalidReturnsNull() {
        var next = CronParser.nextExecution("bad");
        assertNull(next);
    }

    // --- Task retry with backoff ---

    @Test
    void taskRetryIncrements() {
        var agent = createAgent();
        var task = new Task();
        task.agent = agent;
        task.name = "retry-test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        // Simulate failure
        task.retryCount++;
        task.status = Task.Status.PENDING;
        task.lastError = "timeout";
        task.nextRunAt = Instant.now().plusSeconds(30);
        task.save();

        assertEquals(1, task.retryCount);
        assertEquals(Task.Status.PENDING, task.status);
    }

    @Test
    void taskMaxRetriesExceeded() {
        var agent = createAgent();
        var task = new Task();
        task.agent = agent;
        task.name = "fail-test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.RUNNING;
        task.retryCount = 3;
        task.maxRetries = 3;
        task.nextRunAt = Instant.now();
        task.save();

        // Simulate max retries exceeded
        task.status = Task.Status.FAILED;
        task.lastError = "permanent failure";
        task.save();

        Task found = Task.findById(task.id);
        assertEquals(Task.Status.FAILED, found.status);
    }

    @Test
    void cancelPendingTask() {
        var agent = createAgent();
        var task = new Task();
        task.agent = agent;
        task.name = "cancel-test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        task.status = Task.Status.CANCELLED;
        task.save();

        Task found = Task.findById(task.id);
        assertEquals(Task.Status.CANCELLED, found.status);
    }

    @Test
    void findPendingDueReturnsOnlyDueTasks() {
        var agent = createAgent();

        // Due now
        var due = new Task();
        due.agent = agent;
        due.name = "due-now";
        due.type = Task.Type.IMMEDIATE;
        due.status = Task.Status.PENDING;
        due.nextRunAt = Instant.now().minusSeconds(10);
        due.save();

        // Not due yet
        var future = new Task();
        future.agent = agent;
        future.name = "future";
        future.type = Task.Type.SCHEDULED;
        future.status = Task.Status.PENDING;
        future.nextRunAt = Instant.now().plusSeconds(3600);
        future.save();

        var pending = Task.findPendingDue();
        assertEquals(1, pending.size());
        assertEquals("due-now", pending.getFirst().name);
    }

    private Agent createAgent() {
        var agent = new Agent();
        agent.name = "task-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
        return agent;
    }
}
