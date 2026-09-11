import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import utils.CircuitBreaker;
import utils.CircuitBreakers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * JCLAW-1183: an abandoned stream gives back its transport thread and its socket, not only its
 * turn. The mechanism is {@code EventSource.cancel()}, which JCLAW-1181 could not reach because
 * the only entrance was interrupting the stream thread — barred, since its retry path writes to
 * H2 and the JDK closes a file database's channel on interrupt.
 *
 * <p>The fixture is a raw loopback socket rather than {@code MockWebServer} or a canned
 * {@code HttpFactories.runWith} transport, because the facts under test are a real connection's:
 * that the client hangs up, and that a thread blocked in a socket read unwinds. An interceptor
 * has no socket for {@code cancel()} to close, and so cannot show either.
 *
 * <p>It serves the pathology exactly — SSE keepalive comments and nothing else. Those are reads,
 * so OkHttp's 180s {@code readTimeout} never fires and the stream would otherwise be held for
 * ever.
 */
class LlmStreamTransportReleaseTest extends UnitTest {

    private static final String CHUNK = """
            data: {"id":"c1","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Hel"}}]}

            """;

    private final KeepaliveOnlyServer server = new KeepaliveOnlyServer();

    @AfterEach
    void tearDown() {
        server.stop();
    }

    // ─── The transport, driven directly ──────────────────────────────────

    @Test
    void cancellingTheCallUnwindsTheParkedThreadAndHangsUpWithoutAnInterrupt() throws Exception {
        var stream = new StreamRun();
        var url = server.start(0);

        stream.startOn(url);
        assertTrue(server.awaitStreaming(10), "the server should be streaming before we cancel");
        stream.abort().run();

        assertTrue(stream.returned.await(10, TimeUnit.SECONDS),
                "cancel must unwind the untimed await — that is the whole point of publishing it");
        assertTrue(server.awaitHangup(10),
                "and the socket must go back, which is what the server sees as an EOF");
        assertFalse(stream.interruptedOnReturn.get(),
                "no interrupt anywhere: this thread's retry path writes to H2");
        assertFalse(stream.completed.get(), "an aborted stream did not complete");
        assertInstanceOf(IOException.class, stream.error.get(),
                "cancel surfaces as onFailure, which is what counts the latch down");
    }

    @Test
    void interruptingTheStreamThreadStillCancelsTheCall() throws Exception {
        var stream = new StreamRun();
        var url = server.start(0);

        // The Stop button and a route navigation still arrive this way; JCLAW-1183 adds a second
        // entrance to the same cancel rather than replacing this one.
        var thread = stream.startOn(url);
        assertTrue(server.awaitStreaming(10), "the server should be streaming before we interrupt");
        thread.interrupt();

        assertTrue(stream.returned.await(10, TimeUnit.SECONDS));
        assertTrue(server.awaitHangup(10), "the interrupt path cancels, and cancelling hangs up");
        assertTrue(stream.interruptedOnReturn.get(),
                "the flag is restored for whoever called in, which only this path does");
        // Which of the two reports lands is a race the interrupt path has always had: the cancel
        // raises its own onFailure on the reader thread. dispatchStream's settled latch is what
        // keeps the loser off the turn, so the transport only has to deliver one of them.
        assertNotNull(stream.error.get());
    }

    // ─── The whole chain: sweep → guard → turn → transport ───────────────

