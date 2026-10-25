import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
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

    // --- typed event listeners ---

    @Test
    void anEventListenerHearsEachEventsTypeAndDataAndOneThatThrowsStaysSubscribed() {
        var type = "test.typed-" + System.nanoTime();
        var thrown = new java.util.concurrent.atomic.AtomicInteger();
        var seen = new java.util.concurrent.CopyOnWriteArrayList<String>();
        unsubscribers.add(NotificationBus.subscribeEvents((t, d) -> {
            if (!t.equals(type)) return;
            thrown.incrementAndGet();
            throw new IllegalStateException("listener failure");
        }));
        unsubscribers.add(NotificationBus.subscribeEvents((t, d) -> {
            if (t.equals(type)) seen.add(d.get("runId").toString());
        }));

        NotificationBus.publish(type, Map.of("runId", 7));
        NotificationBus.publish(type, Map.of("runId", 8));

        Assertions.assertEquals(java.util.List.of("7", "8"), seen);
        Assertions.assertEquals(2, thrown.get(), "a listener that throws is kept");
    }

    @Test
    void anUnsubscribedEventListenerHearsNothingMore() {
        var type = "test.typed-" + System.nanoTime();
        var heard = new java.util.concurrent.atomic.AtomicInteger();
        var unsubscribe = NotificationBus.subscribeEvents((t, d) -> {
            if (t.equals(type)) heard.incrementAndGet();
        });

        NotificationBus.publish(type, Map.of());
        unsubscribe.run();
        NotificationBus.publish(type, Map.of());

        Assertions.assertEquals(1, heard.get());
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

        // Time one slow listener, then six, and compare the two. The contract is "bounded
        // to a single shared deadline no matter how many", which is a ratio: a per-future
        // budget on a fixed 2-thread pool blocks for ceil(N/2) x LISTENER_TIMEOUT_MS, so
        // six listeners cost ~3x one, while a shared deadline costs ~1x.
        //
        // This was an absolute bound — elapsed < 2 x LISTENER_TIMEOUT_MS — and it could not
        // tell a slow machine from a serializing publisher. A full-suite run measured
        // 8005 ms, sitting between the healthy ~3 s and the ~9 s failure it exists to catch,
        // and the number alone said nothing about which had happened. Load inflates both
        // measurements together, so their ratio survives it where a constant does not.
        long oneSlow = publishPastTheTimeout(1, "concurrent.baseline", null);

        int slowCount = 6;
        var allEntered = new java.util.concurrent.CountDownLatch(slowCount);
        long manySlow = publishPastTheTimeout(slowCount, "concurrent.test", allEntered);

        // Healthy listener was delivered to despite the crowd of slow ones.
        assertEquals(1, received.size(), "Healthy listener should have received the event");
        assertTrue(received.getFirst().contains("concurrent.test"));

        // Every slow listener was actually dispatched concurrently (not queued behind a
        // small worker count): all entered their bodies. This alone does not prove the
        // deadline is shared — a per-listener budget would let all six enter and still
        // block for the sum — which is why the comparison below stays.
        assertTrue(allEntered.await(NotificationBus.LISTENER_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS),
                "All slow listeners should have been dispatched concurrently");

        assertTrue(manySlow < oneSlow * 3 / 2,
                "publish() must not serialize behind slow listeners: %d listeners took %d ms against %d ms for one"
                        .formatted(slowCount, manySlow, oneSlow));

        // All slow listeners timed out and were removed; the healthy one remains.
        assertEquals(before + 1, NotificationBus.listenerCount(),
                "All slow listeners should have been removed after exceeding the timeout");
    }

    /**
     * Subscribe {@code count} listeners that each stall well past the timeout, publish
     * {@code event}, and return how long {@code publish()} blocked. They all time out and
     * are removed, so the bus is left as it was found and the call can be repeated.
     *
     * @param entered counted down by each listener on entry, or {@code null} to skip
     */
    private long publishPastTheTimeout(int count, String event,
                                       java.util.concurrent.CountDownLatch entered) {
        for (int i = 0; i < count; i++) {
            subscribe(msg -> {
                if (entered != null) entered.countDown();
                try {
                    Thread.sleep(NotificationBus.LISTENER_TIMEOUT_MS + 5_000L);
                } catch (InterruptedException _) {
                    // Future.cancel(true) interrupts us — expected.
                    Thread.currentThread().interrupt();
                }
            });
        }
        var start = System.currentTimeMillis();
        NotificationBus.publish(event, Map.of("k", "v"));
        return System.currentTimeMillis() - start;
    }
}
