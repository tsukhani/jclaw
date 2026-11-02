import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.metrics.data.HistogramPointData;
import io.opentelemetry.sdk.metrics.data.MetricData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.HttpAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import llm.LlmTypes.ChatCompletionChunk;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChunkChoice;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ReasoningDetail;
import llm.LlmTypes.ToolCallChunk;
import llm.LlmTypes.Usage;
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
import services.telemetry.GenAiAttributes;
import services.telemetry.GenAiMetrics;
import services.telemetry.GenAiSpans;
import services.telemetry.OtelRuntime;
import utils.HttpFactories;
import utils.LatencyTrace;

import java.util.Collection;
import java.util.List;
import java.util.Map;


/**
 * One CLIENT span per model call, under the turn span, carrying the usage the provider
 * reported (JCLAW-34). Goes through the real provider and the real HTTP driver: the
 * non-streaming path against a canned client on the {@code HttpFactories.runWith} seam,
 * the streaming path against a {@link MockWebServer}, because the stream runs on its own
 * virtual thread and a ScopedValue does not follow it there.
 */
class GenAiSpansTest extends UnitTest {

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

    private static final String SSE_WITHOUT_OUTPUT = """
            data: {"id":"chatcmpl-e","object":"chat.completion.chunk","model":"gpt-x-2024","choices":[{"index":0,"delta":{"role":"assistant","content":""},"finish_reason":null}]}

            data: {"id":"chatcmpl-e","object":"chat.completion.chunk","model":"gpt-x-2024","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

            data: {"id":"chatcmpl-e","object":"chat.completion.chunk","model":"gpt-x-2024","choices":[],"usage":{"prompt_tokens":10,"completion_tokens":0,"total_tokens":10}}

            data: [DONE]

            """;

    private static final String EMBEDDINGS_JSON = """
            {"object":"list","model":"embed-x-2024",
             "data":[{"index":0,"embedding":[0.1,0.2]}],
             "usage":{"prompt_tokens":7,"total_tokens":7}}
            """;

    private static final String RETIRED_OPERATION_DURATION = "gen_ai.client.operation.duration";

    private static final Usage USAGE = new Usage(10, 5, 15, 0, 0, 0, 0);

    private static final Map<String, ChatCompletionChunk> FRAMES_WITHOUT_OUTPUT = Map.of(
            "a role alone", frame(new ChunkDelta("assistant", null, null, null, null, null)),
            "an empty start", frame(new ChunkDelta("assistant", "", null, null, null, null)),
            "empty reasoning", frame(new ChunkDelta(null, null, null, "", "", List.of())),
            "a tool call's id alone", toolFrame(new ToolCallChunk(0, "call_1", "function", null)),
            "an unnamed tool call", toolFrame(new ToolCallChunk(0, "call_1", "function", new FunctionCall(null, ""))),
            "an empty tool call", toolFrame(new ToolCallChunk(0, "call_1", "function", new FunctionCall("", ""))),
            "usage alone", new ChatCompletionChunk("chatcmpl-f", "gpt-x-2024", List.of(), USAGE),
            "a finish marker", new ChatCompletionChunk("chatcmpl-f", "gpt-x-2024",
                    List.of(new ChunkChoice(0, null, "stop")), null));

    private static final Map<String, ChatCompletionChunk> FRAMES_WITH_OUTPUT = Map.of(
            "text", frame(new ChunkDelta(null, "Hel", null, null, null, null)),
            "whitespace", frame(new ChunkDelta(null, " ", null, null, null, null)),
            "reasoning", frame(new ChunkDelta(null, null, null, "weighing it", null, null)),
            "reasoning_content", frame(new ChunkDelta(null, null, null, null, "weighing it", null)),
            "an opaque reasoning payload", frame(new ChunkDelta(null, null, null, null, null,
                    List.of(new ReasoningDetail("reasoning.encrypted", null)))),
            "a tool call's name", toolFrame(new ToolCallChunk(0, "call_1", "function", new FunctionCall("search", ""))),
            "a tool call's arguments", toolFrame(new ToolCallChunk(0, null, null, new FunctionCall(null, "{\"q\""))));

    private static ChatCompletionChunk frame(ChunkDelta delta) {
        return new ChatCompletionChunk("chatcmpl-f", "gpt-x-2024", List.of(new ChunkChoice(0, delta, null)), null);
    }

