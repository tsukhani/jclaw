package llm;

import llm.LlmTypes.ToolCall;
import llm.LlmTypes.Usage;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Extraction home (JCLAW-725) for the two streaming/usage value carriers that
 * {@link LlmProvider} used to nest: {@link StreamAccumulator} (per-round stream
 * accumulation) and {@link TurnUsage} (per-turn billing aggregate). Splitting
 * them out of {@code LlmProvider} separates wire-protocol handling from stream
 * accumulation and turn-level billing.
 *
 * <p>They live inside this host interface — which {@link LlmProvider}
 * {@code implements} — rather than as free-standing top-level classes so that
 * the qualified names {@code LlmProvider.StreamAccumulator} and
 * {@code LlmProvider.TurnUsage} keep resolving (as inherited member types)
 * across {@code app/agents} (StreamingAgentRunner, ToolCallLoopRunner,
 * UsageMetricsBuilder, CancellationManager). Those call sites are outside this
 * change's editable scope, so the public surface stays byte-for-byte identical
 * to the former nested types.
 */
interface LlmStreamCarriers {

    /**
     * Accumulates one streaming LLM round: assembled content, tool calls,
     * finish reason, provider usage, reasoning text/timing, and JTokkit
     * estimates. One {@link StreamAccumulator} is produced per round; rounds
     * fold into a {@link TurnUsage} via {@link TurnUsage#addRound}.
     *
     * <p>Every field is package-private (JCLAW-725): {@link LlmProvider} — same
     * package — writes them during stream accumulation, {@code app/agents} reads
     * through the record-style accessors below, and the default-package
     * streaming unit tests read through those same accessors and assemble round
     * fixtures via {@link Builder}. No field is publicly mutable, so the former
     * S1104 smell is gone.
     */
    class StreamAccumulator {
        volatile String content = "";
        volatile List<ToolCall> toolCalls = List.of();
        volatile String finishReason;
        volatile boolean complete = false;
        volatile Exception error;
        volatile boolean reasoningDetected = false;
        volatile int reasoningTokens = 0;
        /**
         * Accumulated reasoning text across all streamed deltas. Populated even
         * when the provider doesn't report {@code reasoning_tokens} in the usage
         * block, so callers can estimate a token count from the text length when
         * needed (see {@code AgentRunner.emitUsageAndComplete}).
         *
         * <p>Guarded by {@link #reasoningLock} (a {@link java.util.concurrent.locks.ReentrantLock}
         * rather than {@code synchronized}). The streaming callback fires on
         * an OkHttp virtual thread; under JEP-444 a {@code synchronized} block
         * pins the carrier for the duration of the lock — directly contradicting
         * the architecture rationale ("zero Thread is pinned events"). A
         * {@code ReentrantLock} parks the virtual thread instead, allowing
         * the carrier to be reused for other work.
         */
        private final StringBuilder reasoningTextBuffer = new StringBuilder();
        private final ReentrantLock reasoningLock =
                new ReentrantLock();
        volatile Usage usage;
        /** JTokkit-measured prompt tokens for this provider request, available even when provider usage is absent. */
        volatile TokenUsageEstimator.ChatRequestTokens promptTokenEstimate;
        /** JTokkit-measured completion tokens for streamed content/tool calls/reasoning. */
        volatile TokenUsageEstimator.TokenCount completionTokenEstimate;
        /** JTokkit-measured reasoning-token subset for streamed reasoning text. */
        volatile TokenUsageEstimator.TokenCount reasoningTokenEstimate;
        // Wall-clock nanoTime at first and latest reasoning chunk. Both remain 0
        // when the model emitted no reasoning. reasoningEndNanos is updated on
        // every append so it naturally captures "end of reasoning phase" — the
        // gap between the last reasoning chunk and the first content chunk is
        // within one provider tick, accurate enough for a user-visible seconds
        // label. See AgentRunner.buildUsageJson for the ms conversion.
        volatile long reasoningStartNanos = 0L;
        volatile long reasoningEndNanos = 0L;
        /**
         * Wall-clock nanoTime at the first content chunk of THIS round, or 0
         * if the round produced no content (tool-only round, or reasoning-only
         * response). {@link TurnUsage#addRound} aggregates the earliest
         * non-zero value across rounds so the persisted "Thought for X
         * seconds" matches what the user saw live: from first reasoning
         * event to first content event of the entire turn (including any
         * tool-execution gap between rounds).
         */
        volatile long firstContentNanos = 0L;
        private final CountDownLatch latch = new CountDownLatch(1);

