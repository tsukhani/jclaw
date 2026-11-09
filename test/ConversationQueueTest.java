import models.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConversationQueue;
import services.ConversationQueue.QueuedMessage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

class ConversationQueueTest extends UnitTest {

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
    void firstMessageAcquiresImmediately() {
        var msg = new QueuedMessage("Hello", "web", "admin", agent);
        var acquired = ConversationQueue.tryAcquire(1000L, msg);
        assertTrue(acquired, "First message should acquire immediately");

        // Cleanup
        ConversationQueue.drain(1000L);
    }

    @Test
    void secondMessageIsQueued() {
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
    void drainReturnsQueuedMessages() {
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
    void isBusyReflectsProcessingState() {
        assertFalse(ConversationQueue.isBusy(1003L));

        var msg = new QueuedMessage("Test", "web", "admin", agent);
        ConversationQueue.tryAcquire(1003L, msg);
        assertTrue(ConversationQueue.isBusy(1003L));

        ConversationQueue.drain(1003L);
        assertFalse(ConversationQueue.isBusy(1003L));
    }

    @Test
    void formatCollectedMessagesSingleMessage() {
        var messages = java.util.List.of(new QueuedMessage("Hello", "web", "admin", agent));
        var formatted = ConversationQueue.formatCollectedMessages(messages);
        assertEquals("Hello", formatted);
    }

    @Test
    void formatCollectedMessagesMultipleMessages() {
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
    void queueCapDropsOldest() {
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
    void concurrentAcquireSerializes() throws Exception {
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
    void getQueueSizeReturnsZeroForUnknownConversation() {
        assertEquals(0, ConversationQueue.getQueueSize(9999L));
    }

    @Test
    void concurrentArrivalsAndDrainsDoNotCorruptTheLine() throws Exception {
        // Task 3.7: Arrivals and drains on one conversation at once.
        // Before the fix, the unsynchronized state.pending.clear() could corrupt the deque.

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
                        // Half arrive, half drain
                        if (idx % 2 == 0) {
                            ConversationQueue.tryAcquire(convId,
                                    new QueuedMessage("Interrupt " + idx, "web", "admin", agent));
                        } else {
                            ConversationQueue.drain(convId);
                        }
                    } catch (Exception _) {
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
    }

    @Test
    void getQueueSizeConsistentUnderConcurrentEnqueue() throws Exception {
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
            } catch (Exception _) {
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

    // --- What runs next follows the conversation's channel (JF-245) ---

    private QueuedMessage from(String text, String channel, String sender, boolean owner) {
        return new QueuedMessage(text, channel, "peer", agent, false, List.of(), null, owner, sender);
    }

    @Test
    void aSendersWaitingMessagesOnTelegramRunTogetherAsOneTurn() {
        long convId = 5000L;
        assertTrue(ConversationQueue.tryAcquire(convId, from("First", "telegram", "u1", false)));
        assertFalse(ConversationQueue.tryAcquire(convId, from("Second", "telegram", "u1", false)));
        assertFalse(ConversationQueue.tryAcquire(convId, from("Third", "telegram", "u1", false)));
        assertFalse(ConversationQueue.tryAcquire(convId, from("Fourth", "telegram", "u1", false)));

        assertEquals(List.of("Second", "Third", "Fourth"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of(), ConversationQueue.drain(convId), "the batch emptied the line and released it");
    }

    @Test
    void waitingMessagesOnSlackAndWhatsAppRunTogetherToo() {
        for (var channel : List.of("slack", "whatsapp")) {
            long convId = 5010L + channel.length();
            ConversationQueue.tryAcquire(convId, from("First", channel, "u1", false));
            ConversationQueue.tryAcquire(convId, from("Second", channel, "u1", false));
            ConversationQueue.tryAcquire(convId, from("Third", channel, "u1", false));

            assertEquals(List.of("Second", "Third"), texts(ConversationQueue.drain(convId)), channel);
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void eachMembersRunOfMessagesInAGroupIsItsOwnTurnInArrivalOrder() {
        long convId = 5020L;
        ConversationQueue.tryAcquire(convId, from("running", "telegram", "u0", false));
        for (var m : List.of(new String[]{"a1", "u1"}, new String[]{"a2", "u1"}, new String[]{"b1", "u2"},
                new String[]{"a3", "u1"})) {
            ConversationQueue.tryAcquire(convId, from(m[0], "telegram", m[1], false));
        }

        assertEquals(List.of("a1", "a2"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of("b1"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of("a3"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of(), ConversationQueue.drain(convId));
    }

    @Test
    void anOwnersMessageFromChatNeverJoinsAChannelSendersBatch() {
        long convId = 5030L;
        ConversationQueue.tryAcquire(convId, from("running", "telegram", "u1", false));
        ConversationQueue.tryAcquire(convId, from("customer 1", "telegram", "u1", false));
        ConversationQueue.tryAcquire(convId, from("operator", "web", null, true));
        ConversationQueue.tryAcquire(convId, from("customer 2", "telegram", "u1", false));

        assertEquals(List.of("customer 1"), texts(ConversationQueue.drain(convId)));
        var operator = ConversationQueue.drain(convId);
        assertEquals(List.of("operator"), texts(operator));
        assertEquals(List.of("customer 2"), texts(ConversationQueue.drain(convId)));
    }

    @Test
    void anOwnersAndAGuestsMessageFromOneSenderIdAreNotBatched() {
        long convId = 5035L;
        ConversationQueue.tryAcquire(convId, from("running", "telegram", "u1", true));
        ConversationQueue.tryAcquire(convId, from("owner", "telegram", "u1", true));
        ConversationQueue.tryAcquire(convId, from("guest", "telegram", "u1", false));

        assertEquals(List.of("owner"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of("guest"), texts(ConversationQueue.drain(convId)));
    }

    @Test
    void waitingMessagesInChatEachRunAsTheirOwnTurnInTheOrderSent() {
        long convId = 5040L;
        ConversationQueue.tryAcquire(convId, from("First", "web", null, true));
        ConversationQueue.tryAcquire(convId, from("Second", "web", null, true));
        ConversationQueue.tryAcquire(convId, from("Third", "web", null, true));

        assertEquals(List.of("Second"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of("Third"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of(), ConversationQueue.drain(convId));
    }

    @Test
    void waitingMessagesInVoiceEachRunAsTheirOwnTurn() {
        long convId = 5050L;
        ConversationQueue.tryAcquire(convId, from("First", "voice", null, true));
        ConversationQueue.tryAcquire(convId, from("Second", "voice", null, true));
        ConversationQueue.tryAcquire(convId, from("Third", "voice", null, true));

        assertEquals(List.of("Second"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of("Third"), texts(ConversationQueue.drain(convId)));
    }

    @Test
    void aWaitingSubagentResultRunsAloneAndIsNotFoldedIntoASendersBatch() {
        long convId = 5060L;
        ConversationQueue.tryAcquire(convId, from("running", "telegram", "u1", false));
        ConversationQueue.tryAcquire(convId, from("one", "telegram", "u1", false));
        ConversationQueue.tryAcquire(convId, new QueuedMessage("[subagent result]", "telegram", "peer", agent, true));
        ConversationQueue.tryAcquire(convId, from("two", "telegram", "u1", false));

        assertEquals(List.of("one"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of("[subagent result]"), texts(ConversationQueue.drain(convId)));
        assertEquals(List.of("two"), texts(ConversationQueue.drain(convId)));
    }

    @Test
    void aBatchKeepsOwnershipSoANewArrivalQueuesBehindIt() {
        long convId = 7004L;
        try {
            ConversationQueue.tryAcquire(convId, from("A", "telegram", "u1", false));
            ConversationQueue.tryAcquire(convId, from("B", "telegram", "u1", false));
            ConversationQueue.tryAcquire(convId, from("C", "telegram", "u1", false));

            assertEquals(2, ConversationQueue.drain(convId).size());
            assertFalse(ConversationQueue.tryAcquire(convId, from("D", "telegram", "u1", false)));
        } finally {
            while (!ConversationQueue.drain(convId).isEmpty()) {}
            ConversationQueue.releaseOwnership(convId);
        }
    }

    @Test
    void aMessageArrivingWhileAReplyRunsNeverCancelsIt() {
        long convId = 5100L;
        ConversationQueue.tryAcquire(convId, from("In-flight", "web", null, true));
        var flag = ConversationQueue.cancellationFlag(convId);

        ConversationQueue.tryAcquire(convId, from("Barge-in", "web", null, true));

        assertFalse(flag.get());
        assertEquals(List.of("Barge-in"), texts(ConversationQueue.drain(convId)));
    }

    @Test
    void theTwentyFirstWaitingMessageDropsTheOldest() {
        long convId = 5110L;
        ConversationQueue.tryAcquire(convId, from("running", "web", null, true));
        for (int i = 1; i <= 21; i++) ConversationQueue.tryAcquire(convId, from("m" + i, "web", null, true));

        assertEquals(20, ConversationQueue.getQueueSize(convId));
        assertEquals(List.of("m2"), texts(ConversationQueue.drain(convId)));
    }

    @Test
    void theMessagesWaitingInChatAreListedAndClearedOneByOne() {
        long convId = 18006L;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            for (var text : List.of("B", "C", "D")) {
                ConversationQueue.tryAcquire(convId, new QueuedMessage(text, "web", "admin", agent));
            }

            assertEquals(List.of("B", "C", "D"), texts(ConversationQueue.waiting(convId, null, null)));
            assertEquals(3, ConversationQueue.clearWaiting(convId, null, null));
            assertEquals(List.of(), ConversationQueue.drain(convId, gen), "nothing is left to run");
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aQueuedMessageKeepsItsAttachmentsThroughTheDrain() {
        var photo = new services.AttachmentService.Input("att-15140", "photo.jpg", "image/jpeg", 10, "IMAGE");
        long convId = 15140L;
        assertTrue(ConversationQueue.tryAcquire(convId, new QueuedMessage("owner", "telegram", "p", agent)));
        assertFalse(ConversationQueue.tryAcquire(convId,
                new QueuedMessage("with photo", "telegram", "p", agent, List.of(photo))));

        assertEquals(List.of(photo), ConversationQueue.drain(convId).getFirst().attachments());
        assertTrue(ConversationQueue.drain(convId).isEmpty());
    }

    @Test
    void aMessageQueuedWithoutAttachmentsCarriesNone() {
        assertEquals(List.of(), new QueuedMessage("plain", "telegram", "p", agent).attachments());
        assertEquals(List.of(), new QueuedMessage("resume", "telegram", "p", agent, true).attachments());
        assertEquals(List.of(), new QueuedMessage("text only", "telegram", "p", agent,
                (List<services.AttachmentService.Input>) null).attachments());
    }

    // --- Interrupt mode cancellation signal ---

    // ── JCLAW-117: atomic ownership transfer on drain ──

    @Test
    void drainKeepsProcessingTrueWhenReturningWork() {
        // The core invariant the fix establishes: when drain returns a
        // non-empty list, processing stays true. This prevents a new
        // inbound tryAcquire from slipping in before the caller re-runs.
        long convId = 7001L;
        ConversationQueue.tryAcquire(convId, new QueuedMessage("A", "web", "admin", agent));
        ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));

        var drained = ConversationQueue.drain(convId);
        assertEquals(1, drained.size(), "drain returns queued B");

        // A new inbound message MUST see processing=true and get queued.
        var acquired = ConversationQueue.tryAcquire(convId,
                new QueuedMessage("C", "web", "admin", agent));
        assertFalse(acquired,
                "processing must still be true after drain returned work; C must queue behind B");

        // Release so C is picked up on the next drain cycle.
        ConversationQueue.releaseOwnership(convId);
        var next = ConversationQueue.drain(convId);
        assertEquals(1, next.size(), "C is next after B");
        assertEquals("C", next.get(0).text());
        ConversationQueue.releaseOwnership(convId);
    }

    @Test
    void drainReleasesOwnershipWhenPendingEmpty() {
        // When there's nothing to drain, processing flips to false so the
        // next message can be processed directly.
        long convId = 7002L;
        ConversationQueue.tryAcquire(convId, new QueuedMessage("A", "web", "admin", agent));

        var drained = ConversationQueue.drain(convId);
        assertTrue(drained.isEmpty(), "no pending → empty drain");

        // Next inbound must be able to acquire.
        var acquired = ConversationQueue.tryAcquire(convId,
                new QueuedMessage("B", "web", "admin", agent));
        assertTrue(acquired, "empty drain released ownership so B can acquire");
        ConversationQueue.releaseOwnership(convId);
    }

    @Test
    void releaseOwnershipFlipsProcessingToFalse() {
        long convId = 7003L;
        ConversationQueue.tryAcquire(convId, new QueuedMessage("A", "web", "admin", agent));

        // A new caller would fail tryAcquire because processing=true.
        var blocked = ConversationQueue.tryAcquire(convId,
                new QueuedMessage("B", "web", "admin", agent));
        assertFalse(blocked);

        // Release and drain picks up the queued B.
        ConversationQueue.releaseOwnership(convId);
        var drained = ConversationQueue.drain(convId);
        assertEquals(1, drained.size());
        assertEquals("B", drained.get(0).text());
        ConversationQueue.releaseOwnership(convId);
    }

    // ── JCLAW-286: idle eviction ──

    @Test
    void evictIdleRemovesQuiescentStateAfterThreshold() throws Exception {
        // A conversation that has been touched but is fully idle (no pending,
        // not processing, not cancelled) and whose lastActivityMs is older
        // than the threshold must be evicted by evictIdle.
        long convId = 8001L;
        ConversationQueue.tryAcquire(convId, new QueuedMessage("A", "web", "admin", agent));
        ConversationQueue.releaseOwnership(convId); // pending empty, processing false

        // Sleep past the eviction window so the state's lastActivityMs is stale.
        // 50 ms threshold + 100 ms sleep gives a stable boundary on slow CI.
        Thread.sleep(100);

        var beforeBusy = ConversationQueue.isBusy(convId);
        assertFalse(beforeBusy, "preconditions: state quiescent before eviction");

        var evicted = ConversationQueue.evictIdle(50);
        assertTrue(evicted >= 1, "idle state must be evicted; got count=" + evicted);

        // After eviction, a fresh tryAcquire must succeed (no leftover state).
        var reacquired = ConversationQueue.tryAcquire(convId,
                new QueuedMessage("B", "web", "admin", agent));
        assertTrue(reacquired, "post-eviction tryAcquire acquires cleanly");
        ConversationQueue.releaseOwnership(convId);
    }

    @Test
    void evictIdleSkipsRecentlyActiveState() {
        // A state whose lastActivityMs is fresh (just touched) must NOT be
        // evicted even if it is quiescent — the eviction window protects
        // entries that are likely to be reused imminently.
        long convId = 8002L;
        ConversationQueue.tryAcquire(convId, new QueuedMessage("A", "web", "admin", agent));
        ConversationQueue.releaseOwnership(convId); // quiescent but fresh

        // Threshold of 60s; the state was touched milliseconds ago, so the
        // sweep must skip it.
        var evicted = ConversationQueue.evictIdle(60_000);
        assertEquals(0, evicted, "recently-touched state must survive eviction");

        // State is still present: a follow-up tryAcquire still works.
        var acquired = ConversationQueue.tryAcquire(convId,
                new QueuedMessage("B", "web", "admin", agent));
        assertTrue(acquired);
        ConversationQueue.releaseOwnership(convId);
    }

    @Test
    void evictIdleSkipsProcessingState() throws Exception {
        // Even if the activity timestamp is older than the threshold, an
        // in-flight processing state must NOT be evicted — eviction is
        // contingent on processing == false. We can't assert evicted==0
        // because earlier tests in the class leave quiescent states behind;
        // the load-bearing invariant is that THIS conversation's busy state
        // survives the sweep.
        long convId = 8003L;
        ConversationQueue.tryAcquire(convId, new QueuedMessage("A", "web", "admin", agent));
        // Leave processing=true (no drain/release).

        Thread.sleep(100);
        ConversationQueue.evictIdle(50);
        assertTrue(ConversationQueue.isBusy(convId),
                "processing state must survive eviction sweep");

        // And a follow-up tryAcquire must still queue behind the in-flight run.
        var acquired = ConversationQueue.tryAcquire(convId,
                new QueuedMessage("B", "web", "admin", agent));
        assertFalse(acquired, "subsequent message queues — state was not evicted");

        // Cleanup
        ConversationQueue.drain(convId);
        ConversationQueue.releaseOwnership(convId);
    }

    @Test
    void concurrentTryAcquireAndEvictIdleDoNotCorruptState() throws Exception {
        // Race-test: while a writer is concurrently calling tryAcquire on
        // many conversations, an evictor repeatedly sweeps. The
        // synchronized-block on QueueState atomically pairs the
        // lastActivityMs/processing read in evict with the lastActivityMs/
        // processing write in tryAcquire, so no acquired-but-then-evicted
        // state can be observed.
        int convCount = 50;
        int evictPasses = 20;
        var startBarrier = new CyclicBarrier(2);
        var done = new CountDownLatch(2);
        var anomalies = new AtomicInteger(0);

        // Writer: acquire + release each conv, then verify isBusy is false
        // immediately after release (which it must be — eviction can only
        // remove a state that is quiescent).
        Thread.ofVirtual().start(() -> {
            try {
                startBarrier.await();
                for (int i = 0; i < convCount; i++) {
                    long cid = 9000L + i;
                    var msg = new QueuedMessage("W" + i, "web", "admin", agent);
                    ConversationQueue.tryAcquire(cid, msg);
                    // Immediately release — keep the window narrow but real.
                    ConversationQueue.releaseOwnership(cid);
                }
            } catch (Exception _) {
                anomalies.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        // Evictor: aggressive sweeps with a 0ms threshold (anything quiescent
        // is fair game). Each pass that races with the writer must leave the
        // map in a consistent state — no exceptions, no stranded processing
        // flags.
        Thread.ofVirtual().start(() -> {
            try {
                startBarrier.await();
                for (int i = 0; i < evictPasses; i++) {
                    ConversationQueue.evictIdle(0);
                }
            } catch (Exception _) {
                anomalies.incrementAndGet();
            } finally {
                done.countDown();
            }
        });

        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(0, anomalies.get(),
                "concurrent tryAcquire + evictIdle must not raise exceptions");

        // Final eviction with 0ms catches any quiescent stragglers; the
        // important invariant is that the run completed cleanly, not the
        // exact final count (the writer may have just touched a state
        // when the last pass ran, leaving it 0-ms-old but unevictable
        // in this pass).
        ConversationQueue.evictIdle(0);
    }

    @Test
    void fifoOrderSurvivesRaceBetweenDrainAndInboundMessage() {
        // Regression for JCLAW-117: without atomic ownership transfer, a
        // message arriving between drain()'s pop and the caller's re-run
        // could overtake the drained message. With the fix, this test
        // observes strict FIFO: A finishes → drain pops B → C arrives
        // mid-window → C queues BEHIND B, not in front.
        long convId = 7005L;

        // A is "in flight" — processing=true, B is pending.
        ConversationQueue.tryAcquire(convId, new QueuedMessage("A", "web", "admin", agent));
        ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));

        // Drain pops B but keeps ownership.
        var firstDrain = ConversationQueue.drain(convId);
        assertEquals("B", firstDrain.get(0).text());

        // C arrives right now — must queue, not overtake B.
        var cAcquired = ConversationQueue.tryAcquire(convId,
                new QueuedMessage("C", "web", "admin", agent));
        assertFalse(cAcquired, "C must not overtake B — ownership held");

        // Simulate B's run completing: release, then drain again picks up C.
        ConversationQueue.releaseOwnership(convId);
        var secondDrain = ConversationQueue.drain(convId);
        assertEquals(1, secondDrain.size());
        assertEquals("C", secondDrain.get(0).text(),
                "C runs after B — FIFO order preserved across the race window");

        ConversationQueue.releaseOwnership(convId);
    }

    // ── GH-12: operator stop and the ownership generation fence ──

    @Test
    void stopReleasesABusyConversationAndCancelsItsOwner() {
        long convId = 12001L;
        var ownerCancel = new AtomicBoolean(false);
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), ownerCancel);
        assertNotEquals(ConversationQueue.NOT_ACQUIRED, gen);

        assertTrue(ConversationQueue.stop(convId));

        assertFalse(ConversationQueue.isBusy(convId), "with nothing queued, stop releases ownership at once");
        assertTrue(ownerCancel.get(), "stop flips the stopped turn's own cancel flag");
        assertFalse(ConversationQueue.cancellationFlag(convId).get(),
                "the shared cancellation flag is untouched");
    }

    @Test
    void stopOnAnIdleConversationChangesNothing() {
        long convId = 12002L;
        assertFalse(ConversationQueue.stop(convId), "unknown conversation");
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), null);
        ConversationQueue.releaseOwnership(convId, gen);

        assertFalse(ConversationQueue.stop(convId), "idle conversation");
        assertEquals(gen, ConversationQueue.currentGeneration(convId), "generation unchanged");
        assertTrue(ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent)));
        ConversationQueue.releaseOwnership(convId);
    }

    @Test
    void aStoppedTurnsLateReleaseLeavesTheNewOwnerAlone() {
        long convId = 12003L;
        var stale = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), new AtomicBoolean());
        ConversationQueue.stop(convId);

        var fresh = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("B", "web", "admin", agent), new AtomicBoolean());
        assertNotEquals(ConversationQueue.NOT_ACQUIRED, fresh, "a send right after stop acquires");
        ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));

        assertTrue(ConversationQueue.drain(convId, stale).isEmpty(), "stale drain returns nothing");
        ConversationQueue.releaseOwnership(convId, stale);
        assertTrue(ConversationQueue.isBusy(convId), "the new owner still owns the conversation");
        assertEquals(1, ConversationQueue.getQueueSize(convId), "the new owner's pending queue is untouched");

        var next = ConversationQueue.drain(convId, fresh);
        assertEquals("C", next.getFirst().text(), "the current generation still drains FIFO");
        assertTrue(ConversationQueue.drain(convId, fresh).isEmpty(), "and releases once empty");
        assertFalse(ConversationQueue.isBusy(convId));
    }

    @Test
    void stopEndsAnOwnerThatPassedNoCancelFlagOnTheOneItWasGiven() {
        long convId = 12005L;
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), null);

        assertTrue(ConversationQueue.stop(convId), "every owner holds a flag the stop can set (JF-197)");
        assertFalse(ConversationQueue.isBusy(convId), "the conversation is released");
        assertNotEquals(gen, ConversationQueue.currentGeneration(convId), "the stopped owner's generation is stale");
        assertTrue(ConversationQueue.drain(convId, gen).isEmpty(), "its late drain runs nothing");
    }

    @Test
    void aCurrentGenerationReleaseStillReleases() {
        long convId = 12004L;
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), null);
        ConversationQueue.releaseOwnership(convId, gen);
        assertFalse(ConversationQueue.isBusy(convId));
    }

    @Test
    void stopEndsARunThatTookOverThroughADrainOnAFlagOfItsOwn() {
        long convId = 12006L;
        var streamed = new AtomicBoolean(false);
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), streamed);
        ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
        assertEquals(1, ConversationQueue.drain(convId, gen).size(), "B takes over through the drain");

        assertTrue(ConversationQueue.stop(convId), "the drained run holds a flag of its own");

        assertFalse(streamed.get(), "the finished turn's flag is not the drained run's");
        assertFalse(ConversationQueue.isBusy(convId), "the drained run lost the conversation");
        assertTrue(ConversationQueue.drain(convId, gen).isEmpty(), "the drained run's late drain runs nothing");
        assertFalse(ConversationQueue.isBusy(convId));
    }

