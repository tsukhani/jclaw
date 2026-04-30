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
import play.Play;
import play.test.UnitTest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SSE streaming contract for {@link llm.LlmHttpDriver} drivers, exercised
 * through {@link LlmProvider#chatStream}. Backs JCLAW-185 acceptance
 * criteria 1 and 2:
 *
 * <ul>
 *   <li><b>AC1</b>: {@code play.llm.client=jdk} and {@code play.llm.client=okhttp}
 *       drive the same SSE fixture and yield byte-identical content streams.
 *       The {@code byteIdentical_jdkAndOkHttp} test enforces this by
 *       collecting the streamed content payloads from both paths against
 *       a single {@link MockWebServer} fixture and asserting equality.</li>
 *   <li><b>AC2</b>: For each {@code data:} line the server emits, the
 *       driver fires {@code onChunk} exactly once; once the server closes
 *       the stream after the {@code [DONE]} marker, {@code onComplete}
 *       fires. Verified by both {@code chatStream_jdk_emitsOneChunkPerDataLine}
 *       and the OkHttp twin.</li>
 * </ul>
 *
 * <p>Tests use the same fixture for both drivers — the only difference
 * between runs is the {@code play.llm.client} flag, which is restored to
 * its prior value in {@link #tearDown}. Streaming runs on a virtual
 * thread inside {@code chatStream}; we wait on a {@link CountDownLatch}
 * counted down by either {@code onComplete} or {@code onError}.
 */
public class LlmHttpDriverSseTest extends UnitTest {

    private MockWebServer server;
    private LlmProvider provider;
    private String savedClient;

    @BeforeEach
    public void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        var baseUrl = server.url("/v1").toString();
        var config = new ProviderConfig("test-provider", baseUrl, "fake-key", List.of());
        provider = new OpenAiProvider(config);
        savedClient = Play.configuration.getProperty("play.llm.client");
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (savedClient != null) Play.configuration.setProperty("play.llm.client", savedClient);
        else Play.configuration.remove("play.llm.client");
        server.close();
    }

    @Test
    public void chatStream_jdk_emitsOneChunkPerDataLine_thenCompletes() throws Exception {
        server.enqueue(threeChunkSseResponse());
        var collected = streamAndCollect("jdk");
        assertEquals(3, collected.chunks.size(),
                "one chunk per data: line, [DONE] sentinel filtered by LlmProvider");
        assertTrue(collected.completed.get(),
                "onComplete must fire after the server closes the stream");
        assertNull(collected.error.get(), "no error expected on a clean SSE response");
        assertEquals("Hello world!", joinContent(collected.chunks));
    }

    @Test
    public void chatStream_okhttp_emitsOneChunkPerDataLine_thenCompletes() throws Exception {
        server.enqueue(threeChunkSseResponse());
        var collected = streamAndCollect("okhttp");
        assertEquals(3, collected.chunks.size(),
                "one chunk per data: line, [DONE] sentinel filtered by LlmProvider");
        assertTrue(collected.completed.get(),
                "onComplete must fire after the server closes the stream");
        assertNull(collected.error.get(), "no error expected on a clean SSE response");
        assertEquals("Hello world!", joinContent(collected.chunks));
    }

    /**
     * AC1: both drivers consume the same SSE fixture and yield the same
     * sequence of content tokens. Byte-identical means: same number of
     * chunks, same payloads in the same order. The fixture is enqueued
     * twice (once per driver) since {@link MockWebServer} consumes one
     * response per call.
     */
    @Test
    public void chatStream_jdkAndOkHttp_yieldByteIdenticalContent() throws Exception {
        server.enqueue(threeChunkSseResponse());
        var jdk = streamAndCollect("jdk");
        server.enqueue(threeChunkSseResponse());
        var okhttp = streamAndCollect("okhttp");

        assertNull(jdk.error.get(), "jdk path must not error");
        assertNull(okhttp.error.get(), "okhttp path must not error");
        assertEquals(jdk.chunks.size(), okhttp.chunks.size(), "chunk count must match (AC1)");
        assertEquals(joinContent(jdk.chunks), joinContent(okhttp.chunks),
                "concatenated content must match byte-for-byte (AC1)");
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

    private Collected streamAndCollect(String mode) throws Exception {
        Play.configuration.setProperty("play.llm.client", mode);
        var collected = new Collected();
        var latch = new CountDownLatch(1);
        provider.chatStream("x", List.of(ChatMessage.user("hi")), null,
                chunk -> collected.chunks.add(chunk),
                () -> { collected.completed.set(true); latch.countDown(); },
                e -> { collected.error.set(e); latch.countDown(); },
                null, null);
        assertTrue(latch.await(5, TimeUnit.SECONDS),
                "stream should complete within 5s on " + mode + " driver");
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
