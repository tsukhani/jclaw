import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.ConversationQueue;
import services.ConversationQueue.QueuedMessage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
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
    public void getQueueSizeReturnsZeroForUnknownConversation() {
        assertEquals(0, ConversationQueue.getQueueSize(9999L));
    }
}
