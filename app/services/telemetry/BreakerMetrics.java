package services.telemetry;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.trace.Span;
import org.jspecify.annotations.Nullable;

/**
 * The {@code jclaw.breaker.transitions} counter and a matching span event, one per
 * circuit-breaker state change (JCLAW-1170).
 *
 * <p>The alarm target is the state change rather than the failures underneath it: an open
 * breaker turns every call away in microseconds, so latency graphs improve and the error
 * rate stays flat while real work is failing. Counting transitions also survives a scrape
 * interval longer than {@code llm.breaker.wait-seconds} — sampling state would miss a
 * breaker that opened and closed between two scrapes.
 */
public final class BreakerMetrics {

    public static final String TRANSITIONS = "jclaw.breaker.transitions";
    public static final AttributeKey<String> BREAKER = AttributeKey.stringKey("jclaw.breaker");
    public static final AttributeKey<String> STATE = AttributeKey.stringKey("jclaw.breaker.state");
    public static final AttributeKey<String> REASON = AttributeKey.stringKey("jclaw.breaker.reason");
    public static final AttributeKey<String> FROM = AttributeKey.stringKey("jclaw.breaker.from");

    /** Span-event name for one transition, on whatever span the transition happened under. */
    public static final String EVENT = "circuit_breaker.transition";

    private static final String SCOPE = "jclaw";

    private record Instrument(OpenTelemetry api, LongCounter counter) {}

    private static volatile @Nullable Instrument instrument;

    private BreakerMetrics() {}

    /** With export off this is one volatile read. */
    public static void recordTransition(String breaker, String from, String to, String reason) {
        if (!TelemetryState.enabled) {
            return;
        }
        var attrs = Attributes.of(BREAKER, breaker, STATE, to, REASON, reason);
        instrument().counter().add(1, attrs);
        Span.current().addEvent(EVENT, attrs.toBuilder().put(FROM, from).build());
    }

    private static Instrument instrument() {
        var api = TelemetryState.api;
        var current = instrument;
        if (current != null && current.api() == api) {
            return current;
        }
        var built = new Instrument(api, api.getMeter(SCOPE).counterBuilder(TRANSITIONS)
                .setDescription("Circuit-breaker state changes, by breaker, target state and reason")
                .setUnit("{transition}")
                .build());
        instrument = built;
        return built;
    }
}
