import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import services.ChromeBridge;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * Serializes tests that touch {@link ChromeBridge}'s one live connection, which is a process-global:
 * two classes attaching a fake extension at once would each answer the other's requests.
 */
public final class ChromeBridgeSync {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private ChromeBridgeSync() {}

    /** A scripted extension: {@code responder} gets each request and returns the reply, or null to stay silent. */
    public static final class Fake implements ChromeBridge.Transport {
        public final List<JsonObject> sent = new CopyOnWriteArrayList<>();
        public volatile boolean closed;
        private final Function<JsonObject, JsonObject> responder;

        public Fake(Function<JsonObject, JsonObject> responder) {
            this.responder = responder;
        }

        public static Fake silent() {
            return new Fake(_ -> null);
        }

        @Override
        public void send(String frame) {
            var msg = JsonParser.parseString(frame).getAsJsonObject();
            sent.add(msg);
            var reply = responder.apply(msg);
            if (reply != null) {
                reply.addProperty("id", msg.get("id").getAsLong());
                ChromeBridge.onResult(this, reply);
            }
        }

        @Override
        public void close() {
            closed = true;
        }

        public List<String> actions() {
            return sent.stream().map(m -> m.get("action").getAsString()).toList();
        }
    }

    public static JsonObject ok(JsonElement result) {
        var o = new JsonObject();
        o.addProperty("ok", true);
        o.add("result", result);
        return o;
    }

    public static JsonObject error(String message) {
        var o = new JsonObject();
        o.addProperty("ok", false);
        o.addProperty("error", message);
        return o;
    }

    /** Runs {@code body} with {@code fake} as the live connection, then detaches it. */
    public static void with(Fake fake, ThrowingBody body) throws Exception {
        LOCK.lock();
        try {
            ChromeBridge.attach(fake);
            body.run();
        } finally {
            ChromeBridge.detach(fake);
            LOCK.unlock();
        }
    }

    /** Runs {@code body} holding the lock with nothing attached. */
    public static void whileDisconnected(ThrowingBody body) throws Exception {
        LOCK.lock();
        try {
            body.run();
        } finally {
            LOCK.unlock();
        }
    }

    @FunctionalInterface
    public interface ThrowingBody {
        void run() throws Exception;
    }
}
