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

    public static final String PROLOGUE_REQUEST_PARSED = "prologue_request_parsed";
    public static final String PROLOGUE_CONV_RESOLVED = "prologue_conv_resolved";
    public static final String PROLOGUE_PROMPT_ASSEMBLED = "prologue_prompt_assembled";
    public static final String PROLOGUE_DONE = "prologue_done";
    public static final String FIRST_TOKEN = "first_token";
    public static final String STREAM_BODY_END = "stream_body_end";
    public static final String PERSIST_DONE = "persist_done";
    public static final String TERMINAL_SENT = "terminal_sent";

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

        // Prologue sub-segments — missing marks are OK, we just skip that sub-bucket.
        // The sequence is: startNs → request_parsed → conv_resolved → prompt_assembled → prologue_done.
        // Each emitted sub-segment measures the gap between adjacent marks so they sum back
        // to `prologue` (modulo integer ms rounding).
        Long requestParsed = marks.get(PROLOGUE_REQUEST_PARSED);
        Long convResolved = marks.get(PROLOGUE_CONV_RESOLVED);
        Long promptAssembled = marks.get(PROLOGUE_PROMPT_ASSEMBLED);

        if (requestParsed != null) {
            LatencyStats.record("prologue_parse", nsToMs(requestParsed - startNs));
        }
        if (requestParsed != null && convResolved != null) {
            LatencyStats.record("prologue_conv", nsToMs(convResolved - requestParsed));
        }
        if (convResolved != null && promptAssembled != null) {
            LatencyStats.record("prologue_prompt", nsToMs(promptAssembled - convResolved));
        }
        if (promptAssembled != null) {
            LatencyStats.record("prologue_tools", nsToMs(prologueDone - promptAssembled));
        }

        Long firstToken = marks.get(FIRST_TOKEN);
        if (firstToken != null) {
            LatencyStats.record("ttft", nsToMs(firstToken - prologueDone));
        }

        Long streamBodyEnd = marks.get(STREAM_BODY_END);
        if (firstToken != null && streamBodyEnd != null) {
            LatencyStats.record("stream_body", nsToMs(streamBodyEnd - firstToken));
        }

        // `persist` is no longer derived here — it's recorded directly by AgentRunner
        // because it now runs AFTER the terminal SSE frame (off the user-visible path),
        // and trace.end() fires as soon as the terminal SSE write returns. The legacy
        // PERSIST_DONE constant is retained so older callers / tests that still set it
        // can produce a persist sample via this path.
        Long persistDone = marks.get(PERSIST_DONE);
        if (streamBodyEnd != null && persistDone != null) {
            LatencyStats.record("persist", nsToMs(persistDone - streamBodyEnd));
        }

        // `terminal_tail` measures the gap between stream_body_end and the terminal
        // SSE frame being written to the response — that is, the wall time for the
        // final usage-logging callbacks, onStatus + onComplete dispatch, and the
        // terminal writeChunk itself. This is part of `total` (pre-refactor it was
        // hidden behind the DB persist); surfacing it lets us confirm the post-stream
        // emit path stays cheap.
        Long terminalSent = marks.get(TERMINAL_SENT);
        if (streamBodyEnd != null && terminalSent != null) {
            LatencyStats.record("terminal_tail", nsToMs(terminalSent - streamBodyEnd));
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
