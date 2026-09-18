package llm;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import llm.LlmFailureClassifier.CallSite;
import llm.LlmProvider.LlmException;
import llm.LlmTypes.ChatCompletionChunk;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatRequest;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.Choice;
import llm.LlmTypes.ChunkChoice;
import llm.LlmTypes.ChunkDelta;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ProviderMetrics;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolCallChunk;
import llm.LlmTypes.Usage;
import models.MessageRole;
import org.jspecify.annotations.Nullable;
import services.discovery.ModelCatalogParser;
import utils.HttpKeys;
import utils.LatencyStats;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Ollama's native {@code /api/chat} as a {@link ChatWire} (JCLAW-1158).
 *
 * <p>The daemon's OpenAI-compatible shim maps only the two token counts out of its response;
 * the native object also carries the per-request timings — {@code total_duration},
 * {@code load_duration}, {@code prompt_eval_duration}, {@code eval_duration}, nanoseconds all —
 * and a local daemon reports every one. ollama.com reports {@code total_duration} alone: the
 * other three are node-local, and a hosted service does not expose them.
 *
 * <p>Both directions mirror the shim ({@code openai/openai.go} in the Ollama repository), so a
 * request on this wire reaches the model in the shape it would have taken through {@code /v1}:
 * the reasoning effort becomes {@code think}, {@code max_tokens} becomes
 * {@code options.num_predict}, an {@code image_url} data URL becomes an entry in {@code images},
 * tool-call arguments are an object on the wire and a string in {@link ToolCall}, and
 * {@code done_reason} reads {@code tool_calls} when the message carries any.
 *
 * <p>The timings land in {@link ProviderMetrics} under the daemon's own field names and unit,
 * and as milliseconds in {@link LatencyStats} under {@code llm_<field>}, so they show as
 * histograms on {@code /api/metrics/latency} beside the turn segments.
 */
public final class OllamaNativeWire implements ChatWire {

    public static final String NAME = "ollama-native";
    public static final String CHAT_PATH = "/api/chat";
    /** The nanosecond timings the daemon reports; the two counts fill {@link Usage} instead. */
    public static final List<String> DURATION_FIELDS = List.of(
            "total_duration", "load_duration", "prompt_eval_duration", "eval_duration");
    /** Prefix of the {@link LatencyStats} segment each duration is recorded under, in milliseconds. */
    public static final String SEGMENT_PREFIX = "llm_";

    private static final String JSON_MESSAGE = "message";
    private static final String JSON_CONTENT = "content";
    private static final String JSON_TOOL_CALLS = "tool_calls";
    private static final String JSON_FUNCTION = "function";
    private static final String JSON_ARGUMENTS = "arguments";
    private static final String INVALID_MESSAGE_FORMAT = "invalid message format";

    private final OllamaProvider provider;

