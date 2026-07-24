package services.voice;

import org.jspecify.annotations.Nullable;
import utils.LatencyStats;

import java.util.function.LongSupplier;

/**
 * Per-turn voice-pipeline latency (JCLAW-800). The LLM leg already records
 * under channel {@code "voice"} via {@link utils.LatencyTrace} (prologue,
 * {@code ttft}, {@code stream_body}, {@code total}), because {@code
 * VoiceController} runs the agent turn on that channel. This fills the
 * voice-specific stages around it so the whole turn is visible in one place:
 * the STT / input leg, the first spoken chunk's TTS synthesis cost, and the
 * two voice-to-voice headline numbers.
 *
 * <p>All stages are measured from {@code t0} — the endpoint, i.e. the instant
 * the finalized utterance is in hand, which is when the voice-to-voice clock
 * starts. Everything lands in the same {@link LatencyStats} histograms and
 * persisted rows, so the Chat Performance dashboard (filter channel = {@code
 * voice}) shows the full breakdown alongside the LLM segments.
 *
 * <p>Recorded segments (channel {@code voice}):
 * <ul>
 *   <li>{@link #STT} — endpoint → transcript/attachment ready (local ASR, or native-audio staging)</li>
 *   <li>{@link #TTS_SYNTH} — wall time to synthesize the first spoken chunk</li>
 *   <li>{@link #REPLY} — endpoint → first audio frame sent (the server voice-to-voice metric)</li>
 *   <li>{@link #TURN} — endpoint → turn complete (all chunks sent)</li>
 * </ul>
 *
 * <p>The clock is injectable ({@link LongSupplier}) so the stage math is
 * unit-testable without a live WebSocket turn.
 */
public final class VoiceTurnMetrics {

    public static final String CHANNEL = "voice";
    public static final String STT = "voice_stt";
    public static final String TTS_SYNTH = "voice_tts_synth";
    public static final String REPLY = "voice_reply";
    public static final String TURN = "voice_turn";

    private final @Nullable String agentId;
    private final long endpointNs;
    private final LongSupplier clockNs;
    private boolean replyRecorded;

    /** Start from a caller-supplied endpoint {@code nanoTime} so the metric shares
     *  the exact {@code t0} the turn already stamped. */
    public VoiceTurnMetrics(@Nullable String agentId, long endpointNs) {
        this(agentId, endpointNs, System::nanoTime);
    }

    /** Clock-injection seam: pass a controllable {@link LongSupplier} so the stage
     *  math is deterministic under test. Production uses {@link #VoiceTurnMetrics(String, long)}. */
    public VoiceTurnMetrics(@Nullable String agentId, long endpointNs, LongSupplier clockNs) {
        this.agentId = agentId;
        this.endpointNs = endpointNs;
        this.clockNs = clockNs;
    }

    /** Record the input leg: endpoint → transcript (or native-audio staging) ready. */
    public void sttDone() {
        record(STT, sinceEndpointMs());
    }

    /** Record the synthesis cost of one TTS chunk (call for the first, critical-path chunk). */
    public void ttsSynth(long ms) {
        record(TTS_SYNTH, ms);
    }

    /** Record endpoint → first audio sent. Idempotent — only the first call counts,
     *  so a caller can invoke it defensively without inflating the sample count. */
    public void firstAudioSent() {
        if (replyRecorded) return;
        replyRecorded = true;
        record(REPLY, sinceEndpointMs());
    }

    /** Record endpoint → turn complete. */
    public void turnComplete() {
        record(TURN, sinceEndpointMs());
    }

    private long sinceEndpointMs() {
        return (clockNs.getAsLong() - endpointNs) / 1_000_000L;
    }

    // S6213: `record` is the semantic verb here (delegates to LatencyStats.record,
    // matching HdrHistogram.recordValue) — renaming would misname the metrics API
    // purely to dodge Java's contextual keyword. Mirrors LatencyStats.record.
    @SuppressWarnings("java:S6213")
    private void record(String segment, long ms) {
        LatencyStats.record(CHANNEL, segment, ms, agentId);
    }
}
