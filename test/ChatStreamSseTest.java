import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatCompletionChunk;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE streaming contract for {@link LlmProvider#chatStream}, exercised
 * through {@link llm.OkHttpLlmHttpDriver} (the only LLM HTTP transport
 * since JCLAW-187 deleted the JDK alternative).
 *
 * <p>For each {@code data:} line the server emits, the driver fires
 * {@code onChunk} exactly once; once the server closes the stream after
 * the {@code [DONE]} marker, {@code onComplete} fires. Streaming runs on
 * a virtual thread inside {@code chatStream}; we wait on a
 * {@link CountDownLatch} counted down by either {@code onComplete} or
 * {@code onError}.
 */
public class ChatStreamSseTest extends UnitTest {

    private MockWebServer server;
    private LlmProvider provider;

    @BeforeEach
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        var baseUrl = server.url("/v1").toString();
        var config = new ProviderConfig("test-provider", baseUrl, "fake-key", List.of());
        provider = new OpenAiProvider(config);
    }

    @AfterEach
    public void tearDown() throws Exception {
        server.close();
    }

    @Test
    public void chatStream_emitsOneChunkPerDataLine_thenCompletes() throws Exception {
        server.enqueue(threeChunkSseResponse());
        var collected = streamAndCollect();
        assertEquals(3, collected.chunks.size(),
                "one chunk per data: line, [DONE] sentinel filtered by LlmProvider");
        assertTrue(collected.completed.get(),
                "onComplete must fire after the server closes the stream");
        assertNull(collected.error.get(), "no error expected on a clean SSE response");
        assertEquals("Hello world!", joinContent(collected.chunks));
    }

    private MockResponse threeChunkSseResponse() {
        // OpenAI-style streaming chunks. Blank line between events is the
        // SSE wire-format terminator. [DONE] is the OpenAI sentinel — the
        // driver yields it as a normal data: payload; LlmProvider's
        // chatStream lambda filters it before the gson parse.
        var body = """
                data: {"id":"c1","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"Hello"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":" world"}}]}

                data: {"id":"c1","object":"chat.completion.chunk","model":"x","choices":[{"index":0,"delta":{"content":"!"},"finish_reason":"stop"}]}

                data: [DONE]

                """;
        return new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "text/event-stream")
                .body(body)
                .build();
    }

    private Collected streamAndCollect() throws Exception {
        var collected = new Collected();
        var latch = new CountDownLatch(1);
        provider.chatStream("x", List.of(ChatMessage.user("hi")), null,
                chunk -> collected.chunks.add(chunk),
                () -> { collected.completed.set(true); latch.countDown(); },
                e -> { collected.error.set(e); latch.countDown(); },
                null, null);
        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "stream should complete within 5s");
        return collected;
    }

    private static String joinContent(List<ChatCompletionChunk> chunks) {
        var sb = new StringBuilder();
        for (var c : chunks) {
            if (c.choices() == null) continue;
            for (var choice : c.choices()) {
                if (choice.delta() != null && choice.delta().content() != null) {
                    sb.append(choice.delta().content());
                }
            }
        }
        return sb.toString();
    }

    private static class Collected {
        final List<ChatCompletionChunk> chunks = new CopyOnWriteArrayList<>();
        final AtomicBoolean completed = new AtomicBoolean(false);
        final AtomicReference<Exception> error = new AtomicReference<>();
    }
}
