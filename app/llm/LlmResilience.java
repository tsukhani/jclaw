package llm;

import llm.LlmProvider.LlmException;
import utils.CircuitBreaker;
import utils.CircuitBreakers;
import utils.PlayConfig;

import java.util.function.Supplier;

/**
 * Per-provider circuit breaker for the synchronous chat path (JCLAW-1167).
 *
 * <p>{@link #guard} wraps a whole {@link LlmProvider#chat} call — the retry loop
 * inside it included — so an exhausted chat records exactly one failure. Recording
 * per attempt instead would multiply every failure by four and silently move the
 * configured threshold.
 *
 * <p>An open breaker throws before the wire call, in microseconds rather than after
 * four attempts and their backoff, which is what leaves
 * {@link LlmProvider#chatWithFailover} enough of the turn to reach the secondary.
 * The failure is an {@link LlmException.ServerError}: the breaker only opens because
 * the provider was failing, and failover triggers on any {@code LlmException}.
 *
 * <p>Breakers are shared by provider name through {@link CircuitBreakers}, so one
 * provider's outage cannot reach another's. Streaming chat is deliberately not
 * guarded here — that is JCLAW-1169.
 */
public final class LlmResilience {

    private LlmResilience() {}

    /** Registry name for a provider's chat breaker; prefixed so it cannot collide with an MCP server's. */
    public static String breakerName(String providerName) {
        return "llm:" + providerName;
    }

    /**
     * Breaker tuning from {@code llm.breaker.*}. Read on every lookup but applied only
     * where the name is first minted ({@link CircuitBreakers#get}), so an edit reaches a
     * provider that has not been called yet and a restart reaches the rest.
     */
    public static CircuitBreaker.Config config() {
        return CircuitBreaker.Config.of(
                        PlayConfig.intOr("llm.breaker.window", 100),
                        PlayConfig.intOr("llm.breaker.failure-rate", 50) / 100.0,
                        PlayConfig.intOr("llm.breaker.min-calls", 20),
                        PlayConfig.longOr("llm.breaker.wait-seconds", 60) * 1000L)
                .withHalfOpenPermits(PlayConfig.intOr("llm.breaker.half-open-probes", 3));
    }

    public static CircuitBreaker breakerFor(String providerName) {
        return CircuitBreakers.get(breakerName(providerName), config());
    }

    /**
     * Run one whole chat call under {@code providerName}'s breaker, recording a single
     * outcome for it.
     *
     * @throws LlmException.ServerError immediately, without running {@code call}, when the
     *                                  breaker is open
     */
    public static <T> T guard(String providerName, Supplier<T> call) {
        var breaker = breakerFor(providerName);
        if (!breaker.allowRequest()) {
            throw new LlmException.ServerError(
                    "Circuit breaker open for " + providerName + ": not calling it until it recovers");
        }
        var reported = false;
        try {
            var result = call.get();
            breaker.recordSuccess();
            reported = true;
            return result;
        } catch (LlmException e) {
            // JCLAW-1166: a ClientError is a request this codebase got wrong, so charging it to
            // the provider would trip the breaker on our own bug rather than on an outage.
            if (!(e instanceof LlmException.ClientError)) {
                breaker.recordFailure();
                reported = true;
            }
            throw e;
        } finally {
            // A HALF_OPEN probe holds a permit until it reports an outcome; stranding one
            // wedges the breaker shut for good, since nothing re-enters HALF_OPEN from
            // HALF_OPEN. A provider that answered 4xx is answering, so report success.
            if (!reported && breaker.state() == CircuitBreaker.State.HALF_OPEN) breaker.recordSuccess();
        }
    }
}
