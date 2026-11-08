import com.google.gson.JsonObject;
import models.ChromeBridgeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.FunctionalTest;
import services.ChromeBridge;
import services.ChromeBridgePairing;
import services.Tx;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static com.google.gson.JsonParser.parseString;

/**
 * The extension socket over a real connection to the autotest server: the origin check, the hello
 * handshake, the hello deadline, and a request answered end to end. The pairing token is minted in a
 * committed transaction because the server thread must see it.
 */
class ChromeBridgeSocketTest extends FunctionalTest {

    private static final String CLOSED = "<closed>";

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    /** Collects every frame the server sends, and a marker when it closes the socket. */
    private static final class Frames implements WebSocket.Listener {
        final BlockingQueue<String> queue = new LinkedBlockingQueue<>();
        volatile java.util.function.Function<JsonObject, String> responder = _ -> null;

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            var text = data.toString();
            queue.add(text);
            var msg = parseString(text).getAsJsonObject();
            if (msg.has("action")) {
                var reply = responder.apply(msg);
                if (reply != null) ws.sendText(reply.replace("\"id\":0", "\"id\":" + msg.get("id").getAsLong()), true);
            }
            ws.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
            queue.add(CLOSED);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            queue.add(CLOSED);
        }

        String next(long seconds) throws InterruptedException {
            return queue.poll(seconds, TimeUnit.SECONDS);
        }
    }

    private static WebSocket open(String origin, Frames frames) {
        var port = Play.configuration.getProperty("http.port", System.getenv().getOrDefault("PLAY_TEST_PORT", "9000"));
        var builder = HttpClient.newHttpClient().newWebSocketBuilder();
        if (origin != null) builder.header("Origin", origin);
        return builder.buildAsync(URI.create("ws://127.0.0.1:" + port + "/api/chrome-bridge/socket"), frames).join();
    }

    @BeforeEach
    @AfterEach
    void clear() {
        commitInFreshTx(() -> ChromeBridgeToken.deleteAll());
    }

    @Test
    void aValidHelloGetsReadyAndThenAnswersARequestEndToEnd() throws Exception {
        var token = commitInFreshTx(ChromeBridgePairing::mint);
        ChromeBridgeSync.whileDisconnected(() -> {
            var frames = new Frames();
            frames.responder = msg -> "{\"id\":0,\"ok\":true,\"result\":{\"echo\":\"" + msg.get("action").getAsString() + "\"}}";
            var ws = open("chrome-extension://abcdefghijklmnop", frames);
            try {
                ws.request(1);
                ws.sendText("{\"type\":\"hello\",\"token\":\"" + token + "\"}", true);
                assertEquals("{\"type\":\"ready\"}", frames.next(10));
                assertTrue(ChromeBridge.isConnected());

                assertEquals("{\"echo\":\"tabs_list\"}", ChromeBridge.call("tabs_list", new JsonObject()).toString());
            } finally {
                ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            }
        });
    }

    @Test
    void aWrongTokenIsRefusedAndTheSocketClosed() throws Exception {
        commitInFreshTx(ChromeBridgePairing::mint);
        ChromeBridgeSync.whileDisconnected(() -> {
            var frames = new Frames();
            var ws = open("chrome-extension://abcdefghijklmnop", frames);
            ws.request(1);
            ws.sendText("{\"type\":\"hello\",\"token\":\"jcl_wrong\"}", true);

            assertEquals("{\"type\":\"error\",\"message\":\"pairing token refused\"}", frames.next(10));
            assertEquals(CLOSED, frames.next(10));
            assertFalse(ChromeBridge.isConnected());
        });
    }

    @Test
    void aWebPageOriginIsClosedWithoutAnyAnswer() throws Exception {
        var token = commitInFreshTx(ChromeBridgePairing::mint);
        ChromeBridgeSync.whileDisconnected(() -> {
            var frames = new Frames();
            var ws = open("https://evil.example", frames);
            ws.request(1);
            ws.sendText("{\"type\":\"hello\",\"token\":\"" + token + "\"}", true);

            assertEquals(CLOSED, frames.next(10));
            assertFalse(ChromeBridge.isConnected());
        });
    }

    @Test
    void aSocketThatNeverSaysHelloIsClosedAtTheDeadline() throws Exception {
        ChromeBridgeSync.whileDisconnected(() -> {
            var frames = new Frames();
            var ws = open("chrome-extension://abcdefghijklmnop", frames);
            ws.request(1);

            assertEquals(CLOSED, frames.next(15));
        });
    }
}