    private static ChatCompletionChunk toolFrame(ToolCallChunk call) {
        return frame(new ChunkDelta(null, null, List.of(call), null, null, null));
    }

    @BeforeEach
    void lock() {
        TelemetryTestSync.acquire();
        OtelRuntime.init();
    }

    @AfterEach
    void unlock() {
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
    void aChatCallIsOneClientSpanUnderTheTurnCarryingTheReportedUsage() {
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
        assertEquals("chat", a.get(GenAiAttributes.GEN_AI_OPERATION_NAME));
        assertEquals("openai", a.get(GenAiAttributes.GEN_AI_PROVIDER_NAME));
        assertEquals("gpt-x", a.get(GenAiAttributes.GEN_AI_REQUEST_MODEL));
        assertEquals("gpt-x-2024", a.get(GenAiAttributes.GEN_AI_RESPONSE_MODEL));
        assertEquals("chatcmpl-1", a.get(GenAiAttributes.GEN_AI_RESPONSE_ID));
        assertEquals(128L, a.get(GenAiAttributes.GEN_AI_REQUEST_MAX_TOKENS));
        assertEquals(10L, a.get(GenAiAttributes.GEN_AI_USAGE_INPUT_TOKENS));
        assertEquals(5L, a.get(GenAiAttributes.GEN_AI_USAGE_OUTPUT_TOKENS));
        assertEquals(4L, a.get(GenAiAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS));
        assertEquals(Boolean.TRUE, a.get(GenAiSpans.JCLAW_CACHE_HIT));
        assertEquals(List.of("stop"), a.get(GenAiAttributes.GEN_AI_RESPONSE_FINISH_REASONS));
        assertEquals("42", a.get(GenAiAttributes.GEN_AI_CONVERSATION_ID));
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
        var duration = histogramPoint(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_DURATION_NAME);
        assertTrue(duration.getBoundaries().contains(0.01), () -> "semconv buckets on the duration: " + duration.getBoundaries());
        // Aggregation is cumulative and the streamed test records the same model, so key on this call's host.
        var input = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_INPUT_TOKENS_NAME, "api.example.test");
        var output = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_OUTPUT_TOKENS_NAME, "api.example.test");
        assertEquals(1, input.size(), () -> "input series: " + input);
        assertEquals(1, output.size(), () -> "output series: " + output);
        assertEquals(10.0, input.getFirst().getSum());
        assertEquals(5.0, output.getFirst().getSum());
        for (var point : List.of(input.getFirst(), output.getFirst())) {
            assertEquals(1L, point.getCount());
            assertEquals("openai", point.getAttributes().get(GenAiAttributes.GEN_AI_PROVIDER_NAME));
            assertEquals("gpt-x", point.getAttributes().get(GenAiAttributes.GEN_AI_REQUEST_MODEL));
            assertNull(point.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.token.type")));
            assertTrue(point.getBoundaries().contains(4.0), () -> "semconv buckets on tokens: " + point.getBoundaries());
        }
        assertTrue(metrics.stream().noneMatch(m -> m.getName().equals("gen_ai.client.token.usage")),
                () -> "the pre-PR-374 histogram is gone: " + metrics.stream().map(MetricData::getName).toList());
        assertNoSeries(metrics, "api.example.test", RETIRED_OPERATION_DURATION,
                GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_DURATION_NAME,
                GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_OPERATION_INPUT_TOKENS_NAME);
    }

