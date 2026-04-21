import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.telegram.telegrambots.meta.TelegramUrl;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Embedded HTTP mock of the Telegram Bot API for integration tests (JCLAW-96).
 *
 * <p>Listens on {@code 127.0.0.1:0} (ephemeral port) and responds to Bot API
 * requests at the {@code /bot<token>/<method>} path shape. Tests inspect
 * the recorded request list via {@link #requests()} to assert wire-format
 * behavior, and can override responses per method name via
 * {@link #respondWith}. Delays are per-method, configurable via
 * {@link #delay} for the timing tests.
 *
 * <p>Default responses aim to satisfy the telegrambots SDK's deserializer:
 * <ul>
 *   <li>{@code Message}-returning methods ({@code sendMessage},
 *       {@code editMessageText}, {@code sendPhoto}) → minimal valid Message.
 *   <li>{@code Boolean}-returning methods ({@code sendMessageDraft},
 *       {@code deleteMessage}, {@code sendChatAction}) → {@code {ok:true,result:true}}.
 *   <li>Anything else → {@code {ok:true,result:true}}.
 * </ul>
 *
 * <p>Lifecycle: {@link #start} binds the port and starts the executor;
 * {@link #close} stops both. Tests that override behavior via
 * {@link #respondWith} or {@link #delay} should reset state by recreating
 * the server per-test (cheap; binding is sub-millisecond on localhost).
 */
public final class MockTelegramServer implements AutoCloseable {

    /** Recorded request tuple — method name derived from the path after {@code /bot<token>/}. */
    public record RecordedRequest(String method, String path, String body) {}

    /** Canned response override. */
    private record CannedResponse(int statusCode, String body, long delayMs) {}

    private final HttpServer server;
    private final List<RecordedRequest> requests = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, CannedResponse> overrides = new ConcurrentHashMap<>();
    private volatile long globalDelayMs = 0;

    public MockTelegramServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // Small thread pool — tests exercise at most ~10 concurrent requests
        // for the parallelism timing assertion. A fixed size keeps the
        // scheduler behavior predictable.
        server.setExecutor(Executors.newFixedThreadPool(16));
        server.createContext("/", new BotApiHandler());
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(0);
        if (server.getExecutor() instanceof java.util.concurrent.ExecutorService es) {
            es.shutdownNow();
        }
    }

    /** Port the server bound to. Ephemeral; assigned by the OS. */
    public int port() {
        return server.getAddress().getPort();
    }

    /**
     * TelegramUrl pointing at this mock server. Pass to
     * {@link channels.TelegramChannel#installForTest} so the sink's Bot
     * API calls target the mock instead of api.telegram.org.
     */
    public TelegramUrl telegramUrl() {
        return TelegramUrl.builder()
                .schema("http")
                .host("127.0.0.1")
                .port(port())
                .build();
    }

    /** Snapshot of recorded requests in arrival order. */
    public List<RecordedRequest> requests() {
        return List.copyOf(requests);
    }

    /**
     * Count requests whose method (path suffix after /bot&lt;token&gt;/) equals
     * {@code methodName} case-insensitively. The telegrambots SDK sends
     * lowercase method names on the wire (e.g. {@code sendmessage}) even
     * though the public Bot API docs use camelCase, so callers can assert
     * against the documented name and the mock normalizes internally.
     */
    public long countRequests(String methodName) {
        return requests.stream().filter(r -> r.method().equalsIgnoreCase(methodName)).count();
    }

    /**
     * Override the response for a specific Bot API method. Matched
     * case-insensitively against the SDK's lowercased request path.
     * Status codes other than 200 exercise the SDK's error path — the
     * SDK throws {@code TelegramApiRequestException} which the sink's
     * catch blocks route through {@code recordFlushFailure} /
     * {@code tryDraftFlushWithFallback}.
     */
    public void respondWith(String methodName, int statusCode, String body) {
        overrides.put(methodName.toLowerCase(), new CannedResponse(statusCode, body, 0));
    }

    /** Override + inject a per-response delay in milliseconds (for timing tests). */
    public void respondWithDelay(String methodName, int statusCode, String body, long delayMs) {
        overrides.put(methodName.toLowerCase(), new CannedResponse(statusCode, body, delayMs));
    }

    /** Global delay injected into every response. Use for the parallelism timing test. */
    public void delay(long delayMs) {
        this.globalDelayMs = delayMs;
    }

    // ── Default response bodies ─────────────────────────────────────────────

    private static final String DEFAULT_MESSAGE_RESPONSE =
            "{\"ok\":true,\"result\":{\"message_id\":1,\"chat\":{\"id\":1,\"type\":\"private\"},\"date\":1,\"text\":\"mock\"}}";
    private static final String DEFAULT_BOOLEAN_RESPONSE =
            "{\"ok\":true,\"result\":true}";

    private static String defaultResponseFor(String methodName) {
        // Compare case-insensitively — the SDK lowercases method names in
        // the URL path (sendmessagedraft, etc.) even though the Bot API
        // docs and our test assertions use camelCase.
        String m = methodName.toLowerCase();
        if (m.equals("sendmessagedraft")
                || m.equals("deletemessage")
                || m.equals("sendchataction")
                || m.equals("setwebhook")
                || m.equals("setmycommands")
                // JCLAW-109: answerCallbackQuery returns True per the Bot API.
                // editMessageText can return True or the updated Message — the
                // SDK accepts either, but True is the safer default since the
                // test assertions only care about the request side.
                || m.equals("answercallbackquery")
                || m.equals("editmessagetext")) {
            return DEFAULT_BOOLEAN_RESPONSE;
        }
        // Message-returning methods: sendMessage, sendPhoto, sendDocument.
        return DEFAULT_MESSAGE_RESPONSE;
    }

    /**
     * Extract the Bot API method name from a path shaped like
     * {@code /bot<token>/<method>}. Returns the part after the last slash,
     * or the whole path if the shape doesn't match.
     */
    private static String methodNameFromPath(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash >= 0 ? path.substring(lastSlash + 1) : path;
    }

    // ── Handler ─────────────────────────────────────────────────────────────

    private final class BotApiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String httpMethod = exchange.getRequestMethod();
            byte[] bodyBytes = exchange.getRequestBody().readAllBytes();
            String body = new String(bodyBytes, StandardCharsets.UTF_8);
            String apiMethod = methodNameFromPath(path);

            requests.add(new RecordedRequest(apiMethod, path, body));

            var override = overrides.get(apiMethod.toLowerCase());
            long delayMs = override != null ? override.delayMs() : globalDelayMs;
            if (delayMs > 0) {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }

            int status = override != null ? override.statusCode() : 200;
            String responseBody = override != null ? override.body() : defaultResponseFor(apiMethod);
            byte[] responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, responseBytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(responseBytes);
            }
        }
    }
}
