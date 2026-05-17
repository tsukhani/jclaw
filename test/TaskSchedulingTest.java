import org.junit.jupiter.api.*;
import play.test.*;
import services.JClawCronUtils;
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

    // --- JClawCronUtils tests (post-JCLAW-294 cron migration to db-scheduler) ---

    @Test
    void cronEveryMinute() {
        // Spring 6-field: seconds minutes hours dom month dow.
        // "0 * * * * *" = "at second 0 of every minute".
        var next = JClawCronUtils.nextExecution("0 * * * * *");
        assertNotNull(next);
        assertTrue(next.isAfter(Instant.now()));
        var ldt = LocalDateTime.ofInstant(next, ZoneId.systemDefault());
        assertEquals(0, ldt.getSecond());
    }

    @Test
    void cronAtShortcutDaily() {
        // db-scheduler's CronSchedule supports @-shortcuts natively
        // (exposed to operators per JCLAW-294 AC). @daily = midnight every day.
        var next = JClawCronUtils.nextExecution("@daily");
        assertNotNull(next);
        var ldt = LocalDateTime.ofInstant(next, ZoneId.systemDefault());
        assertEquals(0, ldt.getHour());
        assertEquals(0, ldt.getMinute());
    }

    @Test
    void cronWithStep() {
        // "0 */15 * * * *" = at second 0 of every 15th minute.
        var next = JClawCronUtils.nextExecution("0 */15 * * * *");
        assertNotNull(next);
        var ldt = LocalDateTime.ofInstant(next, ZoneId.systemDefault());
        assertEquals(0, ldt.getMinute() % 15);
        assertEquals(0, ldt.getSecond());
    }

    @Test
    void cronInvalidReturnsNull() {
        var next = JClawCronUtils.nextExecution("bad");
        assertNull(next);
    }

    @Test
    void cronUnixFiveFieldRejectedAtValidationBoundary() {
        // Validation throws with a helpful hint (prepend "0 " for seconds)
        // when an operator pastes a legacy Unix 5-field expression.
        var ex = assertThrows(IllegalArgumentException.class,
                () -> JClawCronUtils.validate("0 9 * * *"));
        assertTrue(ex.getMessage().contains("5 fields"),
                "message should call out the field count");
        assertTrue(ex.getMessage().contains("'0 0 9 * * *'"),
                "message should suggest the prepended fix");
    }

    @Test
    void cronAtShortcutPassesValidation() {
        // Should not throw — sanity check that validate() admits @-shortcuts.
        JClawCronUtils.validate("@hourly");
        JClawCronUtils.validate("@daily");
        JClawCronUtils.validate("@weekly");
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

    private Agent createAgent() {
        var agent = new Agent();
        agent.name = "task-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
        return agent;
    }
}
