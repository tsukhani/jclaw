package services.telemetry;

import org.jspecify.annotations.Nullable;
import services.ConfigService;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The {@code otel.*} Config DB keys, read live, never seeded: an absent key is the
 * default and the default is off, the same posture as {@code shell.sandbox}.
 *
 * @param enabled        whether anything leaves the process at all
 * @param endpoint       collector base URL; the per-signal path is appended for http/protobuf
 * @param protocol       {@code http/protobuf} or {@code grpc}
 * @param headers        extra request headers, typically vendor auth
 * @param serviceName    {@code service.name} on the resource
 * @param samplerRatio   share of root spans recorded, 0..1
 * @param metricsInterval seconds between metric exports; read at start, not live
 */
public record OtelConfig(boolean enabled,
                         String endpoint,
                         Protocol protocol,
                         Map<String, String> headers,
                         String serviceName,
                         double samplerRatio,
                         int metricsInterval) {

    public static final String KEY_PREFIX = "otel.";
    public static final String KEY_ENABLED = "otel.enabled";
    public static final String KEY_ENDPOINT = "otel.exporter.endpoint";
    public static final String KEY_PROTOCOL = "otel.exporter.protocol";
    /** Named so {@link ConfigService#isSensitive} masks it: the value carries vendor auth. */
    public static final String KEY_SECRET_HEADERS = "otel.exporter.secretHeaders";
    public static final String KEY_SERVICE_NAME = "otel.service.name";
    public static final String KEY_SAMPLER_RATIO = "otel.traces.sampler.ratio";
    public static final String KEY_METRICS_INTERVAL = "otel.metrics.interval.seconds";

    public static final String DEFAULT_ENDPOINT = "http://localhost:4318";
    public static final String DEFAULT_SERVICE_NAME = "jclaw";
    public static final int DEFAULT_METRICS_INTERVAL = 60;

    public enum Protocol {
        HTTP_PROTOBUF("http/protobuf"), GRPC("grpc");

        public final String wire;

        Protocol(String wire) {
            this.wire = wire;
        }

        static @Nullable Protocol parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) return HTTP_PROTOBUF;
            for (var p : values()) {
                if (p.wire.equalsIgnoreCase(raw.trim())) return p;
            }
            return null;
        }
    }

    /** Export off, defaults everywhere; what the runtime holds before {@link #load()} first runs. */
    public static OtelConfig disabled() {
        return new OtelConfig(false, DEFAULT_ENDPOINT, Protocol.HTTP_PROTOBUF, Map.of(),
                DEFAULT_SERVICE_NAME, 1.0, DEFAULT_METRICS_INTERVAL);
    }

    public static OtelConfig load() {
        var protocol = Protocol.parse(ConfigService.get(KEY_PROTOCOL));
        return new OtelConfig(
                ConfigService.getBoolean(KEY_ENABLED, false),
                ConfigService.get(KEY_ENDPOINT, DEFAULT_ENDPOINT).trim(),
                protocol != null ? protocol : Protocol.HTTP_PROTOBUF,
                parseHeaders(ConfigService.get(KEY_SECRET_HEADERS)),
                ConfigService.get(KEY_SERVICE_NAME, DEFAULT_SERVICE_NAME).trim(),
                Math.clamp(ConfigService.getDouble(KEY_SAMPLER_RATIO, 1.0), 0.0, 1.0),
                Math.max(1, ConfigService.getInt(KEY_METRICS_INTERVAL, DEFAULT_METRICS_INTERVAL)));
    }

    /**
     * Write-time validation for {@link ConfigService#setWithSideEffects}: the rejection
     * message for a value that would not take effect, or {@code null} to accept. Every
     * key is reachable through {@code POST /api/config}, so a hidden option is not a
     * disabled one.
     */
    public static @Nullable String rejectionFor(String key, @Nullable String value) {
        var v = value == null ? "" : value.trim();
        return switch (key) {
            case KEY_ENABLED -> "true".equalsIgnoreCase(v) || "false".equalsIgnoreCase(v) || v.isEmpty()
                    ? null : KEY_ENABLED + " must be 'true' or 'false'.";
            case KEY_ENDPOINT -> rejectEndpoint(v);
            case KEY_PROTOCOL -> Protocol.parse(v) != null
                    ? null : KEY_PROTOCOL + " must be 'http/protobuf' or 'grpc'.";
            case KEY_SECRET_HEADERS -> rejectHeaders(v);
            case KEY_SAMPLER_RATIO -> rejectRatio(v);
            case KEY_METRICS_INTERVAL -> rejectInterval(v);
            default -> null;
        };
    }

    private static @Nullable String rejectEndpoint(String v) {
        if (v.isEmpty()) return null;
        try {
            var uri = URI.create(v);
            var scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                return KEY_ENDPOINT + " must be an absolute http(s) URL such as " + DEFAULT_ENDPOINT + ".";
            }
            return null;
        } catch (IllegalArgumentException _) {
            return KEY_ENDPOINT + " is not a valid URL.";
        }
    }

    private static @Nullable String rejectHeaders(String v) {
        if (v.isEmpty()) return null;
        for (var pair : v.split(",")) {
            var eq = pair.indexOf('=');
            if (eq <= 0 || pair.substring(0, eq).isBlank()) {
                return KEY_SECRET_HEADERS + " must be comma-separated name=value pairs.";
            }
        }
        return null;
    }

    private static @Nullable String rejectRatio(String v) {
        if (v.isEmpty()) return null;
        try {
            var d = Double.parseDouble(v);
            // parseDouble accepts NaN and Infinity, which a plain range check would let through.
            if (Double.isNaN(d) || d < 0.0 || d > 1.0) {
                return KEY_SAMPLER_RATIO + " must be between 0 and 1.";
            }
            return null;
        } catch (NumberFormatException _) {
            return KEY_SAMPLER_RATIO + " must be a number between 0 and 1.";
        }
    }

    private static @Nullable String rejectInterval(String v) {
        if (v.isEmpty()) return null;
        try {
            return Integer.parseInt(v) >= 1 ? null : KEY_METRICS_INTERVAL + " must be at least 1.";
        } catch (NumberFormatException _) {
            return KEY_METRICS_INTERVAL + " must be a whole number of seconds.";
        }
    }

    public static Map<String, String> parseHeaders(@Nullable String raw) {
        var out = new LinkedHashMap<String, String>();
        if (raw == null || raw.isBlank()) return out;
        for (var pair : raw.split(",")) {
            var eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(pair.substring(0, eq).trim(), pair.substring(eq + 1).trim());
            }
        }
        return out;
    }
}
