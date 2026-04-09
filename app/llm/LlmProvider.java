package llm;

import com.google.gson.*;
import llm.LlmTypes.*;
import services.EventLogger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Abstract base for LLM provider integrations. Implements shared OpenAI-compatible
 * HTTP, retry, streaming, and serialization logic. Subclasses override template methods
 * to handle provider-specific differences (reasoning params, response parsing, etc.).
 */
public abstract class LlmProvider {

    protected static final Gson gson = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .create();

    private static final int MAX_RETRIES = 3;
    private static final long[] BACKOFF_MS = {1000, 2000, 4000};

    protected final ProviderConfig config;

    protected LlmProvider(ProviderConfig config) {
        this.config = config;
    }

    public ProviderConfig config() { return config; }

    // ─── Template methods (override in subclasses) ───────────────────────

    /** Add provider-specific reasoning/thinking parameters to the request JSON. */
    protected void addReasoningParams(JsonObject request, String thinkingMode) {
        // Default: no reasoning support
    }

    /** Explicitly disable reasoning for models that think by default. Called when thinkingMode is off. */
    protected void disableReasoning(JsonObject request) {
        // Default: no action (most providers don't need explicit disable)
    }

    /**
     * Extract reasoning text from a streaming chunk delta.
     * Called for each chunk during streaming. Return null if no reasoning in this chunk.
     */
    protected String extractReasoningFromDelta(ChunkDelta delta) {
        return null;
    }

    /**
     * Extract reasoning token count from a usage JSON object.
     * Called when parsing the usage block in responses.
     */
    protected int extractReasoningTokens(JsonObject usageObj) {
        return 0;
    }

    // ─── Synchronous chat ────────────────────────────────────────────────

    public ChatResponse chat(String model, List<ChatMessage> messages, List<ToolDef> tools,
                             Integer maxTokens, String thinkingMode) {
        var request = new ChatRequest(model, messages, tools, false, maxTokens, thinkingMode);
        var json = serializeRequest(request);
        var responseBody = executeWithRetry("/chat/completions", json);
        return deserializeResponse(responseBody);
    }

    // ─── Streaming chat ──────────────────────────────────────────────────

