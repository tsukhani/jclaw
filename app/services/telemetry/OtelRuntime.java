package services.telemetry;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.exporter.otlp.http.metrics.OtlpHttpMetricExporter;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.exporter.otlp.metrics.OtlpGrpcMetricExporter;
import io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter;
import io.opentelemetry.instrumentation.okhttp.v3_0.OkHttpTelemetry;
import io.opentelemetry.instrumentation.runtimetelemetry.RuntimeTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.export.RetryPolicy;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.metrics.export.MetricExporter;
import io.opentelemetry.sdk.metrics.export.PeriodicMetricReader;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.opentelemetry.semconv.ServiceAttributes;
import io.opentelemetry.semconv.incubating.DeploymentIncubatingAttributes;
import okhttp3.Call;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import play.Play;
import services.EventLogger;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Owns the OpenTelemetry SDK for the process (JCLAW-34).
 *
 * <p>The SDK, its processors and every instrument are created once, at
 * {@link #init()}; only three leaves ever change — the sampler and the two OTLP
 * exporters — and {@link #applyConfig()} swaps them from the {@code otel.*} keys, which
 * is what lets the collector endpoint change without a restart. With export off the
 * sampler refuses every span at creation, so nothing is batched and nothing leaves the
 * process.
 *
 * <p>When the OpenTelemetry Java agent is attached (PF-92 {@code javaagent.path} or
 * {@code JAVA_TOOL_OPTIONS}), the agent owns the SDK: this class adopts
 * {@link GlobalOpenTelemetry} so the app's spans flow into the agent's exporter, builds
 * nothing of its own, and reports the keys as read at start.
 */
public final class OtelRuntime {

    private static final String CATEGORY = "telemetry";
    private static final String INSTRUMENTATION_SCOPE = "jclaw";
    private static final Duration EXPORT_TIMEOUT = Duration.ofSeconds(5);
    // Bounded so a config write cannot hang a request thread on a dead collector.
    private static final Duration SWAP_FLUSH_TIMEOUT = Duration.ofSeconds(2);
    // Bounded so a refused collector answers the Settings delivery check within its wait,
    // rather than the exporter's default five attempts of growing backoff.
    private static final RetryPolicy RETRY = RetryPolicy.builder()
            .setMaxAttempts(3)
            .setInitialBackoff(Duration.ofMillis(500))
            .setMaxBackoff(Duration.ofSeconds(2))
            .build();

    // Closed by shutdown(); the runtime is the longer-lived owner, as with McpConnectionManager.
    @SuppressWarnings("MustBeClosed")
    private static final DelegatingSampler SAMPLER = new DelegatingSampler();
    @SuppressWarnings("MustBeClosed")
    private static final DelegatingSpanExporter SPANS = new DelegatingSpanExporter(OtelRuntime::noteExportFailure);
    @SuppressWarnings("MustBeClosed")
    private static final DelegatingMetricExporter METRICS = new DelegatingMetricExporter(OtelRuntime::noteExportFailure);

    private static volatile @Nullable OpenTelemetrySdk sdk;
    private static volatile Resource resource = Resource.getDefault();
    private static volatile OpenTelemetry api = OpenTelemetry.noop();
    private static volatile boolean agentAttached;
    private static volatile OtelConfig applied = OtelConfig.disabled();
    private static volatile @Nullable String lastExportError;
    private static volatile @Nullable RuntimeTelemetry jvmMetrics;

    private OtelRuntime() {}

    /** What the Settings panel and {@code GET /api/telemetry} show. */
    public record Status(boolean initialized,
                         boolean enabled,
                         boolean exporting,
                         String endpoint,
                         String protocol,
                         String serviceName,
                         double samplerRatio,
                         boolean agentAttached,
                         @Nullable String lastExportError) {}

    /** Outcome of {@link #sendTestSpan()}: whether the exporter confirmed delivery. */
    public record TestResult(boolean delivered, String traceId, @Nullable String error) {}

    /** Called once from the plugin at application start; safe to call again after a reload. */
    public static synchronized void init() {
        if (sdk != null) {
            applyConfig();
            return;
        }
        if (javaAgentPresent()) {
            agentAttached = true;
            api = GlobalOpenTelemetry.get();
            applied = OtelConfig.load();
            TelemetryState.api = api;
            TelemetryState.enabled = true;
            EventLogger.info(CATEGORY, "OpenTelemetry Java agent detected — its exporter settings apply");
            return;
        }
        var config = OtelConfig.load();
        resource = Resource.getDefault().toBuilder()
                .put(ServiceAttributes.SERVICE_NAME, config.serviceName())
                .put(ServiceAttributes.SERVICE_VERSION, Play.configuration.getProperty("application.version", "0.0.0"))
                .put(DeploymentIncubatingAttributes.DEPLOYMENT_ENVIRONMENT_NAME, Play.id)
                .build();
        var tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.parentBased(SAMPLER))
                .addSpanProcessor(BatchSpanProcessor.builder(SPANS)
                        .setExporterTimeout(EXPORT_TIMEOUT)
                        .build())
                .build();
        var meterProvider = SdkMeterProvider.builder()
                .setResource(resource)
                .registerMetricReader(PeriodicMetricReader.builder(METRICS)
                        .setInterval(Duration.ofSeconds(config.metricsInterval()))
                        .build())
                .build();
        var built = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setMeterProvider(meterProvider)
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        sdk = built;
        api = built;
        TelemetryState.api = built;
        applyLeaves(config);
        // JMX-backed jvm.* instruments; collected on the reader's interval even with export off.
        jvmMetrics = RuntimeTelemetry.create(built);
        EventLogger.info(CATEGORY, config.enabled()
                ? "OpenTelemetry export on → " + config.endpoint() + " (" + config.protocol().wire + ")"
                : "OpenTelemetry SDK ready; export off");
    }

    /**
     * Re-reads the {@code otel.*} keys and swaps the leaves. Called from
     * {@code ConfigService.setWithSideEffects} after any of them is written, so a change
     * takes effect on the next span and the next metric collection. What accumulated for
     * the exporter being replaced is flushed to it first.
     */
    public static synchronized void applyConfig() {
        var config = OtelConfig.load();
        var s = sdk;
        if (s == null) {
            applied = config;
            return;
        }
        if (applied.enabled()) {
            s.getSdkMeterProvider().forceFlush().join(SWAP_FLUSH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            s.getSdkTracerProvider().forceFlush().join(SWAP_FLUSH_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        applyLeaves(config);
        EventLogger.info(CATEGORY, config.enabled()
                ? "OpenTelemetry export on → " + config.endpoint() + " (" + config.protocol().wire + ")"
                : "OpenTelemetry export off");
    }

    private static void applyLeaves(OtelConfig config) {
        lastExportError = null;
        if (!config.enabled()) {
            SAMPLER.swap(Sampler.alwaysOff());
            SPANS.swap(null);
            METRICS.swap(null);
        } else {
            SPANS.swap(spanExporter(config));
            METRICS.swap(metricExporter(config));
            SAMPLER.swap(config.samplerRatio() >= 1.0
                    ? Sampler.alwaysOn()
                    : Sampler.traceIdRatioBased(config.samplerRatio()));
        }
        applied = config;
        TelemetryState.enabled = config.enabled();
    }

    /** Flushes what is queued and closes the exporters; a {@code ShutdownJob} component. */
    public static synchronized void shutdown() {
        var s = sdk;
        if (s == null) return;
        s.getSdkTracerProvider().forceFlush().join(EXPORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        s.getSdkMeterProvider().forceFlush().join(EXPORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        var jvm = jvmMetrics;
        if (jvm != null) {
            jvm.close();
            jvmMetrics = null;
        }
        s.close();
        sdk = null;
        api = OpenTelemetry.noop();
        TelemetryState.api = api;
        TelemetryState.enabled = false;
    }

    public static Tracer tracer() {
        return api.getTracer(INSTRUMENTATION_SCOPE);
    }

    public static Meter meter() {
        return api.getMeter(INSTRUMENTATION_SCOPE);
    }

    public static OpenTelemetry openTelemetry() {
        return api;
    }

    private static volatile @Nullable OkHttpTelemetry okHttp;
    private static volatile @Nullable OpenTelemetry okHttpApi;

    /**
     * {@code client} wrapped to emit an HTTP CLIENT span per call under whatever span is
     * current — the raw client while export is off, so the disabled path adds nothing.
     */
    public static Call.Factory traced(OkHttpClient client) {
        if (!applied.enabled()) {
            return client;
        }
        var a = api;
        var t = okHttp;
        if (t == null || okHttpApi != a) {
            t = OkHttpTelemetry.create(a);
            okHttp = t;
            okHttpApi = a;
        }
        return t.createCallFactory(client);
    }

    public static boolean isEnabled() {
        return applied.enabled();
    }

    public static Status status() {
        var c = applied;
        return new Status(sdk != null || agentAttached, c.enabled(), SPANS.hasDelegate(), c.endpoint(),
                c.protocol().wire, c.serviceName(), c.samplerRatio(), agentAttached, lastExportError);
    }

    /**
     * Emits one span and waits for the exporter's verdict, so the Settings panel can
     * tell "saved" from "reaching the collector". Bounded by {@link #EXPORT_TIMEOUT}.
     */
    public static TestResult sendTestSpan() {
        var s = sdk;
        if (s == null) {
            return new TestResult(agentAttached, "",
                    agentAttached ? null : "telemetry runtime is not initialized");
        }
        if (!applied.enabled()) {
            return new TestResult(false, "", "export is off — enable it first");
        }
        lastExportError = null;
        // A private provider with a SimpleSpanProcessor: its forceFlush aggregates the
        // export's own result, which the batch processor's never does — that one only
        // says the queue drained. The span still leaves through the configured leaf.
        var probe = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(SimpleSpanProcessor.create(SPANS.probeView()))
                .build();
        try {
            var span = probe.get(INSTRUMENTATION_SCOPE).spanBuilder("jclaw.telemetry.test")
                    .setSpanKind(SpanKind.INTERNAL)
                    .setAttribute(AttributeKey.stringKey("jclaw.telemetry.test.source"), "settings")
                    .startSpan();
            span.end();
            var traceId = span.getSpanContext().getTraceId();
            var flush = probe.forceFlush();
            flush.join(EXPORT_TIMEOUT.toMillis() * 2, TimeUnit.MILLISECONDS);
            if (!flush.isDone()) {
                return new TestResult(false, traceId, "no reply from " + applied.endpoint() + " within "
                        + EXPORT_TIMEOUT.toSeconds() * 2 + "s");
            }
            if (flush.isSuccess()) {
                return new TestResult(true, traceId, null);
            }
            var error = lastExportError;
            return new TestResult(false, traceId, error != null ? error : "export to " + applied.endpoint() + " failed");
        } finally {
            probe.shutdown();
        }
    }

    /**
     * Test seam: routes every span started while {@code body} runs into memory and
     * returns them, with the sampler forced on. Process-global, so a test that uses it
     * holds {@code TelemetryTestSync}; the leaves are restored from the Config DB on exit.
     */
    public static java.util.List<io.opentelemetry.sdk.trace.data.SpanData> captureForTest(Runnable body) {
        var s = sdk;
        if (s == null) {
            throw new IllegalStateException("telemetry runtime is not initialized");
        }
        var memory = io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter.create();
        synchronized (OtelRuntime.class) {
            SPANS.swap(memory);
            SAMPLER.swap(Sampler.alwaysOn());
            applied = new OtelConfig(true, applied.endpoint(), applied.protocol(), applied.headers(),
                    applied.serviceName(), 1.0, applied.metricsInterval());
            TelemetryState.enabled = true;
        }
        try {
            body.run();
            s.getSdkTracerProvider().forceFlush().join(EXPORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return memory.getFinishedSpanItems();
        } finally {
            applyConfig();
        }
    }

    /**
     * Same contract as {@link #captureForTest}, for metrics: every instrument is collected
     * into memory after {@code body} and the points returned. Aggregation is cumulative, so
     * a test keys its own series with a unique attribute value.
     */
    public static java.util.Collection<io.opentelemetry.sdk.metrics.data.MetricData> captureMetricsForTest(Runnable body) {
        var s = sdk;
        if (s == null) {
            throw new IllegalStateException("telemetry runtime is not initialized");
        }
        var memory = io.opentelemetry.sdk.testing.exporter.InMemoryMetricExporter.create();
        synchronized (OtelRuntime.class) {
            METRICS.swap(memory);
            applied = new OtelConfig(true, applied.endpoint(), applied.protocol(), applied.headers(),
                    applied.serviceName(), 1.0, applied.metricsInterval());
            TelemetryState.enabled = true;
        }
        try {
            body.run();
            s.getSdkMeterProvider().forceFlush().join(EXPORT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            return memory.getFinishedMetricItems();
        } finally {
            applyConfig();
        }
    }

    private static SpanExporter spanExporter(OtelConfig c) {
        return switch (c.protocol()) {
            case GRPC -> {
                var b = OtlpGrpcSpanExporter.builder().setEndpoint(c.endpoint()).setTimeout(EXPORT_TIMEOUT).setRetryPolicy(RETRY);
                c.headers().forEach(b::addHeader);
                yield b.build();
            }
            case HTTP_PROTOBUF -> {
                var b = OtlpHttpSpanExporter.builder().setEndpoint(signalUrl(c.endpoint(), "traces"))
                        .setTimeout(EXPORT_TIMEOUT).setRetryPolicy(RETRY);
                c.headers().forEach(b::addHeader);
                yield b.build();
            }
        };
    }

    private static MetricExporter metricExporter(OtelConfig c) {
        return switch (c.protocol()) {
            case GRPC -> {
                var b = OtlpGrpcMetricExporter.builder().setEndpoint(c.endpoint()).setTimeout(EXPORT_TIMEOUT).setRetryPolicy(RETRY);
                c.headers().forEach(b::addHeader);
                yield b.build();
            }
            case HTTP_PROTOBUF -> {
                var b = OtlpHttpMetricExporter.builder().setEndpoint(signalUrl(c.endpoint(), "metrics"))
                        .setTimeout(EXPORT_TIMEOUT).setRetryPolicy(RETRY);
                c.headers().forEach(b::addHeader);
                yield b.build();
            }
        };
    }

    /** OTLP/HTTP addresses each signal at its own path; a bare collector URL gets it appended. */
    public static String signalUrl(String endpoint, String signal) {
        var base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        return base.endsWith("/v1/" + signal) ? base : base + "/v1/" + signal;
    }

    private static void noteExportFailure(String description) {
        lastExportError = description;
        EventLogger.warn(CATEGORY, "OpenTelemetry export failed: " + description);
    }

    private static boolean javaAgentPresent() {
        try {
            Class.forName("io.opentelemetry.javaagent.bootstrap.AgentClassLoader", false, null);
            return true;
        } catch (ClassNotFoundException _) {
            return false;
        }
    }
}