        public void appendReasoningText(String text) {
            if (text == null) return;
            reasoningLock.lock();
            try {
                reasoningTextBuffer.append(text);
                var now = System.nanoTime();
                if (reasoningStartNanos == 0L) reasoningStartNanos = now;
                reasoningEndNanos = now;
            } finally {
                reasoningLock.unlock();
            }
        }

        /**
         * Record the timestamp of the first content chunk in this round.
         * {@link TurnUsage#addRound} reads {@link #firstContentNanos} to
         * compute turn-level reasoning duration (first reasoning event of the
         * turn → first content event of the turn). Idempotent: only the first
         * call records.
         *
         * <p>Also bookends {@link #reasoningEndNanos} for the single-chunk
         * reasoning case, so the round-local {@link #reasoningDurationMs}
         * still computes a non-zero value for diagnostic / per-round needs.
         * Multi-chunk reasoning is unaffected (reasoningEndNanos was already
         * advanced past reasoningStartNanos via prior appends).
         */
        public void noteFirstContentChunk() {
            reasoningLock.lock();
            try {
                if (firstContentNanos != 0L) return;
                firstContentNanos = System.nanoTime();
                if (reasoningStartNanos != 0L && reasoningEndNanos == reasoningStartNanos) {
                    reasoningEndNanos = firstContentNanos;
                }
            } finally {
                reasoningLock.unlock();
            }
        }

        /** Character count of accumulated reasoning text. Used for token estimation. */
        public int reasoningChars() {
            reasoningLock.lock();
            try {
                return reasoningTextBuffer.length();
            } finally {
                reasoningLock.unlock();
            }
        }

        /**
         * Full streamed reasoning text for this round. Returned as a plain
         * {@link String} (buffer is copied) so callers can hand it to JPA /
         * downstream consumers without racing against concurrent appends on
         * the streaming thread.
         */
        public String reasoningText() {
            reasoningLock.lock();
            try {
                return reasoningTextBuffer.toString();
            } finally {
                reasoningLock.unlock();
            }
        }

        /**
         * Milliseconds spent in the reasoning phase, or 0 when no reasoning was
         * streamed. Computed lazily so callers get a stable snapshot even if the
         * stream is still in flight.
         */
        public long reasoningDurationMs() {
            if (reasoningStartNanos == 0L) return 0L;
            return (reasoningEndNanos - reasoningStartNanos) / 1_000_000L;
        }

        void markComplete() { complete = true; latch.countDown(); }
        public void awaitCompletion() throws InterruptedException { latch.await(); }
        public boolean awaitCompletion(long timeoutMs) throws InterruptedException {
            return latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        }

        // Record-style read accessors. The timing/status group below is for the
        // package-private fields (never read across the app boundary — only by
        // the default-package streaming tests and, for the nanos, by TurnUsage
        // in this same package via direct field access). The content/toolCalls/
        // finishReason/error group is what app/agents reads through, so those
        // call sites no longer reach into the fields directly (JCLAW-725).
        public boolean complete() { return complete; }
        public long reasoningStartNanos() { return reasoningStartNanos; }
        public long reasoningEndNanos() { return reasoningEndNanos; }
        public long firstContentNanos() { return firstContentNanos; }
        public String content() { return content; }
        public List<ToolCall> toolCalls() { return toolCalls; }
        public String finishReason() { return finishReason; }
        public Exception error() { return error; }

        /**
         * Fluent construction of a round fixture for the default-package unit
         * tests, exposing only the value fields a fixture sets. Production writes
         * the fields directly from {@link LlmProvider} (same package); reasoning
         * text and content timing are set by calling {@link #appendReasoningText}
         * / {@link #noteFirstContentChunk} on the built instance. Lets the fields
         * stay package-private without public setters (JCLAW-725 residual).
         */
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private final StreamAccumulator acc = new StreamAccumulator();

            public Builder usage(Usage usage) { acc.usage = usage; return this; }
            public Builder reasoningDetected(boolean detected) { acc.reasoningDetected = detected; return this; }
            public Builder promptTokenEstimate(TokenUsageEstimator.ChatRequestTokens estimate) { acc.promptTokenEstimate = estimate; return this; }
            public Builder completionTokenEstimate(TokenUsageEstimator.TokenCount estimate) { acc.completionTokenEstimate = estimate; return this; }
            public Builder reasoningTokenEstimate(TokenUsageEstimator.TokenCount estimate) { acc.reasoningTokenEstimate = estimate; return this; }

