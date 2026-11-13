package agents;

import io.opentelemetry.context.Context;
import memory.MemoryStoreFactory;
import models.Agent;
import models.Conversation;
import org.jspecify.annotations.Nullable;
import utils.LatencyTrace;

import java.lang.ScopedValue.CallableOp;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;

/**
 * A routed turn's query embedding, started before the router's classifier call so that wait covers it (JF-364). The
 * embedding needs only the user's message, never the chosen model, yet both prologue builders ran it after the route:
 * the classifier's 377 ms (p50, the Main Agent over 14 days) and the embedding's 68 ms then added up.
 *
 * <p>The started embedding is handed to the prompt build through a {@link ScopedValue} bound for the turn, not left
 * in the embedding cache for it to find: that cache is not single-flight, so a build that asked while the request was
 * still in flight would send it a second time.
 */
public final class QueryEmbedPrefetch {

    private static final ScopedValue<QueryEmbedPrefetch> BOUND = ScopedValue.newInstance();

    private final String text;
    private final CompletableFuture<float @Nullable []> result = new CompletableFuture<>();

    private QueryEmbedPrefetch(String text) {
        this.text = text;
    }

    /** The embedding of {@code userMessage}, started on a virtual thread when the turn is on the router; else null. */
    public static @Nullable QueryEmbedPrefetch startForRoutedTurn(Agent agent, Conversation conversation,
                                                                  String userMessage, @Nullable LatencyTrace trace) {
        if (!TurnRouting.usesRouter(agent, conversation)) return null;
        return start(userMessage, trace);
    }

    /** Start embedding {@code text} now; {@code trace} receives its time, as an inline embedding records it. */
    public static QueryEmbedPrefetch start(String text, @Nullable LatencyTrace trace) {
        return start(text, trace, QueryEmbedPrefetch::embedQuery);
    }

    /** {@link #start(String, LatencyTrace)} with {@code embedder} standing in for the memory store's. */
    public static QueryEmbedPrefetch start(String text, @Nullable LatencyTrace trace,
                                           Function<String, float @Nullable []> embedder) {
        var prefetch = new QueryEmbedPrefetch(text);
        Thread.ofVirtual().name("query-embed").start(Context.current().wrap(() -> {
            try (var _ = LatencyTrace.bind(trace)) {
                prefetch.result.complete(timedEmbed(text, embedder));
            } catch (RuntimeException | Error e) {
                prefetch.result.completeExceptionally(e);
            }
        }));
        return prefetch;
    }

    /** Run {@code body} with {@code prefetch} bound, so {@link #embed} inside it returns that embedding. */
    public static <R, X extends Throwable> R callWith(@Nullable QueryEmbedPrefetch prefetch, CallableOp<R, X> body)
            throws X {
        return prefetch == null ? body.call() : ScopedValue.where(BOUND, prefetch).call(body);
    }

    /**
     * The query embedding of {@code text}: the one this turn started, waited for, when it is for the same text and did
     * not fail; otherwise computed here, as before the prefetch.
     */
    public static float @Nullable [] embed(String text) {
        return embed(text, QueryEmbedPrefetch::embedQuery);
    }

    /** {@link #embed(String)} with {@code embedder} standing in for the memory store's. */
    public static float @Nullable [] embed(String text, Function<String, float @Nullable []> embedder) {
        var prefetch = BOUND.isBound() ? BOUND.get() : null;
        if (prefetch != null && prefetch.text.equals(text)) {
            try {
                return prefetch.result.join();
            } catch (CompletionException _) {
                // The fork's failure is the inline call's to report, with the cause it gives today.
            }
        }
        return timedEmbed(text, embedder);
    }

    private static float @Nullable [] timedEmbed(String text, Function<String, float @Nullable []> embedder) {
        long startNs = System.nanoTime();
        var embedding = embedder.apply(text);
        LatencyTrace.recordQueryEmbed((System.nanoTime() - startNs) / 1_000_000L);
        return embedding;
    }

    private static float @Nullable [] embedQuery(String text) {
        return MemoryStoreFactory.get().embedQuery(text);
    }
}
