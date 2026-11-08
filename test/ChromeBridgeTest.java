import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ChromeBridge;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.gson.JsonParser.parseString;

class ChromeBridgeTest extends UnitTest {

    private static String message(Runnable call) {
        try {
            call.run();
        } catch (ChromeBridge.BridgeException e) {
            return e.getMessage();
        }
        return "no exception";
    }

    private static JsonObject args(String json) {
        return parseString(json).getAsJsonObject();
    }

    @Test
    void aCallSendsTheActionAndArgumentsWithAnIdAndReturnsTheResult() throws Exception {
        var fake = new ChromeBridgeSync.Fake(_ -> ChromeBridgeSync.ok(parseString("{\"tabId\":7}")));
        ChromeBridgeSync.with(fake, () -> {
            var result = ChromeBridge.call("navigate", args("{\"url\":\"https://example.com\"}"));

            assertEquals("{\"tabId\":7}", result.toString());
            assertEquals(1, fake.sent.size());
            var frame = fake.sent.getFirst();
            assertEquals("navigate", frame.get("action").getAsString());
            assertEquals("https://example.com", frame.get("url").getAsString());
            assertTrue(frame.has("id"));
        });
    }

    @Test
    void everyCallGetsItsOwnId() throws Exception {
        var fake = new ChromeBridgeSync.Fake(_ -> ChromeBridgeSync.ok(parseString("{}")));
        ChromeBridgeSync.with(fake, () -> {
            ChromeBridge.call("tabs_list", new JsonObject());
            ChromeBridge.call("tabs_list", new JsonObject());

            assertNotEquals(fake.sent.get(0).get("id").getAsLong(), fake.sent.get(1).get("id").getAsLong());
        });
    }

    @Test
    void anErrorReplyBecomesAnExceptionCarryingItsMessage() throws Exception {
        var fake = new ChromeBridgeSync.Fake(_ -> ChromeBridgeSync.error("stale or unknown ref e3"));
        ChromeBridgeSync.with(fake, () ->
                assertEquals("stale or unknown ref e3", message(() -> ChromeBridge.call("click", new JsonObject()))));
    }

    @Test
    void withNoExtensionAttachedACallSaysSo() throws Exception {
        ChromeBridgeSync.whileDisconnected(() -> {
            assertFalse(ChromeBridge.isConnected());
            assertEquals("The Chrome extension is not connected.",
                    message(() -> ChromeBridge.call("tabs_list", new JsonObject())));
        });
    }

    @Test
    void aSilentExtensionTimesTheCallOut() throws Exception {
        ChromeBridgeSync.with(ChromeBridgeSync.Fake.silent(), () ->
                assertEquals("The Chrome extension did not answer in time.",
                        message(() -> ChromeBridge.call("tabs_list", new JsonObject(), 100))));
    }

    @Test
    void disconnectingFailsACallThatIsWaiting() throws Exception {
        var fake = ChromeBridgeSync.Fake.silent();
        ChromeBridgeSync.with(fake, () -> {
            var outcome = new AtomicReference<String>();
            var done = new CountDownLatch(1);
            Thread.ofPlatform().start(() -> {
                outcome.set(message(() -> ChromeBridge.call("read", new JsonObject(), 10_000)));
                done.countDown();
            });
            while (fake.sent.isEmpty()) Thread.onSpinWait();

            ChromeBridge.detach(fake);

            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("The Chrome extension disconnected.", outcome.get());
            assertFalse(ChromeBridge.isConnected());
        });
    }

    @Test
    void aSecondExtensionReplacesTheFirstAndClosesIt() throws Exception {
        var first = ChromeBridgeSync.Fake.silent();
        var second = new ChromeBridgeSync.Fake(_ -> ChromeBridgeSync.ok(parseString("{\"from\":\"second\"}")));
        ChromeBridgeSync.with(first, () -> {
            ChromeBridge.attach(second);
            try {
                assertTrue(first.closed);
                assertEquals("{\"from\":\"second\"}", ChromeBridge.call("tabs_list", new JsonObject()).toString());
                assertTrue(first.sent.isEmpty());
            } finally {
                ChromeBridge.detach(second);
            }
        });
    }

    @Test
    void aReplyFromAConnectionThatIsNotTheLiveOneIsIgnored() throws Exception {
        var live = ChromeBridgeSync.Fake.silent();
        var stranger = ChromeBridgeSync.Fake.silent();
        ChromeBridgeSync.with(live, () -> {
            var outcome = new AtomicReference<String>();
            var done = new CountDownLatch(1);
            Thread.ofPlatform().start(() -> {
                outcome.set(message(() -> ChromeBridge.call("read", new JsonObject(), 300)));
                done.countDown();
            });
            while (live.sent.isEmpty()) Thread.onSpinWait();

            ChromeBridge.onResult(stranger, args("{\"id\":" + live.sent.getFirst().get("id") + ",\"ok\":true,\"result\":{}}"));

            assertTrue(done.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("The Chrome extension did not answer in time.", outcome.get());
        });
    }
}