            public StreamAccumulator build() { return acc; }
        }
    }

    /**
     * Cumulative token usage across every LLM round in a single user turn.
     * A "turn" is one user message → one final assistant message, which for
     * tool-using models can span many LLM API calls (each round has its own
     * {@link StreamAccumulator}). Token counts get folded in via
     * {@link #addRound(StreamAccumulator)} after each round's stream completes.
     *
     * <p>Summing per-round usage is the billing-correct behaviour because each
     * round is a separate API call; it also matches user intuition for
     * reasoning/completion counts (the user sees all reasoning and all
     * synthesis output across rounds, not just round 1). Reasoning-phase
     * <em>timing</em> is also turn-level (see {@link #turnReasoningStartNanos}
     * and {@link #turnFirstContentNanos}): the persisted "Thought for X
     * seconds" matches what the user saw live — from first reasoning event
     * of the turn to first content event of the turn, including any tool-
     * execution gap between rounds. Anchoring it to round 1 only (the
     * pre-fix behaviour) made reloaded turns show e.g. 1.23s while the
     * live UI showed 9.60s for the same turn.
     *
     * <p>See JCLAW-76 for the accounting defect this class fixes.
     */
    // Cumulative-usage value carrier (JCLAW-725). Every counter is
    // package-private: addRound (this class) writes them, and both
    // app/agents/UsageMetricsBuilder and the default-package AgentRunnerUsageTest
    // read through the record-style accessors below — no field is publicly
    // mutable. The two turn-local nanos have no cross-boundary reader and stay
    // private.
    class TurnUsage {
        int promptTokens;
        int completionTokens;
        int totalTokens;
        /** Sum of provider-reported {@code reasoning_tokens} across all rounds. */
        int reasoningTokens;
        int cachedTokens;
        int cacheCreationTokens;
        /** Sum of streamed reasoning-text chars, used as a token fallback when the provider returns 0 reasoning_tokens. */
        int reasoningChars;
        /** True once any round has detected reasoning, used to gate the fallback estimate. */
        boolean reasoningDetected;
        /** True once any round returned a non-null {@link Usage}. Gates the zero-usage JSON path. */
        boolean hasProviderUsage;
        /** True once any round has a JTokkit request/response measurement. */
        boolean hasJtokkitUsage;
        int jtokkitPromptTokens;
        int jtokkitCompletionTokens;
        int jtokkitReasoningTokens;
        int jtokkitTotalTokens;
        String jtokkitEncoding;
        boolean jtokkitModelMatched = true;
        /**
         * Wall-clock nanoTime at the first reasoning chunk anywhere in this
         * turn (any round). Set on the first {@code addRound} that sees a
         * non-zero {@link StreamAccumulator#reasoningStartNanos}; never
         * overwritten — matches the frontend's "stamp once" semantics.
         */
        private volatile long turnReasoningStartNanos = 0L;
        /**
         * Wall-clock nanoTime at the first content chunk anywhere in this
         * turn (any round). Same first-non-zero-wins propagation rule as
         * {@link #turnReasoningStartNanos}. Stays 0 if the turn was
         * reasoning-only (no content streamed); see
         * {@link #reasoningDurationMs} for that fallback.
         */
        private volatile long turnFirstContentNanos = 0L;
        /**
         * Concatenated reasoning text across every LLM round in the turn.
         * Matches what the frontend bubble displays live (reasoning SSE
         * events stream in across all rounds before the first content byte)
         * so persisting this and rendering it on conversation reload keeps
         * historical bubbles consistent with how they first appeared.
         */
        private final StringBuilder reasoningText = new StringBuilder();

        public synchronized void addRound(StreamAccumulator acc) {
            if (acc == null) return;
            var u = acc.usage;
            if (u != null) {
                hasProviderUsage = true;
                promptTokens += u.promptTokens();
                completionTokens += u.completionTokens();
                totalTokens += u.totalTokens();
                reasoningTokens += u.reasoningTokens();
                cachedTokens += u.cachedTokens();
                cacheCreationTokens += u.cacheCreationTokens();
            }
            addJtokkitRound(acc);
            if (acc.reasoningDetected) reasoningDetected = true;
            reasoningChars += acc.reasoningChars();
            reasoningText.append(acc.reasoningText());
            if (acc.reasoningStartNanos != 0L && turnReasoningStartNanos == 0L) {
                turnReasoningStartNanos = acc.reasoningStartNanos;
            }
            if (acc.firstContentNanos != 0L && turnFirstContentNanos == 0L) {
                turnFirstContentNanos = acc.firstContentNanos;
            }
        }

        private void addJtokkitRound(StreamAccumulator acc) {
            if (acc.promptTokenEstimate == null && acc.completionTokenEstimate == null) return;
            hasJtokkitUsage = true;
            if (acc.promptTokenEstimate != null) {
                jtokkitPromptTokens += acc.promptTokenEstimate.promptTokens();
                noteJtokkitEncoding(acc.promptTokenEstimate.encodingName(), acc.promptTokenEstimate.modelMatched());
            }
            if (acc.completionTokenEstimate != null) {
                jtokkitCompletionTokens += acc.completionTokenEstimate.tokens();
                noteJtokkitEncoding(acc.completionTokenEstimate.encodingName(), acc.completionTokenEstimate.modelMatched());
            }
            if (acc.reasoningTokenEstimate != null) {
                jtokkitReasoningTokens += acc.reasoningTokenEstimate.tokens();
                noteJtokkitEncoding(acc.reasoningTokenEstimate.encodingName(), acc.reasoningTokenEstimate.modelMatched());
            }
            jtokkitTotalTokens = jtokkitPromptTokens + jtokkitCompletionTokens;
        }

        private void noteJtokkitEncoding(String encoding, boolean modelMatched) {
            if (encoding != null && jtokkitEncoding == null) jtokkitEncoding = encoding;
            jtokkitModelMatched &= modelMatched;
        }

        /** Returns the aggregated reasoning text, or {@code null} if nothing was streamed. */
        public synchronized String reasoningText() {
            return reasoningText.isEmpty() ? null : reasoningText.toString();
        }

        /**
         * Milliseconds from the first reasoning chunk of this turn to the
         * first content chunk of this turn. Matches the frontend's live
         * {@code _thinkingDurationMs} measurement so a turn shows the same
         * "Thought for X seconds" value during streaming and after reload.
         *
         * <p>Reasoning-only turns (no content ever streamed) fall back to
         * {@code turnEndNanos} — pass {@code System.nanoTime()} from the
         * caller at end-of-turn. Returns 0 when no reasoning was detected
         * (so the persistence layer can omit the field, matching the
         * pre-feature historical-message rendering).
         */
        public synchronized long reasoningDurationMs(long turnEndNanos) {
            if (turnReasoningStartNanos == 0L) return 0L;
            long endNanos = turnFirstContentNanos != 0L ? turnFirstContentNanos : turnEndNanos;
            long diffNanos = endNanos - turnReasoningStartNanos;
            return diffNanos > 0L ? diffNanos / 1_000_000L : 0L;
        }

        // Record-style read accessors so app/agents/UsageMetricsBuilder reads
        // the cumulative counters without reaching into the fields directly
        // (JCLAW-725). Plain reads, no synchronization — identical semantics to
        // the former direct field access; the counters are only read after the
        // turn's rounds have all folded in.
        public int promptTokens() { return promptTokens; }
        public int completionTokens() { return completionTokens; }
        public int totalTokens() { return totalTokens; }
        public int reasoningTokens() { return reasoningTokens; }
        public int cachedTokens() { return cachedTokens; }
        public int cacheCreationTokens() { return cacheCreationTokens; }
        public int reasoningChars() { return reasoningChars; }
        public boolean reasoningDetected() { return reasoningDetected; }
        public boolean hasProviderUsage() { return hasProviderUsage; }
        public boolean hasJtokkitUsage() { return hasJtokkitUsage; }
        public int jtokkitPromptTokens() { return jtokkitPromptTokens; }
        public int jtokkitCompletionTokens() { return jtokkitCompletionTokens; }
        public int jtokkitReasoningTokens() { return jtokkitReasoningTokens; }
        public int jtokkitTotalTokens() { return jtokkitTotalTokens; }
        public String jtokkitEncoding() { return jtokkitEncoding; }
        public boolean jtokkitModelMatched() { return jtokkitModelMatched; }
    }
}
