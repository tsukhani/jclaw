package services;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Executors;

/**
 * Embedded OpenAI-compatible SSE server for load testing. Serves
 * deterministic streaming responses so latency measurements isolate
 * JClaw's own overhead from real-provider variance.
 *
 * <p>Binds to loopback only (127.0.0.1) on a configured port. Started
 * on demand by {@link LoadTestRunner}; safe to leave running (no auth,
 * but not reachable off-host). Started and stopped by the loadtest endpoint.
 */
public final class LoadTestHarness {

    /**
     * Scenario shape streamed by the mock endpoint.
     *
     * <p>When {@code simulatedToolCalls > 0}, the first response in a round
     * emits that many {@code loadtest_sleep} tool_calls (each with
     * {@code ms=toolSleepMs}) instead of content. The follow-up request
     * carrying tool results triggers a normal content stream. This drives
     * the agent's parallel tool-execution path end-to-end.
     */
    public record Scenario(int ttftMs, int tokensPerSecond, int responseTokens,
                            int simulatedToolCalls, int toolSleepMs) {
        public static Scenario defaults() { return new Scenario(100, 50, 40, 0, 200); }

        /** Backwards-compat overload — content-only scenario, no tool calls. */
        public Scenario(int ttftMs, int tokensPerSecond, int responseTokens) {
            this(ttftMs, tokensPerSecond, responseTokens, 0, 200);
        }
    }

    private static final Object lock = new Object();
    private static volatile HttpServer server;
    private static volatile int port;
    private static volatile Scenario scenario = Scenario.defaults();

    private LoadTestHarness() {}

    public static int port() { return port; }
    public static boolean isRunning() { return server != null; }
    public static void setScenario(Scenario s) { scenario = s; }
    public static Scenario scenario() { return scenario; }

    public static int start(int requestedPort) throws IOException {
        synchronized (lock) {
            if (server != null) return port;
            var s = HttpServer.create(new InetSocketAddress("127.0.0.1", requestedPort), 0);
            s.createContext("/v1/chat/completions", LoadTestHarness::handle);
            s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            s.start();
            server = s;
            port = s.getAddress().getPort();
            return port;
        }
    }

    public static void stop() {
        synchronized (lock) {
            if (server != null) {
                server.stop(0);
                server = null;
                port = 0;
            }
        }
    }

    private static void handle(HttpExchange ex) throws IOException {
        try {
            byte[] body = ex.getRequestBody().readAllBytes();
            var scn = scenario;
            boolean continuation = isToolResultContinuation(body);

            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.getResponseHeaders().add("Cache-Control", "no-cache");
            ex.sendResponseHeaders(200, 0);
            try (var out = ex.getResponseBody()) {
                if (!continuation && scn.simulatedToolCalls() > 0) {
                    streamToolCalls(out, scn);
                } else {
                    streamResponse(out, scn);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Returns true when the last message in the request has {@code role=tool},
     * meaning the agent is carrying tool results back to us for a follow-up.
     * The mock responds with content tokens in that case instead of another
     * tool_calls round, preventing infinite loops.
     */
    private static boolean isToolResultContinuation(byte[] body) {
        try {
            var json = com.google.gson.JsonParser.parseString(
                    new String(body, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!json.has("messages")) return false;
            var msgs = json.getAsJsonArray("messages");
            if (msgs.isEmpty()) return false;
            var last = msgs.get(msgs.size() - 1).getAsJsonObject();
            return last.has("role") && "tool".equals(last.get("role").getAsString());
        } catch (Exception _) {
            return false;
        }
    }

    private static void streamToolCalls(OutputStream out, Scenario scn)
            throws IOException, InterruptedException {
        Thread.sleep(Math.max(0, scn.ttftMs()));
        for (int i = 0; i < scn.simulatedToolCalls(); i++) {
            var callId = "call-mock-" + i;
            // Arguments string is a JSON document embedded *inside* the outer
            // JSON chunk, so its quotes need double-escaping.
            var argsJson = "{\\\"ms\\\":" + scn.toolSleepMs() + "}";
            var chunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":"
                    + "[{\"index\":" + i + ",\"id\":\"" + callId + "\","
                    + "\"type\":\"function\",\"function\":{\"name\":\"loadtest_sleep\","
                    + "\"arguments\":\"" + argsJson + "\"}}]}}]}\n\n";
            out.write(chunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
        }
        var finalChunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}\n\n"
                + "data: [DONE]\n\n";
        out.write(finalChunk.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static void streamResponse(OutputStream out, Scenario scn)
            throws IOException, InterruptedException {
        Thread.sleep(Math.max(0, scn.ttftMs()));
        int delayMs = scn.tokensPerSecond() > 0
                ? Math.max(1, 1000 / scn.tokensPerSecond())
                : 20;
        for (int i = 0; i < scn.responseTokens(); i++) {
            var tok = (i == 0 ? "Hello" : " tok" + i);
            var chunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\""
                    + tok + "\"}}]}\n\n";
            out.write(chunk.getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(delayMs);
        }
        var finalChunk = "data: {\"id\":\"mock\",\"object\":\"chat.completion.chunk\","
                + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":"
                + scn.responseTokens() + ",\"total_tokens\":"
                + (scn.responseTokens() + 10) + "}}\n\n"
                + "data: [DONE]\n\n";
        out.write(finalChunk.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }
}
