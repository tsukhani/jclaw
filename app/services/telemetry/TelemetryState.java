package services.telemetry;

import io.opentelemetry.api.OpenTelemetry;

/**
 * What {@link OtelPlayPlugin} reads on every request, published by {@link OtelRuntime}.
 *
 * <p>Deliberately references nothing in {@code app/} beyond this package: a plugin listed
 * in {@code conf/play.plugins} is compiled by ECJ before the application compile, and
 * every application type it reaches is dragged into that early pass. Reaching
 * {@code OtelRuntime} — and through it {@code ConfigService} — collided with the main
 * compile ("type … is already defined"). Two volatile fields keep the plugin's graph to
 * the OpenTelemetry API.
 */
final class TelemetryState {

    static volatile OpenTelemetry api = OpenTelemetry.noop();
    static volatile boolean enabled;
    static volatile boolean agentAttached;

    private TelemetryState() {}
}
