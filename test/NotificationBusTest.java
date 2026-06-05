import org.junit.jupiter.api.*;
import play.test.*;
import services.NotificationBus;

import java.util.ArrayList;
import java.util.Map;

/**
 * Tests for the in-memory pub/sub NotificationBus.
 * Pure in-memory — no DB needed.
 */
class NotificationBusTest extends UnitTest {

    private final java.util.List<Runnable> unsubscribers = new ArrayList<>();

    @AfterEach
    void cleanup() {
        // Unsubscribe all listeners added during the test
        for (var unsub : unsubscribers) {
            unsub.run();
        }
        unsubscribers.clear();
    }

    private Runnable subscribe(java.util.function.Consumer<String> listener) {
        var unsub = NotificationBus.subscribe(listener);
        unsubscribers.add(unsub);
        return unsub;
    }

    // --- subscribe / unsubscribe lifecycle ---

    @Test
    void subscribeIncreasesListenerCount() {
        int before = NotificationBus.listenerCount();
        var unsub = subscribe(msg -> {});
        assertEquals(before + 1, NotificationBus.listenerCount());
        unsub.run();
        assertEquals(before, NotificationBus.listenerCount());
    }

    @Test
    void unsubscribeRemovesListener() {
        int before = NotificationBus.listenerCount();
        var unsub = subscribe(msg -> {});
        unsub.run();
        // Remove from our cleanup list too since already unsubscribed
        unsubscribers.remove(unsub);
        assertEquals(before, NotificationBus.listenerCount());
    }

    // --- publish delivers to subscribers ---

    @Test
    void publishDeliversToSubscriber() {
        var received = new ArrayList<String>();
        subscribe(received::add);

        NotificationBus.publish("test.event", Map.of("key", "value"));

        assertEquals(1, received.size());
        assertTrue(received.getFirst().contains("test.event"));
        assertTrue(received.getFirst().contains("value"));
    }

    @Test
    void publishDeliversToMultipleSubscribers() {
        var received1 = new ArrayList<String>();
        var received2 = new ArrayList<String>();
        subscribe(received1::add);
        subscribe(received2::add);

        NotificationBus.publish("multi.event", "msg", "hello");

        assertEquals(1, received1.size());
        assertEquals(1, received2.size());
    }

    @Test
    void publishFormatsAsSsePayload() {
        var received = new ArrayList<String>();
        subscribe(received::add);

        NotificationBus.publish("sse.test", Map.of("data", "payload"));

        var payload = received.getFirst();
        assertTrue(payload.startsWith("data: "), "SSE payload should start with 'data: '");
        assertTrue(payload.endsWith("\n\n"), "SSE payload should end with double newline");
    }

    // --- failed listener is auto-removed ---

    @Test
    void failedListenerIsAutoRemoved() {
        int before = NotificationBus.listenerCount();
        // Subscribe a listener that always throws
        NotificationBus.subscribe(msg -> { throw new RuntimeException("boom"); });

        // Publish to trigger the failure
        NotificationBus.publish("fail.event", Map.of("x", "y"));

        // The failed listener should have been removed
        assertEquals(before, NotificationBus.listenerCount());
    }

    // --- listenerCount ---

    @Test
    void listenerCountReturnsCorrectCount() {
        int before = NotificationBus.listenerCount();
        var unsub1 = subscribe(msg -> {});
        var unsub2 = subscribe(msg -> {});

        assertEquals(before + 2, NotificationBus.listenerCount());

        unsub1.run();
        assertEquals(before + 1, NotificationBus.listenerCount());

        unsub2.run();
        assertEquals(before, NotificationBus.listenerCount());
        // Remove from cleanup since already unsubscribed
        unsubscribers.remove(unsub1);
        unsubscribers.remove(unsub2);
    }

    @Test
    void publishWithNoSubscribersDoesNotThrow() {
        // Should complete without error even if no subscribers exist
        Assertions.assertDoesNotThrow(() -> NotificationBus.publish("orphan.event", Map.of("lonely", "true")));
    }

    // --- per-listener timeout: slow listener is auto-removed, fast listener still receives ---

