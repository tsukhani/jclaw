import agents.SystemPromptAssembler;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmFailureClassifier.CallSite;
import llm.LlmProvider.LlmException;
import llm.LlmResilience;
import llm.LlmTypes.ChatCompletionChunk;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import llm.OllamaNativeWire;
import llm.OllamaProvider;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import utils.CircuitBreakers;
import utils.HttpFactories;
import utils.LatencyStats;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * JCLAW-1158: Ollama's native {@code /api/chat} as a second wire under {@code OllamaProvider}.
 *
 * <p>The response fixtures are what a local Ollama 0.34.1 daemon actually answered on
 * 2026-09-18 (dolphin3:8b for the plain turn, qwen3.5:9b-mlx for the thinking + tool-call
 * stream), so the parser is pinned to the real wire shape rather than to the documentation's.
 * The single-shot and synchronous-wire tests ride the canned {@code HttpFactories} transport;
 * the two that need the provider's own stream thread use a loopback {@code HttpServer}, since a
 * {@code ScopedValue} binding does not follow {@code Thread.start()}.
 */
class OllamaNativeWireTest extends UnitTest {

    /** A real local answer: all six documented fields plus prompt_eval_cached_count. */
    private static final String LOCAL_REPLY = """
            {"model":"dolphin3:8b","created_at":"2026-09-18T05:27:19.301199Z","message":{"role":"assistant","content":"Hello there."},"done":true,"done_reason":"stop","total_duration":6777481000,"load_duration":6423727042,"prompt_eval_count":29,"prompt_eval_cached_count":0,"prompt_eval_duration":222321000,"eval_count":4,"eval_duration":66063999}
            """;

    /** What ollama.com returns: the three fields a hosted service can know. */
    private static final String CLOUD_REPLY = """
            {"model":"kimi-k2.5:cloud","created_at":"2026-09-18T05:27:19.301199Z","message":{"role":"assistant","content":"Hello there."},"done":true,"done_reason":"stop","total_duration":812000000,"prompt_eval_count":17,"eval_count":9}
            """;

    private static final String LOCAL_STREAM = """
            {"model":"dolphin3:8b","created_at":"2026-09-18T05:27:19.384699Z","message":{"role":"assistant","content":"Hello"},"done":false}
            {"model":"dolphin3:8b","created_at":"2026-09-18T05:27:19.407318Z","message":{"role":"assistant","content":" world"},"done":false}
            {"model":"dolphin3:8b","created_at":"2026-09-18T05:27:19.433357Z","message":{"role":"assistant","content":"."},"done":false}
            {"model":"dolphin3:8b","created_at":"2026-09-18T05:27:19.45605Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":108659042,"load_duration":13482084,"prompt_eval_count":29,"prompt_eval_cached_count":28,"prompt_eval_duration":22670000,"eval_count":4,"eval_duration":71295000}
            """;

    /** Reasoning arrives in {@code thinking}, the tool call whole in one line with the daemon's own id. */
    private static final String THINKING_TOOL_STREAM = """
            {"model":"qwen3.5:9b-mlx","created_at":"2026-09-18T05:28:38.4829Z","message":{"role":"assistant","content":"","thinking":"The"},"done":false}
            {"model":"qwen3.5:9b-mlx","created_at":"2026-09-18T05:28:38.495953Z","message":{"role":"assistant","content":"","thinking":" user"},"done":false}
            {"model":"qwen3.5:9b-mlx","created_at":"2026-09-18T05:28:40.642063Z","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_zy4pkytc","function":{"index":0,"name":"weather_get","arguments":{"city":"Paris"}}},{"function":{"index":0,"name":"weather_get","arguments":{"city":"Lyon"}}}]},"done":false}
            {"model":"qwen3.5:9b-mlx","created_at":"2026-09-18T05:28:40.668901Z","message":{"role":"assistant","content":""},"done":true,"done_reason":"stop","total_duration":6333627375,"load_duration":2169395125,"prompt_eval_count":277,"prompt_eval_cached_count":0,"prompt_eval_duration":1355263167,"eval_count":82,"eval_duration":2249808459}
            """;