    @Test
    void aStalledStreamReleasesItsCallerAndItsSocket() throws Exception {
        var name = "jclaw1183-stall";
        // beginStream builds the guard's stall budget from whatever breaker the name already
        // carries, so a sub-second one here buys a test that is not thirty seconds long. minVolume
        // 5 keeps the single slow call below the window the breaker would evaluate, so this test
        // says nothing about tripping — LlmStreamBreakerTest owns that.
        CircuitBreakers.get(LlmResilience.breakerName(name),
                CircuitBreaker.Config.of(10, 0.5, 5, 60_000L).withSlowCalls(500L, 0.5));
        // The abort budget is read from the static configuration at dispatch; one second is the
        // smallest it goes, and the first five-second sweep is past it. Play.configuration is
        // process-global and play1 runs test classes concurrently, so the window is one call.
        Play.configuration.setProperty("llm.breaker.stall-abort-seconds", "1");
        try {
            // One chunk arms the inter-chunk budget; everything after it is keepalive comments.
            var provider = new OpenAiProvider(
                    new ProviderConfig(name, server.start(1), "sk-test", List.of()));
            var errors = new CopyOnWriteArrayList<Exception>();
            var completions = new AtomicInteger();
            var chunks = new AtomicInteger();
            var firstOutcome = new CountDownLatch(1);
            var secondOutcome = new CountDownLatch(2);

            provider.chatStream("x", List.of(ChatMessage.user("hi")), null,
                    _ -> chunks.incrementAndGet(),
                    () -> {
                        completions.incrementAndGet();
                        firstOutcome.countDown();
                        secondOutcome.countDown();
                    },
                    e -> {
                        errors.add(e);
                        firstOutcome.countDown();
                        secondOutcome.countDown();
                    },
                    null, null, null);
            Play.configuration.remove("llm.breaker.stall-abort-seconds");

            assertTrue(firstOutcome.await(30, TimeUnit.SECONDS),
                    "the stall sweep runs every five seconds and ends the turn");
            assertTrue(server.awaitHangup(10),
                    "a turn that ended while the socket is still held is the leak this fixes");
            assertEquals(1, chunks.get(), "the chunk that armed the budget did reach the caller");
            assertEquals(0, completions.get());
            assertEquals(1, errors.size());
            assertTrue(errors.getFirst().getMessage().contains("abandoning the stream"),
                    () -> "the caller reads the abandonment, not the cancel: " + errors.getFirst());
            assertFalse(secondOutcome.await(2, TimeUnit.SECONDS),
                    "the cancel's own onFailure must not reach a turn that already ended");

            var stats = LlmResilience.breakerFor(name).stats();
            assertEquals(1, stats.samples(), "abandoning a stream is still exactly one outcome");
            assertEquals(1, stats.slowCalls(), "a stream that delivered content stalled, it did not fail");
            assertEquals(0, stats.failures());
        } finally {
            Play.configuration.remove("llm.breaker.stall-abort-seconds");
            CircuitBreakers.remove(LlmResilience.breakerName(name));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    /** One {@code streamSse} call on its own thread, with everything it reported back. */
    private static final class StreamRun {
        final AtomicReference<Runnable> cancel = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicBoolean interruptedOnReturn = new AtomicBoolean();
        final CountDownLatch returned = new CountDownLatch(1);

        Thread startOn(String baseUrl) {
            return Thread.ofPlatform().daemon().name("test-llm-stream").start(() -> {
                try {
                    streamSse(baseUrl + "/chat/completions", completed, error, cancel);
                } finally {
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                    returned.countDown();
                }
            });
        }

        /** The published abort, which exists from the moment there is an in-flight call. */
        Runnable abort() throws InterruptedException {
            for (var i = 0; i < 100 && cancel.get() == null; i++) Thread.sleep(20);
            var abort = cancel.get();
            assertNotNull(abort, "streamSse must publish its cancel before it parks");
            return abort;
        }
    }

    /**
     * {@code OkHttpLlmHttpDriver} is package-private in {@code llm} and the test package is the
     * default one, so the driver is reached the way {@link OkHttpLlmHttpDriverTest} reaches it.
     */
    private static void streamSse(String url, AtomicBoolean completed,
                                  AtomicReference<Throwable> error,
                                  AtomicReference<Runnable> publishCancel) {
        try {
            var cls = Class.forName("llm.OkHttpLlmHttpDriver");
            var m = cls.getDeclaredMethod("streamSse", URI.class, String.class, String.class,
                    Consumer.class, Runnable.class, Consumer.class, Consumer.class, String.class);
            m.setAccessible(true);
            Consumer<String> onEvent = _ -> { };
            Runnable onComplete = () -> completed.set(true);
            Consumer<Throwable> onError = error::set;
            Consumer<Runnable> onCancel = publishCancel::set;
            m.invoke(null, URI.create(url), "Bearer sk-test", "{}",
                    onEvent, onComplete, onError, onCancel, null);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /**
     * A loopback server that answers one request with an SSE stream it never ends, and reports
     * when the client hangs up. Detection is the client's half of the connection reaching EOF
     * rather than a write failing, so the assertion does not depend on how many keepalives it
     * takes for a broken pipe to surface.
     */
    private static final class KeepaliveOnlyServer {

        private final CountDownLatch streaming = new CountDownLatch(1);
        private final CountDownLatch hungUp = new CountDownLatch(1);
        private volatile ServerSocket listener;

        /** @return the provider base URL, once the listener is bound */
        String start(int dataChunks) throws IOException {
            listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
            Thread.ofPlatform().daemon().name("test-sse-server").start(() -> serve(dataChunks));
            return "http://127.0.0.1:" + listener.getLocalPort() + "/v1";
        }

        boolean awaitStreaming(int seconds) throws InterruptedException {
            return streaming.await(seconds, TimeUnit.SECONDS);
        }

        boolean awaitHangup(int seconds) throws InterruptedException {
            return hungUp.await(seconds, TimeUnit.SECONDS);
        }

        void stop() {
            var bound = listener;
            if (bound == null) return;
            try { bound.close(); } catch (IOException _) { /* already gone */ }
        }

        private void serve(int dataChunks) {
            try (var socket = listener.accept()) {
                socket.setTcpNoDelay(true);
                var in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                for (var line = in.readLine(); line != null && !line.isEmpty(); line = in.readLine()) {
                    // request line and headers; the body is drained by the EOF loop below
                }
                var out = socket.getOutputStream();
                write(out, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n");
                for (var i = 0; i < dataChunks; i++) write(out, CHUNK);
                write(out, ": keepalive\n\n");
                streaming.countDown();
                Thread.ofPlatform().daemon().name("test-sse-keepalive").start(() -> keepalive(out));
                while (in.read() >= 0) {
                    // the request body, then a block until the client closes its end
                }
            } catch (IOException _) {
                // the connection ended under us, which is the same fact as the EOF
            } finally {
                hungUp.countDown();
            }
        }

        private static void keepalive(OutputStream out) {
            try {
                while (true) {
                    write(out, ": keepalive\n\n");
                    Thread.sleep(50);
                }
            } catch (IOException | InterruptedException _) {
                // the socket is gone, or the fixture is being torn down
            }
        }

        private static void write(OutputStream out, String text) throws IOException {
            out.write(text.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
    }
}