    public OllamaNativeWire(OllamaProvider provider) {
        this.provider = provider;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** {@code <baseUrl minus /v1>/api/chat} — the root the discovery flow's {@code /api/tags} already lives under. */
    @Override
    public URI uri() {
        return URI.create(ModelCatalogParser.stripV1Suffix(provider.config().baseUrl()) + CHAT_PATH);
    }

    // ─── Request ─────────────────────────────────────────────────────────

    @Override
    public String serialize(ChatRequest request) {
        var obj = new JsonObject();
        obj.addProperty("model", request.model());
        obj.add("messages", nativeMessages(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty() && provider.modelSupportsTools(request.model())) {
            obj.add("tools", LlmProvider.gson.toJsonTree(request.tools()));
        }
        obj.addProperty("stream", request.stream());
        if (request.maxTokens() != null) {
            var options = new JsonObject();
            options.addProperty("num_predict", request.maxTokens());
            obj.add("options", options);
        }
        obj.add("think", thinkFor(request));
        obj.addProperty("keep_alive", provider.keepAlive());
        LlmProvider.stripCacheBoundaryMarker(obj);
        return LlmProvider.gson.toJson(obj);
    }

    /**
     * What the OpenAI path's {@code reasoning_effort} becomes once the shim has translated it
     * ({@code thinkFromReasoningEffort}): off is {@code false}, and the tiers past Ollama's range
     * clamp to its ends. No effort chosen means the model's mandatory level or off — the same
     * two outcomes {@link LlmProvider#serializeRequest} produces.
     */
    private JsonPrimitive thinkFor(ChatRequest request) {
        var effort = request.thinkingMode();
        if (effort == null || effort.isBlank()) {
            effort = provider.mandatoryThinkingLevel(request.model()).orElse("none");
        }
        return thinkValue(effort);
    }

    static JsonPrimitive thinkValue(String effort) {
        return switch (effort) {
            case "none" -> new JsonPrimitive(false);
            case "minimal" -> new JsonPrimitive("low");
            case "xhigh", "ultra" -> new JsonPrimitive("max");
            default -> new JsonPrimitive(effort);
        };
    }

    private static JsonArray nativeMessages(List<ChatMessage> messages) {
        var array = new JsonArray();
        for (var msg : messages) {
            var obj = new JsonObject();
            obj.addProperty("role", msg.role());
            var images = new JsonArray();
            obj.addProperty(JSON_CONTENT, contentText(msg.content(), images));
            if (!images.isEmpty()) obj.add("images", images);
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                obj.add(JSON_TOOL_CALLS, nativeToolCalls(msg.toolCalls()));
            }
            if (msg.toolCallId() != null) obj.addProperty("tool_call_id", msg.toolCallId());
            if (msg.toolName() != null) obj.addProperty("tool_name", msg.toolName());
            array.add(obj);
        }
        return array;
    }

    /**
     * The message text, with every image or audio part moved into {@code images} as bare base64:
     * the native message has no content-part array. Accepts the part types the shim accepts and
     * refuses the rest the way it does, so a request this wire cannot carry fails as it would
     * have through {@code /v1}.
     */
    private static String contentText(@Nullable Object content, JsonArray images) {
        if (content == null) return "";
        if (content instanceof String s) return s;
        var parts = LlmProvider.gson.toJsonTree(content);
        if (!parts.isJsonArray()) return parts.toString();
        var text = new StringBuilder();
        for (var el : parts.getAsJsonArray()) {
            if (!el.isJsonObject()) throw new LlmException.ClientError(INVALID_MESSAGE_FORMAT);
            var part = el.getAsJsonObject();
            switch (stringOrNull(part, "type")) {
                case "text" -> {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(stringOr(part, "text", ""));
                }
                case "image_url" -> images.add(base64OfImageUrl(part.get("image_url")));
                case "input_audio" -> images.add(audioData(part.get("input_audio")));
                case null, default -> throw new LlmException.ClientError(INVALID_MESSAGE_FORMAT);
            }
        }
        return text.toString();
    }

    /** The base64 payload of an {@code image_url} part, in either spelling: a {@code {url}} object or a bare string. */
    private static String base64OfImageUrl(@Nullable JsonElement imageUrl) {
        String url = null;
        if (imageUrl != null && imageUrl.isJsonObject()) url = stringOrNull(imageUrl.getAsJsonObject(), "url");
        else if (imageUrl != null && imageUrl.isJsonPrimitive()) url = imageUrl.getAsString();
        if (url == null) throw new LlmException.ClientError(INVALID_MESSAGE_FORMAT);
        if (url.startsWith("http://") || url.startsWith("https://")) {
            throw new LlmException.ClientError(
                    "image URLs are not currently supported, please use base64 encoded data instead");
        }
        var marker = ";base64,";
        var at = url.indexOf(marker);
        if (!url.startsWith("data:") || at < 0) throw new LlmException.ClientError("invalid image input");
        return url.substring(at + marker.length());
    }

    /** The shim carries audio bytes in {@code images} too; the native message has no audio slot. */
    private static String audioData(@Nullable JsonElement inputAudio) {
        var data = inputAudio != null && inputAudio.isJsonObject()
                ? stringOrNull(inputAudio.getAsJsonObject(), "data") : null;
        if (data == null) throw new LlmException.ClientError("invalid input_audio format");
        return data;
    }

    private static JsonArray nativeToolCalls(List<ToolCall> toolCalls) {
        var array = new JsonArray();
        for (var tc : toolCalls) {
            var fn = new JsonObject();
            fn.addProperty("name", tc.function().name());
            fn.add(JSON_ARGUMENTS, argumentsObject(tc.function().arguments()));
            var obj = new JsonObject();
            if (tc.id() != null && !tc.id().isEmpty()) obj.addProperty("id", tc.id());
            obj.add(JSON_FUNCTION, fn);
            array.add(obj);
        }
        return array;
    }

    /** Arguments are a JSON string in the OpenAI shape and an object here; one that is not an object is refused, as the shim refuses it. */
    private static JsonObject argumentsObject(@Nullable String arguments) {
        if (arguments == null || arguments.isBlank()) return new JsonObject();
        try {
            var parsed = JsonParser.parseString(arguments);
            if (parsed.isJsonObject()) return parsed.getAsJsonObject();
        } catch (JsonParseException _) {
            // refused below
        }
        throw new LlmException.ClientError("invalid tool call arguments");
    }

    // ─── Response ────────────────────────────────────────────────────────

    @Override
    public ChatResponse parseResponse(String body, @Nullable String channel) {
        var obj = JsonParser.parseString(body).getAsJsonObject();
        var message = obj.get(JSON_MESSAGE);
        if (message == null || !message.isJsonObject()) {
            throw new IllegalStateException("no message in the /api/chat response");
        }
        var toolCalls = toolCallsOf(message.getAsJsonObject());
        var reply = new ChatMessage(stringOr(message.getAsJsonObject(), "role", MessageRole.ASSISTANT.value),
                stringOr(message.getAsJsonObject(), JSON_CONTENT, ""),
                toolCalls.isEmpty() ? null : toolCalls, null, null);
        var choice = new Choice(0, reply, finishReason(obj, !toolCalls.isEmpty()));
        return new ChatResponse(null, stringOrNull(obj, "model"), List.of(choice), usageOf(obj, channel));
    }

    /** {@code done_reason} as the shim reports it: a stop that produced tool calls reads {@code tool_calls}. */
    private static @Nullable String finishReason(JsonObject obj, boolean hasToolCalls) {
        var reason = stringOrNull(obj, "done_reason");
        return "stop".equals(reason) && hasToolCalls ? "tool_calls" : reason;
    }

    private static List<ToolCall> toolCallsOf(JsonObject message) {
        var raw = message.get(JSON_TOOL_CALLS);
        if (raw == null || !raw.isJsonArray()) return List.of();
        var out = new ArrayList<ToolCall>();
        for (var el : raw.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            var tc = el.getAsJsonObject();
            var fn = tc.has(JSON_FUNCTION) && tc.get(JSON_FUNCTION).isJsonObject()
                    ? tc.getAsJsonObject(JSON_FUNCTION) : new JsonObject();
            var args = fn.get(JSON_ARGUMENTS);
            // The shim hands the arguments on as the string form of whatever object the model produced.
            var arguments = args == null ? "{}"
                    : args.isJsonPrimitive() && args.getAsJsonPrimitive().isString() ? args.getAsString()
                    : LlmProvider.gson.toJson(args);
            // The shim emits an empty id when the daemon has none, and the OpenAI path reads it as "".
            out.add(new ToolCall(stringOr(tc, "id", ""), JSON_FUNCTION,
                    new FunctionCall(stringOrNull(fn, "name"), arguments)));
        }
        return out;
    }

    /**
     * The counts fill the {@link Usage} slots the shim fills; the timings, which the shim drops,
     * are the provider metrics, and each also lands in {@link LatencyStats} in milliseconds.
     * A field the daemon did not report — the three ollama.com omits — is absent from the
     * metrics, so the popover shows what the daemon said rather than a zero it never did.
     */
    Usage usageOf(JsonObject done, @Nullable String channel) {
        int prompt = intOf(done, "prompt_eval_count");
        int completion = intOf(done, "eval_count");
        var metrics = new LinkedHashMap<String, Double>();
        for (var field : DURATION_FIELDS) {
            var value = done.get(field);
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) continue;
            long nanos = value.getAsLong();
            metrics.put(field, (double) nanos);
            long ms = nanos / 1_000_000L;
            // HdrHistogram takes positive values only and LatencyStats clamps to 1 ms, so a zero would record as one.
            if (ms > 0) LatencyStats.record(channel, SEGMENT_PREFIX + field, ms);
        }
        return new Usage(prompt, completion, prompt + completion, 0,
                intOf(done, "prompt_eval_cached_count"), 0, 0d, new ProviderMetrics(metrics));
    }

