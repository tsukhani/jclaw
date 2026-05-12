package services;

import com.google.gson.Gson;
import static utils.GsonHolder.INSTANCE;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Simple in-memory pub/sub notification bus for pushing real-time events
 * to SSE-connected frontends. Any backend code can publish events;
 * the SSE endpoint subscribes listeners.
 *
 * Events are typed JSON objects: { "type": "skill.promoted", "data": { ... } }
 *
 * Each listener invocation is dispatched on a small shared platform-thread
 * executor with a per-listener timeout. Listeners that throw or exceed the
 * timeout are removed so that a slow/stalled SSE writer can never block the
 * fan-out for other subscribers.
 */
public class NotificationBus {

    private static final Gson gson = INSTANCE;
    private static final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Per-listener dispatch timeout. SSE writes hanging longer indicate a dead client. */
    public static final long LISTENER_TIMEOUT_MS = 3_000L;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2, r -> {
        var t = new Thread(r, "notification-bus-" + THREAD_COUNTER.incrementAndGet());
        t.setDaemon(true);
        return t;
    });

    /** Subscribe to receive SSE-formatted event strings. Returns an unsubscribe handle. */
    public static Runnable subscribe(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Publish an event to all connected listeners. */
    public static void publish(String type, Map<String, Object> data) {
        var event = gson.toJson(Map.of("type", type, "data", data));
        var ssePayload = "data: %s\n\n".formatted(event);
        if (listeners.isEmpty()) return;

        var pairs = new java.util.ArrayList<java.util.Map.Entry<Consumer<String>, CompletableFuture<Void>>>();
        for (var listener : listeners) {
            var future = CompletableFuture.runAsync(() -> {
                try {
                    listener.accept(ssePayload);
                } finally {
                    // Clear any interrupt status before the worker returns to the pool —
                    // future.cancel(true) interrupts the thread to abort a stalled listener,
                    // and we don't want that flag to leak into the next task.
                    Thread.interrupted();
                }
            }, EXECUTOR);
            pairs.add(java.util.Map.entry(listener, future));
        }

        var failed = new java.util.ArrayList<Consumer<String>>();
        for (var pair : pairs) {
            var future = pair.getValue();
            try {
                future.get(LISTENER_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException _) {
                future.cancel(true);
                failed.add(pair.getKey());
            } catch (ExecutionException | InterruptedException _) {
                failed.add(pair.getKey());
                if (Thread.currentThread().isInterrupted()) {
                    // Preserve interrupt status; stop processing further futures.
                    break;
                }
            }
        }
        if (!failed.isEmpty()) listeners.removeAll(failed);
    }

    /** Convenience: publish a simple event with a message. */
    public static void publish(String type, String key, Object value) {
        publish(type, Map.of(key, value));
    }

    /** Number of active listeners (for diagnostics). */
    public static int listenerCount() {
        return listeners.size();
    }
}
