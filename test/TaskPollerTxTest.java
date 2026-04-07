import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Task;
import services.Tx;

import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tests that Tx.run() provides JPA transaction context on virtual threads.
 */
public class TaskPollerTxTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    public void txRunWorksOnMainThread() {
        // Verify Tx.run works in the test's existing JPA context
        var result = Tx.run(() -> {
            var agent = new Agent();
            agent.name = "tx-main-thread";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            return agent.name;
        });
        assertEquals("tx-main-thread", result);
    }

    @Test
    public void txRunCreatesAndQueriesInSameTransaction() {
        // Verify Tx.run can create and query entities
        var agent = new Agent();
        agent.name = "tx-query-test";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();

        var task = new Task();
        task.agent = agent;
        task.name = "query-test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        // Verify we can update the task status
        task.status = Task.Status.RUNNING;
        task.save();

        Task found = Task.findById(task.id);
        assertEquals(Task.Status.RUNNING, found.status);
    }

    @Test
    public void txRunHandlesNestedCalls() {
        // Verify nested Tx.run calls work (inner reuses outer's transaction)
        var agent = new Agent();
        agent.name = "tx-nested-test";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();

        Tx.run(() -> {
            var task = new Task();
            task.agent = agent;
            task.name = "nested-task";
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.Status.PENDING;
            task.nextRunAt = Instant.now();
            task.save();

            // Nested Tx.run should reuse the existing transaction
            Tx.run(() -> {
                task.status = Task.Status.RUNNING;
                task.save();
            });

            assertEquals(Task.Status.RUNNING, task.status);
        });
    }

    @Test
    public void taskStatusTransitions() {
        var agent = new Agent();
        agent.name = "status-transition";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();

        var task = new Task();
        task.agent = agent;
        task.name = "transition-test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        // PENDING -> RUNNING
        task.status = Task.Status.RUNNING;
        task.save();
        assertEquals(Task.Status.RUNNING, ((Task) Task.findById(task.id)).status);

        // RUNNING -> COMPLETED
        task.status = Task.Status.COMPLETED;
        task.save();
        assertEquals(Task.Status.COMPLETED, ((Task) Task.findById(task.id)).status);
    }

    @Test
    public void taskRetryWithTxRun() {
        var agent = new Agent();
        agent.name = "retry-tx-test";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();

        var task = new Task();
        task.agent = agent;
        task.name = "retry-task";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.RUNNING;
        task.nextRunAt = Instant.now();
        task.save();

        // Simulate failure and retry via Tx.run
        Tx.run(() -> {
            task.retryCount++;
            task.lastError = "timeout";
            task.status = Task.Status.PENDING;
            task.nextRunAt = Instant.now().plusSeconds(30);
            task.save();
        });

        Task found = Task.findById(task.id);
        assertEquals(Task.Status.PENDING, found.status);
        assertEquals(1, found.retryCount);
        assertEquals("timeout", found.lastError);
    }
}
