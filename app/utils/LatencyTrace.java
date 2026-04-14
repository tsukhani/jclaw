package utils;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request latency trace: records nanoTime marks and tool-round
 * accumulations, then emits segment durations to {@link LatencyStats}
 * on {@link #end()}.
 *
 * <p>Marks are written from multiple threads (the Play worker, the
 * streaming virtual thread, and the HTTP client's IO thread that
 * invokes the token callback), so all state is thread-safe.
 */
public final class LatencyTrace {

    public static final String PROLOGUE_DONE = "prologue_done";
    public static final String FIRST_TOKEN = "first_token";
    public static final String STREAM_BODY_END = "stream_body_end";
    public static final String PERSIST_DONE = "persist_done";

    private final long startNs;
    private final long acceptedAtNs;
    private final ConcurrentHashMap<String, Long> marks = new ConcurrentHashMap<>();
    private final AtomicLong toolExecMs = new AtomicLong();
    private final AtomicInteger toolRoundCount = new AtomicInteger();
    private volatile boolean ended;

    public LatencyTrace() {
        this(0L);
    }

    private LatencyTrace(long acceptedAtNs) {
        this.startNs = System.nanoTime();
        this.acceptedAtNs = acceptedAtNs;
    }

    /**
     * Build a trace from the current Play request, reading the
     * {@code acceptedAtNanos} stamp set by the framework's Netty handler.
     * When the stamp is present, a {@code queue_wait} segment is recorded
     * measuring the gap between Netty acceptance and controller entry —
     * the symptom of invocation-pool saturation.
     */
    public static LatencyTrace fromCurrentRequest() {
        var req = play.mvc.Http.Request.current();
        if (req != null && req.args != null) {
            Object accepted = req.args.get("acceptedAtNanos");
            if (accepted instanceof Long ns) {
                return new LatencyTrace(ns);
            }
        }
        return new LatencyTrace();
    }

    /** Record a named mark. First writer wins; subsequent calls are no-ops. */
    public void mark(String name) {
        marks.putIfAbsent(name, System.nanoTime());
    }

    /** Record the wall-clock cost of a single tool-execution round. */
    public void addToolRound(long elapsedMs) {
        toolExecMs.addAndGet(elapsedMs);
        toolRoundCount.incrementAndGet();
    }

    /**
     * Finalize the trace and submit segment durations to {@link LatencyStats}.
     * Idempotent. Early-exit traces (no {@code PROLOGUE_DONE}) are skipped so
     * histograms reflect actual end-to-end streams, not queue rejections or
     * provider-missing errors.
     */
    public void end() {
        if (ended) return;
        ended = true;
        long endNs = System.nanoTime();

        Long prologueDone = marks.get(PROLOGUE_DONE);
        if (prologueDone == null) return;

        if (acceptedAtNs > 0) {
            // Clamp to 0 defensively: nanoTime is monotonic within a JVM, but
            // the stamp is captured on the Netty thread and read here from the
            // virtual/worker thread — any skew we see would be a bug worth
            // surfacing as 0 rather than a negative outlier.
            long queueNs = Math.max(0L, startNs - acceptedAtNs);
            LatencyStats.record("queue_wait", nsToMs(queueNs));
        }

        LatencyStats.record("total", nsToMs(endNs - startNs));
        LatencyStats.record("prologue", nsToMs(prologueDone - startNs));

        Long firstToken = marks.get(FIRST_TOKEN);
        if (firstToken != null) {
            LatencyStats.record("ttft", nsToMs(firstToken - prologueDone));
        }

        Long streamBodyEnd = marks.get(STREAM_BODY_END);
        if (firstToken != null && streamBodyEnd != null) {
            LatencyStats.record("stream_body", nsToMs(streamBodyEnd - firstToken));
        }

        Long persistDone = marks.get(PERSIST_DONE);
        if (streamBodyEnd != null && persistDone != null) {
            LatencyStats.record("persist", nsToMs(persistDone - streamBodyEnd));
        }

        if (toolRoundCount.get() > 0) {
            LatencyStats.record("tool_exec", toolExecMs.get());
            LatencyStats.record("tool_round_count", toolRoundCount.get());
        }
    }

    private static long nsToMs(long ns) {
        return ns / 1_000_000L;
    }
}