    public void chatStream(String model, List<ChatMessage> messages, List<ToolDef> tools,
                           Consumer<ChatCompletionChunk> onChunk,
                           Runnable onComplete, Consumer<Exception> onError,
                           Integer maxTokens, String thinkingMode) {
        Thread.ofVirtual().start(() -> {
            try {
                var request = new ChatRequest(model, messages, tools, true, maxTokens, thinkingMode);
                var json = serializeRequest(request);
                var httpReq = buildRequest("/chat/completions", json);

                var response = utils.HttpClients.LLM.send(httpReq, HttpResponse.BodyHandlers.ofInputStream());
                if (response.statusCode() != 200) {
                    var body = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    onError.accept(new LlmException("HTTP %d: %s".formatted(response.statusCode(), body)));
                    return;
                }

                try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("data: ")) {
                            var data = line.substring(6).trim();
                            if ("[DONE]".equals(data)) break;
                            try {
                                var chunk = gson.fromJson(data, ChatCompletionChunk.class);
                                if (chunk != null) onChunk.accept(chunk);
                            } catch (JsonSyntaxException _) {
                                // Skip malformed chunks
                            }
                        }
                    }
                }
                onComplete.run();
            } catch (Exception e) {
                onError.accept(e);
            }
        });
    }

    // ─── Streaming with accumulation ─────────────────────────────────────

    public StreamAccumulator chatStreamAccumulate(String model, List<ChatMessage> messages,
                                                   List<ToolDef> tools, Consumer<String> onToken,
                                                   Consumer<String> onReasoning,
                                                   Integer maxTokens, String thinkingMode) {
        var accumulator = new StreamAccumulator();
        var contentBuilder = new StringBuilder();
        var toolCallAccumulator = new java.util.HashMap<Integer, ToolCallBuilder>();

        chatStream(model, messages, tools,
                chunk -> {
                    if (chunk.usage() != null) {
                        accumulator.usage = chunk.usage();
                        if (chunk.usage().reasoningTokens() > 0) {
                            accumulator.reasoningDetected = true;
                            accumulator.reasoningTokens = chunk.usage().reasoningTokens();
                        }
                    }
                    for (var choice : chunk.choices()) {
                        var delta = choice.delta();
                        if (delta.content() != null) {
                            contentBuilder.append(delta.content());
                            onToken.accept(delta.content());
                        }
                        // Delegate reasoning extraction to the provider subclass
                        var reasoningText = extractReasoningFromDelta(delta);
                        if (reasoningText != null && onReasoning != null) {
                            accumulator.reasoningDetected = true;
                            onReasoning.accept(reasoningText);
                        }
                        if (delta.toolCalls() != null) {
                            for (var tc : delta.toolCalls()) {
                                var builder = toolCallAccumulator.computeIfAbsent(
                                        tc.index(), _ -> new ToolCallBuilder());
                                if (tc.id() != null) builder.id = tc.id();
                                if (tc.type() != null) builder.type = tc.type();
                                if (tc.function() != null) {
                                    if (tc.function().name() != null) builder.functionName = tc.function().name();
                                    if (tc.function().arguments() != null) builder.arguments.append(tc.function().arguments());
                                }
                            }
                        }
                        if (choice.finishReason() != null) {
                            accumulator.finishReason = choice.finishReason();
                        }
                    }
                },
                () -> {
                    accumulator.content = contentBuilder.toString();
                    accumulator.toolCalls = toolCallAccumulator.values().stream()
                            .map(ToolCallBuilder::build).toList();
                    accumulator.markComplete();
                },
                e -> {
                    accumulator.error = e;
                    accumulator.markComplete();
                },
                maxTokens, thinkingMode);

        return accumulator;
    }

    // ─── Embeddings ──────────────────────────────────────────────────────

    public float[] embeddings(String model, String input) {
        var request = new EmbeddingRequest(model, input);
        var json = gson.toJson(request);
        var responseBody = executeWithRetry("/embeddings", json);
        var response = gson.fromJson(responseBody, EmbeddingResponse.class);
        if (response.data() == null || response.data().isEmpty()) {
            throw new LlmException("Empty embedding response");
        }
        return response.data().getFirst().embedding();
    }

    // ─── Failover (static utility) ───────────────────────────────────────

    public static ChatResponse chatWithFailover(LlmProvider primary, LlmProvider secondary,
                                                 String model, List<ChatMessage> messages,
                                                 List<ToolDef> tools, Integer maxTokens,
                                                 String thinkingMode) {
        try {
            return primary.chat(model, messages, tools, maxTokens, thinkingMode);
        } catch (LlmException e) {
            if (secondary != null) {
                EventLogger.warn("llm", "Failing over from %s to %s: %s"
                        .formatted(primary.config().name(), secondary.config().name(), e.getMessage()));
                return secondary.chat(model, messages, tools, maxTokens, thinkingMode);
            }
            throw e;
        }
    }

    // ─── Shared internals ────────────────────────────────────────────────

    protected String serializeRequest(ChatRequest request) {
        var obj = new JsonObject();
        obj.addProperty("model", request.model());
        obj.add("messages", serializeMessages(request.messages()));
        if (request.tools() != null && !request.tools().isEmpty()) {
            obj.add("tools", gson.toJsonTree(request.tools()));
        }
        if (request.stream()) {
            obj.addProperty("stream", true);
            var streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            obj.add("stream_options", streamOptions);
        }
        if (request.maxTokens() != null) {
            obj.addProperty("max_tokens", request.maxTokens());
        }
        if (request.thinkingMode() != null && !request.thinkingMode().isBlank()) {
            addReasoningParams(obj, request.thinkingMode());
        } else {
            disableReasoning(obj);
        }
        return gson.toJson(obj);
    }

    private JsonArray serializeMessages(List<ChatMessage> messages) {
        var array = new JsonArray();
        for (var msg : messages) {
            var obj = new JsonObject();
            obj.addProperty("role", msg.role());
            if (msg.content() instanceof String s) {
                obj.addProperty("content", s);
            } else if (msg.content() != null) {
                obj.add("content", gson.toJsonTree(msg.content()));
            }
            if (msg.toolCalls() != null && !msg.toolCalls().isEmpty()) {
                obj.add("tool_calls", gson.toJsonTree(msg.toolCalls()));
            }
            if (msg.toolCallId() != null) {
                obj.addProperty("tool_call_id", msg.toolCallId());
            }
            array.add(obj);
        }
        return array;
    }

    protected ChatResponse deserializeResponse(String json) {
        var obj = JsonParser.parseString(json).getAsJsonObject();
        var id = obj.has("id") ? obj.get("id").getAsString() : null;
        var model = obj.has("model") ? obj.get("model").getAsString() : null;

        var choices = new ArrayList<Choice>();
        if (obj.has("choices")) {
            for (var choiceEl : obj.getAsJsonArray("choices")) {
                var choiceObj = choiceEl.getAsJsonObject();
                var index = choiceObj.get("index").getAsInt();
                var finishReason = choiceObj.has("finish_reason") && !choiceObj.get("finish_reason").isJsonNull()
                        ? choiceObj.get("finish_reason").getAsString() : null;
                var msgObj = choiceObj.getAsJsonObject("message");
                var message = deserializeMessage(msgObj);
                choices.add(new Choice(index, message, finishReason));
            }
        }

        Usage usage = null;
        if (obj.has("usage") && !obj.get("usage").isJsonNull()) {
            var usageObj = obj.getAsJsonObject("usage");
            int reasoningTokens = extractReasoningTokens(usageObj);
            usage = new Usage(
                    usageObj.has("prompt_tokens") ? usageObj.get("prompt_tokens").getAsInt() : 0,
                    usageObj.has("completion_tokens") ? usageObj.get("completion_tokens").getAsInt() : 0,
                    usageObj.has("total_tokens") ? usageObj.get("total_tokens").getAsInt() : 0,
                    reasoningTokens
            );
        }

        return new ChatResponse(id, model, choices, usage);
    }

    private ChatMessage deserializeMessage(JsonObject msgObj) {
        var role = msgObj.get("role").getAsString();
        String content = null;
        if (msgObj.has("content") && !msgObj.get("content").isJsonNull()) {
            content = msgObj.get("content").getAsString();
        }

        List<ToolCall> toolCalls = null;
        if (msgObj.has("tool_calls") && !msgObj.get("tool_calls").isJsonNull()) {
            toolCalls = new ArrayList<>();
            for (var tcEl : msgObj.getAsJsonArray("tool_calls")) {
                var tcObj = tcEl.getAsJsonObject();
                var tcId = tcObj.get("id").getAsString();
                var tcType = tcObj.has("type") ? tcObj.get("type").getAsString() : "function";
                var fnObj = tcObj.getAsJsonObject("function");
                var fnName = fnObj.get("name").getAsString();
                var fnArgs = fnObj.get("arguments").getAsString();
                toolCalls.add(new ToolCall(tcId, tcType, new FunctionCall(fnName, fnArgs)));
            }
        }

        String toolCallId = null;
        if (msgObj.has("tool_call_id") && !msgObj.get("tool_call_id").isJsonNull()) {
            toolCallId = msgObj.get("tool_call_id").getAsString();
        }

        return new ChatMessage(role, content, toolCalls, toolCallId);
    }

    protected HttpRequest buildRequest(String path, String json) {
        var url = config.baseUrl().endsWith("/")
                ? config.baseUrl() + path.substring(1)
                : config.baseUrl() + path;

        return HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.apiKey())
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    protected String executeWithRetry(String path, String json) {
        Exception lastException = null;

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                var httpReq = buildRequest(path, json);
                var response = utils.HttpClients.LLM.send(httpReq, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) return response.body();

                if (response.statusCode() == 429) {
                    var defaultBackoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)] / 1000;
                    var retryAfter = response.headers().firstValue("Retry-After")
                            .map(v -> { try { return Long.parseLong(v); } catch (NumberFormatException _) { return defaultBackoff; } })
                            .orElse(defaultBackoff);
                    EventLogger.warn("llm", "Rate limited by %s, retrying after %ds".formatted(config.name(), retryAfter));
                    Thread.sleep(retryAfter * 1000);
                    continue;
                }

                if (response.statusCode() >= 400 && response.statusCode() < 500) {
                    throw new LlmException("HTTP %d from %s: %s".formatted(
                            response.statusCode(), config.name(), response.body()));
                }

                lastException = new LlmException("HTTP %d from %s: %s".formatted(
                        response.statusCode(), config.name(), response.body()));

            } catch (LlmException e) {
                throw e;
            } catch (Exception e) {
                lastException = e;
            }

            if (attempt < MAX_RETRIES) {
                try {
                    var backoff = BACKOFF_MS[Math.min(attempt, BACKOFF_MS.length - 1)];
                    EventLogger.warn("llm", "Retry %d/%d for %s after %dms"
                            .formatted(attempt + 1, MAX_RETRIES, config.name(), backoff));
                    Thread.sleep(backoff);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new LlmException("Interrupted during retry", ie);
                }
            }
        }

        throw new LlmException("All retries exhausted for " + config.name(), lastException);
    }

    // ─── Shared helper classes ───────────────────────────────────────────

    public static class StreamAccumulator {
        public volatile String content = "";
        public volatile List<ToolCall> toolCalls = List.of();
        public volatile String finishReason;
        public volatile boolean complete = false;
        public volatile Exception error;
        public volatile boolean reasoningDetected = false;
        public volatile int reasoningTokens = 0;
        public volatile Usage usage;
        private final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

        void markComplete() { complete = true; latch.countDown(); }
        public void awaitCompletion() throws InterruptedException { latch.await(); }
        public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
    }

    protected static class ToolCallBuilder {
        String id;
        String type = "function";
        String functionName;
        StringBuilder arguments = new StringBuilder();

        ToolCall build() {
            return new ToolCall(id, type, new FunctionCall(functionName, arguments.toString()));
        }
    }

    public static class LlmException extends RuntimeException {
        public LlmException(String message) { super(message); }
        public LlmException(String message, Throwable cause) { super(message, cause); }
    }
}
