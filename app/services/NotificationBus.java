package services;

import com.google.gson.Gson;

import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static utils.GsonHolder.GSON;

/**
 * Simple in-memory pub/sub notification bus for pushing real-time events
 * to SSE-connected frontends. Any backend code can publish events;
 * the SSE endpoint subscribes listeners.
 *
 * Events are typed JSON objects: { "type": "skill.promoted", "data": { ... } }
 *
 * Each listener invocation is dispatched on a per-task virtual-thread
 * executor with a per-listener timeout. Because every listener runs on its
 * own thread, all dispatches proceed concurrently and the publisher's total
 * wait is bounded to a single shared deadline ({@code LISTENER_TIMEOUT_MS}
 * from the first {@code get}) regardless of listener count — so a
 * slow/stalled SSE writer can never block the fan-out for other subscribers.
 * Listeners that throw or exceed the timeout are removed.
 */
public class NotificationBus {

    private NotificationBus() {}

    /** JCLAW-662: bus event type for one persisted step of a coding-harness run
     *  (Rail B). Payload carries {@code runId}, {@code seq}, {@code kind}, {@code text}. */
    public static final String BUS_CODINGRUN_STEP = "codingrun.step";
    /** JCLAW-662: bus event type signalling a coding-harness run's output stream
     *  reached a terminal state, so a live monitor can stop tailing. */
    public static final String BUS_CODINGRUN_DONE = "codingrun.done";

    private static final Gson gson = GSON;
    private static final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Per-listener dispatch timeout. SSE writes hanging longer indicate a dead client. */
    public static final long LISTENER_TIMEOUT_MS = 3_000L;

    // Per-task virtual threads so every listener dispatch runs concurrently — the
    // per-listener timeout then actually caps the publisher, instead of futures
    // queueing behind a fixed worker count. SSE writes are blocking socket I/O
    // (not Thread.sleep), so virtual threads unmount cleanly while a writer stalls.
    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("notification-bus-", 0).factory());

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

        var pairs = new ArrayList<Map.Entry<Consumer<String>, CompletableFuture<Void>>>();
        for (var listener : listeners) {
            var future = CompletableFuture.runAsync(() -> {
                try {
                    listener.accept(ssePayload);
                } finally {
                    // Clear any interrupt status before the worker thread terminates —
                    // future.cancel(true) interrupts the thread to abort a stalled
                    // listener; clearing it keeps the abort from surfacing as a stray
                    // InterruptedException in any shutdown/finalization on this thread.
                    Thread.interrupted();
                }
            }, EXECUTOR);
            pairs.add(Map.entry(listener, future));
        }

        // One shared deadline for the whole fan-out: dispatches run concurrently on
        // their own virtual threads, so the publisher's total wait is bounded to
        // LISTENER_TIMEOUT_MS regardless of how many listeners are slow — get() each
        // future against the time REMAINING to that deadline, not a fresh budget each.
        var deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(LISTENER_TIMEOUT_MS);
        var failed = new ArrayList<Consumer<String>>();
        for (var pair : pairs) {
            var future = pair.getValue();
            try {
                var remainingNanos = deadlineNanos - System.nanoTime();
                // A non-positive remaining budget still means "wait until the shared
                // deadline" of now — get(0) returns immediately if already done,
                // otherwise times out, so a listener that finished in time is never
                // dropped while late ones are cancelled.
                future.get(Math.max(0L, remainingNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException _) {
                future.cancel(true);
                failed.add(pair.getKey());
            } catch (ExecutionException _) {
                failed.add(pair.getKey());
            } catch (InterruptedException _) {
                // Re-set the flag the catch cleared, then stop processing further
                // futures so the interrupt actually propagates up the call stack.
                Thread.currentThread().interrupt();
                failed.add(pair.getKey());
                break;
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
