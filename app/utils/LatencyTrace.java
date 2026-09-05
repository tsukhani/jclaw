package utils;

import com.google.errorprone.annotations.MustBeClosed;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import play.mvc.Http;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
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
    public static final String PROLOGUE_PROMPT_BUILT = "prologue_prompt_built";
    public static final String PROLOGUE_PROMPT_ASSEMBLED = "prologue_prompt_assembled";
    public static final String PROLOGUE_DONE = "prologue_done";
    /** First output of any kind — content, reasoning, or a tool-call delta. See {@link #recordStreamSegments}. */
    public static final String FIRST_SIGNAL = "first_signal";
    public static final String FIRST_TOKEN = "first_token";
    public static final String STREAM_BODY_END = "stream_body_end";
    public static final String PERSIST_DONE = "persist_done";
    public static final String TERMINAL_SENT = "terminal_sent";

    private final @Nullable String channel;
    // Set once the conversation/agent is resolved (AgentRunner, at PROLOGUE_CONV_RESOLVED).
    // Tags every persisted segment sample so the dashboard's agent filter works (JCLAW-515).
    private volatile @Nullable String agentId;
    private final long startNs;
    private final long acceptedAtNs;
    private final ConcurrentHashMap<String, Long> marks = new ConcurrentHashMap<>();
    private final AtomicLong toolExecMs = new AtomicLong();
    private final AtomicLong persistMs = new AtomicLong();
    private final AtomicLong queryEmbedMs = new AtomicLong();
    private final AtomicBoolean reasoningSeen = new AtomicBoolean();
    private final AtomicLong memoryRecallMs = new AtomicLong();
    private final AtomicInteger memoryRecallCount = new AtomicInteger();
    private final AtomicInteger toolRoundCount = new AtomicInteger();
    private final AtomicInteger llmCallCount = new AtomicInteger();
    private final AtomicInteger llmCachedCallCount = new AtomicInteger();
    private final AtomicInteger toolVerifyCount = new AtomicInteger();
    private final AtomicInteger toolVerifyFailedCount = new AtomicInteger();
    private final AtomicBoolean ended = new AtomicBoolean();

    // Turn binding for the provider dispatch point (JCLAW-882). LlmProvider holds
    // no reference to the turn's trace, and threading one through every chat /
    // stream signature would put the counter at N call sites — exactly where a
    // later planner or critic story could add an uncounted one. A thread binding
    // lets the single dispatch point resolve the owning turn instead.
    //
    // Deliberately a plain ThreadLocal, not inheritable: a thread the turn does
    // not explicitly hand the binding to (an async subagent running its own turn)
    // must open its own trace rather than silently billing the parent's.
    private static final ThreadLocal<LatencyTrace> CURRENT = new ThreadLocal<>();

    public LatencyTrace() {
        this(null, 0L);
    }

    private LatencyTrace(@Nullable String channel, long acceptedAtNs) {
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
    public static @NonNull LatencyTrace forTurn(@Nullable String channel, @Nullable Long acceptedAtNs) {
        return new LatencyTrace(channel, acceptedAtNs == null ? 0L : acceptedAtNs);
    }

    /** Tag this turn's persisted segment samples with the owning agent id (JCLAW-515).
     *  Called by AgentRunner once the conversation/agent is resolved. A null id leaves
     *  the samples agent-less — they still record, just without agent attribution. */
    public void agentId(@Nullable String id) {
        this.agentId = id;
    }

    /**
     * Pull the {@code acceptedAtNanos} stamp set by Play's Netty handler out
     * of the current request, or {@code null} if no request is bound to this
     * thread (e.g. background jobs, sub-agent spawns). Used by web entrypoints
     * so they can forward the stamp to {@link #forTurn} across a thread hop.
     */
    public static @Nullable Long acceptedAtNsFromCurrentRequest() {
        var req = Http.Request.current();
        if (req != null && req.args != null && req.args.get("acceptedAtNanos") instanceof Long ns) {
            return ns;
        }
        return null;
    }

    /** Record a named mark. First writer wins; subsequent calls are no-ops. */
    public void mark(@NonNull String name) {
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

    /**
     * Record one memory recall's wall-clock cost against the calling thread's turn.
     *
     * <p>Memory sat inside {@code prologue_prompt} with no breakdown of its own, so the
     * only way to answer "what does recall cost a turn" was to measure it by hand from
     * outside. Measured that way on a 606-memory corpus it is 18.8 ms p50 — of which the
     * embedding round-trip is roughly half — against a 66 ms prologue_prompt. Small, but
     * invisible, and the embedding leg is a network call whose cost moves with the
     * provider.
     *
     * <p>An accumulator rather than a mark pair, because recall is nested inside prompt
     * assembly rather than a phase boundary in the prologue sequence. Counts every recall
     * in the turn, including one the {@code memory} tool makes mid-turn — hence the
     * companion count segment, which separates one prompt-assembly recall from several.
     *
     * <p>No-op when no trace is bound, so the introspection endpoint and the eval harness
     * do not bill a turn that is not theirs.
     */
    public static void recordMemoryRecall(long elapsedMs) {
        var trace = CURRENT.get();
        if (trace == null) return;
        trace.memoryRecallMs.addAndGet(elapsedMs);
        trace.memoryRecallCount.incrementAndGet();
    }

    /**
     * Record the query-embedding round-trip that precedes prompt assembly.
     *
     * <p>It is a network call sitting inside {@code prologue_assemble} and counted by
     * nothing else — {@code memory_recall} covers the search, not the embed. Measured on
     * the live instance it is roughly half the segment: assembling with a novel user
     * message costs ~85 ms against ~15 ms when the message repeats, and the embed alone
     * times at 32-41 ms novel versus ~5 ms once memoized. Repeating a message is a test
     * artifact; real turns are always novel, so the segment cannot be read without this.
     */
    public static void recordQueryEmbed(long elapsedMs) {
        var trace = CURRENT.get();
        if (trace == null) return;
        trace.queryEmbedMs.addAndGet(elapsedMs);
    }


    /** Record the wall-clock cost of a single tool-execution round. */
    public void addToolRound(long elapsedMs) {
        toolExecMs.addAndGet(elapsedMs);
        toolRoundCount.incrementAndGet();
    }

    /**
     * Record the DB write that persists the assistant message.
     *
     * <p>Recorded through the trace rather than straight to {@link LatencyStats} so
     * {@code terminal_tail} can subtract it: the write happens between
     * {@code STREAM_BODY_END} and {@code TERMINAL_SENT}, so reporting both as measured
     * would count the same milliseconds twice for anyone summing the segments.
     */
    public void recordPersist(long elapsedMs) {
        persistMs.addAndGet(elapsedMs);
    }

    /** Note that the model emitted reasoning before any content token. */
    public void noteReasoning() {
        reasoningSeen.set(true);
    }

    /**
     * Bind {@code trace} to the calling thread so {@link #countLlmCall()} at the
     * provider dispatch point can attribute a call to it. Close the returned
     * binding on the same thread — it restores whatever was bound before, so a
     * nested turn (a subagent turn running inline on a parent's tool thread)
     * unwinds back to the parent's trace instead of leaving the thread unbound.
     *
     * <p>{@code null} is accepted and binds nothing, so dispatch paths that may
     * or may not be inside a turn need no guard of their own.
     */
    @MustBeClosed
    public static @NonNull Binding bind(@Nullable LatencyTrace trace) {
        var prev = CURRENT.get();
        if (trace == null) CURRENT.remove();
        else CURRENT.set(trace);
        return () -> {
            if (prev == null) CURRENT.remove();
            else CURRENT.set(prev);
        };
    }

    /** Restores the thread's previous trace binding. See {@link #bind}. */
    @FunctionalInterface
    public interface Binding extends AutoCloseable {
        @Override void close();
    }

    /** The trace bound to the calling thread, or {@code null} outside a turn. */
    public static @Nullable LatencyTrace current() {
        return CURRENT.get();
    }

    /**
     * Count one chat request dispatched to a provider against the turn bound to
     * the calling thread (JCLAW-882). Called from the provider's two dispatch
     * entrypoints, so a planner pass, a critic pass, or a best-of-N sample cannot
     * add a model call without moving this counter — which is the whole point:
     * {@code tool_round_count} misses every one of them.
     *
     * <p>Counts logical dispatches, not HTTP attempts. A transport retry inside a
     * single dispatch (5xx, clamped 429 backoff) is latency, not a call the
     * harness chose to make; failing over to a second provider is a second
     * dispatch and does count, because a second model genuinely ran.
     *
     * <p>No-op outside a turn — skill promotion, prompt generation and scheduled
     * summarization are not part of anyone's turn, so they have nothing to bill.
     */
    public static void countLlmCall() {
        var trace = CURRENT.get();
        if (trace != null) trace.llmCallCount.incrementAndGet();
    }

    /**
     * Model calls counted against this turn so far (JCLAW-883). Readable without
     * {@link #end()} because an eval sweep needs the number but must not emit the
     * turn into {@link LatencyStats} — hundreds of eval turns landing in the
     * request-path histograms would skew the very baseline JCLAW-833 measures
     * against. Capture binds a trace, runs the turn, reads this, and drops the
     * trace unended.
     */
    public int llmCallCount() {
        return llmCallCount.get();
    }

    /**
     * Count one verified tool result against the turn bound to the calling thread
     * (JCLAW-836). Recorded from the tool-result commit phase, which runs on the
     * turn's own thread rather than the dispatch virtual threads, so the binding is
     * the same one {@link #countLlmCall()} uses.
     *
     * <p>Both a total and a failure count, because a failure count alone cannot be
     * read as a rate — a quiet turn and a flawless one would look identical. No-op
     * outside a turn, matching {@code countLlmCall}.
     */
    public static void countToolVerification(boolean failed) {
        var trace = CURRENT.get();
        if (trace == null) return;
        trace.toolVerifyCount.incrementAndGet();
        if (failed) trace.toolVerifyFailedCount.incrementAndGet();
    }

    /**
     * Note that the call that just completed had its prompt served (at least in
     * part) from the provider's cache. An instance method rather than a lookup of
     * {@link #current()} because the streaming completion callback fires on the
     * provider's IO thread, which carries no turn binding — the streaming path
     * captures the trace at dispatch and calls this on it.
     */
    public void noteCachedLlmCall() {
        llmCachedCallCount.incrementAndGet();
    }

    /**
     * Finalize the trace and submit segment durations to {@link LatencyStats}.
     * Idempotent. Early-exit traces (no {@code PROLOGUE_DONE}) are skipped so
     * histograms reflect actual end-to-end streams, not queue rejections or
     * provider-missing errors.
     */
    public void end() {
        // CAS single-shot: the terminal callbacks can race across the worker,
        // streaming, and IO threads, so a non-atomic check-then-set could let
        // two callers both pass the guard and double-emit. Mirrors the
        // QueueDrainOrchestrator double-terminal CAS.
        if (!ended.compareAndSet(false, true)) return;
        long endNs = System.nanoTime();

        Long prologueDone = marks.get(PROLOGUE_DONE);
        if (prologueDone == null) return;

        if (acceptedAtNs > 0) {
            // Clamp to 0 defensively: nanoTime is monotonic within a JVM, but
            // the stamp is captured on the Netty thread and read here from the
            // virtual/worker thread — any skew we see would be a bug worth
            // surfacing as 0 rather than a negative outlier.
            long queueNs = Math.max(0L, startNs - acceptedAtNs);
            emit("queue_wait", nsToMs(queueNs));
        }

        emit("total", nsToMs(endNs - startNs));
        emit("prologue", nsToMs(prologueDone - startNs));

        recordPrologueSubSegments(prologueDone);
        recordStreamSegments(prologueDone);
        recordToolSegments();
        recordLlmCallSegments();
        recordToolVerificationSegments();
        recordMemorySegments();
    }

    private void recordMemorySegments() {
        int calls = memoryRecallCount.get();
        if (calls > 0) {
            emit("memory_recall", memoryRecallMs.get());
            emit("memory_recall_count", calls);
        }
    }

    /**
     * Prologue sub-segments — missing marks are OK, we just skip that sub-bucket.
     * The sequence is: startNs → request_parsed → conv_resolved → prompt_assembled → prologue_done.
     * Each emitted sub-segment measures the gap between adjacent marks so they sum back
     * to {@code prologue} (modulo integer ms rounding).
     */
    private void recordPrologueSubSegments(long prologueDone) {
        Long requestParsed = marks.get(PROLOGUE_REQUEST_PARSED);
        Long convResolved = marks.get(PROLOGUE_CONV_RESOLVED);
        Long promptAssembled = marks.get(PROLOGUE_PROMPT_ASSEMBLED);

        if (requestParsed != null) {
            emit("prologue_parse", nsToMs(requestParsed - startNs));
        }
        if (requestParsed != null && convResolved != null) {
            emit("prologue_conv", nsToMs(convResolved - requestParsed));
        }
        if (convResolved != null && promptAssembled != null) {
            emit("prologue_prompt", nsToMs(promptAssembled - convResolved));
        }
        // Decompose prologue_prompt rather than extend the chain — these two sum to it, not
        // to prologue, so a consumer must pick one level or double-count.
        //
        // prologue_prompt is bimodal: 3-23 ms on back-to-back turns against ~600 ms
        // occasionally (p50 34, p90 135, max 625 over one 10-turn window). Timing
        // SystemPromptAssembler.assemble on its own through /api/agents/{id}/prompt-text put
        // it at 2-29 ms for a 40 KB prompt, so the spike is downstream of assembly — in
        // applyMediaRewrite, which also runs compression, the compaction gate (an LLM call
        // when it fires) and a full-prompt token estimate. Splitting here says which.
        Long promptBuilt = marks.get(PROLOGUE_PROMPT_BUILT);
        if (convResolved != null && promptBuilt != null) {
            emit("prologue_assemble", nsToMs(promptBuilt - convResolved));
        }
        if (promptBuilt != null && promptAssembled != null) {
            emit("prologue_rewrite", nsToMs(promptAssembled - promptBuilt));
        }
        // Sits inside prologue_assemble, so it decomposes that segment rather than adding
        // to the chain — same rule as the pair above.
        long embed = queryEmbedMs.get();
        if (embed > 0) emit("prologue_embed_query", embed);
        if (promptAssembled != null) {
            emit("prologue_tools", nsToMs(prologueDone - promptAssembled));
        }
    }

    /**
     * Stream-phase segments: ttft, stream_body, persist, terminal_tail.
     *
     * <p>{@code persist} is the assistant-message write, timed by the streaming runner and
     * handed here through {@link #recordPersist}. It runs BEFORE the terminal SSE frame, so
     * it falls inside the stream_body_end → terminal_sent window. The legacy PERSIST_DONE
     * constant is retained for tests that still set it.
     *
     * <p>{@code terminal_tail} is that window <em>net of</em> persist — the final
     * usage-logging callbacks, onStatus + onComplete dispatch, and the terminal writeChunk.
     * Subtracting matters: reporting both as measured would bill the same milliseconds
     * twice to anyone summing the post-stream phase.
     */
    private void recordStreamSegments(long prologueDone) {
        Long firstToken = marks.get(FIRST_TOKEN);
        if (firstToken != null) {
            emit("ttft", nsToMs(firstToken - prologueDone));
        }

        // ttft marks the first *content* token, because that is what the reader sees. On a
        // turn that reasons or calls a tool first it therefore spans work that is not
        // prefill at all — measured 2034 ms on a tool turn whose tool ran for 18 ms, against
        // ~1315 ms for a plain turn on the same agent. Left as-is so its history stays
        // comparable; ttft_first_signal is the prefill-only companion, and the gap between
        // them is attributed below.
        Long firstSignal = marks.get(FIRST_SIGNAL);
        if (firstSignal != null) {
            emit("ttft_first_signal", nsToMs(firstSignal - prologueDone));
        }
        if (firstSignal != null && firstToken != null && firstToken > firstSignal) {
            // One name per turn, never both: they measure the same span and emitting each
            // would double-count it. Tools win when a turn did both, being the larger cost.
            long gapMs = nsToMs(firstToken - firstSignal);
            if (toolRoundCount.get() > 0) emit("tool_gap", gapMs);
            else if (reasoningSeen.get()) emit("think_time", gapMs);
        }

        Long streamBodyEnd = marks.get(STREAM_BODY_END);
        if (firstToken != null && streamBodyEnd != null) {
            emit("stream_body", nsToMs(streamBodyEnd - firstToken));
        }

        // Accumulator first; the PERSIST_DONE mark is the legacy path some tests still set.
        long persisted = persistMs.get();
        Long persistDone = marks.get(PERSIST_DONE);
        if (persisted > 0) {
            emit("persist", persisted);
        } else if (streamBodyEnd != null && persistDone != null) {
            persisted = nsToMs(persistDone - streamBodyEnd);
            emit("persist", persisted);
        }

        Long terminalSent = marks.get(TERMINAL_SENT);
        if (streamBodyEnd != null && terminalSent != null) {
            // Net of the persist write that runs inside this span, so persist and
            // terminal_tail partition the post-stream phase instead of overlapping.
            emit("terminal_tail", Math.max(0L, nsToMs(terminalSent - streamBodyEnd) - persisted));
        }
    }

    private void recordToolSegments() {
        if (toolRoundCount.get() > 0) {
            emit("tool_exec", toolExecMs.get());
            emit("tool_round_count", toolRoundCount.get());
        }
    }

    /**
     * Per-turn LLM call counts (JCLAW-882) — the measurement basis for the
     * JCLAW-833 efficiency NFR, which {@code tool_round_count} cannot serve:
     * it misses the turn's first model call, and it misses every planner,
     * critic and best-of-N call, none of which is a tool round.
     *
     * <p>Both segments are suppressed at zero because {@link LatencyStats}
     * clamps recorded values to a minimum of 1 (HdrHistogram takes positive
     * values only), so emitting "0 cache-served calls" would record as 1 and
     * overstate the cheap share. Read that share as
     * {@code sum(llm_call_cached) / sum(llm_call_count)} across turns —
     * percentiles are not additive, so subtracting them would not give it.
     */
    private void recordLlmCallSegments() {
        int calls = llmCallCount.get();
        if (calls == 0) return;
        emit("llm_call_count", calls);
        // A provider can report cached tokens on a call the counter never saw
        // (a stream that started before the binding was in place); clamp so the
        // cache-served share can never exceed the calls it is a share of.
        int cached = Math.min(llmCachedCallCount.get(), calls);
        if (cached > 0) emit("llm_call_cached", cached);
    }

    /**
     * Per-turn tool-verification counts (JCLAW-836). The pass/fail RATE is what the
     * story asks for, and a rate needs both numbers: read it as
     * {@code sum(tool_verify_failed) / sum(tool_verify_count)} across turns, since
     * percentiles are not additive.
     *
     * <p>Suppressed at zero for the same reason as the LLM-call segments —
     * {@link LatencyStats} clamps to a minimum of 1 because HdrHistogram takes
     * positive values only, so emitting "0 failures" would record as 1 and invent a
     * failure rate on every clean turn.
     */
    private void recordToolVerificationSegments() {
        int verified = toolVerifyCount.get();
        if (verified == 0) return;
        emit("tool_verify_count", verified);
        int failed = toolVerifyFailedCount.get();
        if (failed > 0) emit("tool_verify_failed", failed);
    }

    /** Record one segment to both the live histogram and the persisted time-series. */
    private void emit(String segment, long ms) {
        LatencyStats.record(channel, segment, ms, agentId);
    }

    private static long nsToMs(long ns) {
        return ns / 1_000_000L;
    }
}
