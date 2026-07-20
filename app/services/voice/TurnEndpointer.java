package services.voice;

/**
 * Adaptive-silence turn endpointing (JCLAW-797). Consumes a per-frame
 * speech/non-speech signal (from {@link VoiceVad}) with caller-supplied
 * monotonic timestamps and emits turn events: an utterance OPENS once enough
 * contiguous speech accrues, and CLOSES once trailing silence exceeds an
 * adaptive threshold — short when the turn looks complete, longer otherwise so a
 * mid-sentence pause isn't cut off.
 *
 * <p>Deliberately pure and deterministic (no audio, no models, no clock — the
 * caller passes the timestamp), so the decision logic is unit-tested directly
 * and stays replayable. Whether a turn "looks complete" is delegated to a
 * {@link Confirmer}; the default treats every pause as complete (fixed
 * {@code baseSilenceMs}), and a semantic turn model (Smart Turn v3) plugs into
 * that seam later without touching this state machine.
 *
 * <p>This is the endpointing brain of the JCLAW-795 Tier-1 cascade; it is wired
 * to a live continuous audio stream in the pipelined-orchestration story
 * (JCLAW-799), which replaces the current client-side energy VAD.
 */
public final class TurnEndpointer {

    /** Result of feeding one frame to {@link #accept}. */
    public enum Event {
        /** No turn boundary this frame. */
        NONE,
        /** An utterance just opened (enough contiguous speech accrued). */
        SPEECH_STARTED,
        /** The utterance just closed — the caller should finalize it now. */
        ENDPOINT
    }

    /** Decides whether the current trailing pause should end the turn now. The
     *  semantic-turn hook (Smart Turn v3): returning {@code false} holds the turn
     *  open until {@code maxSilenceMs}. */
    @FunctionalInterface
    public interface Confirmer {
        boolean looksComplete();
    }

    /** Every pause is a turn boundary — fixed {@code baseSilenceMs} endpointing. */
    public static final Confirmer ALWAYS_COMPLETE = () -> true;

    private final long speechStartMs;
    private final long baseSilenceMs;
    private final long maxSilenceMs;
    private final long minUtteranceMs;
    private final Confirmer confirmer;

    private boolean speaking;
    private long speechRunStart = -1; // start of the current pre-open speech run
    private long lastSpeechMs = -1;   // last speech frame inside the open utterance
    private long utteranceStartMs = -1;

    /**
     * @param speechStartMs  contiguous speech required to open an utterance
     * @param baseSilenceMs  trailing silence that closes a turn that looks complete
     * @param maxSilenceMs   trailing silence that closes a turn regardless (ceiling)
     * @param minUtteranceMs shortest speech span kept; briefer blips are dropped
     * @param confirmer      semantic completeness hook (use {@link #ALWAYS_COMPLETE} for fixed silence)
     */
    public TurnEndpointer(long speechStartMs, long baseSilenceMs, long maxSilenceMs,
                          long minUtteranceMs, Confirmer confirmer) {
        if (baseSilenceMs > maxSilenceMs) {
            throw new IllegalArgumentException("baseSilenceMs must not exceed maxSilenceMs");
        }
        this.speechStartMs = speechStartMs;
        this.baseSilenceMs = baseSilenceMs;
        this.maxSilenceMs = maxSilenceMs;
        this.minUtteranceMs = minUtteranceMs;
        this.confirmer = confirmer;
    }

    /**
     * Feed one VAD frame. {@code tsMs} is a monotonically non-decreasing frame
     * timestamp in milliseconds (e.g. window index times window duration).
     */
    public Event accept(boolean speech, long tsMs) {
        if (!speaking) {
            if (!speech) {
                speechRunStart = -1; // a gap resets the pre-open run
                return Event.NONE;
            }
            if (speechRunStart < 0) speechRunStart = tsMs;
            if (tsMs - speechRunStart >= speechStartMs) {
                speaking = true;
                utteranceStartMs = speechRunStart;
                lastSpeechMs = tsMs;
                return Event.SPEECH_STARTED;
            }
            return Event.NONE;
        }

        if (speech) {
            lastSpeechMs = tsMs;
            return Event.NONE;
        }

        long silence = tsMs - lastSpeechMs;
        long threshold = confirmer.looksComplete() ? baseSilenceMs : maxSilenceMs;
        if (silence >= threshold) {
            boolean longEnough = (lastSpeechMs - utteranceStartMs) >= minUtteranceMs;
            reset();
            return longEnough ? Event.ENDPOINT : Event.NONE; // drop sub-minimum blips
        }
        return Event.NONE;
    }

    /** Whether an utterance is currently open. */
    public boolean isSpeaking() {
        return speaking;
    }

    /** Abandon any open utterance and return to the idle state (barge-in / stop). */
    public void reset() {
        speaking = false;
        speechRunStart = -1;
        lastSpeechMs = -1;
        utteranceStartMs = -1;
    }
}
