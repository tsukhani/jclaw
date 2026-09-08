import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingMetrics;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ProviderConfig;
import llm.OpenAiProvider;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.telemetry.GenAiSpans;
import services.telemetry.OtelRuntime;
import utils.HttpFactories;
import utils.LatencyTrace;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One CLIENT span per model call, under the turn span, carrying the usage the provider
 * reported (JCLAW-34). Goes through the real provider and the real HTTP driver: the
 * non-streaming path against a canned client on the {@code HttpFactories.runWith} seam,
 * the streaming path against a {@link MockWebServer}, because the stream runs on its own
 * virtual thread and a ScopedValue does not follow it there.
 */
public class GenAiSpansTest extends UnitTest {

    private static final String CHAT_JSON = """
            {"id":"chatcmpl-1","object":"chat.completion","model":"gpt-x-2024",
             "choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,
                      "prompt_tokens_details":{"cached_tokens":4}}}
            """;

    private static final String SSE = """
            data: {"id":"chatcmpl-s","object":"chat.completion.chunk","model":"gpt-x-2024","choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"},"finish_reason":null}]}

            data: {"id":"chatcmpl-s","object":"chat.completion.chunk","model":"gpt-x-2024","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15,"prompt_tokens_details":{"cached_tokens":4}}}

            data: [DONE]

            """;

    @BeforeEach
    public void lock() {
        TelemetryTestSync.acquire();
        OtelRuntime.init();
    }

    @AfterEach
    public void unlock() {
        TelemetryTestSync.release();
    }

    private static OpenAiProvider provider(String baseUrl) {
        return new OpenAiProvider(new ProviderConfig("OpenAI", baseUrl, "sk-test", List.of()));
    }

