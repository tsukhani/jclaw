package services.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import org.jspecify.annotations.Nullable;

/**
 * The {@code jclaw.turn.segment.duration} histogram behind {@link utils.LatencyStats#record}
 * (JCLAW-34): the channel/segment/agent series the dashboard already reads, exported.
 */
public final class TurnMetrics {

    public static final String SEGMENT_DURATION = "jclaw.turn.segment.duration";
    public static final AttributeKey<String> JCLAW_SEGMENT = AttributeKey.stringKey("jclaw.segment");

    private static final String SCOPE = "jclaw";

    private record Instrument(OpenTelemetry api, DoubleHistogram histogram) {}

    private static volatile @Nullable Instrument instrument;

    private TurnMetrics() {}

    /** With export off this is one volatile read. */
    public static void recordSegment(@Nullable String channel, String segment, long valueMs, @Nullable String agentId) {
        if (!TelemetryState.enabled) {
            return;
        }
        var attrs = Attributes.builder().put(JCLAW_SEGMENT, segment);
        if (channel != null) attrs.put(GenAiSpans.JCLAW_CHANNEL, channel);
        if (agentId != null) attrs.put(GenAiSpans.JCLAW_AGENT, agentId);
        instrument().histogram().record(valueMs / 1000.0, attrs.build());
    }

    private static Instrument instrument() {
        var api = TelemetryState.api;
        var current = instrument;
        if (current != null && current.api() == api) {
            return current;
        }
        var built = new Instrument(api, api.getMeter(SCOPE).histogramBuilder(SEGMENT_DURATION)
                .setDescription("Duration of one turn segment, as LatencyStats records it")
                .setUnit("s")
                .setExplicitBucketBoundariesAdvice(MetricBuckets.SEGMENT_SECONDS)
                .build());
        instrument = built;
        return built;
    }
}
