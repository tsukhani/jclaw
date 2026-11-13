import agents.QueryEmbedPrefetch;
import agents.TurnRouting;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.LatencyStats;
import utils.LatencyTrace;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * JF-364: a routed turn's query embedding runs beside the router's classifier, and the prompt build takes that
 * embedding instead of asking the provider again. Each test passes its own embedder, so none touches the memory
 * store's process-global seams.
 */
class QueryEmbedPrefetchTest extends UnitTest {

    private static final float[] VECTOR = {1f, 0f, 0f, 0f};
    private static final long HOLD_MS = 400;

    private final List<String> embedded = Collections.synchronizedList(new ArrayList<>());

    private Function<String, float[]> embedderHolding(long holdMs) {
        return text -> {
            embedded.add(text);
            sleep(holdMs);
            return VECTOR;
        };
    }

    @Test
    void theEmbeddingRunsBesideTheClassifierAndIsNotRequestedAgain() throws Exception {
        long startNs = System.nanoTime();
        var prefetch = QueryEmbedPrefetch.start("what changed today", null, embedderHolding(HOLD_MS));
        sleep(HOLD_MS); // the classifier's wait on the turn thread
        var embedding = QueryEmbedPrefetch.callWith(prefetch,
                () -> QueryEmbedPrefetch.embed("what changed today", embedderHolding(HOLD_MS)));
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);

        assertArrayEquals(VECTOR, embedding);
        assertEquals(List.of("what changed today"), embedded, "the prompt build must take the started embedding");
        assertTrue(elapsedMs < 2 * HOLD_MS - 100,
                "the two waits overlap: %d ms, where one after the other takes %d".formatted(elapsedMs, 2 * HOLD_MS));
    }

    @Test
    void theStartedEmbeddingIsRecordedOnTheTurnsTrace() {
        var channel = "qep-trace-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        var prefetch = QueryEmbedPrefetch.start("note it", trace, embedderHolding(50));
        QueryEmbedPrefetch.callWith(prefetch, () -> QueryEmbedPrefetch.embed("note it", embedderHolding(50)));
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var segment = LatencyStats.snapshot().getAsJsonObject(channel).getAsJsonObject("prologue_embed_query");
        assertEquals(1L, segment.get("count").getAsLong());
        assertTrue(segment.get("sum_ms").getAsLong() >= 50, segment.toString());
    }

    @Test
    void aDifferentMessageIsEmbeddedInlineNotTakenFromThePrefetch() {
        var prefetch = QueryEmbedPrefetch.start("first message", null, embedderHolding(0));
        var other = new float[] {0f, 1f, 0f, 0f};
        var embedding = QueryEmbedPrefetch.callWith(prefetch,
                () -> QueryEmbedPrefetch.embed("second message", text -> {
                    embedded.add(text);
                    return other;
                }));

        assertArrayEquals(other, embedding);
        assertTrue(embedded.contains("second message"), embedded.toString());
    }

    @Test
    void withNoPrefetchBoundTheEmbeddingIsComputedInline() {
        var embedding = QueryEmbedPrefetch.embed("plain turn", embedderHolding(0));

        assertArrayEquals(VECTOR, embedding);
        assertEquals(List.of("plain turn"), embedded);
    }

    @Test
    void aFailedPrefetchFallsBackToEmbeddingInline() {
        var prefetch = QueryEmbedPrefetch.start("flaky", null, _ -> {
            throw new IllegalStateException("embedding provider down");
        });
        var embedding = QueryEmbedPrefetch.callWith(prefetch,
                () -> QueryEmbedPrefetch.embed("flaky", embedderHolding(0)));

        assertArrayEquals(VECTOR, embedding);
        assertEquals(List.of("flaky"), embedded, "the inline call runs once the started one has failed");
    }

    @Test
    void aTurnOffTheRouterStartsNoPrefetchAndRecordsNoRoute() {
        var agent = new Agent();
        agent.name = "qep-plain-" + System.nanoTime();
        agent.modelProvider = "ollama-cloud";
        agent.modelId = "some-model";
        var conversation = new Conversation();
        conversation.agent = agent;
        var channel = "qep-plain-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);

        assertNull(QueryEmbedPrefetch.startForRoutedTurn(agent, conversation, "hello", trace));
        assertNull(TurnRouting.decide(agent, conversation, "hello", null, "web", trace));
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        assertFalse(LatencyStats.snapshot().getAsJsonObject(channel).has("prologue_route"));
    }

    @Test
    void aRecordedRouteIsEmittedAsItsOwnPrologueSegment() {
        var channel = "qep-route-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        trace.recordRoute(377);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        var segment = LatencyStats.snapshot().getAsJsonObject(channel).getAsJsonObject("prologue_route");
        assertEquals(1L, segment.get("count").getAsLong());
        assertEquals(377L, segment.get("sum_ms").getAsLong());
    }

    @Test
    void aRouteDecidedInNoTimeIsStillRecorded() {
        var channel = "qep-route-zero-" + System.nanoTime();
        var trace = LatencyTrace.forTurn(channel, null);
        trace.recordRoute(0);
        trace.mark(LatencyTrace.PROLOGUE_DONE);
        trace.end();

        assertEquals(1L, LatencyStats.snapshot().getAsJsonObject(channel)
                .getAsJsonObject("prologue_route").get("count").getAsLong());
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
