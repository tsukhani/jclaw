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

    private final String channel;
    private final long startNs;
    private final long acceptedAtNs;
    private final ConcurrentHashMap<String, Long> marks = new ConcurrentHashMap<>();
    private final AtomicLong toolExecMs = new AtomicLong();
    private final AtomicInteger toolRoundCount = new AtomicInteger();
    private volatile boolean ended;

    public LatencyTrace() {
        this(null, 0L);
    }

    private LatencyTrace(String channel, long acceptedAtNs) {
        this.channel = channel;
        this.startNs = System.nanoTime();
        this.acceptedAtNs = acceptedAtNs;
    }

    /**
     * Build a trace for one agent turn on a named transport. {@code channel}
     * partitions the resulting histograms so each transport's distribution
     * is visible separately (JCLAW-102) — pass "web", "telegram", "task", etc.
     * Callers that know when the request hit the process (e.g. web controllers
     * reading the Netty-set {@code acceptedAtNanos} stamp) pass that nanoTime
     * in so the {@code queue_wait} segment gets populated. Channels that
     * don't have a pre-runner timestamp (Telegram polling, scheduled tasks)
     * pass {@code null} — every other segment is still captured.
     */
    public static LatencyTrace forTurn(String channel, Long acceptedAtNs) {
        return new LatencyTrace(channel, acceptedAtNs == null ? 0L : acceptedAtNs);
    }

    /**
     * Pull the {@code acceptedAtNanos} stamp set by Play's Netty handler out
     * of the current request, or {@code null} if no request is bound to this
     * thread (e.g. background jobs, sub-agent spawns). Used by web entrypoints
     * so they can forward the stamp to {@link #forTurn} across a thread hop.
     */
    public static Long acceptedAtNsFromCurrentRequest() {
        var req = play.mvc.Http.Request.current();
        if (req != null && req.args != null && req.args.get("acceptedAtNanos") instanceof Long ns) {
            return ns;
        }
        return null;
    }

    /** Record a named mark. First writer wins; subsequent calls are no-ops. */
    public void mark(String name) {
        marks.putIfAbsent(name, System.nanoTime());
    }

    /**
     * JCLAW-200 / loadtest: time the LLM spent emitting tokens, in ms — i.e.
     * {@link #STREAM_BODY_END} minus {@link #FIRST_TOKEN}. Returns 0 when
     * either mark is missing (e.g. on an early-exit error path), so callers
     * can treat 0 as "data unavailable" without a null check.
     *
     * <p>This is the denominator for "tokens-per-second emitted by the LLM",
     * which excludes the time-to-first-token wait. Pair with the message's
     * completion-token count to compute realized generation rate.
     */
    public long streamBodyMs() {
        Long firstToken = marks.get(FIRST_TOKEN);
        Long streamEnd = marks.get(STREAM_BODY_END);
        if (firstToken == null || streamEnd == null) return 0L;
        return Math.max(0L, nsToMs(streamEnd - firstToken));
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
            LatencyStats.record(channel, "queue_wait", nsToMs(queueNs));
        }

        LatencyStats.record(channel, "total", nsToMs(endNs - startNs));
        LatencyStats.record(channel, "prologue", nsToMs(prologueDone - startNs));

        // Prologue sub-segments — missing marks are OK, we just skip that sub-bucket.
        // The sequence is: startNs → request_parsed → conv_resolved → prompt_assembled → prologue_done.
        // Each emitted sub-segment measures the gap between adjacent marks so they sum back
        // to `prologue` (modulo integer ms rounding).
        Long requestParsed = marks.get(PROLOGUE_REQUEST_PARSED);
        Long convResolved = marks.get(PROLOGUE_CONV_RESOLVED);
        Long promptAssembled = marks.get(PROLOGUE_PROMPT_ASSEMBLED);

        if (requestParsed != null) {
            LatencyStats.record(channel, "prologue_parse", nsToMs(requestParsed - startNs));
        }
        if (requestParsed != null && convResolved != null) {
            LatencyStats.record(channel, "prologue_conv", nsToMs(convResolved - requestParsed));
        }
        if (convResolved != null && promptAssembled != null) {
            LatencyStats.record(channel, "prologue_prompt", nsToMs(promptAssembled - convResolved));
        }
        if (promptAssembled != null) {
            LatencyStats.record(channel, "prologue_tools", nsToMs(prologueDone - promptAssembled));
        }

        Long firstToken = marks.get(FIRST_TOKEN);
        if (firstToken != null) {
            LatencyStats.record(channel, "ttft", nsToMs(firstToken - prologueDone));
        }

        Long streamBodyEnd = marks.get(STREAM_BODY_END);
        if (firstToken != null && streamBodyEnd != null) {
            LatencyStats.record(channel, "stream_body", nsToMs(streamBodyEnd - firstToken));
        }

        // `persist` is no longer derived here — it's recorded directly by AgentRunner
        // because it now runs AFTER the terminal SSE frame (off the user-visible path),
        // and trace.end() fires as soon as the terminal SSE write returns. The legacy
        // PERSIST_DONE constant is retained so older callers / tests that still set it
        // can produce a persist sample via this path.
        Long persistDone = marks.get(PERSIST_DONE);
        if (streamBodyEnd != null && persistDone != null) {
            LatencyStats.record(channel, "persist", nsToMs(persistDone - streamBodyEnd));
        }

        // `terminal_tail` measures the gap between stream_body_end and the terminal
        // SSE frame being written to the response — that is, the wall time for the
        // final usage-logging callbacks, onStatus + onComplete dispatch, and the
        // terminal writeChunk itself. This is part of `total` (pre-refactor it was
        // hidden behind the DB persist); surfacing it lets us confirm the post-stream
        // emit path stays cheap.
        Long terminalSent = marks.get(TERMINAL_SENT);
        if (streamBodyEnd != null && terminalSent != null) {
            LatencyStats.record(channel, "terminal_tail", nsToMs(terminalSent - streamBodyEnd));
        }

        if (toolRoundCount.get() > 0) {
            LatencyStats.record(channel, "tool_exec", toolExecMs.get());
            LatencyStats.record(channel, "tool_round_count", toolRoundCount.get());
        }
    }

    private static long nsToMs(long ns) {
        return ns / 1_000_000L;
    }
}
