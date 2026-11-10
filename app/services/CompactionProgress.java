package services;

import org.jspecify.annotations.Nullable;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * What a compaction tells the person waiting on it. A transport binds {@link #SINK} around the turn or command it
 * runs; the gate and {@code /compact} open a tracker for the compaction they start, and {@link SessionCompactor}
 * reports its progress to it without knowing the transport. The sink receives status-frame JSON of the shape
 * {@code {"compaction":{"phase":"start"|"progress"|"done"|"skipped", ...}}}, which the web chat renders as a card and a
 * channel sink turns into its own notice. Unbound, every call is a no-op.
 *
 * <p>The tracker announces a compaction only once the compactor has found a cut, so a conversation that has none
 * flashes nothing on each turn.
 */
public final class CompactionProgress {

    /** Where the frames go. A {@link ScopedValue} so the gate and {@code /compact} need no extra parameter. */
    public static final ScopedValue<Consumer<String>> SINK = ScopedValue.newInstance();

    private static final ScopedValue<CompactionProgress> CURRENT = ScopedValue.newInstance();

    private final @Nullable Consumer<String> boundSink;
    private final int tokensBefore;
    private boolean announced;

    private CompactionProgress(@Nullable Consumer<String> boundSink, int tokensBefore) {
        this.boundSink = boundSink;
        this.tokensBefore = tokensBefore;
    }

    /** A tracker for a compaction of a prompt estimated at {@code tokensBefore} tokens; silent with no {@link #SINK} bound. */
    public static CompactionProgress open(int tokensBefore) {
        return new CompactionProgress(SINK.isBound() ? SINK.get() : null, tokensBefore);
    }

    /** Runs {@code body}, the compaction, with this tracker as the one the compactor reports to. */
    public <T> T around(Supplier<T> body) {
        if (boundSink == null) return body.get();
        return ScopedValue.where(CURRENT, this).call(body::get);
    }

    /** The compactor found {@code rows} to summarize. */
    static void started(int rows) {
        if (CURRENT.isBound()) CURRENT.get().start(rows);
    }

    /** The compactor has summarized {@code done} of {@code total} rows. */
    static void advanced(int done, int total) {
        if (CURRENT.isBound()) CURRENT.get().progress(done, total);
    }

    private void start(int rows) {
        announced = true;
        emit("{\"compaction\":{\"phase\":\"start\",\"tokensBefore\":%d,\"rows\":%d}}".formatted(tokensBefore, rows));
    }

    private void progress(int done, int total) {
        if (!announced) return;
        emit("{\"compaction\":{\"phase\":\"progress\",\"done\":%d,\"total\":%d}}".formatted(done, total));
    }

    /** The compaction ended with the prompt at {@code tokensAfter} tokens after {@code ms} milliseconds. */
    public void done(int tokensAfter, long ms) {
        if (!announced) return;
        emit("{\"compaction\":{\"phase\":\"done\",\"tokensBefore\":%d,\"tokensAfter\":%d,\"ms\":%d}}"
                .formatted(tokensBefore, tokensAfter, ms));
    }

    /** The compaction ended without storing a summary; closes the card a start opened. */
    public void skipped() {
        if (!announced) return;
        emit("{\"compaction\":{\"phase\":\"skipped\"}}");
    }

    private void emit(String frame) {
        if (boundSink != null) boundSink.accept(frame);
    }

    /**
     * The one-line notice a channel shows for a {@code start} frame, such as "Compacting this conversation (~182k
     * tokens)…"; null for any other frame, which a channel has no use for.
     */
    public static @Nullable String startNotice(@Nullable String frame) {
        if (frame == null || !frame.startsWith("{\"compaction\"")) return null;
        try {
            var body = com.google.gson.JsonParser.parseString(frame).getAsJsonObject().getAsJsonObject("compaction");
            if (!"start".equals(body.get("phase").getAsString())) return null;
            return "Compacting this conversation (~%s tokens)…".formatted(tokenLabel(body.get("tokensBefore").getAsInt()));
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static String tokenLabel(int tokens) {
        return tokens >= 1000 ? Math.round(tokens / 1000.0) + "k" : Integer.toString(tokens);
    }
}
