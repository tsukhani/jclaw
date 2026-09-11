import llm.LlmProvider;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ProviderConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CircuitBreakers;
import utils.LatencyStats;
import utils.LatencyTrace;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JCLAW-882: per-turn {@code llm_call_count} — the measurement basis for the
 * JCLAW-833 harness-efficiency NFR. {@code tool_round_count} cannot serve it
 * (it misses the turn's first model call and every planner / critic / best-of-N
 * call), so these tests pin the two properties the NFR depends on: a provider
 * dispatch is counted wherever it is issued from, and it is counted exactly once.
 *
 * <p>Every test uses a UNIQUE channel name. {@link LatencyStats} is a JVM-global
 * singleton shared with the concurrently-running latency tests, so a shared
 * channel — or a {@code reset()} — would make these assertions race.
 */
class LlmCallCountTest extends UnitTest {

    /** Refused on connect, so the provider path runs end-to-end without a network wait. */
    private static final String DEAD_PROVIDER_URL = "http://127.0.0.1:1/v1";

    private static final String DEAD_PROVIDER = "jclaw882-unreachable";

    private static LlmProvider deadProvider() {
        return LlmProvider.forConfig(
                new ProviderConfig(DEAD_PROVIDER, DEAD_PROVIDER_URL, "test-key", List.of()));
    }

    /** Every dispatch here fails, and three in a row open the provider's breaker (JCLAW-1167);
     *  the count is taken after admission, so a refused dispatch would count nothing. */
    @BeforeEach
    void forgetTheBreaker() {
        CircuitBreakers.remove(LlmResilience.breakerName(DEAD_PROVIDER));
    }

    /** A finished trace's per-turn value for {@code segment}: {@code sum_ms} is the
     *  raw recorded number (unclamped), which for a count segment is the count itself.
     *  Returns -1 when the segment was never emitted. */
    private static long turnValue(String channel, String segment) {
        var snapshot = LatencyStats.snapshot();
        var byChannel = snapshot.getAsJsonObject(channel);
        if (byChannel == null) return -1L;
        var hist = byChannel.getAsJsonObject(segment);
        return hist == null ? -1L : hist.get("sum_ms").getAsLong();
    }

    /**
     * Per-run unique channel suffix, bounded so the channel fits {@code CHANNEL
     * VARCHAR(32)}. Raw {@code System.nanoTime()} is 15 digits once the host has been
     * up ~28 hours, which pushed the longest prefixes here to 33-35 characters; the
     * oversized insert then surfaced as a failure in whatever test shared the
     * connection, not in this class.
     */
    private static String uniqueSuffix() {
        return Long.toString(System.nanoTime() % 10_000_000_000L);
    }

    private static LatencyTrace startedTrace(String channel) {
        var trace = LatencyTrace.forTurn(channel, null);
        // end() skips early-exit traces; PROLOGUE_DONE is what marks a turn as real.
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        return trace;
    }

    @Test
    void syncProviderDispatchIsCountedEvenWhenTheCallFails() {
        var channel = "llm-calls-sync-" + uniqueSuffix();
        var trace = startedTrace(channel);

        try (var _ = LatencyTrace.bind(trace)) {
            assertThrows(LlmProvider.LlmException.class, () ->
                    deadProvider().chat("m", List.of(ChatMessage.user("hi")), List.of(),
                            16, null, channel));
        }
        trace.end();

        // Counted at dispatch, not on success: the NFR measures the calls the
        // harness decided to make. The four transport retries behind this one
        // dispatch are latency, not four decisions.
        assertEquals(1L, turnValue(channel, "llm_call_count"),
                "one dispatch must count once regardless of outcome");
    }

    @Test
    void streamingProviderDispatchIsCounted() throws Exception {
        var channel = "llm-calls-stream-" + uniqueSuffix();
        var trace = startedTrace(channel);

        try (var _ = LatencyTrace.bind(trace)) {
            var acc = deadProvider().chatStreamAccumulate(
                    "m", List.of(ChatMessage.user("hi")), List.of(), _ -> { }, null, 16, null, channel);
            assertTrue(acc.awaitCompletion(10_000), "stream to a refused port should fail fast");
        }
        trace.end();

        // The stream runs on its own virtual thread and completes on the
        // provider's IO thread — neither carries the binding, so counting has to
        // happen on the dispatching thread or it is lost.
        assertEquals(1L, turnValue(channel, "llm_call_count"));
    }

    @Test
    void everyRoundOfATurnAccumulatesOnTheSameTrace() {
        var channel = "llm-calls-rounds-" + uniqueSuffix();
        var trace = startedTrace(channel);

        try (var _ = LatencyTrace.bind(trace)) {
            var provider = deadProvider();
            for (int i = 0; i < 3; i++) {
                assertThrows(LlmProvider.LlmException.class, () ->
                        provider.chat("m", List.of(ChatMessage.user("hi")), List.of(), 16, null, channel));
            }
        }
        trace.end();

        assertEquals(3L, turnValue(channel, "llm_call_count"),
                "a multi-round turn reports its rounds as one per-turn sample, not three turns");
    }

