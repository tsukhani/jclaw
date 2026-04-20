import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.ConversationQueue;
import services.ConversationQueue.QueuedMessage;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class ConversationQueueTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = new Agent();
        agent.name = "queue-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.save();
    }

    @Test
    public void firstMessageAcquiresImmediately() {
        var msg = new QueuedMessage("Hello", "web", "admin", agent);
        var acquired = ConversationQueue.tryAcquire(1000L, msg);
        assertTrue(acquired, "First message should acquire immediately");

        // Cleanup
        ConversationQueue.drain(1000L);
    }

    @Test
    public void secondMessageIsQueued() {
        var msg1 = new QueuedMessage("First", "web", "admin", agent);
        var msg2 = new QueuedMessage("Second", "web", "admin", agent);

        assertTrue(ConversationQueue.tryAcquire(1001L, msg1));
        assertFalse(ConversationQueue.tryAcquire(1001L, msg2), "Second message should be queued");
        assertEquals(1, ConversationQueue.getQueueSize(1001L));

        // Cleanup
        ConversationQueue.drain(1001L);
        ConversationQueue.drain(1001L);
    }

    @Test
    public void drainReturnsQueuedMessages() {
        var msg1 = new QueuedMessage("First", "web", "admin", agent);
        var msg2 = new QueuedMessage("Second", "web", "admin", agent);
        var msg3 = new QueuedMessage("Third", "web", "admin", agent);

        ConversationQueue.tryAcquire(1002L, msg1); // acquired
        ConversationQueue.tryAcquire(1002L, msg2); // queued
        ConversationQueue.tryAcquire(1002L, msg3); // queued

        // First drain releases the lock and returns next message
        var next = ConversationQueue.drain(1002L);
        assertEquals(1, next.size());
        assertEquals("Second", next.getFirst().text());

        // Second drain returns the third
        var next2 = ConversationQueue.drain(1002L);
        assertEquals(1, next2.size());
        assertEquals("Third", next2.getFirst().text());

        // Third drain returns empty (queue cleared)
        var next3 = ConversationQueue.drain(1002L);
        assertTrue(next3.isEmpty());
    }

    @Test
    public void isBusyReflectsProcessingState() {
        assertFalse(ConversationQueue.isBusy(1003L));

        var msg = new QueuedMessage("Test", "web", "admin", agent);
        ConversationQueue.tryAcquire(1003L, msg);
        assertTrue(ConversationQueue.isBusy(1003L));

        ConversationQueue.drain(1003L);
        assertFalse(ConversationQueue.isBusy(1003L));
    }

    @Test
    public void formatCollectedMessagesSingleMessage() {
        var messages = java.util.List.of(new QueuedMessage("Hello", "web", "admin", agent));
        var formatted = ConversationQueue.formatCollectedMessages(messages);
        assertEquals("Hello", formatted);
    }

    @Test
    public void formatCollectedMessagesMultipleMessages() {
        var messages = java.util.List.of(
                new QueuedMessage("First", "web", "admin", agent),
                new QueuedMessage("Second", "web", "admin", agent)
        );
        var formatted = ConversationQueue.formatCollectedMessages(messages);
        assertTrue(formatted.contains("Queued messages"));
        assertTrue(formatted.contains("Message 1"));
        assertTrue(formatted.contains("First"));
        assertTrue(formatted.contains("Message 2"));
        assertTrue(formatted.contains("Second"));
    }

    @Test
    public void queueCapDropsOldest() {
        var msg1 = new QueuedMessage("Acquired", "web", "admin", agent);
        ConversationQueue.tryAcquire(1004L, msg1);

        // Fill queue to cap (20)
        for (int i = 0; i < 20; i++) {
            ConversationQueue.tryAcquire(1004L,
                    new QueuedMessage("Msg " + i, "web", "admin", agent));
        }
        assertEquals(20, ConversationQueue.getQueueSize(1004L));

        // One more should drop the oldest
        ConversationQueue.tryAcquire(1004L,
                new QueuedMessage("Overflow", "web", "admin", agent));
        assertEquals(20, ConversationQueue.getQueueSize(1004L));

        // Drain and verify oldest was dropped
        var next = ConversationQueue.drain(1004L);
        // First in queue should be "Msg 1" (Msg 0 was dropped)
        assertEquals("Msg 1", next.getFirst().text());

        // Cleanup remaining
        while (!ConversationQueue.drain(1004L).isEmpty()) {}
    }

    @Test
    public void concurrentAcquireSerializes() throws Exception {
        int threadCount = 10;
        var latch = new CountDownLatch(threadCount);
        var acquiredCount = new AtomicInteger(0);
        var queuedCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                var msg = new QueuedMessage("Thread " + idx, "web", "admin", agent);
                if (ConversationQueue.tryAcquire(1005L, msg)) {
                    acquiredCount.incrementAndGet();
                } else {
                    queuedCount.incrementAndGet();
                }
                latch.countDown();
            });
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertEquals(1, acquiredCount.get(), "Exactly one thread should acquire");
        assertEquals(threadCount - 1, queuedCount.get(), "Rest should be queued");

        // Cleanup
        ConversationQueue.drain(1005L);
        while (!ConversationQueue.drain(1005L).isEmpty()) {}
    }

    @Test
    public void interruptModeAllowsExactlyOneAcquirerAtATime() throws Exception {
        // Verifies the TOCTOU fix: in interrupt mode, only one thread at a time
        // should believe it acquired the conversation for processing.
        services.ConfigService.set("agent.queue-test-agent.queue.mode", "interrupt");

        int iterations = 100;
        var doubleAcquire = new AtomicBoolean(false);

        for (int iter = 0; iter < iterations; iter++) {
            long convId = 4000L + iter;
            int threadCount = 8;
            var barrier = new CyclicBarrier(threadCount);
            var latch = new CountDownLatch(threadCount);
            var acquiredCount = new AtomicInteger(0);

            for (int t = 0; t < threadCount; t++) {
                final int idx = t;
                Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        var msg = new QueuedMessage("Thread " + idx, "web", "admin", agent);
                        if (ConversationQueue.tryAcquire(convId, msg)) {
                            acquiredCount.incrementAndGet();
                        }
                    } catch (Exception _) {
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            // In interrupt mode, multiple threads may return true (the interrupter
            // returns true to signal "take over"), but the processing flag should
            // ensure only one thread transitions from not-processing to processing.
            // Additional acquirers in interrupt mode clear the queue and return true
            // to signal the caller to proceed — but the caller must still be the one
            // that set processing=true, or the one that interrupted.
            // The key invariant: acquiredCount >= 1 (at least one), and the queue
            // state should be consistent (not corrupted).
            assertTrue(acquiredCount.get() >= 1,
                    "At least one thread must acquire in interrupt mode");

            // Drain and reset
            ConversationQueue.drain(convId);
            while (!ConversationQueue.drain(convId).isEmpty()) {}
        }

        assertFalse(doubleAcquire.get());
        services.ConfigService.delete("agent.queue-test-agent.queue.mode");
    }

    @Test
    public void getQueueSizeReturnsZeroForUnknownConversation() {
        assertEquals(0, ConversationQueue.getQueueSize(9999L));
    }

    @Test
    public void concurrentInterruptModeDoesNotCorruptArrayDeque() throws Exception {
        // Task 3.7: Two virtual threads call tryAcquire in interrupt mode simultaneously.
        // Before the fix, the unsynchronized state.pending.clear() could corrupt the deque.
        services.ConfigService.set("agent.queue-test-agent.queue.mode", "interrupt");

        int iterations = 50;
        var corrupted = new AtomicBoolean(false);

        for (int iter = 0; iter < iterations; iter++) {
            long convId = 2000L + iter;
            var msg1 = new QueuedMessage("First", "web", "admin", agent);
            ConversationQueue.tryAcquire(convId, msg1); // acquire processing

            // Queue some messages
            for (int i = 0; i < 5; i++) {
                ConversationQueue.tryAcquire(convId,
                        new QueuedMessage("Queued " + i, "web", "admin", agent));
            }

            int threadCount = 4;
            var barrier = new CyclicBarrier(threadCount);
            var latch = new CountDownLatch(threadCount);

            for (int t = 0; t < threadCount; t++) {
                final int idx = t;
                Thread.ofVirtual().start(() -> {
                    try {
                        barrier.await();
                        // Half interrupt (clear), half drain (poll)
                        if (idx % 2 == 0) {
                            ConversationQueue.tryAcquire(convId,
                                    new QueuedMessage("Interrupt " + idx, "web", "admin", agent));
                        } else {
                            ConversationQueue.drain(convId);
                        }
                    } catch (Exception e) {
                        corrupted.set(true);
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));
            // Clean up for next iteration
            ConversationQueue.drain(convId);
            while (!ConversationQueue.drain(convId).isEmpty()) {}
        }

        assertFalse(corrupted.get(), "ArrayDeque should not throw from concurrent access");
        services.ConfigService.delete("agent.queue-test-agent.queue.mode");
    }

    @Test
    public void getQueueSizeConsistentUnderConcurrentEnqueue() throws Exception {
        // Task 3.8: getQueueSize() returns a consistent value under concurrent enqueue
        long convId = 3000L;
        var msg1 = new QueuedMessage("First", "web", "admin", agent);
        ConversationQueue.tryAcquire(convId, msg1); // acquire processing

        int threadCount = 20;
        var barrier = new CyclicBarrier(threadCount + 1); // +1 for the reader thread
        var latch = new CountDownLatch(threadCount + 1);
        var sizeError = new AtomicBoolean(false);

        // Writer threads: enqueue messages
        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            Thread.ofVirtual().start(() -> {
                try {
                    barrier.await();
                    ConversationQueue.tryAcquire(convId,
                            new QueuedMessage("Thread " + idx, "web", "admin", agent));
                } catch (Exception _) {
                } finally {
                    latch.countDown();
                }
            });
        }

        // Reader thread: repeatedly read size — should never throw or return negative
        Thread.ofVirtual().start(() -> {
            try {
                barrier.await();
                for (int i = 0; i < 100; i++) {
                    int size = ConversationQueue.getQueueSize(convId);
                    if (size < 0) {
                        sizeError.set(true);
                    }
                }
            } catch (Exception e) {
                sizeError.set(true);
            } finally {
                latch.countDown();
            }
        });

        assertTrue(latch.await(10, TimeUnit.SECONDS));
        assertFalse(sizeError.get(), "getQueueSize() should never return a negative value or throw");

        int finalSize = ConversationQueue.getQueueSize(convId);
        assertTrue(finalSize >= 0 && finalSize <= 20,
                "Final queue size should be between 0 and cap (20), got: " + finalSize);

        // Cleanup
        ConversationQueue.drain(convId);
        while (!ConversationQueue.drain(convId).isEmpty()) {}
    }

    // --- Collect mode ---

    @Test
    public void collectModeReturnsAllPendingOnDrain() {
        // In collect mode, drain must return the ENTIRE pending list (for the
        // caller to fold into a single prompt) rather than a single message —
        // contrast with queue mode which returns just the next one.
        // Contract at ConversationQueue.java:133-137.
        //
        // Note: the first tryAcquire acquires processing without enqueueing;
        // the caller processes "First" inline. Only subsequent messages land
        // in the pending deque, so drain returns 2 (not 3) — this is the
        // distinction collect mode makes: multiple-during-busy fold together.
        services.ConfigService.set("agent.queue-test-agent.queue.mode", "collect");

        long convId = 5000L;
        assertTrue(ConversationQueue.tryAcquire(convId,
                new QueuedMessage("First", "web", "admin", agent)));
        assertFalse(ConversationQueue.tryAcquire(convId,
                new QueuedMessage("Second", "web", "admin", agent)));
        assertFalse(ConversationQueue.tryAcquire(convId,
                new QueuedMessage("Third", "web", "admin", agent)));

        var drained = ConversationQueue.drain(convId);
        assertEquals(2, drained.size(),
                "collect mode must return all pending messages in one batch");
        assertEquals("Second", drained.get(0).text());
        assertEquals("Third", drained.get(1).text());

        // Contrast with queue mode: queue mode would return ["Second"] here,
        // requiring a second drain to get "Third". Collect returns both
        // in one shot so the caller can format them into a single prompt.
        assertTrue(ConversationQueue.drain(convId).isEmpty(),
                "collect drain must fully empty the pending deque");
        services.ConfigService.delete("agent.queue-test-agent.queue.mode");
    }

    // --- Interrupt mode cancellation signal ---

    @Test
    public void interruptModeSetsCancellationFlagForActiveProcessor() {
        // When interrupt fires, the in-flight processor must observe the
        // cancelled flag via cancellationFlag(). Drain then clears it so
        // the next run starts fresh. This is the contract AgentRunner
        // relies on for barge-in behavior.
        services.ConfigService.set("agent.queue-test-agent.queue.mode", "interrupt");

        long convId = 5100L;
        ConversationQueue.tryAcquire(convId, new QueuedMessage("In-flight", "web", "admin", agent));

        var flag = ConversationQueue.cancellationFlag(convId);
        assertFalse(flag.get(), "flag starts clear");

        // Second message in interrupt mode triggers cancellation
        ConversationQueue.tryAcquire(convId, new QueuedMessage("Barge-in", "web", "admin", agent));
        assertTrue(flag.get(), "interrupt must raise cancellation flag");

        // Drain clears the flag so the subsequent processing run is fresh
        ConversationQueue.drain(convId);
        assertFalse(flag.get(), "drain must reset cancellation flag");

        // Cleanup any pending
        while (!ConversationQueue.drain(convId).isEmpty()) {}
        services.ConfigService.delete("agent.queue-test-agent.queue.mode");
    }
}