    private static HistogramPointData histogramPoint(Collection<MetricData> metrics, String name) {
        return metrics.stream()
                .filter(m -> m.getName().equals(name))
                .flatMap(m -> m.getHistogramData().getPoints().stream())
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " in " + metrics.stream().map(MetricData::getName).toList()));
    }

    private static List<SpanData> streamedSpans(String sse) throws Exception {
        try (var server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .addHeader("Content-Type", "text/event-stream")
                    .body(sse)
                    .build());
            var p = provider(server.url("/v1").toString());

            return OtelRuntime.captureForTest(() -> {
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
        }
    }

    @Test
    void aStreamedCallCarriesFirstChunkTimeAndTheFinalChunksUsage() throws Exception {
        var spans = streamedSpans(SSE);

        var turn = one(spans, "turn");
        var chat = one(spans, "chat gpt-x");
        var a = chat.getAttributes();
        assertEquals(Boolean.TRUE, a.get(GenAiAttributes.GEN_AI_REQUEST_STREAM));
        var ttfc = a.get(GenAiAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK);
        assertNotNull(ttfc, "time to first chunk is stamped on a streamed call");
        assertTrue(ttfc >= 0.0);
        assertEquals(10L, a.get(GenAiAttributes.GEN_AI_USAGE_INPUT_TOKENS));
        assertEquals(5L, a.get(GenAiAttributes.GEN_AI_USAGE_OUTPUT_TOKENS));
        assertEquals(4L, a.get(GenAiAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS),
                "the re-scanned cache reads reach the span");
        assertEquals(List.of("stop"), a.get(GenAiAttributes.GEN_AI_RESPONSE_FINISH_REASONS));
        assertEquals(turn.getSpanContext().getSpanId(), chat.getParentSpanContext().getSpanId(),
                "the span opened on the dispatching thread parents under the turn");
        var http = httpClientSpan(spans);
        assertEquals(chat.getSpanContext().getSpanId(), http.getParentSpanContext().getSpanId(),
                "the transport thread inherited the model call's context");
    }

    @Test
    void aStreamThatNeverProducesOutputCarriesNoFirstChunkTime() throws Exception {
        var a = one(streamedSpans(SSE_WITHOUT_OUTPUT), "chat gpt-x").getAttributes();
        assertNull(a.get(GenAiAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK));
        assertEquals(10L, a.get(GenAiAttributes.GEN_AI_USAGE_INPUT_TOKENS), "the frames were decoded");
    }

    private static void stream(ProviderConfig config, String model, ChatCompletionChunk... frames) {
        var call = GenAiSpans.start(config, GenAiSpans.OPERATION_CHAT, model, true, null);
        for (var frame : frames) call.chunk(frame);
        call.succeeded();
    }

    private static ProviderConfig openAiAt(String host) {
        return new ProviderConfig("OpenAI", "https://" + host + "/v1", "sk-test", List.of());
    }

    @Test
    void aFrameWithoutOutputIsNotAFirstChunk() {
        var spans = OtelRuntime.captureForTest(() -> FRAMES_WITHOUT_OUTPUT.forEach((label, frame) ->
                stream(openAiAt("silent.example.test"), label, frame)));
        FRAMES_WITHOUT_OUTPUT.keySet().forEach(label ->
                assertNull(one(spans, "chat " + label).getAttributes().get(GenAiAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK),
                        label));
    }

    @Test
    void aFrameWithoutOutputRecordsNoFirstChunkMetric() {
        var metrics = OtelRuntime.captureMetricsForTest(() -> FRAMES_WITHOUT_OUTPUT.forEach((label, frame) ->
                stream(openAiAt("silent-metric.example.test"), label, frame)));
        assertEquals(FRAMES_WITHOUT_OUTPUT.size(),
                hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_DURATION_NAME, "silent-metric.example.test").size());
        assertNoSeries(metrics, "silent-metric.example.test", GenAiMetrics.GEN_AI_CLIENT_INFERENCE_TIME_TO_FIRST_CHUNK_NAME);
    }

    @Test
    void aFrameWithOutputIsAFirstChunk() {
        var spans = OtelRuntime.captureForTest(() -> FRAMES_WITH_OUTPUT.forEach((label, frame) ->
                stream(openAiAt("speaks.example.test"), label, frame)));
        FRAMES_WITH_OUTPUT.keySet().forEach(label ->
                assertNotNull(one(spans, "chat " + label).getAttributes().get(GenAiAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK),
                        label));
    }

    @Test
    void aFrameWithOutputRecordsAFirstChunkMetric() {
        var metrics = OtelRuntime.captureMetricsForTest(() -> FRAMES_WITH_OUTPUT.forEach((label, frame) ->
                stream(openAiAt("speaks-metric.example.test"), label, frame)));
        assertEquals(FRAMES_WITH_OUTPUT.size(),
                hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_TIME_TO_FIRST_CHUNK_NAME, "speaks-metric.example.test").size());
    }

    @Test
    void firstChunkTimeRunsToTheFirstFrameWithOutputAndNoFurther() {
        var spans = OtelRuntime.captureForTest(() -> {
            var call = GenAiSpans.start(openAiAt("paced.example.test"), GenAiSpans.OPERATION_CHAT, "gpt-x", true, null);
            call.chunk(FRAMES_WITHOUT_OUTPUT.get("an empty start"));
            pause();
            call.chunk(FRAMES_WITH_OUTPUT.get("text"));
            pause();
            call.chunk(FRAMES_WITH_OUTPUT.get("whitespace"));
            call.succeeded();
        });
        var chat = one(spans, "chat gpt-x");
        var ttfc = chat.getAttributes().get(GenAiAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK);
        assertNotNull(ttfc);
        assertTrue(ttfc >= PAUSE_SECONDS, () -> "stamped before the first output: " + ttfc);
        var elapsed = (chat.getEndEpochNanos() - chat.getStartEpochNanos()) / 1_000_000_000.0;
        assertTrue(elapsed - ttfc >= PAUSE_SECONDS, () -> "a later frame moved the stamp: " + ttfc + " of " + elapsed);
    }

    private static final double PAUSE_SECONDS = 0.05;

    private static void pause() {
        try {
            Thread.sleep((long) (PAUSE_SECONDS * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void aRefusedCallEndsTheSpanWithTheErrorType() {
        var p = provider("https://refused.example.test/v1");
        var spans = HttpFactories.callWith(canned(400, "{\"error\":{\"message\":\"bad request\"}}"),
                () -> OtelRuntime.captureForTest(() -> assertThrows(RuntimeException.class, () ->
                        p.chat("gpt-x", List.of(ChatMessage.user("hi")), null, null, null, "web"))));
        var chat = one(spans, "chat gpt-x");
        assertEquals(StatusCode.ERROR, chat.getStatus().getStatusCode());
        assertNotNull(chat.getAttributes().get(ErrorAttributes.ERROR_TYPE));
    }

    private static void assertFailedPoint(Collection<MetricData> metrics, String name, String host, double sum) {
        var series = hostSeries(metrics, name, host);
        assertEquals(1, series.size(), () -> name + " points: " + series);
        assertEquals(1L, series.getFirst().getCount());
        assertEquals(sum, series.getFirst().getSum());
        assertEquals(IllegalStateException.class.getName(), series.getFirst().getAttributes().get(ErrorAttributes.ERROR_TYPE));
    }

    @Test
    void aCallThatFailsAfterReportingUsageRecordsThoseTokensWithTheErrorType() {
        var metrics = OtelRuntime.captureMetricsForTest(() -> {
            var call = GenAiSpans.start(openAiAt("fails.example.test"), GenAiSpans.OPERATION_CHAT, "gpt-x", true, null);
            call.response("chatcmpl-f", "gpt-x-2024", USAGE, List.of());
            call.failed(new IllegalStateException("stream died after its usage chunk"));
        });
        var duration = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_DURATION_NAME, "fails.example.test");
        assertEquals(1, duration.size(), () -> "duration points: " + duration);
        assertEquals(IllegalStateException.class.getName(), duration.getFirst().getAttributes().get(ErrorAttributes.ERROR_TYPE));
        assertFailedPoint(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_INPUT_TOKENS_NAME, "fails.example.test", 10.0);
        assertFailedPoint(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_OUTPUT_TOKENS_NAME, "fails.example.test", 5.0);
    }

    @Test
    void aCallThatFailsBeforeAnyUsageRecordsZeroTokensWithTheErrorType() {
        var metrics = OtelRuntime.captureMetricsForTest(() ->
                GenAiSpans.start(openAiAt("fails-early.example.test"), GenAiSpans.OPERATION_CHAT, "gpt-x", false, null)
                        .failed(new IllegalStateException("refused")));
        assertFailedPoint(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_INPUT_TOKENS_NAME, "fails-early.example.test", 0.0);
        assertFailedPoint(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_OUTPUT_TOKENS_NAME, "fails-early.example.test", 0.0);
    }

    @Test
    void aSuccessfulCallWithoutUsageRecordsNoTokens() {
        var metrics = OtelRuntime.captureMetricsForTest(() ->
                GenAiSpans.start(openAiAt("no-usage.example.test"), GenAiSpans.OPERATION_CHAT, "gpt-x", false, null).succeeded());
        assertEquals(1, hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_DURATION_NAME, "no-usage.example.test").size());
        assertNoSeries(metrics, "no-usage.example.test",
                GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_INPUT_TOKENS_NAME,
                GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_OUTPUT_TOKENS_NAME);
    }

    private static void failStream(String host, ChatCompletionChunk frame) {
        var call = GenAiSpans.start(openAiAt(host), GenAiSpans.OPERATION_CHAT, "gpt-x", true, null);
        call.chunk(frame);
        call.failed(new IllegalStateException("stream died"));
    }

    @Test
    void aStreamThatFailsAfterItsFirstChunkKeepsItsFirstChunkTime() {
        var chat = one(OtelRuntime.captureForTest(() -> failStream("torn.example.test", FRAMES_WITH_OUTPUT.get("text"))),
                "chat gpt-x");
        assertEquals(StatusCode.ERROR, chat.getStatus().getStatusCode());
        assertNotNull(chat.getAttributes().get(GenAiAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK));
    }

    @Test
    void aStreamThatFailsAfterItsFirstChunkRecordsTheMetricWithoutAnErrorType() {
        var metrics = OtelRuntime.captureMetricsForTest(() ->
                failStream("torn-metric.example.test", FRAMES_WITH_OUTPUT.get("text")));
        var firstChunk = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_INFERENCE_TIME_TO_FIRST_CHUNK_NAME, "torn-metric.example.test");
        assertEquals(1, firstChunk.size(), () -> "first-chunk points: " + firstChunk);
        assertNull(firstChunk.getFirst().getAttributes().get(ErrorAttributes.ERROR_TYPE));
    }

    @Test
    void aStreamThatFailsBeforeAnyOutputReportsNoFirstChunkTime() {
        var frame = FRAMES_WITHOUT_OUTPUT.get("an empty start");
        var chat = one(OtelRuntime.captureForTest(() -> failStream("mute.example.test", frame)), "chat gpt-x");
        assertNull(chat.getAttributes().get(GenAiAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK));

        var metrics = OtelRuntime.captureMetricsForTest(() -> failStream("mute-metric.example.test", frame));
        assertNoSeries(metrics, "mute-metric.example.test", GenAiMetrics.GEN_AI_CLIENT_INFERENCE_TIME_TO_FIRST_CHUNK_NAME);
    }

    private static void assertNoInferenceMetrics(Collection<MetricData> metrics, String host) {
        assertNoSeries(metrics, host, RETIRED_OPERATION_DURATION,
                GenAiMetrics.GEN_AI_CLIENT_INFERENCE_DURATION_NAME,
                GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_INPUT_TOKENS_NAME,
                GenAiMetrics.GEN_AI_CLIENT_INFERENCE_OPERATION_OUTPUT_TOKENS_NAME,
                GenAiMetrics.GEN_AI_CLIENT_INFERENCE_TIME_TO_FIRST_CHUNK_NAME);
    }

    @Test
    void anEmbeddingsCallRecordsTheEmbeddingsMetricsAndNoInferenceOnes() {
        var metrics = HttpFactories.callWith(canned(200, EMBEDDINGS_JSON), () -> OtelRuntime.captureMetricsForTest(() ->
                provider("https://embeds.example.test/v1").embeddings("embed-x", "hello", "web")));

        var duration = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_DURATION_NAME, "embeds.example.test");
        assertEquals(1, duration.size(), () -> "duration points: " + duration);
        assertEquals("embeddings", duration.getFirst().getAttributes().get(GenAiAttributes.GEN_AI_OPERATION_NAME));
        assertEquals("embed-x", duration.getFirst().getAttributes().get(GenAiAttributes.GEN_AI_REQUEST_MODEL));
        assertEquals("embed-x-2024", duration.getFirst().getAttributes().get(GenAiAttributes.GEN_AI_RESPONSE_MODEL));
        assertTrue(duration.getFirst().getBoundaries().contains(0.01),
                () -> "semconv buckets on the duration: " + duration.getFirst().getBoundaries());

        var input = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_OPERATION_INPUT_TOKENS_NAME, "embeds.example.test");
        assertEquals(1, input.size(), () -> "input points: " + input);
        assertEquals(1L, input.getFirst().getCount());
        assertEquals(7.0, input.getFirst().getSum());
        assertTrue(input.getFirst().getBoundaries().contains(4.0),
                () -> "semconv buckets on tokens: " + input.getFirst().getBoundaries());

        assertNoInferenceMetrics(metrics, "embeds.example.test");
    }

    @Test
    void anEmbeddingsSpanCarriesInputTokensAndNoOutputTokens() {
        var spans = HttpFactories.callWith(canned(200, EMBEDDINGS_JSON), () -> OtelRuntime.captureForTest(() ->
                provider("https://embeds-span.example.test/v1").embeddings("embed-x", "hello", "web")));
        var a = one(spans, "embeddings embed-x").getAttributes();
        assertEquals(7L, a.get(GenAiAttributes.GEN_AI_USAGE_INPUT_TOKENS));
        assertNull(a.get(GenAiAttributes.GEN_AI_USAGE_OUTPUT_TOKENS));
    }

    @Test
    void aFailedEmbeddingsCallRecordsItsReportedTokensWithTheErrorType() {
        var metrics = OtelRuntime.captureMetricsForTest(() -> {
            var call = GenAiSpans.start(openAiAt("embeds-fail.example.test"), GenAiSpans.OPERATION_EMBEDDINGS, "embed-x", false, null);
            call.response(null, "embed-x-2024", USAGE, List.of());
            call.failed(new IllegalStateException("died after reporting usage"));
        });
        var duration = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_DURATION_NAME, "embeds-fail.example.test");
        assertEquals(1, duration.size(), () -> "duration points: " + duration);
        assertEquals(IllegalStateException.class.getName(), duration.getFirst().getAttributes().get(ErrorAttributes.ERROR_TYPE));
        assertFailedPoint(metrics, GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_OPERATION_INPUT_TOKENS_NAME, "embeds-fail.example.test", 10.0);
        assertNoInferenceMetrics(metrics, "embeds-fail.example.test");
    }

    @Test
    void anEmbeddingsCallThatFailsBeforeAnyUsageRecordsZeroTokensWithTheErrorType() {
        var metrics = OtelRuntime.captureMetricsForTest(() ->
                GenAiSpans.start(openAiAt("embeds-early.example.test"), GenAiSpans.OPERATION_EMBEDDINGS, "embed-x", false, null)
                        .failed(new IllegalStateException("refused")));
        assertFailedPoint(metrics, GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_OPERATION_INPUT_TOKENS_NAME, "embeds-early.example.test", 0.0);
        assertNoInferenceMetrics(metrics, "embeds-early.example.test");
    }

    @Test
    void anEmbeddingsCallWithoutReportedUsageRecordsOnlyItsDuration() {
        var metrics = OtelRuntime.captureMetricsForTest(() ->
                GenAiSpans.start(openAiAt("embeds-bare.example.test"), GenAiSpans.OPERATION_EMBEDDINGS, "embed-x", false, null).succeeded());
        var duration = hostSeries(metrics, GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_DURATION_NAME, "embeds-bare.example.test");
        assertEquals(1, duration.size(), () -> "duration points: " + duration);
        assertNoSeries(metrics, "embeds-bare.example.test", GenAiMetrics.GEN_AI_CLIENT_EMBEDDINGS_OPERATION_INPUT_TOKENS_NAME);
        assertNoInferenceMetrics(metrics, "embeds-bare.example.test");
    }

    private static List<HistogramPointData> hostSeries(Collection<MetricData> metrics, String name, String host) {
        return metrics.stream()
                .filter(m -> m.getName().equals(name))
                .flatMap(m -> m.getHistogramData().getPoints().stream())
                .filter(pt -> host.equals(pt.getAttributes().get(ServerAttributes.SERVER_ADDRESS)))
                .toList();
    }

    private static void assertNoSeries(Collection<MetricData> metrics, String host, String... names) {
        for (var name : names) {
            var series = hostSeries(metrics, name, host);
            assertTrue(series.isEmpty(), () -> name + " recorded for " + host + ": " + series);
        }
    }

    @Test
    void withExportOffAChatCallRecordsNothing() {
        HttpFactories.runWith(canned(200, CHAT_JSON), () ->
                provider("https://api.example.test/v1")
                        .chat("gpt-x", List.of(ChatMessage.user("hi")), null, null, null, "web"));
        var spans = OtelRuntime.captureForTest(() -> { });
        assertTrue(spans.isEmpty(), () -> "leaked spans: " + spans);
    }
}
