package services;

import services.telemetry.BreakerMetrics;
import utils.CircuitBreaker;

import java.util.function.Consumer;

/**
 * What a circuit-breaker state change does: an operator-visible event-log line plus the
 * {@code jclaw.breaker.transitions} metric and span event (JCLAW-1170).
 *
 * <p>One implementation for every breaker in {@link utils.CircuitBreakers}, because two
 * subsystems each logging their own transitions is exactly the drift this story exists to
 * close — the LLM breakers had no transition record at all and the MCP ones had no metric.
 * A subsystem contributes only how it names itself.
 *
 * <p>A manual trip is reported as an operator decision, never as a failure rate: an
 * operator who isolated a provider must not later read the log and conclude the provider
 * broke. The distinction lasts as long as the manual trip does — once the cooldown elapses
 * and a probe fails, the breaker is open on evidence and says so.
 */
public final class BreakerAlarms {

    /** Event-log category for every breaker transition, whichever subsystem owns the breaker. */
    public static final String CATEGORY = "CIRCUIT_BREAKER";

    private BreakerAlarms() {}

    /**
     * @param name    registry name, e.g. {@code llm:openai}
     * @param subject how the subsystem names itself in a log line, e.g. {@code LLM provider 'openai'}
     */
    public static Consumer<CircuitBreaker.Transition> listener(String name, String subject) {
        return transition -> observe(name, subject, transition);
    }

    public static void observe(String name, String subject, CircuitBreaker.Transition transition) {
        BreakerMetrics.recordTransition(
                name, transition.from().name(), transition.to().name(), transition.reason().name());
        var message = describe(subject, transition);
        // An open breaker is a partial outage whoever opened it, so a manual trip warns too;
        // what changes with the operator is the wording, not the severity.
        if (transition.to() == CircuitBreaker.State.OPEN) EventLogger.warn(CATEGORY, message);
        else EventLogger.info(CATEGORY, message);
    }

    /** The operator-facing line one transition produces. Public so a test can assert the
     *  forced-versus-autonomous wording without reading it back out of the event log. */
    public static String describe(String subject, CircuitBreaker.Transition transition) {
        var stats = transition.stats();
        return switch (transition.to()) {
            case OPEN -> transition.reason() == CircuitBreaker.Reason.MANUAL_TRIP
                    ? "%s isolated by the operator: calls are turned away until it is restored"
                            .formatted(subject)
                    : "%s tripped on %s: %d of the last %d calls failed, %d were slow"
                            .formatted(subject, transition.reason(), stats.failures(),
                                    stats.samples(), stats.slowCalls());
            case CLOSED -> transition.reason() == CircuitBreaker.Reason.MANUAL_RESET
                    ? "%s restored by the operator".formatted(subject)
                    : "%s recovered: its probes succeeded and calls are flowing again".formatted(subject);
            case HALF_OPEN -> "%s is being probed: the next few calls decide whether it reopens"
                    .formatted(subject);
        };
    }
}