    // ─── Streaming ───────────────────────────────────────────────────────

    @Override
    public void stream(String json, CallSite site,
                       Consumer<ChatCompletionChunk> onChunk, Runnable onComplete, Consumer<Throwable> onError,
                       Consumer<Runnable> publishCancel, @Nullable String channel) {
        // The daemon emits each tool call whole and once, so a running count gives every call its
        // own slot in ToolCallChunkMerger — the per-call index the OpenAI shape promises.
        var nextToolCallIndex = new AtomicInteger();
        var sawToolCalls = new AtomicBoolean();
        OkHttpLlmHttpDriver.streamNdjson(uri(), HttpKeys.BEARER_PREFIX + provider.config().apiKey(), json, site,
                line -> {
                    ChatCompletionChunk chunk;
                    try {
                        chunk = toChunk(JsonParser.parseString(line).getAsJsonObject(),
                                nextToolCallIndex, sawToolCalls, channel);
                    } catch (RuntimeException _) {
                        return; // not a chat object; skipped, as the SSE path skips a malformed frame
                    }
                    onChunk.accept(chunk);
                },
                onComplete, onError, publishCancel, channel);
    }

    private ChatCompletionChunk toChunk(JsonObject obj, AtomicInteger nextToolCallIndex, AtomicBoolean sawToolCalls,
                                        @Nullable String channel) {
        var message = obj.has(JSON_MESSAGE) && obj.get(JSON_MESSAGE).isJsonObject()
                ? obj.getAsJsonObject(JSON_MESSAGE) : new JsonObject();
        var toolCalls = toolCallsOf(message);
        List<ToolCallChunk> toolCallChunks = null;
        if (!toolCalls.isEmpty()) {
            sawToolCalls.set(true);
            toolCallChunks = new ArrayList<>();
            for (var tc : toolCalls) {
                toolCallChunks.add(new ToolCallChunk(nextToolCallIndex.getAndIncrement(), tc.id(), tc.type(), tc.function()));
            }
        }
        var delta = new ChunkDelta(stringOrNull(message, "role"), emptyToNull(stringOrNull(message, JSON_CONTENT)),
                toolCallChunks, emptyToNull(stringOrNull(message, "thinking")), null, null);
        var done = obj.has("done") && obj.get("done").isJsonPrimitive() && obj.get("done").getAsBoolean();
        var choice = new ChunkChoice(0, delta, done ? finishReason(obj, sawToolCalls.get()) : null);
        return new ChatCompletionChunk(null, stringOrNull(obj, "model"), List.of(choice),
                done ? usageOf(obj, channel) : null);
    }

