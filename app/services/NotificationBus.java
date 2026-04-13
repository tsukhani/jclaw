package services;

import com.google.gson.Gson;
import static utils.GsonHolder.INSTANCE;

import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Simple in-memory pub/sub notification bus for pushing real-time events
 * to SSE-connected frontends. Any backend code can publish events;
 * the SSE endpoint subscribes listeners.
 *
 * Events are typed JSON objects: { "type": "skill.promoted", "data": { ... } }
 */
public class NotificationBus {

    private static final Gson gson = INSTANCE;
    private static final CopyOnWriteArrayList<Consumer<String>> listeners = new CopyOnWriteArrayList<>();

    /** Subscribe to receive SSE-formatted event strings. Returns an unsubscribe handle. */
    public static Runnable subscribe(Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Publish an event to all connected listeners. */
    public static void publish(String type, Map<String, Object> data) {
        var event = gson.toJson(Map.of("type", type, "data", data));
        var ssePayload = "data: %s\n\n".formatted(event);
        var failed = new java.util.ArrayList<Consumer<String>>();
        for (var listener : listeners) {
            try {
                listener.accept(ssePayload);
            } catch (Exception _) {
                failed.add(listener);
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
