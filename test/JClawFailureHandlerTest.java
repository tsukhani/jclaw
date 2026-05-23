import java.net.SocketTimeoutException;
import models.Agent;
import models.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.JClawFailureHandler;
import services.JClawFailureHandler.Decision;
import services.Tx;

import java.time.Instant;

/**
 * Coverage for {@link JClawFailureHandler#decide}. Drives the retry
 * policy directly without an {@link
 * com.github.kagkarlsson.scheduler.task.ExecutionOperations} stub —
 * the integration shell ({@code onFailure}) is a one-line translator
 * from the {@link Decision} record into the scheduler call.
 *
 * <p>What's pinned here:
 * <ul>
 *   <li>Transient + retry budget left → {@code Reschedule}; Task
 *       row gains a bumped retryCount, refreshed lastError, and
 *       nextRunAt set to backoff-future-time.</li>
 *   <li>Permanent error → {@code Fail}; Task row goes to FAILED
 *       regardless of retry budget.</li>
 *   <li>Transient but retries exhausted → {@code Fail}; Task goes
 *       to FAILED with the transient error message preserved.</li>
 *   <li>Backoff schedule positions 0–4 map to 30s / 60s / 5m / 15m
 *       / 1h.</li>
 *   <li>{@code Task.maxRetries} caps the budget shorter than the
 *       full {@link JClawFailureHandler#BACKOFF_SECONDS} array
 *       when operators set it that way.</li>
 * </ul>
 */
class JClawFailureHandlerTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = persistAgent();
    }

    @Test
    void transientWithBudgetLeftReschedules() {
        var task = persistTask(agent, 0, 3);
        var transientErr = new SocketTimeoutException("read");

        var before = Instant.now();
        var decision = JClawFailureHandler.decide(task.id, transientErr);
        var after = Instant.now();

        assertInstanceOf(Decision.Reschedule.class, decision);
        var r = (Decision.Reschedule) decision;
        assertEquals(1, r.newRetryCount());
        // First retry → 30 seconds.
        assertTrue(r.nextRunAt().isAfter(before.plusSeconds(29))
                && r.nextRunAt().isBefore(after.plusSeconds(31)),
                "first retry should be ~30s out, got " + r.nextRunAt());

        // Task row reflects the new state.
        var fresh = (Task) Tx.run(() -> Task.findById(task.id));
        assertEquals(1, fresh.retryCount);
        assertEquals(Task.Status.PENDING, fresh.status, "row stays PENDING on retry");
        assertNotNull(fresh.lastError);
        assertTrue(fresh.lastError.contains("read") || fresh.lastError.contains("SocketTimeout"),
                "lastError should carry the exception signal, got: " + fresh.lastError);
    }

    @Test
    void backoffSchedulePositions() {
        // Walk through positions 0..4 and verify the spec's
        // 30s / 60s / 5m / 15m / 1h schedule.
        long[] expected = {30, 60, 5 * 60, 15 * 60, 60 * 60};
        for (int i = 0; i < expected.length; i++) {
            var task = persistTask(agent, i, 10);
            var before = Instant.now();
            var decision = JClawFailureHandler.decide(task.id, new SocketTimeoutException("x"));
            assertInstanceOf(Decision.Reschedule.class, decision,
                    "position " + i + " should reschedule");
            var r = (Decision.Reschedule) decision;
            long deltaSecs = r.nextRunAt().getEpochSecond() - before.getEpochSecond();
            assertTrue(Math.abs(deltaSecs - expected[i]) <= 2,
                    "position " + i + " expected ~" + expected[i] + "s, got " + deltaSecs);
        }
    }

    @Test
    void permanentErrorFails() {
        var task = persistTask(agent, 0, 3);
        var permanent = new RuntimeException("HTTP 401 Unauthorized");

        var decision = JClawFailureHandler.decide(task.id, permanent);

        assertInstanceOf(Decision.Fail.class, decision);
        var fresh = (Task) Tx.run(() -> Task.findById(task.id));
        assertEquals(Task.Status.FAILED, fresh.status);
        assertEquals("HTTP 401 Unauthorized", fresh.lastError);
    }

    @Test
    void transientWithRetriesExhaustedFails() {
        // maxRetries=2 means budget is min(2, 5) = 2. retryCount=2
        // is already at-budget; the next failure is permanent.
        var task = persistTask(agent, 2, 2);
        var transientErr = new SocketTimeoutException("read");

        var decision = JClawFailureHandler.decide(task.id, transientErr);
        assertInstanceOf(Decision.Fail.class, decision);
        var fresh = (Task) Tx.run(() -> Task.findById(task.id));
        assertEquals(Task.Status.FAILED, fresh.status,
                "transient with retries exhausted should still mark FAILED");
    }

    @Test
    void maxRetriesCapsBudgetShorterThanBackoffArray() {
        // maxRetries=1 means only ONE retry attempt allowed, even
        // though the backoff array has 5 entries.
        var task = persistTask(agent, 0, 1);
        assertInstanceOf(Decision.Reschedule.class,
                JClawFailureHandler.decide(task.id, new SocketTimeoutException("x")));

        // After retryCount became 1, another transient should now FAIL
        // because budget=min(1,5)=1 and currentRetry=1 is at-budget.
        var decision = JClawFailureHandler.decide(task.id, new SocketTimeoutException("x"));
        assertInstanceOf(Decision.Fail.class, decision);
    }

    @Test
    void missingTaskRowFailsCleanly() {
        var decision = JClawFailureHandler.decide(999_999L, new SocketTimeoutException("x"));
        assertInstanceOf(Decision.Fail.class, decision);
        // Doesn't throw, doesn't leave anything in a bad state — just
        // logs a warn and returns the Fail decision so the
        // db-scheduler row gets stopped.
    }

    @Test
    void nullThrowableCountsAsPermanent() {
        var task = persistTask(agent, 0, 3);
        var decision = JClawFailureHandler.decide(task.id, null);
        assertInstanceOf(Decision.Fail.class, decision,
                "null throwable: no signal to retry on → fail");
        var fresh = (Task) Tx.run(() -> Task.findById(task.id));
        assertEquals(Task.Status.FAILED, fresh.status);
        assertEquals("Unknown error", fresh.lastError);
    }

    @Test
    void transientUsesExceptionClassNameWhenMessageIsNull() {
        var task = persistTask(agent, 0, 3);
        var decision = JClawFailureHandler.decide(task.id, new SocketTimeoutException());
        assertInstanceOf(Decision.Reschedule.class, decision);
        var fresh = (Task) Tx.run(() -> Task.findById(task.id));
        // No message → fall back to the simple class name as the
        // lastError so operators have something to grep.
        assertEquals("SocketTimeoutException", fresh.lastError);
    }

    // === Helpers ===

    private Agent persistAgent() {
        var a = new Agent();
        a.name = "failure-handler-test-agent";
        a.modelProvider = "test-provider";
        a.modelId = "test-model";
        a.enabled = true;
        a.save();
        return a;
    }

    private Task persistTask(Agent agent, int retryCount, int maxRetries) {
        var t = new Task();
        t.agent = agent;
        t.name = "fh-test-" + System.nanoTime();
        t.description = "Test task";
        t.type = Task.Type.IMMEDIATE;
        t.status = Task.Status.PENDING;
        t.retryCount = retryCount;
        t.maxRetries = maxRetries;
        t.nextRunAt = Instant.now();
        t.createdAt = Instant.now();
        t.updatedAt = Instant.now();
        t.save();
        return t;
    }
}