    // ─── Fallback rule ───────────────────────────────────────────────────

    /**
     * A 404 or 405 whose body is not the daemon's own {@code {"error": …}} object: whatever
     * answers at the base URL serves the OpenAI surface only, a gateway in front of Ollama. The
     * daemon's 404 for an unknown model carries that object, and is a failure the request would
     * meet on the OpenAI wire too — so it surfaces rather than being paid for twice.
     */
    @Override
    public boolean endpointAbsent(Throwable failure) {
        return failure instanceof LlmException.ClientError e
                && (e.status() == 404 || e.status() == 405)
                && !isDaemonError(e.body());
    }

    private static boolean isDaemonError(@Nullable String body) {
        if (body == null || body.isBlank()) return false;
        try {
            var parsed = JsonParser.parseString(body);
            return parsed.isJsonObject() && parsed.getAsJsonObject().has("error");
        } catch (JsonParseException _) {
            // The body reaches here sanitized and capped, which can cut a long object short.
            return body.trim().startsWith("{\"error\"");
        }
    }

    // ─── JSON helpers ────────────────────────────────────────────────────

    private static int intOf(JsonObject obj, String key) {
        var v = obj.get(key);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isNumber() ? v.getAsInt() : 0;
    }

    private static @Nullable String stringOrNull(JsonObject obj, String key) {
        var v = obj.get(key);
        return v != null && v.isJsonPrimitive() ? v.getAsString() : null;
    }

    private static String stringOr(JsonObject obj, String key, String fallback) {
        var s = stringOrNull(obj, key);
        return s == null ? fallback : s;
    }

    private static @Nullable String emptyToNull(@Nullable String s) {
        return s == null || s.isEmpty() ? null : s;
    }
}
