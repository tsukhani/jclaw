package services.telemetry;

import com.google.errorprone.annotations.MustBeClosed;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.semconv.ErrorAttributes;
import io.opentelemetry.semconv.ServerAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes;
import io.opentelemetry.semconv.incubating.GenAiIncubatingMetrics;
import llm.LlmTypes.ChatCompletionChunk;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.Usage;
import org.jspecify.annotations.Nullable;
import utils.LatencyTrace;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiTokenTypeIncubatingValues.INPUT;
import static io.opentelemetry.semconv.incubating.GenAiIncubatingAttributes.GenAiTokenTypeIncubatingValues.OUTPUT;

/**
 * One CLIENT span and the {@code gen_ai.client.*} metrics per model call, following the
 * GenAI semantic conventions (JCLAW-34). Opened at the two {@code LlmProvider} dispatch
 * points — the same two places JCLAW-882 put the call counter, for the same reason: a
 * span anywhere else would be one more site a later caller could miss.
 *
 * <p>Prompt and completion text are deliberately not recorded: the convention marks them
 * opt-in, and this project's memory-privacy posture keeps conversation content off any
 * third-party collector by default.
 */
public final class GenAiSpans {

    public static final AttributeKey<String> JCLAW_CHANNEL = AttributeKey.stringKey("jclaw.channel");
    public static final AttributeKey<String> JCLAW_AGENT = AttributeKey.stringKey("jclaw.agent");
    public static final AttributeKey<Boolean> JCLAW_CACHE_HIT = AttributeKey.booleanKey("jclaw.cache_hit");
    public static final AttributeKey<Double> JCLAW_COST_USD = AttributeKey.doubleKey("jclaw.cost_usd");

    public static final String OPERATION_CHAT = "chat";
    public static final String OPERATION_EMBEDDINGS = "embeddings";

    private static final String SCOPE = "jclaw";

    private record Instruments(OpenTelemetry api, DoubleHistogram tokenUsage,
                               DoubleHistogram duration, DoubleHistogram firstChunk) {}

    private static volatile @Nullable Instruments instruments;

    private GenAiSpans() {}

