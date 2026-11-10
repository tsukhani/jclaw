import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import services.ConfigService;
import services.Tx;
import utils.CircuitBreakers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * A loopback chat-completions server, registered as a provider of its own (JF-202), that a functional test can hold:
 * a call waits wherever its responder calls {@link #awaitRelease} until the test calls {@link #release}. The request
 * runs on Play's thread, where an {@code HttpFactories} binding does not reach, so the port is real. {@link #close}
 * unregisters the provider and drops its breaker.
 */
public final class HeldModel implements AutoCloseable {

    /** The model every agent on this provider names; {@link #OTHER_MODEL} is there to be picked instead. */
    public static final String MODEL = "turn-model";
    public static final String OTHER_MODEL = "other-model";
    /** A model to pick that thinks, at {@code low} or {@code high}; the other two do not. */
    public static final String THINKING_MODEL = "thinking-model";
    public static final String REPLY = "It is now.";

    /** How one call is answered; it may hold on {@link #awaitRelease} wherever the test needs it to. */
    public interface Responder {
        void respond(HeldModel model, HttpExchange exchange, JsonObject request) throws Exception;
    }

    public final String provider = "held-" + UUID.randomUUID().toString().substring(0, 8);
    public final AtomicInteger calls = new AtomicInteger();
    /** Counted down when the first call arrives. */
    public final CountDownLatch called = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final HttpServer server;
    // Two turns may call at once, and a held call must not hold the other.
    private final ExecutorService handlers = Executors.newCachedThreadPool();

    /** Each call waits for {@link #release}, then answers {@link #REPLY}. */
    public static HeldModel held() throws IOException {
        return new HeldModel((model, exchange, request) -> {
            model.awaitRelease();
            LlmReplyFixture.send(exchange, replyCompletion());
        });
    }

    /** Each call answers {@link #REPLY} at once. */
    public static HeldModel answering() throws IOException {
        return replying(_ -> replyCompletion());
    }

    /** Each call answers at once with the completion JSON {@code completion} makes of its request. */
    public static HeldModel replying(Function<JsonObject, String> completion) throws IOException {
        return new HeldModel((model, exchange, request) -> LlmReplyFixture.send(exchange, completion.apply(request)));
    }

    public HeldModel(Responder responder) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/chat/completions", exchange -> {
            calls.incrementAndGet();
            called.countDown();
            try {
                var request = JsonParser.parseString(
                        new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                responder.respond(this, exchange, request);
            } catch (Exception _) {
                exchange.close();
            }
        });
        server.start();
        inFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models", "[{\"id\":\"" + MODEL
                    + "\",\"name\":\"Turn\",\"contextWindow\":100000,\"maxTokens\":4096},{\"id\":\"" + OTHER_MODEL
                    + "\",\"name\":\"Other\",\"contextWindow\":100000,\"maxTokens\":4096},{\"id\":\"" + THINKING_MODEL
                    + "\",\"name\":\"Thinker\",\"contextWindow\":100000,\"maxTokens\":4096,\"supportsThinking\":true,"
                    + "\"thinkingLevels\":[\"low\",\"high\"]}]");
            return null;
        });
        // refresh() returns at once while another class's refresh runs, and that one may predate this commit.
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) {
            ProviderRegistry.refresh();
            if (ProviderRegistry.get(provider) == null) sleep(20);
        }
        if (ProviderRegistry.get(provider) == null) {
            close();
            throw new IllegalStateException("test precondition: the held provider never registered");
        }
    }

    /** {@link #REPLY} as a completion. */
    public static String replyCompletion() {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"It is now."},"finish_reason":"stop"}],
                "usage":{"prompt_tokens":120,"completion_tokens":4,"total_tokens":124}}""";
    }

    /** Let every held call, and every later one, through. */
    public void release() {
        release.countDown();
    }

    /** For a responder: wait until the test calls {@link #release}, 30 s at most. */
    public void awaitRelease() throws InterruptedException {
        release.await(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        handlers.shutdown();
        inFreshTx(() -> {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            return null;
        });
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    /** Committed on its own thread: a functional test's body is already in a transaction, which Tx.run would join. */
    private static <T> T inFreshTx(Supplier<T> body) {
        var result = new AtomicReference<T>();
        var failure = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().start(() -> {
            try {
                result.set(Tx.run(body::get));
            } catch (Throwable t) {
                failure.set(t);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (failure.get() != null) throw new IllegalStateException(failure.get());
        return result.get();
    }
}