    private static final String OPENAI_REPLY = """
            {"id":"chatcmpl-1","object":"chat.completion","model":"dolphin3:8b","choices":[{"index":0,"message":{"role":"assistant","content":"Hello from /v1."},"finish_reason":"stop"}],"usage":{"prompt_tokens":29,"completion_tokens":5,"total_tokens":34}}
            """;

    private static final String OPENAI_SSE = """
            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"dolphin3:8b","choices":[{"index":0,"delta":{"role":"assistant","content":"Hello from /v1."}}]}

            data: {"id":"chatcmpl-1","object":"chat.completion.chunk","model":"dolphin3:8b","choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":29,"completion_tokens":5,"total_tokens":34}}

            data: [DONE]

            """;

    private static final List<ChatMessage> HI = List.of(ChatMessage.user("Say hi in three words."));

    private final List<String> providerNames = new ArrayList<>();
    private HttpServer server;

    @BeforeEach
    void setup() {
        ConfigService.clearCache();
    }

    @AfterEach
    void teardown() {
        for (var name : providerNames) {
            ConfigService.delete("provider." + name + OllamaProvider.USE_NATIVE_API_SUFFIX);
            CircuitBreakers.remove(LlmResilience.breakerName(name));
        }
        ConfigService.clearCache();
        if (server != null) server.stop(0);
    }

    /** A provider with a name no other test class shares, so its breaker and its toggle are its own. */
    private OllamaProvider provider(String baseUrl, boolean nativeApi) {
        var name = "ollama-1158-" + UUID.randomUUID().toString().substring(0, 8);
        providerNames.add(name);
        ConfigService.set("provider." + name + OllamaProvider.USE_NATIVE_API_SUFFIX, String.valueOf(nativeApi));
        return new OllamaProvider(new ProviderConfig(name, baseUrl, "ollama-key", List.of()));
    }

    private OllamaNativeWire wire() {
        return new OllamaNativeWire(provider("http://127.0.0.1:1/v1", true));
    }

    // ─── Request ─────────────────────────────────────────────────────────