    @Test
    void stopEndsARestoredMessagesRunAndTheRestoredOnesBehindItRunInOrder() {
        long convId = 12013L;
        try {
            // Three members' messages, so the drain hands them on one at a time rather than as one sender's batch.
            var gen = ConversationQueue.tryAcquireRestored(convId, List.of(from("R1", "telegram", "m1", false),
                    from("R2", "telegram", "m2", false), from("R3", "telegram", "m3", false)));
            assertNotEquals(ConversationQueue.NOT_ACQUIRED, gen, "test precondition: R1 takes the conversation");

            assertTrue(ConversationQueue.stop(convId), "a restored message's run holds a flag of its own");

            // R2's run finds no conversation behind this id and only releases it, so R3 stays where it waits.
            assertEquals(List.of("R3"), texts(ConversationQueue.waiting(convId, null, null)), "R2 took the conversation");
            assertTrue(ConversationQueue.drain(convId, gen).isEmpty(), "the stopped run's late drain runs nothing");
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aDrainedRunReadsTheFlagAStopSetsAndASetOneOnceItHasLostTheConversation() {
        long convId = 12014L;
        long unknown = 12015L;
        var finished = new AtomicBoolean(false);
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), finished);
        ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
        assertEquals(1, ConversationQueue.drain(convId, gen).size(), "test precondition: B takes over");

        var drained = ConversationQueue.ownerCancel(convId, gen);
        assertFalse(drained.get(), "the drained run starts unstopped");
        assertNotSame(finished, drained, "the finished turn's flag is not the drained run's");

        assertTrue(ConversationQueue.stop(convId));
        assertTrue(drained.get(), "the stop set the flag the drained run read");
        assertFalse(finished.get(), "and left the finished turn's alone");
        assertTrue(ConversationQueue.ownerCancel(convId, gen).get(),
                "a drained run that reads its flag after the stop finds it set");
        ConversationQueue.forgetForTest(unknown);
        assertTrue(ConversationQueue.ownerCancel(unknown, gen).get(), "a conversation the queue holds nothing for");
    }

