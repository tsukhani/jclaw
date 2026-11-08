package services;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;
import utils.RetryScheduler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import static utils.GsonHolder.GSON;

/**
 * The one live connection to the operator's Chrome extension, and request/response over it.
 *
 * <p>A request waits on an untimed {@code get()}; the deadline is a task on {@link RetryScheduler}
 * that fails the same future. A timed park on many virtual threads is JDK-8373224.
 */
public final class ChromeBridge {

    /** The wire to one extension. The socket controller adapts a WebSocket to this. */
    public interface Transport {
        void send(String frame);

        void close();
    }

    /** A request the extension could not or did not answer; the message is safe to show the model. */
    public static final class BridgeException extends RuntimeException {
        public BridgeException(String message) {
            super(message);
        }
    }

    public static final long DEFAULT_TIMEOUT_MS = 20_000;

    private static final String CATEGORY = "chrome-bridge";

    private static volatile @Nullable Transport current;
    private static final ConcurrentHashMap<Long, CompletableFuture<JsonElement>> PENDING = new ConcurrentHashMap<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private ChromeBridge() {}

    public static boolean isConnected() {
        return current != null;
    }

    /** Makes {@code transport} the live connection; a second extension replaces the first. */
    public static void attach(Transport transport) {
        var old = current;
        current = transport;
        if (old != null && old != transport) {
            failAll("The Chrome extension reconnected from another window.");
            old.close();
        }
        EventLogger.info(CATEGORY, "Chrome extension connected");
    }

    public static void detach(Transport transport) {
        if (current != transport) return;
        current = null;
        failAll("The Chrome extension disconnected.");
        EventLogger.info(CATEGORY, "Chrome extension disconnected");
    }

    /** Delivers an extension reply; ignored unless it came from the live connection. */
    public static void onResult(Transport from, JsonObject msg) {
        if (current != from || !msg.has("id") || !msg.get("id").isJsonPrimitive()) return;
        long id;
        try {
            id = msg.get("id").getAsLong();
        } catch (NumberFormatException _) {
            return;
        }
        var waiting = PENDING.get(id);
        if (waiting == null) return;
        var ok = msg.has("ok") && msg.get("ok").isJsonPrimitive() && msg.get("ok").getAsBoolean();
        if (ok) {
            waiting.complete(msg.has("result") ? msg.get("result") : JsonNull.INSTANCE);
        } else {
            var error = msg.get("error");
            waiting.completeExceptionally(new BridgeException(
                    error != null && error.isJsonPrimitive() ? error.getAsString() : "The Chrome extension reported an error."));
        }
    }

    public static JsonElement call(String action, JsonObject args) {
        return call(action, args, DEFAULT_TIMEOUT_MS);
    }

    public static JsonElement call(String action, JsonObject args, long timeoutMs) {
        var transport = current;
        if (transport == null) throw new BridgeException("The Chrome extension is not connected.");
        long id = SEQ.incrementAndGet();
        var reply = new CompletableFuture<JsonElement>();
        PENDING.put(id, reply);
        var frame = args.deepCopy();
        frame.addProperty("id", id);
        frame.addProperty("action", action);
        RetryScheduler.schedule(() -> {
            reply.completeExceptionally(new BridgeException("The Chrome extension did not answer in time."));
            return null;
        }, timeoutMs);
        try {
            transport.send(GSON.toJson(frame));
            return reply.get();
        } catch (ExecutionException e) {
            throw e.getCause() instanceof BridgeException b ? b : new BridgeException("The Chrome request failed.");
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            throw new BridgeException("The Chrome request was interrupted.");
        } finally {
            PENDING.remove(id);
        }
    }

    private static void failAll(String reason) {
        PENDING.values().forEach(f -> f.completeExceptionally(new BridgeException(reason)));
    }
}