    @Test
    void theRequestReachesTheDaemonInItsOwnShape() {
        var messages = List.of(
                ChatMessage.system("Be brief."),
                new ChatMessage("user", List.of(
                        Map.of("type", "text", "text", "What is in this image?"),
                        Map.of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AAECAw=="))),
                        null, null, null),
                ChatMessage.assistant("", List.of(new ToolCall("call_1", "function",
                        new FunctionCall("weather_get", "{\"city\":\"Paris\"}")))),
                ChatMessage.toolResult("call_1", "weather_get", "{\"temp\":21}"));
        var tools = List.of(ToolDef.of("weather_get", "Current weather", Map.of("type", "object")));

        var body = JsonParser.parseString(wire().serialize(
                new ChatRequest("qwen3.5:9b", messages, tools, false, 64, "high"))).getAsJsonObject();

        assertEquals("qwen3.5:9b", body.get("model").getAsString());
        assertFalse(body.get("stream").getAsBoolean());
        assertEquals(64, body.getAsJsonObject("options").get("num_predict").getAsInt(),
                "max_tokens travels as options.num_predict, as the shim sends it");
        assertEquals("high", body.get("think").getAsString(), "reasoning_effort becomes think");
        assertEquals("5m", body.get("keep_alive").getAsString(), "the same residency the OpenAI wire asks for");
        assertEquals(1, body.getAsJsonArray("tools").size());
        for (var openAiOnly : List.of("max_tokens", "reasoning_effort", "stream_options")) {
            assertFalse(body.has(openAiOnly), openAiOnly + " is an OpenAI-wire field");
        }
        var messagesOut = body.getAsJsonArray("messages");
        var user = messagesOut.get(1).getAsJsonObject();
        assertEquals("What is in this image?", user.get("content").getAsString());
        assertEquals("AAECAw==", user.getAsJsonArray("images").get(0).getAsString(),
                "an image_url data URL becomes bare base64 under images");
        var call = messagesOut.get(2).getAsJsonObject().getAsJsonArray("tool_calls").get(0).getAsJsonObject();
        assertEquals("call_1", call.get("id").getAsString());
        assertEquals("Paris", call.getAsJsonObject("function").getAsJsonObject("arguments").get("city").getAsString(),
                "arguments are an object on the native wire, not the OpenAI string");
        var result = messagesOut.get(3).getAsJsonObject();
        assertEquals("tool", result.get("role").getAsString());
        assertEquals("call_1", result.get("tool_call_id").getAsString());
        assertEquals("weather_get", result.get("tool_name").getAsString());
    }

    @Test
    void thinkFollowsTheShimsLadder() {
        var wire = wire();
        assertFalse(think(wire, null).getAsBoolean(), "no effort and no mandatory level is off, as reasoning_effort:none is");
        assertEquals("low", think(wire, "minimal").getAsString());
        assertEquals("medium", think(wire, "medium").getAsString());
        assertEquals("max", think(wire, "xhigh").getAsString(), "clamped to Ollama's top tier rather than refused");
    }

    private static com.google.gson.JsonElement think(OllamaNativeWire wire, String thinkingMode) {
        var body = JsonParser.parseString(wire.serialize(new ChatRequest("m", HI, null, true, null, thinkingMode)));
        return body.getAsJsonObject().get("think");
    }

    @Test
    void theCacheBoundaryMarkersNeverReachTheWire() {
        var system = ChatMessage.system("Rules." + SystemPromptAssembler.CACHE_BOUNDARY_MARKER + " More rules.");
        var json = wire().serialize(new ChatRequest("m", List.of(system, HI.getFirst()), null, false, null, null));
        assertFalse(json.contains(SystemPromptAssembler.CACHE_BOUNDARY_MARKER),
                "the native wire scrubs the same markers the OpenAI wire does");
        assertTrue(json.contains("Rules. More rules."));
    }

    @Test
    void anImageByUrlIsRefusedAsTheShimRefusesIt() {
        var message = new ChatMessage("user", List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", "https://example.invalid/cat.png"))),
                null, null, null);
        var request = new ChatRequest("m", List.of(message), null, false, null, null);
        assertThrows(LlmException.ClientError.class, () -> wire().serialize(request));
    }

    // ─── Response ────────────────────────────────────────────────────────

    @Test
    void aLocalDaemonsReplyFillsTheUsageAndAllFourTimings() {
        var response = wire().parseResponse(LOCAL_REPLY, null);

        assertEquals("Hello there.", response.choices().getFirst().message().content());
        assertEquals("stop", response.choices().getFirst().finishReason());
        assertEquals("dolphin3:8b", response.model());
        var usage = response.usage();
        assertEquals(29, usage.promptTokens(), "prompt_eval_count is the prompt count, as the shim maps it");
        assertEquals(4, usage.completionTokens(), "eval_count is the completion count");
        assertEquals(33, usage.totalTokens());
        assertEquals(0, usage.cachedTokens(), "prompt_eval_cached_count is the cache read");
        assertEquals(Map.of(
                "total_duration", 6777481000d,
                "load_duration", 6423727042d,
                "prompt_eval_duration", 222321000d,
                "eval_duration", 66063999d), usage.providerMetrics().values(),
                "the timings the shim drops, in the daemon's own nanoseconds");
    }

    @Test
    void aHostedReplyCarriesOnlyWhatItReported() {
        var usage = wire().parseResponse(CLOUD_REPLY, null).usage();
        assertEquals(17, usage.promptTokens());
        assertEquals(9, usage.completionTokens());
        assertEquals(Map.of("total_duration", 812000000d), usage.providerMetrics().values(),
                "an absent timing stays absent rather than reading as zero");
    }

    @Test
    void toolCallsComeBackInTheOpenAiShape() {
        var reply = """
                {"model":"qwen3.5:9b-mlx","message":{"role":"assistant","content":"","tool_calls":[{"id":"call_zy4pkytc","function":{"index":0,"name":"weather_get","arguments":{"city":"Paris"}}},{"function":{"index":0,"name":"weather_get","arguments":{"city":"Lyon"}}}]},"done":true,"done_reason":"stop","total_duration":6333627375,"prompt_eval_count":277,"eval_count":82}
                """;
        var choice = wire().parseResponse(reply, null).choices().getFirst();

        assertEquals("tool_calls", choice.finishReason(), "a stop that produced tool calls reads tool_calls, as the shim reports it");
        var calls = choice.message().toolCalls();
        assertEquals(2, calls.size());
        assertEquals("call_zy4pkytc", calls.get(0).id());
        assertEquals("weather_get", calls.get(0).function().name());
        assertEquals("{\"city\":\"Paris\"}", calls.get(0).function().arguments(), "arguments are a JSON string downstream");
        assertEquals("", calls.get(1).id(), "an id the daemon did not give reads as empty, as it does through /v1");
    }

    @Test
    void theTimingsLandAsMillisecondHistograms() {
        var channel = "t1158-" + UUID.randomUUID().toString().substring(0, 8); // latency_metrics.channel is VARCHAR(32)
        wire().parseResponse(LOCAL_REPLY, channel);

        var segments = LatencyStats.snapshot().getAsJsonObject(channel);
        assertNotNull(segments, "the durations are recorded under the turn's channel");
        for (var field : OllamaNativeWire.DURATION_FIELDS) {
            assertTrue(segments.has(OllamaNativeWire.SEGMENT_PREFIX + field), field);
        }
    }

    @Test
    void aZeroTimingIsNotRecorded() {
        var channel = "t1158-" + UUID.randomUUID().toString().substring(0, 8); // latency_metrics.channel is VARCHAR(32)
        var usage = wire().parseResponse(LOCAL_REPLY.replace("\"load_duration\":6423727042", "\"load_duration\":0"), channel).usage();

        assertEquals(0d, usage.providerMetrics().values().get("load_duration"), "the popover still shows what the daemon said");
        assertFalse(LatencyStats.snapshot().getAsJsonObject(channel).has("llm_load_duration"),
                "but the histogram is spared a zero that would record as one millisecond");
    }

    // ─── Streaming, the wire alone ───────────────────────────────────────

    @Test
    void eachLineBecomesAnOpenAiChunkAndTheLastCarriesTheUsage() {
        var chunks = streamThroughCanned(LOCAL_STREAM);

        assertEquals(4, chunks.size());
        assertEquals(List.of("Hello", " world", "."),
                chunks.subList(0, 3).stream().map(c -> c.choices().getFirst().delta().content()).toList());
        var last = chunks.getLast();
        assertNull(last.choices().getFirst().delta().content(), "an empty content on the final line is no token");
        assertEquals("stop", last.choices().getFirst().finishReason());
        assertEquals(28, last.usage().cachedTokens());
        assertEquals(71295000d, last.usage().providerMetrics().values().get("eval_duration"));
        for (int i = 0; i < 3; i++) assertNull(chunks.get(i).usage(), "usage rides on the final line alone");
    }

    @Test
    void thinkingIsTheReasoningDeltaAndEveryToolCallGetsItsOwnSlot() {
        var chunks = streamThroughCanned(THINKING_TOOL_STREAM);

        assertEquals("The", chunks.get(0).choices().getFirst().delta().reasoning(),
                "the native thinking field is what extractReasoningFromDelta reads as reasoning");
        assertNull(chunks.get(0).choices().getFirst().delta().content());
        var calls = chunks.get(2).choices().getFirst().delta().toolCalls();
        assertEquals(2, calls.size());
        assertEquals(0, calls.get(0).index());
        assertEquals(1, calls.get(1).index(), "the daemon indexes every call 0; distinct slots keep parallel calls apart");
        assertEquals("call_zy4pkytc", calls.get(0).id());
        assertEquals("{\"city\":\"Lyon\"}", calls.get(1).function().arguments());
        assertEquals("tool_calls", chunks.getLast().choices().getFirst().finishReason(),
                "the final stop reads tool_calls once a call has streamed");
        assertEquals(82, chunks.getLast().usage().completionTokens());
    }

    private List<ChatCompletionChunk> streamThroughCanned(String ndjson) {
        var provider = provider("http://127.0.0.1:1/v1", true);
        var wire = new OllamaNativeWire(provider);
        var chunks = new ArrayList<ChatCompletionChunk>();
        var errors = new ArrayList<Throwable>();
        var completed = new boolean[1];
        HttpFactories.runWith(canned(_ -> reply(200, ndjson)), () ->
                wire.stream("{}", new CallSite(provider.config().name(), "m", null),
                        chunks::add, () -> completed[0] = true, errors::add, _ -> { }, null));
        assertTrue(errors.isEmpty(), () -> "stream failed: " + errors);
        assertTrue(completed[0], "a body that ends cleanly completes the stream");
        return chunks;
    }

    // ─── The toggle and the fallback, through the provider ───────────────

    @Test
    void theToggleSendsAChatToTheNativeEndpoint() {
        var seen = new CopyOnWriteArrayList<Seen>();
        var provider = provider("http://127.0.0.1:1/v1", true);

        var response = HttpFactories.callWith(recording(seen, _ -> reply(200, LOCAL_REPLY)),
                () -> provider.chat("dolphin3:8b", HI, null, null, null, "web"));

        assertEquals(List.of("/api/chat"), seen.stream().map(Seen::path).toList());
        assertTrue(seen.getFirst().body().contains("\"keep_alive\""), "the body is the native request");
        assertEquals("Hello there.", response.choices().getFirst().message().content());
        assertEquals(6777481000d, response.usage().providerMetrics().values().get("total_duration"));
    }

    @Test
    void offByDefaultTheChatStaysOnTheOpenAiWire() {
        var seen = new CopyOnWriteArrayList<Seen>();
        var provider = provider("http://127.0.0.1:1/v1", false);

        var response = HttpFactories.callWith(recording(seen, _ -> reply(200, OPENAI_REPLY)),
                () -> provider.chat("dolphin3:8b", HI, null, null, null, "web"));

        assertEquals(List.of("/v1/chat/completions"), seen.stream().map(Seen::path).toList());
        assertTrue(seen.getFirst().body().contains("\"reasoning_effort\""), "the body is the OpenAI request");
        assertEquals("Hello from /v1.", response.choices().getFirst().message().content());
        assertTrue(response.usage().providerMetrics().isEmpty(), "nothing beyond the OpenAI schema, exactly as before");
    }

    @Test
    void aGatewayWithoutTheNativeEndpointFallsBackToTheOpenAiWire() {
        var seen = new CopyOnWriteArrayList<Seen>();
        var provider = provider("http://127.0.0.1:1/v1", true);

        var response = HttpFactories.callWith(recording(seen, path -> path.equals("/api/chat")
                        ? reply(404, "<html><body>Not Found</body></html>")
                        : reply(200, OPENAI_REPLY)),
                () -> provider.chat("dolphin3:8b", HI, null, null, null, "web"));

        assertEquals(List.of("/api/chat", "/v1/chat/completions"), seen.stream().map(Seen::path).toList());
        assertEquals("Hello from /v1.", response.choices().getFirst().message().content(), "the turn did not fail");
    }

    @Test
    void theDaemonsOwnNotFoundIsNotAnAbsentEndpoint() {
        var seen = new CopyOnWriteArrayList<Seen>();
        var provider = provider("http://127.0.0.1:1/v1", true);
        var client = recording(seen, _ -> reply(404, "{\"error\":\"model 'nope:1b' not found\"}"));

        var failure = assertThrows(LlmException.ClientError.class, () ->
                HttpFactories.callWith(client, () -> provider.chat("nope:1b", HI, null, null, null, "web")));

        assertEquals(404, failure.status());
        assertEquals(List.of("/api/chat"), seen.stream().map(Seen::path).toList(),
                "a failure the OpenAI wire would repeat is not paid for twice");
    }

    @Test
    void theFallbackRuleReadsTheStatusAndTheBody() {
        var wire = wire();
        assertTrue(wire.endpointAbsent(new LlmException.ClientError("HTTP 404", null, 404, "<html>Not Found</html>")));
        assertTrue(wire.endpointAbsent(new LlmException.ClientError("HTTP 405", null, 405, "")));
        assertFalse(wire.endpointAbsent(new LlmException.ClientError("HTTP 404", null, 404, "{\"error\":\"model not found\"}")));
        assertFalse(wire.endpointAbsent(new LlmException.ClientError("HTTP 400", null, 400, "<html>Bad Request</html>")));
        assertFalse(wire.endpointAbsent(new LlmException.Transport("connection refused", new IOException())));
    }

    @Test
    void theToggleRefusesAnythingButABoolean() {
        var name = "ollama-1158-" + UUID.randomUUID().toString().substring(0, 8);
        providerNames.add(name);
        var key = "provider." + name + OllamaProvider.USE_NATIVE_API_SUFFIX;

        var refusal = ConfigService.setWithSideEffects(key, "yes");
        assertNotNull(refusal, "a typo must not silently read as off");
        assertTrue(refusal.contains("must be 'true' or 'false'"), refusal);
        assertNull(ConfigService.setWithSideEffects(key, "true"));
    }

    // ─── Streaming through the provider's own stream thread ──────────────

    @Test
    void aStreamedTurnAccumulatesContentUsageAndTimings() throws Exception {
        var baseUrl = loopback(Map.of("/api/chat", reply(200, LOCAL_STREAM)));
        var provider = provider(baseUrl, true);
        var tokens = new CopyOnWriteArrayList<String>();

        var acc = provider.chatStreamAccumulate("dolphin3:8b", HI, null, tokens::add, _ -> { }, null, null, "web");
        assertTrue(acc.awaitCompletion(10_000), "the stream completes");

        assertNull(acc.error(), () -> "stream failed: " + acc.error());
        assertEquals("Hello world.", acc.content());
        assertEquals(List.of("Hello", " world", "."), tokens);
        assertEquals("stop", acc.finishReason());
        assertEquals(108659042d, acc.usage().providerMetrics().values().get("total_duration"),
                "the final line's timings reach the accumulator, and from there the turn's usage");
    }

    @Test
    void aStreamThatFindsNoNativeEndpointFallsBackBeforeAnythingHasStreamed() throws Exception {
        var baseUrl = loopback(Map.of(
                "/api/chat", reply(404, "<html><body>Not Found</body></html>"),
                "/v1/chat/completions", reply(200, OPENAI_SSE)));
        var provider = provider(baseUrl, true);

        var acc = provider.chatStreamAccumulate("dolphin3:8b", HI, null, _ -> { }, _ -> { }, null, null, "web");
        assertTrue(acc.awaitCompletion(10_000), "the stream completes");

        assertNull(acc.error(), () -> "stream failed: " + acc.error());
        assertEquals("Hello from /v1.", acc.content(), "the same request travelled the OpenAI wire");
        assertEquals(5, acc.usage().completionTokens());
    }

    // ─── Fixtures ────────────────────────────────────────────────────────

    private record Reply(int code, String body) { }

    private record Seen(String path, String body) { }

    private static Reply reply(int code, String body) {
        return new Reply(code, body);
    }

    /** A client whose application interceptor answers by request path, never touching a socket. */
    private static OkHttpClient canned(Function<String, Reply> byPath) {
        return recording(new ArrayList<>(), byPath);
    }

    private static OkHttpClient recording(List<Seen> seen, Function<String, Reply> byPath) {
        Interceptor interceptor = chain -> {
            var request = chain.request();
            var body = "";
            if (request.body() != null) {
                var buffer = new Buffer();
                request.body().writeTo(buffer);
                body = buffer.readUtf8();
            }
            seen.add(new Seen(request.url().encodedPath(), body));
            var answer = byPath.apply(request.url().encodedPath());
            return new Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(answer.code())
                    .message("canned")
                    .body(ResponseBody.create(answer.body(), MediaType.get("application/json")))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(interceptor).build();
    }

    /** A loopback server answering each path with a fixed body; returns the provider base URL pointing at it. */
    private String loopback(Map<String, Reply> byPath) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byPath.forEach((path, answer) -> server.createContext(path, exchange -> {
            exchange.getRequestBody().readAllBytes();
            var bytes = answer.body().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type",
                    path.equals("/api/chat") ? "application/x-ndjson" : "text/event-stream");
            exchange.sendResponseHeaders(answer.code(), bytes.length);
            try (var out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }));
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }
}