    @Test
    void slowListenerIsRemovedAndFastListenerStillReceives() throws Exception {
        int before = NotificationBus.listenerCount();

        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        // Fast listener: records ONLY this test's own event. NotificationBus is a
        // process-global static and the parallel unit-test lane can publish other
        // events into this still-subscribed listener during the ~LISTENER_TIMEOUT_MS
        // window the slow listener holds publish() open — filtering by the unique
        // published type isolates the assertion from that cross-talk (a `received::add`
        // recorded the stray event, failing line 163 with "expected 1 but was 2" on the
        // busier CI box). Tracked via @AfterEach cleanup.
        subscribe(msg -> { if (msg.contains("timeout.test")) received.add(msg); });

        // Slow listener: sleeps well past the per-listener timeout. Tracked too — even though
        // publish() will remove it, the unsubscribe handle is a safe no-op if already gone.
        var slowEntered = new java.util.concurrent.CountDownLatch(1);
        subscribe(msg -> {
            slowEntered.countDown();
            try {
                Thread.sleep(NotificationBus.LISTENER_TIMEOUT_MS + 2_000L);
            } catch (InterruptedException _) {
                // Future.cancel(true) interrupts us — expected.
                Thread.currentThread().interrupt();
            }
        });

        assertEquals(before + 2, NotificationBus.listenerCount(),
                "Both listeners should be subscribed before publish");

        var publishStart = System.currentTimeMillis();
        NotificationBus.publish("timeout.test", Map.of("k", "v"));
        var publishElapsed = System.currentTimeMillis() - publishStart;

        // Fast listener received the event.
        assertEquals(1, received.size(), "Fast listener should have received the event");
        assertTrue(received.getFirst().contains("timeout.test"));

        // Slow listener was entered (i.e., dispatch happened) and removed after timeout.
        assertTrue(slowEntered.await(1, java.util.concurrent.TimeUnit.SECONDS),
                "Slow listener should have been invoked");

        // Slow listener should now be gone; fast listener remains.
        assertEquals(before + 1, NotificationBus.listenerCount(),
                "Slow listener should have been removed after exceeding the timeout");

        // Total publish time was bounded by the timeout, not by the slow listener's sleep.
        assertTrue(publishElapsed < NotificationBus.LISTENER_TIMEOUT_MS + 1_000L,
                "publish() should not block past the per-listener timeout (elapsed=" + publishElapsed + "ms)");
    }

    // --- concurrent fan-out: many slow listeners do NOT serialize the publisher ---

    @Test
    void manySlowListenersDoNotSerializeThePublisher() throws Exception {
        int before = NotificationBus.listenerCount();

        // One healthy listener that records ONLY this test's own event — must still be
        // delivered. Filtering by the unique published type isolates the assertion from
        // cross-talk: NotificationBus is a process-global static and the parallel unit-
        // test lane can publish other events into this still-subscribed listener during
        // the ~LISTENER_TIMEOUT_MS window the slow listeners hold publish() open (same
        // flake as slowListenerIsRemovedAndFastListenerStillReceives — "expected 1 but
        // was 2" on the busier CI box).
        var received = new java.util.concurrent.CopyOnWriteArrayList<String>();
        subscribe(msg -> { if (msg.contains("concurrent.test")) received.add(msg); });

        // Several listeners that each stall well past the per-listener timeout. On a
        // fixed 2-thread pool with a fresh per-future budget the publisher would block
        // for roughly ceil(N/2) x LISTENER_TIMEOUT_MS; with concurrent dispatch and a
        // single shared deadline it must stay bounded to ~one cap no matter how many.
        int slowCount = 6;
        var allEntered = new java.util.concurrent.CountDownLatch(slowCount);
        for (int i = 0; i < slowCount; i++) {
            subscribe(msg -> {
                allEntered.countDown();
                try {
                    Thread.sleep(NotificationBus.LISTENER_TIMEOUT_MS + 5_000L);
                } catch (InterruptedException _) {
                    // Future.cancel(true) interrupts us — expected.
                    Thread.currentThread().interrupt();
                }
            });
        }

        assertEquals(before + 1 + slowCount, NotificationBus.listenerCount(),
                "All listeners should be subscribed before publish");

        var publishStart = System.currentTimeMillis();
        NotificationBus.publish("concurrent.test", Map.of("k", "v"));
        var publishElapsed = System.currentTimeMillis() - publishStart;

        // Healthy listener was delivered to despite the crowd of slow ones.
        assertEquals(1, received.size(), "Healthy listener should have received the event");
        assertTrue(received.getFirst().contains("concurrent.test"));

        // Every slow listener was actually dispatched concurrently (not queued behind a
        // small worker count): all entered their bodies.
        assertTrue(allEntered.await(NotificationBus.LISTENER_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
                "All slow listeners should have been dispatched concurrently");

        // Crucially: total publish time stayed near ONE per-listener cap, not the sum.
        // ceil(6/2) x 3s = 9s would be the old worst case; we assert well under 2 caps.
        assertTrue(publishElapsed < (2L * NotificationBus.LISTENER_TIMEOUT_MS),
                "publish() must not serialize behind slow listeners (elapsed=" + publishElapsed
                        + "ms, " + slowCount + " slow listeners)");

        // All slow listeners timed out and were removed; the healthy one remains.
        assertEquals(before + 1, NotificationBus.listenerCount(),
                "All slow listeners should have been removed after exceeding the timeout");
    }
}
