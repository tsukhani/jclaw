import org.junit.jupiter.api.*;
import play.test.*;
import services.NotificationBus;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

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
        // Fast listener: records the event. Tracked via @AfterEach cleanup.
        subscribe(received::add);

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
}