    /**
     * Opens the span for one model call. With export off this returns an inert handle, so
     * the disabled cost is one volatile read.
     */
    public static Call start(ProviderConfig config, String operation, String model,
                             boolean stream, @Nullable Integer maxTokens) {
        if (!TelemetryState.enabled) {
            return Call.NONE;
        }
        var provider = providerName(config);
        var server = serverOf(config);
        var builder = TelemetryState.api.getTracer(SCOPE).spanBuilder(operation + " " + model)
                .setSpanKind(SpanKind.CLIENT)
                .setAttribute(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME, operation)
                .setAttribute(GenAiIncubatingAttributes.GEN_AI_PROVIDER_NAME, provider)
                .setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL, model);
        if (server.address != null) {
            builder.setAttribute(ServerAttributes.SERVER_ADDRESS, server.address);
            if (server.port > 0) builder.setAttribute(ServerAttributes.SERVER_PORT, (long) server.port);
        }
        if (stream) builder.setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_STREAM, true);
        if (maxTokens != null) builder.setAttribute(GenAiIncubatingAttributes.GEN_AI_REQUEST_MAX_TOKENS, (long) maxTokens);
        var trace = LatencyTrace.current();
        if (trace != null) {
            if (trace.channel() != null) builder.setAttribute(JCLAW_CHANNEL, trace.channel());
            if (trace.agentId() != null) builder.setAttribute(JCLAW_AGENT, trace.agentId());
            if (trace.conversationId() != null) {
                builder.setAttribute(GenAiIncubatingAttributes.GEN_AI_CONVERSATION_ID, trace.conversationId());
            }
        }
        return new Call(builder.startSpan(), operation, provider, model, server);
    }

    /** A provider's identity for {@code gen_ai.provider.name}: the configured name, lower-cased. */
    static String providerName(ProviderConfig config) {
        return config.name().trim().toLowerCase(Locale.ROOT);
    }

    private record Server(@Nullable String address, int port) {}

    private static Server serverOf(ProviderConfig config) {
        try {
            var uri = URI.create(config.baseUrl());
            var host = uri.getHost();
            if (host == null) return new Server(null, -1);
            var port = uri.getPort();
            if (port < 0) port = "https".equalsIgnoreCase(uri.getScheme()) ? 443 : "http".equalsIgnoreCase(uri.getScheme()) ? 80 : -1;
            return new Server(host, port);
        } catch (IllegalArgumentException _) {
            return new Server(null, -1);
        }
    }

    private static Instruments instruments() {
        var api = TelemetryState.api;
        var current = instruments;
        if (current != null && current.api() == api) {
            return current;
        }
        var meter = api.getMeter(SCOPE);
        var built = new Instruments(api,
                meter.histogramBuilder(GenAiIncubatingMetrics.GEN_AI_CLIENT_TOKEN_USAGE_NAME)
                        .setDescription(GenAiIncubatingMetrics.GEN_AI_CLIENT_TOKEN_USAGE_DESCRIPTION)
                        .setUnit(GenAiIncubatingMetrics.GEN_AI_CLIENT_TOKEN_USAGE_UNIT)
                        .setExplicitBucketBoundariesAdvice(MetricBuckets.TOKENS).build(),
                meter.histogramBuilder(GenAiIncubatingMetrics.GEN_AI_CLIENT_OPERATION_DURATION_NAME)
                        .setDescription(GenAiIncubatingMetrics.GEN_AI_CLIENT_OPERATION_DURATION_DESCRIPTION)
                        .setUnit(GenAiIncubatingMetrics.GEN_AI_CLIENT_OPERATION_DURATION_UNIT)
                        .setExplicitBucketBoundariesAdvice(MetricBuckets.GEN_AI_SECONDS).build(),
                meter.histogramBuilder(GenAiIncubatingMetrics.GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_NAME)
                        .setDescription(GenAiIncubatingMetrics.GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_DESCRIPTION)
                        .setUnit(GenAiIncubatingMetrics.GEN_AI_CLIENT_OPERATION_TIME_TO_FIRST_CHUNK_UNIT)
                        .setExplicitBucketBoundariesAdvice(MetricBuckets.GEN_AI_SECONDS).build());
        instruments = built;
        return built;
    }

    /**
     * One in-flight model call. Thread-safe: a streaming call reports its chunks and its
     * completion from the HTTP client's IO thread.
     */
    public static final class Call {

        static final Call NONE = new Call();

        private final Span span;
        private final boolean live;
        private final String operation;
        private final String provider;
        private final String requestModel;
        private final Server server;
        private final long startNanos;
        private volatile long firstChunkNanos = -1L;
        private volatile @Nullable String responseId;
        private volatile @Nullable String responseModel;
        private volatile @Nullable Usage usage;
        private volatile List<String> finishReasons = List.of();
        private volatile boolean ended;

        private Call() {
            this.span = Span.getInvalid();
            this.live = false;
            this.operation = "";
            this.provider = "";
            this.requestModel = "";
            this.server = new Server(null, -1);
            this.startNanos = 0L;
        }

        private Call(Span span, String operation, String provider, String requestModel, Server server) {
            this.span = span;
            this.live = true;
            this.operation = operation;
            this.provider = provider;
            this.requestModel = requestModel;
            this.server = server;
            this.startNanos = System.nanoTime();
        }

        /** Makes the span current on this thread, so a synchronous HTTP client span nests under it. */
        @MustBeClosed
        public Scope makeCurrent() {
            return live ? span.makeCurrent() : Scope.noop();
        }

        /** The context a streaming transport thread should run under, so its spans nest here. */
        public Context context() {
            return live ? Context.current().with(span) : Context.current();
        }

        /** One streamed chunk: the first stamps time-to-first-chunk, every one may carry usage. */
        public void chunk(ChatCompletionChunk chunk) {
            if (!live) return;
            if (firstChunkNanos < 0) {
                firstChunkNanos = System.nanoTime();
            }
            if (chunk.usage() != null) usage = chunk.usage();
            if (chunk.model() != null) responseModel = chunk.model();
            if (chunk.id() != null) responseId = chunk.id();
            if (chunk.choices() != null) {
                var reasons = new ArrayList<>(finishReasons);
                for (var choice : chunk.choices()) {
                    if (choice.finishReason() != null && !reasons.contains(choice.finishReason())) {
                        reasons.add(choice.finishReason());
                    }
                }
                finishReasons = List.copyOf(reasons);
            }
        }

        /** The non-streaming outcome; {@link #succeeded()} follows. */
        public void response(@Nullable String id, @Nullable String model, @Nullable Usage usage,
                             List<String> finishReasons) {
            if (!live) return;
            this.responseId = id;
            this.responseModel = model;
            this.usage = usage;
            this.finishReasons = List.copyOf(finishReasons);
        }

        public void succeeded() {
            if (!live || ended) return;
            ended = true;
            var u = usage;
            if (responseId != null) span.setAttribute(GenAiIncubatingAttributes.GEN_AI_RESPONSE_ID, responseId);
            if (responseModel != null) span.setAttribute(GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL, responseModel);
            if (!finishReasons.isEmpty()) span.setAttribute(GenAiIncubatingAttributes.GEN_AI_RESPONSE_FINISH_REASONS, finishReasons);
            if (firstChunkNanos >= 0) {
                span.setAttribute(GenAiIncubatingAttributes.GEN_AI_RESPONSE_TIME_TO_FIRST_CHUNK, seconds(firstChunkNanos));
            }
            if (u != null) {
                span.setAttribute(GenAiIncubatingAttributes.GEN_AI_USAGE_INPUT_TOKENS, (long) u.promptTokens());
                span.setAttribute(GenAiIncubatingAttributes.GEN_AI_USAGE_OUTPUT_TOKENS, (long) u.completionTokens());
                if (u.cachedTokens() > 0) {
                    span.setAttribute(GenAiIncubatingAttributes.GEN_AI_USAGE_CACHE_READ_INPUT_TOKENS, (long) u.cachedTokens());
                }
                if (u.cacheCreationTokens() > 0) {
                    span.setAttribute(GenAiIncubatingAttributes.GEN_AI_USAGE_CACHE_CREATION_INPUT_TOKENS, (long) u.cacheCreationTokens());
                }
                if (u.reasoningTokens() > 0) {
                    span.setAttribute(GenAiIncubatingAttributes.GEN_AI_USAGE_REASONING_OUTPUT_TOKENS, (long) u.reasoningTokens());
                }
                span.setAttribute(JCLAW_CACHE_HIT, u.cachedTokens() > 0);
                if (u.costUsd() > 0) span.setAttribute(JCLAW_COST_USD, u.costUsd());
            }
            span.setStatus(StatusCode.OK);
            span.end();
            record(null);
        }

        public void failed(Throwable t) {
            if (!live || ended) return;
            ended = true;
            span.recordException(t);
            span.setAttribute(ErrorAttributes.ERROR_TYPE, t.getClass().getName());
            span.setStatus(StatusCode.ERROR, t.getClass().getSimpleName());
            span.end();
            record(t.getClass().getName());
        }

        private void record(@Nullable String errorType) {
            if (!TelemetryState.enabled) return;
            var i = instruments();
            var attrs = baseAttributes(errorType);
            i.duration().record(seconds(System.nanoTime()), attrs);
            var u = usage;
            if (u != null && errorType == null) {
                i.tokenUsage().record(u.promptTokens(), attrs.toBuilder()
                        .put(GenAiIncubatingAttributes.GEN_AI_TOKEN_TYPE, INPUT).build());
                i.tokenUsage().record(u.completionTokens(), attrs.toBuilder()
                        .put(GenAiIncubatingAttributes.GEN_AI_TOKEN_TYPE, OUTPUT).build());
            }
            if (firstChunkNanos >= 0 && errorType == null) {
                i.firstChunk().record(seconds(firstChunkNanos), attrs);
            }
        }

        private Attributes baseAttributes(@Nullable String errorType) {
            AttributesBuilder b = Attributes.builder()
                    .put(GenAiIncubatingAttributes.GEN_AI_OPERATION_NAME, operation)
                    .put(GenAiIncubatingAttributes.GEN_AI_PROVIDER_NAME, provider)
                    .put(GenAiIncubatingAttributes.GEN_AI_REQUEST_MODEL, requestModel);
            if (responseModel != null) b.put(GenAiIncubatingAttributes.GEN_AI_RESPONSE_MODEL, responseModel);
            if (server.address != null) {
                b.put(ServerAttributes.SERVER_ADDRESS, server.address);
                if (server.port > 0) b.put(ServerAttributes.SERVER_PORT, (long) server.port);
            }
            if (errorType != null) b.put(ErrorAttributes.ERROR_TYPE, errorType);
            return b.build();
        }

        private double seconds(long untilNanos) {
            return (untilNanos - startNanos) / 1_000_000_000.0;
        }
    }
}
