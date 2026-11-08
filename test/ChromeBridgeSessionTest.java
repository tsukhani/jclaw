import models.ChromeBridgeToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ChromeBridge;
import services.ChromeBridgePairing;
import services.ChromeBridgeSession;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class ChromeBridgeSessionTest extends UnitTest {

    private static final String READY = "{\"type\":\"ready\"}";
    private static final String REFUSED = "{\"type\":\"error\",\"message\":\"pairing token refused\"}";

    /** Records every frame the server writes, so a test reads exactly what the extension would. */
    private static final class Recording implements ChromeBridge.Transport {
        final List<String> frames = new java.util.concurrent.CopyOnWriteArrayList<>();
        volatile boolean closed;

        @Override
        public void send(String frame) {
            frames.add(frame);
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    @BeforeEach
    @AfterEach
    void clear() {
        ChromeBridgeToken.deleteAll();
    }

    private static String hello(String token) {
        return "{\"type\":\"hello\",\"token\":\"" + token + "\"}";
    }

    @Test
    void aHelloWithTheRightTokenMakesTheSocketTheLiveBridge() throws Exception {
        var token = ChromeBridgePairing.mint();
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();
            var session = new ChromeBridgeSession(wire);

            session.onText(hello(token));

            assertTrue(session.authenticated());
            assertEquals(List.of(READY), wire.frames);
            assertFalse(wire.closed);
            assertTrue(ChromeBridge.isConnected());
            session.closed();
            assertFalse(ChromeBridge.isConnected());
        });
    }

    @Test
    void aWrongTokenIsRefusedAndTheSocketClosed() throws Exception {
        ChromeBridgePairing.mint();
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();
            var session = new ChromeBridgeSession(wire);

            session.onText(hello("jcl_not-the-token"));

            assertFalse(session.authenticated());
            assertEquals(List.of(REFUSED), wire.frames);
            assertTrue(wire.closed);
            assertFalse(ChromeBridge.isConnected());
        });
    }

    @Test
    void aRevokedTokenIsRefused() throws Exception {
        var token = ChromeBridgePairing.mint();
        ChromeBridgePairing.revoke();
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();

            new ChromeBridgeSession(wire).onText(hello(token));

            assertEquals(List.of(REFUSED), wire.frames);
            assertTrue(wire.closed);
        });
    }

    @Test
    void aFirstFrameThatIsNotAHelloClosesTheSocketWithoutAnswering() throws Exception {
        var token = ChromeBridgePairing.mint();
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();
            var session = new ChromeBridgeSession(wire);

            session.onText("{\"type\":\"ping\",\"token\":\"" + token + "\"}");

            assertFalse(session.authenticated());
            assertEquals(List.of(REFUSED), wire.frames);
            assertTrue(wire.closed);
        });
    }

    @Test
    void malformedJsonClosesTheSocket() throws Exception {
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();

            new ChromeBridgeSession(wire).onText("not json");

            assertTrue(wire.closed);
            assertTrue(wire.frames.isEmpty());
        });
    }

    @Test
    void aTokenThatIsNotAStringIsRefused() throws Exception {
        ChromeBridgePairing.mint();
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();

            new ChromeBridgeSession(wire).onText("{\"type\":\"hello\",\"token\":12345}");

            assertEquals(List.of(REFUSED), wire.frames);
            assertTrue(wire.closed);
        });
    }

    @Test
    void anAuthenticatedPingGetsAPong() throws Exception {
        var token = ChromeBridgePairing.mint();
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();
            var session = new ChromeBridgeSession(wire);
            session.onText(hello(token));

            session.onText("{\"type\":\"ping\"}");

            assertEquals(List.of(READY, "{\"type\":\"pong\"}"), wire.frames);
            session.closed();
        });
    }

    @Test
    void aResultFrameReachesTheCallWaitingForIt() throws Exception {
        var token = ChromeBridgePairing.mint();
        ChromeBridgeSync.whileDisconnected(() -> {
            var wire = new Recording();
            var session = new ChromeBridgeSession(wire);
            session.onText(hello(token));
            var answer = new AtomicReference<String>();
            var done = new CountDownLatch(1);
            Thread.ofPlatform().start(() -> {
                answer.set(ChromeBridge.call("tabs_list", new com.google.gson.JsonObject(), 10_000).toString());
                done.countDown();
            });
            while (wire.frames.size() < 2) Thread.onSpinWait();
            var id = com.google.gson.JsonParser.parseString(wire.frames.get(1)).getAsJsonObject().get("id").getAsLong();

            session.onText("{\"id\":" + id + ",\"ok\":true,\"result\":[{\"id\":9}]}");

            assertTrue(done.await(5, TimeUnit.SECONDS));
            assertEquals("[{\"id\":9}]", answer.get());
            session.closed();
        });
    }

    @Test
    void expiryClosesASocketThatNeverSaidHelloAndSparesOneThatDid() throws Exception {
        var token = ChromeBridgePairing.mint();
        ChromeBridgeSync.whileDisconnected(() -> {
            var silent = new Recording();
            new ChromeBridgeSession(silent).expireIfUnauthenticated();
            assertTrue(silent.closed);

            var live = new Recording();
            var session = new ChromeBridgeSession(live);
            session.onText(hello(token));
            session.expireIfUnauthenticated();
            assertFalse(live.closed);
            session.closed();
        });
    }
}