    @Test
    void cacheServedCallsAreCountedAlongsideTheTotal() {
        var channel = "llm-calls-cached-" + uniqueSuffix();
        var trace = startedTrace(channel);

        try (var _ = LatencyTrace.bind(trace)) {
            LatencyTrace.countLlmCall();
            LatencyTrace.countLlmCall();
            LatencyTrace.countLlmCall();
        }
        trace.noteCachedLlmCall();
        trace.end();

        assertEquals(3L, turnValue(channel, "llm_call_count"));
        assertEquals(1L, turnValue(channel, "llm_call_cached"),
                "a cache-served call costs a fraction of an uncached one — the NFR needs the split");
    }

    @Test
    void aTurnWithNoCacheHitsOmitsTheCachedSegment() {
        var channel = "llm-calls-uncached-" + uniqueSuffix();
        var trace = startedTrace(channel);

        try (var _ = LatencyTrace.bind(trace)) {
            LatencyTrace.countLlmCall();
        }
        trace.end();

        assertEquals(1L, turnValue(channel, "llm_call_count"));
        // LatencyStats clamps recorded values to a minimum of 1, so emitting a
        // literal 0 here would read back as one cache-served call and overstate
        // the cheap share of every uncached turn.
        assertEquals(-1L, turnValue(channel, "llm_call_cached"),
                "zero cache hits must be omitted, not recorded as the clamped value 1");
    }

    @Test
    void dispatchOutsideATurnIsNotBilledToTheLastTurn() {
        var channel = "llm-calls-unbound-" + uniqueSuffix();
        var trace = startedTrace(channel);

        try (var _ = LatencyTrace.bind(trace)) {
            LatencyTrace.countLlmCall();
        }
        // Skill promotion, prompt generation and scheduled summarization dispatch
        // outside any turn; they must not land on whichever turn ran last.
        LatencyTrace.countLlmCall();
        assertNull(LatencyTrace.current(), "the binding must not outlive its scope");
        trace.end();

        assertEquals(1L, turnValue(channel, "llm_call_count"));
    }

    @Test
    void aNestedTurnBillsItselfAndRestoresTheParent() {
        var suffix = System.nanoTime();
        var parentChannel = "llm-calls-parent-" + suffix;
        var childChannel = "llm-calls-child-" + suffix;
        var parent = startedTrace(parentChannel);
        var child = startedTrace(childChannel);

        try (var _ = LatencyTrace.bind(parent)) {
            LatencyTrace.countLlmCall();
            // A subagent turn running inline on one of the parent's tool threads.
            try (var _ = LatencyTrace.bind(child)) {
                LatencyTrace.countLlmCall();
                LatencyTrace.countLlmCall();
            }
            LatencyTrace.countLlmCall();
        }
        parent.end();
        child.end();

        assertEquals(2L, turnValue(parentChannel, "llm_call_count"));
        assertEquals(2L, turnValue(childChannel, "llm_call_count"));
    }

    @Test
    void aTurnHandedToAWorkerThreadStillCollectsItsCalls() throws Exception {
        var channel = "llm-calls-worker-" + uniqueSuffix();
        var trace = startedTrace(channel);

        // The contract ParallelToolExecutor relies on when it hands each tool
        // work unit the turn's binding: a model call a tool makes on its own
        // (a subagent's bootstrap summary, a memory rerank) bills the turn that
        // caused it, even though it dispatches from a different thread.
        var worker = Thread.ofVirtual().start(() -> {
            try (var _ = LatencyTrace.bind(trace)) {
                LatencyTrace.countLlmCall();
            }
        });
        worker.join();
        trace.end();

        assertEquals(1L, turnValue(channel, "llm_call_count"));
    }

    @Test
    void aWorkerThreadNotHandedTheTurnSeesNothing() {
        var channel = "llm-calls-noinherit-" + uniqueSuffix();
        var trace = startedTrace(channel);
        var sawTurn = new AtomicBoolean(true);

        try (var _ = LatencyTrace.bind(trace)) {
            // The binding is deliberately non-inheritable: a thread that spawns
            // its own turn (an async subagent) must open its own trace rather
            // than silently billing whoever started it. That is exactly why
            // ParallelToolExecutor has to hand the binding over explicitly.
            var worker = Thread.ofVirtual().start(() -> sawTurn.set(LatencyTrace.current() != null));
            worker.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("worker join interrupted: " + e);
        }
        trace.end();

        assertFalse(sawTurn.get(), "a spawned thread must not inherit the turn binding");
    }
}