    // ── JCLAW-1385: the write fence a stopped turn's late commits hit ──

    @Test
    void theOwnerCommitPasses() {
        long convId = 13001L;
        var flag = new AtomicBoolean(false);
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), flag);
        var ran = new AtomicBoolean(false);
        assertTrue(ConversationQueue.commitIfOwner(convId, gen, flag, () -> ran.set(true)));
        assertTrue(ran.get());
        ConversationQueue.releaseOwnership(convId, gen);
    }

    @Test
    void aStaleGenerationCommitIsDroppedAndTheNewOwnersPasses() {
        long convId = 13002L;
        var stale = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), new AtomicBoolean());
        ConversationQueue.stop(convId);
        var fresh = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("B", "web", "admin", agent), new AtomicBoolean());

        var ran = new AtomicInteger();
        assertFalse(ConversationQueue.commitIfOwner(convId, stale, null, ran::incrementAndGet));
        assertEquals(0, ran.get(), "a dropped commit runs nothing");
        assertTrue(ConversationQueue.commitIfOwner(convId, fresh, null, ran::incrementAndGet));
        assertEquals(1, ran.get());
        ConversationQueue.releaseOwnership(convId, fresh);
    }

    @Test
    void aCommitWithItsTurnFlagSetIsDropped() {
        long convId = 13003L;
        var flag = new AtomicBoolean(false);
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), flag);
        flag.set(true);
        var ran = new AtomicBoolean(false);
        assertFalse(ConversationQueue.commitIfOwner(convId, gen, flag, () -> ran.set(true)),
                "an SSE disconnect leaves the generation alone, so the flag alone must drop it");
        assertFalse(ran.get());
        ConversationQueue.releaseOwnership(convId, gen);
    }

    @Test
    void aCommitAfterStopWithNoNewOwnerIsDropped() {
        long convId = 13004L;
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), new AtomicBoolean());
        assertTrue(ConversationQueue.stop(convId));
        var ran = new AtomicBoolean(false);
        assertFalse(ConversationQueue.commitIfOwner(convId, gen, null, () -> ran.set(true)));
        assertFalse(ran.get());
        assertFalse(ConversationQueue.commitIfOwner(99_999_013L, 1, null, () -> ran.set(true)),
                "an unknown conversation has no owner to commit for");
    }

    @Test
    void stopWaitsForACommitAlreadyInsideTheFence() throws Exception {
        long convId = 13005L;
        var flag = new AtomicBoolean(false);
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), flag);
        var inside = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var committed = new java.util.concurrent.CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> committed.complete(ConversationQueue.commitIfOwner(convId, gen, flag, () -> {
            inside.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        })));
        assertTrue(inside.await(10, TimeUnit.SECONDS));

        var stopped = new java.util.concurrent.CompletableFuture<Boolean>();
        Thread.ofVirtual().start(() -> stopped.complete(ConversationQueue.stop(convId)));
        Thread.sleep(300);
        assertFalse(stopped.isDone(), "stop must block while a commit holds the fence");

        release.countDown();
        assertTrue(committed.get(10, TimeUnit.SECONDS), "the in-flight commit lands");
        assertTrue(stopped.get(10, TimeUnit.SECONDS), "and stop returns true once it has");
        assertFalse(ConversationQueue.commitIfOwner(convId, gen, flag, () -> {}), "later writes drop");
    }

    @Test
    void stoppingAChannelTurnLeavesTheNextTurnUncancelled() {
        long convId = 13006L;
        var first = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("warm", "telegram", "peer", agent), null);
        ConversationQueue.releaseOwnership(convId, first);

        // A channel turn's own flag is the conversation's, as ChannelInboundDispatcher passes it.
        var stoppedFlag = ConversationQueue.cancellationFlag(convId);
        ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "telegram", "peer", agent), stoppedFlag);
        assertTrue(ConversationQueue.stop(convId));

        var nextFlag = ConversationQueue.cancellationFlag(convId);
        assertFalse(nextFlag.get(), "the next channel turn must not start cancelled");
        var gen = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("B", "telegram", "peer", agent), nextFlag);
        assertTrue(ConversationQueue.commitIfOwner(convId, gen, nextFlag, () -> {}), "the next turn's rows land");
        assertTrue(stoppedFlag.get(), "the stopped turn stays stopped");
        ConversationQueue.releaseOwnership(convId, gen);
    }

    // ── JCLAW-1385: every cancel site runs the turn's registered actions ──

    @Test
    void stopRunsTheOwnersRegisteredCancelActions() throws Exception {
        long convId = 14001L;
        var flag = new AtomicBoolean(false);
        ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), flag);
        var ran = new CountDownLatch(1);
        try (var _ = agents.TurnCancellation.register(flag, ran::countDown)) {
            assertTrue(ConversationQueue.stop(convId));
            assertTrue(ran.await(5, TimeUnit.SECONDS), "stop ran the turn's cancel action");
        }
    }

    @Test
    void cancelTurnRunsActionsRegisteredOnTheConversationFlag() throws Exception {
        long convId = 14002L;
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
        var ran = new CountDownLatch(1);
        try (var _ = agents.TurnCancellation.register(ConversationQueue.cancellationFlag(convId), ran::countDown)) {
            ConversationQueue.cancelTurn(convId);
            assertTrue(ran.await(5, TimeUnit.SECONDS), "/stop ran the turn's cancel action");
        }
        ConversationQueue.releaseOwnership(convId, gen);
    }

    @Test
    void aFencedSinkKeepsDiscardingAfterOneRefusedCommit() {
        var conv = services.ConversationService.create(agent, "web", "sticky");
        var flag = new AtomicBoolean(true);
        var gen = ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "web", "admin", agent), flag);
        var sink = new agents.ConversationSink(conv, gen, flag);

        assertFalse(sink.commit(() -> sink.appendUserMessage("refused", null)));
        flag.set(false);
        assertTrue(ConversationQueue.commitIfOwner(conv.id, gen, flag, () -> {}), "ownership would allow it now");
        assertFalse(sink.commit(() -> sink.appendAssistantMessage("still dropped", null, null, null, false)),
                "discard mode is sticky");

        assertEquals(2, sink.droppedRows());
        assertEquals(0L, models.Message.count("conversation.id = ?1", conv.id));
        ConversationQueue.releaseOwnership(conv.id, gen);
    }

    @Test
    void aHandOffLeavesAStoppedChannelTurnsFlagSetSoItsLateWritesDrop() {
        long convId = 12010L;
        ConversationQueue.drain(convId, ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("earlier", "telegram", "peer", agent), null));
        // A channel turn's own flag is the conversation's shared flag, read before it acquires.
        var shared = ConversationQueue.cancellationFlag(convId);
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "telegram", "peer", agent), shared);
        ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "telegram", "peer", agent));
        ConversationQueue.cancelTurn(convId);

        assertEquals(1, ConversationQueue.drain(convId, gen).size(), "B takes over through the drain");

        assertTrue(shared.get(), "the stopped turn's flag stays set");
        assertNotSame(shared, ConversationQueue.cancellationFlag(convId));
        assertFalse(ConversationQueue.cancellationFlag(convId).get(), "the next turn starts with a fresh flag");
        var wrote = new AtomicBoolean(false);
        assertFalse(ConversationQueue.commitIfOwner(convId, gen, shared, () -> wrote.set(true)));
        assertFalse(wrote.get(), "the stopped turn's late write does not land beside B");
        ConversationQueue.drain(convId, gen);
    }

    @Test
    void stopAfterTheTurnReleasedLeavesTheNextTurnUncancelled() {
        long convId = 12011L;
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "telegram", "peer", agent), null);
        ConversationQueue.drain(convId, gen);

        ConversationQueue.cancelTurn(convId);

        assertFalse(ConversationQueue.cancellationFlag(convId).get(),
                "a /stop that lands after the release does not cancel the next message's turn");
    }

    @Test
    void aWriteUnderACancelledTurnFlagDoesNotRun() {
        long convId = 12012L;
        var turn = new AtomicBoolean(false);
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), turn);
        var ran = new AtomicInteger();

        assertTrue(ConversationQueue.commitUnlessCancelled(convId, turn, ran::incrementAndGet));
        turn.set(true);
        assertFalse(ConversationQueue.commitUnlessCancelled(convId, turn, ran::incrementAndGet));

        assertEquals(1, ran.get(), "only the write before the stop ran");
        ConversationQueue.drain(convId, gen);
    }

    // ── JCLAW-1388: a stopped turn leaves a stop marker under its unanswered request ──

    private models.Conversation conversationWithRequest(String peer) {
        var conv = services.ConversationService.create(agent, "web", peer);
        services.ConversationService.appendUserMessage(conv, "run the long command");
        return conv;
    }

    private static long markers(Long convId) {
        return models.Message.count("conversation.id = ?1 AND messageKind = ?2",
                convId, services.ConversationService.MESSAGE_KIND_STOP_MARKER);
    }

    private static java.util.List<models.Message> rows(Long convId) {
        return models.Message.find("conversation.id = ?1 ORDER BY createdAt, id", convId).fetch();
    }

    @Test
    void stopWritesOneMarkerUnderTheUnansweredRequest() {
        var conv = conversationWithRequest("marker-stop");
        ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "web", "admin", agent),
                new AtomicBoolean());

        assertTrue(ConversationQueue.stop(conv.id));

        var all = rows(conv.id);
        assertEquals(2, all.size());
        var marker = all.get(1);
        assertEquals("assistant", marker.role);
        assertEquals("stop_marker", marker.messageKind);
        assertEquals("(Stopped by the user before replying.)", marker.content);
    }

    @Test
    void aStopThatFindsNoRunningTurnWritesNoMarker() {
        var conv = conversationWithRequest("marker-idle");
        var gen = ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "web", "admin", agent),
                new AtomicBoolean());
        ConversationQueue.releaseOwnership(conv.id, gen);

        assertFalse(ConversationQueue.stop(conv.id));
        assertEquals(0, markers(conv.id));
    }

    @Test
    void noMarkerAfterASavedReply() {
        var conv = conversationWithRequest("marker-answered");
        services.ConversationService.appendAssistantMessage(conv, "the reply", null);
        ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "web", "admin", agent),
                new AtomicBoolean());

        assertTrue(ConversationQueue.stop(conv.id));
        assertEquals(0, markers(conv.id));
    }

    @Test
    void aMarkerFollowsToolRoundsWithNoReply() {
        var conv = conversationWithRequest("marker-tools");
        services.ConversationService.appendAssistantMessage(conv, "Let me run that.",
                "[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"exec\",\"arguments\":\"{}\"}}]");
        services.ConversationService.appendToolResult(conv, "c1", "partial output");

        assertTrue(services.ConversationService.appendStopMarkerIfUnanswered(conv.id),
                "a tool-call row is not a reply");
        assertEquals(1, markers(conv.id));
        assertEquals("stop_marker", rows(conv.id).getLast().messageKind);
    }

    @Test
    void noMarkerWhenTheNewestRequestIsAlreadyAnswered() {
        var conv = conversationWithRequest("marker-earlier");
        services.ConversationService.appendAssistantMessage(conv, "earlier reply", null);

        assertFalse(services.ConversationService.appendStopMarkerIfUnanswered(conv.id),
                "a stop before the new request was saved finds only the answered one");
        assertEquals(0, markers(conv.id));
    }

    @Test
    void aRepeatedMarkerWriteLeavesOneRow() {
        var conv = conversationWithRequest("marker-repeat");

        assertTrue(services.ConversationService.appendStopMarkerIfUnanswered(conv.id));
        assertFalse(services.ConversationService.appendStopMarkerIfUnanswered(conv.id));
        assertEquals(1, markers(conv.id));
    }

    @Test
    void theTurnSideMarkerWriteIsRefusedAfterStop() {
        var conv = conversationWithRequest("marker-fence");
        var flag = new AtomicBoolean();
        var gen = ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "web", "admin", agent), flag);
        assertTrue(ConversationQueue.stop(conv.id));

        assertFalse(ConversationQueue.writeStopMarkerIfOwner(conv.id, gen), "stale generation");
        assertEquals(1, markers(conv.id), "only stop's own marker");
    }

    @Test
    void theTurnSideMarkerWriteIgnoresTheTurnsSetFlag() {
        var conv = conversationWithRequest("marker-own-cancel");
        var flag = new AtomicBoolean();
        var gen = ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "web", "admin", agent), flag);
        flag.set(true);

        assertTrue(ConversationQueue.writeStopMarkerIfOwner(conv.id, gen));
        assertEquals(1, markers(conv.id));
        ConversationQueue.releaseOwnership(conv.id, gen);
    }

    @Test
    void aMarkerWriteThatThrowsStillReleasesAndStopStillReportsTrue() {
        var conv = conversationWithRequest("marker-throws");
        var em = play.db.jpa.JPA.em();
        em.getTransaction().commit();
        var gen = ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "web", "admin", agent),
                new AtomicBoolean());
        var stopped = new boolean[1];
        List<services.EventLogger.Captured> logged;
        try {
            // The thread's JPA context stays bound with no transaction open, so Tx.run joins it,
            // the read succeeds and the marker's flush fails with TransactionRequiredException.
            logged = services.EventLogger.captureForTest(() -> stopped[0] = ConversationQueue.stop(conv.id));
        } finally {
            em.getTransaction().begin();
        }

        assertTrue(stopped[0], "a failed marker write does not undo the stop");
        assertFalse(ConversationQueue.isBusy(conv.id), "ownership was released");
        assertNotEquals(gen, ConversationQueue.currentGeneration(conv.id));
        em.clear();
        assertEquals(0, markers(conv.id), "no marker reached the database");
        var prefix = "Stop marker not written for conversation " + conv.id + ":";
        assertEquals(1L, logged.stream().filter(e -> e.category().equals("queue") && e.message().startsWith(prefix))
                .count(), "the failure is logged");
    }

    @Test
    void aRequestMoreThanFiftyRowsBackStillGetsAMarker() {
        var conv = conversationWithRequest("marker-long-loop");
        for (int i = 0; i < 40; i++) {
            services.ConversationService.appendAssistantMessage(conv, "round " + i,
                    "[{\"id\":\"c" + i + "\",\"type\":\"function\",\"function\":{\"name\":\"exec\",\"arguments\":\"{}\"}}]");
            services.ConversationService.appendToolResult(conv, "c" + i, "output " + i);
        }

        assertTrue(services.ConversationService.appendStopMarkerIfUnanswered(conv.id),
                "80 tool rows after the request, n=" + rows(conv.id).size());
        assertEquals("stop_marker", rows(conv.id).getLast().messageKind);
    }

    @Test
    void anInlineSubagentsRowsAreNotTheParentsReply() {
        var conv = conversationWithRequest("marker-inline");
        services.ConversationService.withSubagentRunIdMarker(424242L, () -> {
            services.ConversationService.appendAssistantMessage(conv, "Spawning subagent: research — dig", null);
            services.ConversationService.appendUserMessage(conv, "dig");
            services.ConversationService.appendAssistantMessage(conv, "the child's reply", null);
            return null;
        });

        assertTrue(services.ConversationService.appendStopMarkerIfUnanswered(conv.id),
                "inline child rows neither answer nor replace the parent's request");
        assertEquals(1, markers(conv.id));
    }

    // ── the operator's stop and the queue snapshot (JF-202) ──

    @Test
    void anOperatorStopFindsAConversationWithNoTurnIdle() {
        long unknown = 17001L;
        long released = 17002L;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(released, new QueuedMessage("A", "web", "admin", agent),
                    new AtomicBoolean());
            ConversationQueue.releaseOwnership(released, gen);

            assertEquals(ConversationQueue.OperatorStop.IDLE, ConversationQueue.stopOperatorTurn(unknown, "web"));
            assertEquals(ConversationQueue.OperatorStop.IDLE, ConversationQueue.stopOperatorTurn(released, "web"));
        } finally {
            ConversationQueue.forgetForTest(unknown);
            ConversationQueue.forgetForTest(released);
        }
    }

    @Test
    void anOperatorStopEndsAWebTurnThatPassedNoCancelFlagAndKeepsItsQueueInOrder() {
        long convId = 17003L;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            var flag = ConversationQueue.ownerCancel(convId, gen);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));

            assertEquals(ConversationQueue.OperatorStop.STOPPED, ConversationQueue.stopOperatorTurn(convId, "web"));

            assertTrue(flag.get(), "A was told to stop");
            // B's run finds no conversation behind this id and only releases it, so C stays where it waits.
            assertEquals(List.of("C"), texts(ConversationQueue.waiting(convId, null, null)), "B took the conversation");
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void anOperatorStopEndsADrainedChannelTurnSendingItsChannelTheNoticeAndHandsTheConversationOn() {
        long convId = 17009L;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId,
                    new QueuedMessage("A", "telegram", "peer", agent), null);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "telegram", "peer", agent));
            assertEquals(1, ConversationQueue.drain(convId, gen).size(), "test precondition: B takes over");
            var drained = ConversationQueue.ownerCancel(convId, gen);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "telegram", "peer", agent));

            var heard = busyAnnouncedWhile(convId, () -> assertEquals(ConversationQueue.OperatorStop.STOPPED,
                    ConversationQueue.stopOperatorTurn(convId, "telegram")));

            assertTrue(drained.get(), "the drained turn was told to stop");
            assertEquals(ConversationQueue.ChannelStop.SENT, ConversationQueue.channelStop(drained),
                    "the stop sent the notice where the drained reply goes, so the turn sends none");
            // C takes the conversation once the notice is sent, off the stop's thread.
            for (int i = 0; i < 200 && ConversationQueue.getQueueSize(convId) > 0; i++) sleep(50);
            assertEquals(0, ConversationQueue.getQueueSize(convId), "C took the conversation");
            assertEquals(busy(convId, true), heard.getFirst(), "the conversation stayed busy with C: " + heard);
            assertSame(drained, ConversationQueue.ownerCancel(convId, gen),
                    "a run the stop overtook reads the flag the stop set");
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aStopSentOnTheChannelOwesItNothingAndAWebStopOwesNoChannelAnything() {
        long channel = 17004L;
        long web = 17010L;
        var channelFlag = new AtomicBoolean();
        var webFlag = new AtomicBoolean();
        try {
            ConversationQueue.tryAcquireOwnership(channel, new QueuedMessage("A", "telegram", "peer", agent),
                    channelFlag);
            ConversationQueue.tryAcquireOwnership(web, new QueuedMessage("A", "web", "admin", agent), webFlag);

            assertEquals(ConversationQueue.OperatorStop.STOPPED,
                    ConversationQueue.stopOperatorTurn(channel, "telegram", false));
            assertEquals(ConversationQueue.OperatorStop.STOPPED, ConversationQueue.stopOperatorTurn(web, "web"));

            assertTrue(channelFlag.get());
            assertEquals(ConversationQueue.ChannelStop.SILENT, ConversationQueue.channelStop(channelFlag));
            assertFalse(ConversationQueue.isBusy(channel));
            assertTrue(webFlag.get());
            assertNull(ConversationQueue.channelStop(webFlag));
            assertNull(ConversationQueue.channelStop(new AtomicBoolean(true)), "no stop set this flag");
        } finally {
            ConversationQueue.forgetForTest(channel);
            ConversationQueue.forgetForTest(web);
        }
    }

    @Test
    void anOperatorStopEndsAVoiceTurnDroppingItsQueuedUtteranceAndLeavesAnAppConversationsTurnRunning() {
        long voice = 17011L;
        long app = 17012L;
        var voiceFlag = new AtomicBoolean();
        var appFlag = new AtomicBoolean();
        try {
            ConversationQueue.tryAcquireOwnership(voice, new QueuedMessage("A", "voice", "admin", agent), voiceFlag);
            ConversationQueue.tryAcquire(voice, new QueuedMessage("B", "voice", "admin", agent));
            ConversationQueue.tryAcquireOwnership(app, new QueuedMessage("A", "app", "app-user", agent), appFlag);

            var heard = busyAnnouncedWhile(voice, () -> assertEquals(ConversationQueue.OperatorStop.STOPPED,
                    ConversationQueue.stopOperatorTurn(voice, "voice")));
            assertEquals(ConversationQueue.OperatorStop.UNSTOPPABLE, ConversationQueue.stopOperatorTurn(app, "app"));

            assertTrue(voiceFlag.get());
            assertNull(ConversationQueue.channelStop(voiceFlag), "the voice overlay ends its own turn");
            assertEquals(List.of(busy(voice, false)), heard, "B was dropped, not handed the conversation");
            assertEquals(0, ConversationQueue.getQueueSize(voice));
            assertFalse(ConversationQueue.isBusy(voice));
            assertFalse(appFlag.get());
            assertTrue(ConversationQueue.isBusy(app));
        } finally {
            ConversationQueue.forgetForTest(voice);
            ConversationQueue.forgetForTest(app);
        }
    }

    @Test
    void anOperatorStopEndsAWebTurnHoldingItsOwnFlagAndHandsTheConversationToTheMessageQueuedFirst() {
        long convId = 17005L;
        var flag = new AtomicBoolean();
        try {
            ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), flag);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("D", "web", "admin", agent));

            assertEquals(ConversationQueue.OperatorStop.STOPPED, ConversationQueue.stopOperatorTurn(convId, "web"));

            assertTrue(flag.get());
            assertEquals(List.of("C", "D"), texts(ConversationQueue.waiting(convId, null, null)), "B took the conversation");
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void anOperatorStopEndsTheTurnOnItsStreamAsStoppedBeforeItReturns() throws Exception {
        // The chat page ends the turn on the stop's answer, so the stopped frame must already be published by then.
        long convId = 17008L;
        var sink = new ConversationTurnStreamTest.RecordingSink();
        var unsubscribe = services.ConversationTurnStream.subscribe(convId, null, sink);
        services.ConversationTurnStream.Turn turn = null;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent),
                    new AtomicBoolean());
            turn = services.ConversationTurnStream.begin(convId, gen);

            assertEquals(ConversationQueue.OperatorStop.STOPPED, ConversationQueue.stopOperatorTurn(convId, "web"));

            assertNull(services.ConversationTurnStream.current(convId), "the turn ended on the stream before the return");
            assertEquals(List.of("{\"type\":\"stopped\"}"),
                    ConversationTurnStreamTest.data(sink.awaitData("{\"type\":\"stopped\"}")));

            turn.stop();
            // A subscriber hears frames in order, written off this thread: once a later turn's frame lands, a second
            // stopped frame would have landed before it.
            services.ConversationTurnStream.begin(convId, gen + 1).end("{\"type\":\"complete\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            assertEquals(List.of("{\"type\":\"stopped\"}", "{\"type\":\"complete\"}"),
                    ConversationTurnStreamTest.data(sink.awaitData("{\"type\":\"complete\"}")),
                    "a turn the operator's stop already ended drops a second terminal");
        } finally {
            // Ends the turn when an assertion above failed before it did.
            if (turn != null) turn.stop();
            unsubscribe.run();
            ConversationQueue.forgetForTest(convId);
        }
    }

    // ── a Stop names the turn the page shows (JF-226) ──

    @Test
    void everyTurnThatOwnsTheConversationHasAnIdOfItsOwnDrainedAndRestoredOnesIncluded() {
        long convId = 17013L;
        long restoredId = 17014L;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            var a = ConversationQueue.ownerTurn(convId, gen);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));
            assertEquals("B", ConversationQueue.drain(convId, gen).getFirst().text());
            var b = ConversationQueue.ownerTurn(convId, gen);
            assertEquals("C", ConversationQueue.drain(convId, gen).getFirst().text());
            var c = ConversationQueue.ownerTurn(convId, gen);
            var restoredGen = ConversationQueue.tryAcquireRestored(restoredId,
                    List.of(new QueuedMessage("R", "web", "admin", agent)));
            var r = ConversationQueue.ownerTurn(restoredId, restoredGen);
            assertEquals(List.of(), ConversationQueue.drain(convId, gen));

            var ids = java.util.Arrays.asList(a, b, c, r);
            assertFalse(ids.contains(null), "every owner has an id: " + ids);
            assertEquals(4, new java.util.HashSet<>(ids).size(), "no two owners share one: " + ids);
            assertNull(ConversationQueue.ownerTurn(convId, gen), "a released conversation has no owner");
            assertNull(ConversationQueue.ownerTurn(restoredId, restoredGen - 1), "nor a generation that is not its own");
            assertNull(ConversationQueue.ownerTurn(17099L, 1), "nor a conversation with no queue");
        } finally {
            ConversationQueue.forgetForTest(convId);
            ConversationQueue.forgetForTest(restoredId);
        }
    }

    @Test
    void aStopNamingADrainedTurnThatHandedTheConversationOnChangesNothingAndTheNextRunsOn() {
        long convId = 17015L;
        services.ConversationTurnStream.Turn running = null;
        try {
            // Each from its own member, so each drain hands on one turn rather than one sender's batch.
            var gen = ConversationQueue.tryAcquireOwnership(convId, from("X", "telegram", "mX", false), null);
            ConversationQueue.tryAcquire(convId, from("A", "telegram", "mA", false));
            ConversationQueue.tryAcquire(convId, from("B", "telegram", "mB", false));
            ConversationQueue.tryAcquire(convId, from("C", "telegram", "mC", false));
            assertEquals("A", ConversationQueue.drain(convId, gen).getFirst().text());
            var a = ConversationQueue.ownerTurn(convId, gen);
            assertEquals("B", ConversationQueue.drain(convId, gen).getFirst().text());
            var b = ConversationQueue.ownerTurn(convId, gen);
            var bFlag = ConversationQueue.ownerCancel(convId, gen);
            running = services.ConversationTurnStream.begin(convId, gen);

            var heard = busyAnnouncedWhile(convId, () -> assertEquals(ConversationQueue.OperatorStop.IDLE,
                    ConversationQueue.stopOperatorTurn(convId, "telegram", true, a)));

            assertFalse(bFlag.get(), "B was not told to stop");
            assertNull(ConversationQueue.channelStop(bFlag), "B's channel is owed no notice");
            assertEquals(List.of(), heard, "the conversation stayed busy with B");
            assertEquals(b, ConversationQueue.ownerTurn(convId, gen), "B still owns it, at its generation");
            assertEquals(1, ConversationQueue.getQueueSize(convId), "C still waits");
            assertTrue(running.isOpen(), "B's turn goes on for its watchers");
            assertEquals(ConversationQueue.OperatorStop.STOPPED,
                    ConversationQueue.stopOperatorTurn(convId, "telegram", true, b), "a stop naming B stops it");
            assertTrue(bFlag.get());
        } finally {
            if (running != null) running.stop();
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aStopNamingTheRunningWebTurnStopsItAndKeepsItsQueueAsAStopNamingNoneDoes() {
        long named = 17016L;
        long unnamed = 17017L;
        var namedFlag = new AtomicBoolean();
        var unnamedFlag = new AtomicBoolean();
        try {
            var gen = ConversationQueue.tryAcquireOwnership(named, new QueuedMessage("A", "web", "admin", agent),
                    namedFlag);
            ConversationQueue.tryAcquireOwnership(unnamed, new QueuedMessage("A", "web", "admin", agent), unnamedFlag);
            for (var conv : List.of(named, unnamed)) {
                ConversationQueue.tryAcquire(conv, new QueuedMessage("B", "web", "admin", agent));
                ConversationQueue.tryAcquire(conv, new QueuedMessage("C", "web", "admin", agent));
            }

            assertEquals(ConversationQueue.OperatorStop.STOPPED, ConversationQueue.stopOperatorTurn(named, "web", true,
                    ConversationQueue.ownerTurn(named, gen)));
            assertEquals(ConversationQueue.OperatorStop.STOPPED,
                    ConversationQueue.stopOperatorTurn(unnamed, "web", true, null));

            for (var conv : List.of(named, unnamed)) {
                assertEquals(List.of("C"), texts(ConversationQueue.waiting(conv, null, null)), "B took the conversation");
            }
            assertTrue(namedFlag.get());
            assertTrue(unnamedFlag.get());
        } finally {
            ConversationQueue.forgetForTest(named);
            ConversationQueue.forgetForTest(unnamed);
        }
    }

    @Test
    void whileAStopsNoticeIsSentNoTurnOwnsTheConversationSoANamedStopFindsItIdle() throws Exception {
        long convId = 17018L;
        var sending = new CountDownLatch(1);
        var sent = new CountDownLatch(1);
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "telegram", "peer", agent),
                    new AtomicBoolean(), _ -> {
                        sending.countDown();
                        try {
                            sent.await(10, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            var a = ConversationQueue.ownerTurn(convId, gen);
            assertEquals(ConversationQueue.OperatorStop.STOPPED,
                    ConversationQueue.stopOperatorTurn(convId, "telegram", true, a));
            assertTrue(sending.await(10, TimeUnit.SECONDS), "test precondition: the notice is being sent");

            assertTrue(ConversationQueue.isBusy(convId), "test precondition: the notice owns the conversation");
            assertNull(ConversationQueue.ownerTurn(convId, ConversationQueue.currentGeneration(convId)));
            assertEquals(ConversationQueue.OperatorStop.IDLE,
                    ConversationQueue.stopOperatorTurn(convId, "telegram", true, a));
        } finally {
            sent.countDown();
            for (int i = 0; i < 200 && ConversationQueue.isBusy(convId); i++) sleep(50);
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aSnapshotTakesBusySizeStoppableAndTheShownMessagesTogether() {
        long convId = 17006L;
        var report = new services.AttachmentService.Input("att-1", "report.pdf", "application/pdf", 10, "file");
        var photo = new services.AttachmentService.Input("att-2", null, "image/png", 20, "image");
        try {
            ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent),
                    new AtomicBoolean());
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent, List.of(report, photo)));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("[subagent result]", "web", "admin", agent, true));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));

            var pending = List.of(new ConversationQueue.PendingView("B", List.of("report.pdf", "image"), 1),
                    new ConversationQueue.PendingView("C", List.of(), 3));
            assertEquals(new ConversationQueue.Snapshot(true, 3, true, false, pending, 0),
                    ConversationQueue.snapshot(convId, "web"));
            assertEquals(new ConversationQueue.Snapshot(true, 3, true, false, pending, 0),
                    ConversationQueue.snapshot(convId, "telegram"));
            assertEquals(new ConversationQueue.Snapshot(true, 3, true, true, pending, 0),
                    ConversationQueue.snapshot(convId, "voice"));
            assertEquals(new ConversationQueue.Snapshot(true, 3, false, false, pending, 0),
                    ConversationQueue.snapshot(convId, "app"));
            assertEquals(new ConversationQueue.Snapshot(false, 0, false, false, List.of(), 0),
                    ConversationQueue.snapshot(17007L, "web"));
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void onlyAVoiceStopDropsTheQueue() {
        assertTrue(ConversationQueue.stopDropsQueue("voice"));
        for (var channel : java.util.Arrays.asList("web", "telegram", "slack", "whatsapp", "app", null)) {
            assertFalse(ConversationQueue.stopDropsQueue(channel), "a stop on " + channel + " keeps the queue");
        }
    }

    // ── /queue lists and clears the waiting messages (JF-227) ──

    @Test
    void theWaitingMessagesAreListedInArrivalOrderWithoutTheSubagentResultAmongThem() {
        long convId = 18001L;
        try {
            ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("[subagent result]", "web", "admin", agent, true));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));

            assertEquals(List.of("B", "C"), texts(ConversationQueue.waiting(convId, null, null)));
            assertEquals(3, ConversationQueue.getQueueSize(convId), "listing changes nothing");
            assertEquals(List.of(), ConversationQueue.waiting(18099L, null, null), "a conversation the queue holds nothing for");
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aClearDropsTheWaitingMessagesLeavesTheSubagentResultAndTheRunningTurnAndTellsEveryTab() {
        long convId = 18002L;
        var flag = new AtomicBoolean();
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), flag);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("[subagent result]", "web", "admin", agent, true));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));

            var dropped = new int[1];
            var heard = busyAnnouncedWhile(convId, () -> dropped[0] = ConversationQueue.clearWaiting(convId, null, null));

            assertEquals(2, dropped[0]);
            assertEquals(List.of(busy(convId, true)), heard, "every open tab re-reads the queue");
            assertEquals(List.of(), ConversationQueue.waiting(convId, null, null));
            assertFalse(flag.get(), "the running turn was not told to stop");
            assertEquals(gen, ConversationQueue.currentGeneration(convId), "and still owns the conversation");
            var next = ConversationQueue.drain(convId, gen);
            assertEquals(List.of("[subagent result]"), texts(next), "the subagent result still wakes the agent");
            assertTrue(next.getFirst().skipUserAppend());
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aClearWithNothingWaitingDropsNothingAndAnnouncesNothing() {
        long convId = 18003L;
        long unknown = 18004L;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("[subagent result]", "web", "admin", agent, true));

            var heard = busyAnnouncedWhile(convId, () -> assertEquals(0, ConversationQueue.clearWaiting(convId, null, null)));

            assertEquals(List.of(), heard);
            assertEquals(1, ConversationQueue.getQueueSize(convId), "the subagent result keeps its place");
            assertEquals(0, ConversationQueue.clearWaiting(unknown, null, null), "a conversation the queue holds nothing for");
            assertEquals(1, ConversationQueue.drain(convId, gen).size(), "test precondition: the result takes over");
            assertEquals(0, ConversationQueue.clearWaiting(convId, null, null), "nothing is left waiting");
        } finally {
            ConversationQueue.forgetForTest(convId);
            ConversationQueue.forgetForTest(unknown);
        }
    }

    @Test
    void aClearOfTheOneMessageWaitingLetsTheRunningTurnEndWithNothingAfterIt() {
        long convId = 18005L;
        try {
            var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));

            assertEquals(1, ConversationQueue.clearWaiting(convId, null, null));

            assertTrue(ConversationQueue.isBusy(convId), "A still runs");
            assertEquals(List.of(), ConversationQueue.drain(convId, gen), "A's end runs nothing");
            assertFalse(ConversationQueue.isBusy(convId));
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    @Test
    void aClearDeletesTheStoredRowsOfTheChannelMessagesItDrops() {
        var conv = services.ConversationService.create(agent, "telegram", "jf227-clear-rows");
        // A queued message's row is written on a thread of its own, which must find the conversation committed.
        play.db.jpa.JPA.em().getTransaction().commit();
        play.db.jpa.JPA.em().getTransaction().begin();
        try {
            ConversationQueue.tryAcquireOwnership(conv.id, new QueuedMessage("A", "telegram", conv.peerId, agent), null);
            ConversationQueue.tryAcquire(conv.id, new QueuedMessage("B", "telegram", conv.peerId, agent));
            ConversationQueue.tryAcquire(conv.id, new QueuedMessage("C", "telegram", conv.peerId, agent));
            assertEquals(2L, models.PendingInbound.count("conversation.id = ?1", conv.id),
                    "test precondition: the queued channel messages are stored");

            assertEquals(2, ConversationQueue.clearWaiting(conv.id, null, null));

            assertEquals(0L, models.PendingInbound.count("conversation.id = ?1", conv.id),
                    "a restart finds none of them to run");
        } finally {
            ConversationQueue.forgetForTest(conv.id);
        }
    }

    @Test
    void theSnapshotCountsEachClearThatDroppedAMessageAndNoOther() {
        long convId = 18007L;
        try {
            ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));
            assertEquals(new ConversationQueue.Snapshot(true, 1, true, false,
                            List.of(new ConversationQueue.PendingView("B", List.of(), 1)), 0),
                    ConversationQueue.snapshot(convId, "web"));

            assertEquals(1, ConversationQueue.clearWaiting(convId, null, null));
            assertEquals(new ConversationQueue.Snapshot(true, 0, true, false, List.of(), 1),
                    ConversationQueue.snapshot(convId, "web"));

            assertEquals(0, ConversationQueue.clearWaiting(convId, null, null));
            ConversationQueue.tryAcquire(convId, new QueuedMessage("[subagent result]", "web", "admin", agent, true));
            assertEquals(0, ConversationQueue.clearWaiting(convId, null, null));
            assertEquals(new ConversationQueue.Snapshot(true, 1, true, false, List.of(), 1),
                    ConversationQueue.snapshot(convId, "web"), "a clear that dropped nothing is not counted");

            ConversationQueue.tryAcquire(convId, new QueuedMessage("C", "web", "admin", agent));
            assertEquals(1, ConversationQueue.clearWaiting(convId, null, null));
            assertEquals(new ConversationQueue.Snapshot(true, 1, true, false, List.of(), 2),
                    ConversationQueue.snapshot(convId, "web"));
        } finally {
            ConversationQueue.forgetForTest(convId);
        }
    }

    // ── conversation.busy: each idle↔busy transition is announced once ──

    @Test
    void aTurnTakingAnIdleConversationAnnouncesItBusy() {
        long convId = 16001L;
        var gen = new long[1];
        var heard = busyAnnouncedWhile(convId, () -> gen[0] = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "web", "admin", agent), null));

        assertEquals(List.of(busy(convId, true)), heard);
        ConversationQueue.releaseOwnership(convId, gen[0]);
    }

    @Test
    void aMessageQueuedBehindARunningTurnAnnouncesTheConversationBusy() {
        // Every tab open on the conversation re-reads its queue on this and shows the message waiting (JF-202).
        long convId = 16002L;
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);

        var heard = busyAnnouncedWhile(convId, () -> assertFalse(ConversationQueue.tryAcquire(convId,
                new QueuedMessage("B", "web", "admin", agent))));

        assertEquals(List.of(busy(convId, true)), heard);
        ConversationQueue.drain(convId, gen);
        ConversationQueue.drain(convId, gen);
    }

    @Test
    void aDrainThatHandsTheConversationToAQueuedMessageAnnouncesNothing() {
        long convId = 16003L;
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
        ConversationQueue.tryAcquire(convId, new QueuedMessage("B", "web", "admin", agent));

        var heard = busyAnnouncedWhile(convId,
                () -> assertEquals("B", ConversationQueue.drain(convId, gen).getFirst().text()));

        assertEquals(List.of(), heard);
        assertTrue(ConversationQueue.isBusy(convId));
        ConversationQueue.drain(convId, gen);
    }

    @Test
    void aDrainThatFindsNothingQueuedAnnouncesTheConversationIdle() {
        long convId = 16004L;
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);

        var heard = busyAnnouncedWhile(convId, () -> assertEquals(List.of(), ConversationQueue.drain(convId, gen)));

        assertEquals(List.of(busy(convId, false)), heard);
    }

    @Test
    void aReleaseAnnouncesIdleOnceAndAReleaseOfAnIdleConversationNothing() {
        long convId = 16005L;
        var gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);

        var heard = busyAnnouncedWhile(convId, () -> {
            ConversationQueue.releaseOwnership(convId, gen);
            ConversationQueue.releaseOwnership(convId, gen);
            ConversationQueue.drain(convId, gen);
        });

        assertEquals(List.of(busy(convId, false)), heard);
    }

    @Test
    void aStaleGenerationsDrainAndReleaseAnnounceNothing() {
        long convId = 16006L;
        var stale = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent),
                new AtomicBoolean());
        ConversationQueue.stop(convId);
        var fresh = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("B", "web", "admin", agent), null);

        var heard = busyAnnouncedWhile(convId, () -> {
            ConversationQueue.drain(convId, stale);
            ConversationQueue.releaseOwnership(convId, stale);
        });

        assertEquals(List.of(), heard);
        assertTrue(ConversationQueue.isBusy(convId), "the new owner keeps the conversation");
        ConversationQueue.releaseOwnership(convId, fresh);
    }

    @Test
    void aStopAnnouncesTheConversationIdleAndARefusedStopNothing() {
        long convId = 16007L;
        var appTurn = ConversationQueue.tryAcquireOwnership(convId,
                new QueuedMessage("A", "app", "app-user", agent), null);
        var refused = busyAnnouncedWhile(convId, () -> assertEquals(ConversationQueue.OperatorStop.UNSTOPPABLE,
                ConversationQueue.stopOperatorTurn(convId, "app")));
        ConversationQueue.releaseOwnership(convId, appTurn);
        ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("B", "web", "admin", agent),
                new AtomicBoolean());

        var stopped = busyAnnouncedWhile(convId, () -> assertTrue(ConversationQueue.stop(convId)));

        assertEquals(List.of(), refused);
        assertEquals(List.of(busy(convId, false)), stopped);
    }

    @Test
    void aRestoredMessageThatTakesTheConversationAnnouncesItBusyAndOneThatQueuesNothing() {
        long idle = 16008L;
        long held = 16009L;
        var heldGen = ConversationQueue.tryAcquireOwnership(held, new QueuedMessage("A", "web", "admin", agent), null);
        var restored = List.of(new QueuedMessage("R", "telegram", "peer", agent));
        var gen = new long[1];

        var heardIdle = busyAnnouncedWhile(idle, () -> gen[0] = ConversationQueue.tryAcquireRestored(idle, restored));
        var heardHeld = busyAnnouncedWhile(held, () -> assertEquals(ConversationQueue.NOT_ACQUIRED,
                ConversationQueue.tryAcquireRestored(held, restored)));

        assertEquals(List.of(busy(idle, true)), heardIdle);
        assertEquals(List.of(), heardHeld);
        ConversationQueue.releaseOwnership(idle, gen[0]);
        ConversationQueue.drain(held, heldGen);
        ConversationQueue.drain(held, heldGen);
    }

    @Test
    void theBusyChangeGoesOutOverTheEventStreamInTheShapeThePageReads() {
        long convId = 16010L;
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var unsubscribe = services.NotificationBus.subscribe(line -> {
            if (line.contains("\"conversationId\":" + convId + ",") || line.contains("\"conversationId\":" + convId + "}")) {
                lines.add(line);
            }
        });
        long gen;
        try {
            gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            // A client's copy goes out on the bus's dispatcher, after the acquire returns.
            services.NotificationBus.awaitDetachedForTest();
        } finally {
            unsubscribe.run();
        }

        assertEquals(List.of(com.google.gson.JsonParser.parseString(
                        "{\"type\":\"conversation.busy\",\"data\":{\"conversationId\":" + convId + ",\"busy\":true}}")),
                lines.stream().map(line -> {
                    assertTrue(line.startsWith("data: ") && line.endsWith("\n\n"), line);
                    return com.google.gson.JsonParser.parseString(line.substring("data: ".length()).strip());
                }).toList());
        ConversationQueue.releaseOwnership(convId, gen);
    }

    @Test
    void neitherTheAcquireNorTheReleaseWaitsForAnEventClientWhoseWritesBlock() throws Exception {
        long convId = 16011L;
        var unblock = new CountDownLatch(1);
        var lines = new java.util.concurrent.CopyOnWriteArrayList<String>();
        var unsubscribe = services.NotificationBus.subscribe(line -> {
            if (!line.contains("\"conversationId\":" + convId + ",") && !line.contains("\"conversationId\":" + convId + "}")) {
                return;
            }
            try {
                unblock.await();
                lines.add(line);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
        });
        long callerMs;
        try {
            long start = System.nanoTime();
            long gen = ConversationQueue.tryAcquireOwnership(convId, new QueuedMessage("A", "web", "admin", agent), null);
            ConversationQueue.releaseOwnership(convId, gen);
            callerMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            unblock.countDown();
            services.NotificationBus.awaitDetachedForTest();
        } finally {
            unblock.countDown();
            unsubscribe.run();
        }

        assertTrue(callerMs < services.NotificationBus.LISTENER_TIMEOUT_MS / 2,
                "the acquire and release waited " + callerMs + " ms on a blocked client");
        assertEquals(List.of(
                        com.google.gson.JsonParser.parseString(
                                "{\"type\":\"conversation.busy\",\"data\":{\"conversationId\":" + convId + ",\"busy\":true}}"),
                        com.google.gson.JsonParser.parseString(
                                "{\"type\":\"conversation.busy\",\"data\":{\"conversationId\":" + convId + ",\"busy\":false}}")),
                lines.stream().map(line -> com.google.gson.JsonParser.parseString(
                        line.substring("data: ".length()).strip())).toList());
    }

    private static List<String> texts(List<QueuedMessage> messages) {
        return messages.stream().map(QueuedMessage::text).toList();
    }

    /** The {@code conversation.busy} payloads published for {@code convId} while {@code body} runs. */
    private static List<java.util.Map<String, Object>> busyAnnouncedWhile(long convId, Runnable body) {
        var heard = new java.util.concurrent.CopyOnWriteArrayList<java.util.Map<String, Object>>();
        var unsubscribe = services.NotificationBus.subscribeEvents((type, data) -> {
            if (services.NotificationBus.BUS_CONVERSATION_BUSY.equals(type)
                    && Long.valueOf(convId).equals(data.get("conversationId"))) heard.add(data);
        });
        try {
            body.run();
        } finally {
            unsubscribe.run();
        }
        return List.copyOf(heard);
    }

    private static java.util.Map<String, Object> busy(long convId, boolean busy) {
        return java.util.Map.of("conversationId", convId, "busy", busy);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
