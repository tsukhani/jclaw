import com.sun.net.httpserver.HttpServer;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.LatencyStats;
import utils.LatencyTrace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * JF-370: a streamed model call records how long it ran on after its first tool call's arguments were complete, the
 * most a call started early could save. The stub streams real SSE with pauses between chunks.
 */
class ToolCallTailTest extends UnitTest {

    private static final long PAUSE_MS = 400;
    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) server.stop(0);
    }

    private static String chunk(String delta) {
        return "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":"
                + delta + "}]}\n\n";
    }

    private static final String FINISH = "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"model\":\"m\","
            + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":5,\"total_tokens\":10}}\n\n";

    /** Serve {@code parts} in order, sleeping {@code PAUSE_MS} wherever a part is null. */
    private OpenAiProvider streaming(String... parts) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                for (var part : parts) {
                    if (part == null) {
                        try {
                            Thread.sleep(PAUSE_MS);
                        } catch (InterruptedException _) {
                            Thread.currentThread().interrupt();
                        }
                        continue;
                    }
                    out.write(part.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
                out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        var name = "tail-" + UUID.randomUUID().toString().substring(0, 8);
        return new OpenAiProvider(new ProviderConfig(name, "http://127.0.0.1:" + server.getAddress().getPort(),
                "sk-test", List.of(new ModelInfo("test-model", "Test", 100000, 4096, false))));
    }

    /** The turn's recorded {@code tool_call_tail} in ms, or -1 when the turn recorded none. */
    private static long recordedTail(OpenAiProvider provider) throws Exception {
        var channel = "tail-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        try (var _ = LatencyTrace.bind(trace)) {
            var stream = provider.chatStreamAccumulate("test-model", List.of(ChatMessage.user("go")), null,
                    _ -> {}, _ -> {}, null, null, "web");
            assertTrue(stream.awaitCompletion(10_000), "the stream must finish");
        }
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();
        var stats = LatencyStats.snapshot().getAsJsonObject(channel);
        return stats != null && stats.has("tool_call_tail")
                ? stats.getAsJsonObject("tool_call_tail").get("sum_ms").getAsLong() : -1;
    }

    @Test
    void theStreamAfterTheFirstCallIsCompleteIsRecorded() throws Exception {
        var provider = streaming(
                chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"a\",\"arguments\":\"{}\"}}]}"),
                chunk("{\"tool_calls\":[{\"index\":1,\"id\":\"c2\",\"type\":\"function\",\"function\":{\"name\":\"b\",\"arguments\":\"\"}}]}"),
                null,
                chunk("{\"tool_calls\":[{\"index\":1,\"function\":{\"arguments\":\"{}\"}}]}"),
                FINISH);

        long tail = recordedTail(provider);

        assertTrue(tail >= PAUSE_MS - 50 && tail < PAUSE_MS * 3,
                "the second call's arguments streamed for about %d ms after the first was complete; recorded %d"
                        .formatted(PAUSE_MS, tail));
    }

    @Test
    void aSingleCallIsCompleteAtItsLastChunkSoItsTailIsShort() throws Exception {
        var provider = streaming(
                chunk("{\"tool_calls\":[{\"index\":0,\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"a\",\"arguments\":\"\"}}]}"),
                null,
                chunk("{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"{}\"}}]}"),
                FINISH);

        long tail = recordedTail(provider);

        assertTrue(tail >= 0 && tail < 150,
                "the call was complete when the stream ended, after its %d ms of arguments; recorded %d"
                        .formatted(PAUSE_MS, tail));
    }

    @Test
    void aReplyWithNoToolCallRecordsNoTail() throws Exception {
        var provider = streaming(
                chunk("{\"content\":\"hello\"}"),
                "data: {\"id\":\"s\",\"object\":\"chat.completion.chunk\",\"model\":\"m\",\"choices\":[{\"index\":0,"
                        + "\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n");

        assertEquals(-1, recordedTail(provider));
    }
}
