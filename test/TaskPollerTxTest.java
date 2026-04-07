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
 * Tests that TaskPollerJob JPA operations work correctly with Tx.run() on virtual threads.
 */
public class TaskPollerTxTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    public void txRunWorksOnVirtualThread() throws Exception {
        // Create a task on the main thread (has JPA context)
        var agent = createAgent();
        var task = new Task();
        task.agent = agent;
        task.name = "tx-test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        var taskId = task.id;
        var latch = new CountDownLatch(1);
        var error = new AtomicReference<Exception>();

        // Run JPA operations on a virtual thread via Tx.run() — same pattern as TaskPollerJob
        Thread.ofVirtual().start(() -> {
            try {
                Tx.run(() -> {
                    Task t = Task.findById(taskId);
                    t.status = Task.Status.RUNNING;
                    t.save();
                });
                latch.countDown();
            } catch (Exception e) {
                error.set(e);
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS), "Virtual thread should complete");
        assertNull(error.get(), "No exception should be thrown: " +
                (error.get() != null ? error.get().getMessage() : ""));

        // Verify the update persisted
        Task updated = Task.findById(taskId);
        assertEquals(Task.Status.RUNNING, updated.status);
    }

    @Test
    public void txRunHandlesMultipleOperationsOnVirtualThread() throws Exception {
        var agent = createAgent();
        var task = new Task();
        task.agent = agent;
        task.name = "multi-op-test";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        var taskId = task.id;
        var latch = new CountDownLatch(1);
        var error = new AtomicReference<Exception>();

        Thread.ofVirtual().start(() -> {
            try {
                // First transaction: mark as running
                Tx.run(() -> {
                    Task t = Task.findById(taskId);
                    t.status = Task.Status.RUNNING;
                    t.save();
                });

                // Second transaction: mark as completed
                Tx.run(() -> {
                    Task t = Task.findById(taskId);
                    t.status = Task.Status.COMPLETED;
                    t.save();
                });

                latch.countDown();
            } catch (Exception e) {
                error.set(e);
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNull(error.get(), "No exception: " +
                (error.get() != null ? error.get().getMessage() : ""));

        Task completed = Task.findById(taskId);
        assertEquals(Task.Status.COMPLETED, completed.status);
    }

    @Test
    public void txRunWithReturnValueOnVirtualThread() throws Exception {
        var agent = createAgent();
        var latch = new CountDownLatch(1);
        var result = new AtomicReference<String>();
        var error = new AtomicReference<Exception>();

        Thread.ofVirtual().start(() -> {
            try {
                var name = Tx.run(() -> {
                    Agent a = Agent.findByName("poller-tx-agent");
                    return a.name;
                });
                result.set(name);
                latch.countDown();
            } catch (Exception e) {
                error.set(e);
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertEquals("poller-tx-agent", result.get());
    }

    private Agent createAgent() {
        var agent = new Agent();
        agent.name = "poller-tx-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
        return agent;
    }
}