    private static OkHttpClient canned(int code, String body) {
        Interceptor reply = chain -> new Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "Error")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
        return new OkHttpClient.Builder().addInterceptor(reply).build();
    }

    private static SpanData one(List<SpanData> spans, String name) {
        var matches = spans.stream().filter(s -> s.getName().equals(name)).toList();
        assertEquals(1, matches.size(), () -> name + " in " + spans.stream().map(SpanData::getName).toList());
        return matches.getFirst();
    }

    private static SpanData httpClientSpan(List<SpanData> spans) {
        var matches = spans.stream()
                .filter(s -> s.getKind() == SpanKind.CLIENT
                        && "POST".equals(s.getAttributes().get(HttpAttributes.HTTP_REQUEST_METHOD)))
                .toList();
        assertEquals(1, matches.size(), () -> "http client spans in " + spans.stream().map(SpanData::getName).toList());
        return matches.getFirst();
    }

    @Test
    public void aChatCallIsOneClientSpanUnderTheTurnCarryingTheReportedUsage() {
        var spans = HttpFactories.callWith(canned(200, CHAT_JSON), () -> OtelRuntime.captureForTest(() -> {
            var trace = LatencyTrace.forTurn("web", null);
            trace.conversationId(42L);
            trace.agentId("main");
            try (var _ = LatencyTrace.bind(trace)) {
                trace.mark(LatencyTrace.PROLOGUE_DONE);
                provider("https://api.example.test/v1")
                        .chat("gpt-x", List.of(ChatMessage.user("hi")), null, 128, null, "web");
            }
            trace.end();
        }));

        var turn = one(spans, "turn");
        var chat = one(spans, "chat gpt-x");
        assertEquals(SpanKind.CLIENT, chat.getKind());
        assertEquals(turn.getSpanContext().getSpanId(), chat.getParentSpanContext().getSpanId(),
                "the model call nests under the turn");
        var a = chat.getAttributes();
        assertEquals("chat", a.get(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME));
        assertEquals("openai", a.get(GenAiIncubatingAttributes.GEN_AI_PROVIDER_NAME));
        assertEquals("gpt-x", a.get(GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL));
        assertEquals("gpt-x-2024", a.get(GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL));
        assertEquals("chatcmpl-1", a.get(GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID));
        assertEquals(128L, a.get(GenAiIncubatingAttributes.GEN_AI_REQUEST_MAX_TOKENS));
        assertEquals(10L, a.get(GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS));
        assertEquals(5L, a.get(GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS));
        assertEquals(4L, a.get(GenAiIncubatingAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS));
        assertEquals(Boolean.TRUE, a.get(GenAiSpans.JCLAW_CACHE_HIT));
        assertEquals(List.of("stop"), a.get(GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS));
        assertEquals("42", a.get(GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID));
        assertEquals("main", a.get(GenAiSpans.JCLAW_AGENT));
        assertEquals("web", a.get(GenAiSpans.JCLAW_CHANNEL));
        assertEquals("api.example.test", a.get(ServerAttributes.SERVER_ADDRESS));
        assertEquals(443L, a.get(ServerAttributes.SERVER_PORT));
        assertEquals(StatusCode.OK, chat.getStatus().getStatusCode());

        assertTrue(turn.getEvents().stream().anyMatch(e -> e.getName().equals(LatencyTrace.PROLOGUE_DONE)),
                "marks become events on the turn span");
        assertEquals(1L, turn.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("jclaw.llm.calls")));
        assertEquals(1L, turn.getAttributes().get(io.opentelemetry.api.common.AttributeKey.longKey("jclaw.llm.cached_calls")));

        var http = httpClientSpan(spans);
        assertEquals(chat.getSpanContext().getSpanId(), http.getParentSpanContext().getSpanId(),
                "the HTTP client span nests under the model call");

        var metrics = OtelRuntime.captureMetricsForTest(() -> { });
        var duration = histogramPoint(metrics, GenAiIncubatingMetrics.GEN_AI_CLIENT_OPERATION_DURATION_NAME);
        assertTrue(duration.getBoundaries().contains(0.01), () -> "semconv buckets on the duration: " + duration.getBoundaries());
        var tokens = histogramPoint(metrics, GenAiIncubatingMetrics.GEN_AI_CLIENT_TOKEN_USAGE_NAME);
        assertTrue(tokens.getBoundaries().contains(4.0), () -> "semconv buckets on token usage: " + tokens.getBoundaries());
    }

    private static HistogramPointData histogramPoint(Collection<MetricData> metrics, String name) {
        return metrics.stream()
                .filter(m -> m.getName().equals(name))
                .flatMap(m -> m.getHistogramData().getPoints().stream())
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " in " + metrics.stream().map(MetricData::getName).toList()));
    }

    @Test
    public void aStreamedCallCarriesFirstChunkTimeAndTheFinalChunksUsage() throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(SSE)
                    .build());
            var p = provider(server.url("/v1").toString());

            var spans = OtelRuntime.captureForTest(() -> {
                var trace = LatencyTrace.forTurn("web", null);
                try (var _ = LatencyTrace.bind(trace)) {
                    var acc = p.chatStreamAccumulate("gpt-x", List.of(ChatMessage.user("hi")), null,
                            t -> { }, r -> { }, null, null, "web");
                    try {
                        assertTrue(acc.awaitCompletion(10_000), "stream completed");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }
                trace.end();
            });

            var turn = one(spans, "turn");
            var chat = one(spans, "chat gpt-x");
            var a = chat.getAttributes();
            assertEquals(Boolean.TRUE, a.get(GenAiIncubatingAttributes.GEN_AI_REQUEST_STREAM));
            var ttfc = a.get(GenAiIncubatingAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK);
            assertNotNull(ttfc, "time to first chunk is stamped on a streamed call");
            assertTrue(ttfc >= 0.0);
            assertEquals(10L, a.get(GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS));
            assertEquals(5L, a.get(GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS));
            assertEquals(4L, a.get(GenAiIncubatingAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS),
                    "the re-scanned cache reads reach the span");
            assertEquals(List.of("stop"), a.get(GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS));
            assertEquals(turn.getSpanContext().getSpanId(), chat.getParentSpanContext().getSpanId(),
                    "the span opened on the dispatching thread parents under the turn");
            var http = httpClientSpan(spans);
            assertEquals(chat.getSpanContext().getSpanId(), http.getParentSpanContext().getSpanId(),
                    "the transport thread inherited the model call's context");
        }
    }

    @Test
    public void aRefusedCallEndsTheSpanWithTheErrorType() {
        var spans = HttpFactories.callWith(canned(400, "{\"error\":{\"message\":\"bad request\"}}"),
                () -> OtelRuntime.captureForTest(() -> assertThrows(RuntimeException.class, () ->
                        provider("https://api.example.test/v1")
                                .chat("gpt-x", List.of(ChatMessage.user("hi")), null, null, null, "web"))));
        var chat = one(spans, "chat gpt-x");
        assertEquals(StatusCode.ERROR, chat.getStatus().getStatusCode());
        assertNotNull(chat.getAttributes().get(ErrorAttributes.ERROR_TYPE));
    }

    @Test
    public void withExportOffAChatCallRecordsNothing() {
        HttpFactories.runWith(canned(200, CHAT_JSON), () ->
                provider("https://api.example.test/v1")
                        .chat("gpt-x", List.of(ChatMessage.user("hi")), null, null, null, "web"));
        var spans = OtelRuntime.captureForTest(() -> { });
        assertTrue(spans.isEmpty(), () -> "leaked spans: " + spans);
    }
}
