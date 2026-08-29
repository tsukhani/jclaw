package services.voice;

import play.Logger;
import services.tts.TtsRouter;
import services.tts.TtsText;

import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;

/**
 * The speaking half of a voice turn (JCLAW-869): drains reply sentences as the
 * agent streams them, emits the growing reply text, and speaks each one.
 *
 * <p>Split out of {@code VoiceController.runTurn}, which had grown to do STT, the
 * agent run, and this, in one frame. Everything that survives here is behavior
 * that was arrived at by fixing a real bug, so each rule is stated with the
 * failure it prevents rather than left as folklore.
 *
 * <p>The two collaborators are interfaces rather than the concrete Play socket and
 * {@link TtsRouter} so this is reachable from a test. The degradation path in
 * particular is only meaningful when synthesis can be made to fail on demand, and
 * with no mocking framework in the build there is no other way to reach it.
 */
public final class VoiceTurnSpeaker {

    /** Sentinel the producer queues to mark the end of the reply stream. */
    public static final String END_OF_TURN = "";

    /** How long to wait on a silent queue before treating the stream as over. Long
     *  because a slow model is not a broken one; this is a backstop, not a policy. */
    private static final long SENTENCE_TIMEOUT_MINUTES = 10;

    /** Where frames go, and whether the peer is still listening. */
    public interface Sink {
        boolean isOpen();

        void send(Map<String, Object> frame);
    }

    /** Turns one utterance into audio. Production binds
     *  {@code TtsRouter::synthesizeForVoice}; a test binds a fake. */
    @FunctionalInterface
    public interface Synthesizer {
        TtsRouter.Spoken speak(String text);
    }

    private final Sink sink;
    private final int turnId;
    private final AtomicBoolean cancel;
    private final Synthesizer synthesizer;

    private final Base64.Encoder enc = Base64.getEncoder();
    private final StringBuilder spoken = new StringBuilder();
    /** Latched so a dead engine logs once per turn rather than once per sentence —
     *  every utterance after the first fails the same way, and a per-sentence log
     *  buries the one line that matters. */
    private boolean audioDegraded;
    private int chunks;

    public VoiceTurnSpeaker(Sink sink, int turnId, AtomicBoolean cancel, Synthesizer synthesizer) {
        this.sink = sink;
        this.turnId = turnId;
        this.cancel = cancel;
        this.synthesizer = synthesizer;
    }

    /** Audio chunks emitted so far. */
    public int chunksSent() {
        return chunks;
    }

    /** True once an utterance failed to synthesize and the turn continued text-only. */
    public boolean degraded() {
        return audioDegraded;
    }

    /**
     * Speak sentences until the stream ends or the turn is abandoned.
     *
     * @param onFirstAudio invoked once, with the synthesis time of the first audio
     *                     chunk — the voice-to-voice number that actually matters.
     * @return true when the reply stream ended on its own, so the caller may emit
     *         {@code turn_complete}; false when the turn was canceled, the socket
     *         closed, or the thread was interrupted. None of those complete a turn.
     */
    public boolean speakAll(BlockingQueue<String> sentences, LongConsumer onFirstAudio) {
        while (!cancel.get()) {
            String sentence;
            try {
                sentence = sentences.poll(SENTENCE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (sentence == null || END_OF_TURN.equals(sentence)) return true;
            if (!speak(sentence, onFirstAudio)) return false;
        }
        return false;
    }

    /** Emit one sentence's text and audio. @return false when the turn should stop. */
    private boolean speak(String sentence, LongConsumer onFirstAudio) {
        var speakable = TtsText.toSpeakable(sentence);
        if (speakable.isBlank()) return true; // whitespace-only chunk (e.g. stripped emoji)
        if (cancel.get() || !sink.isOpen()) return false;

        // JCLAW-860: text first, and independent of audio. The reply exists whether
        // or not it can be spoken — emitting it only after a successful synthesize
        // meant a TTS failure discarded an answer the model had already produced and
        // the operator had already paid for. A text-only turn is a supported client
        // state: turn_complete with nothing queued for playback hands the floor
        // straight back to the mic.
        spoken.append(!spoken.isEmpty() ? " " : "").append(speakable);
        sink.send(Map.of("type", "reply", "turn", turnId, "text", spoken.toString()));

        long synthStart = System.nanoTime();
        var synth = synthesize(speakable);
        if (synth.isEmpty()) return true; // no audio for this one; the turn goes on
        long synthMs = (System.nanoTime() - synthStart) / 1_000_000L;
        if (cancel.get()) return false;

        sink.send(Map.of("type", "audio", "turn", turnId, "index", chunks++,
                "audio", enc.encodeToString(synth.get().audio()),
                // Which engine actually spoke, so downgraded audio is identifiable on
                // the wire rather than passed off as the operator's choice (JCLAW-861).
                "engine", synth.get().engine().id(), "degraded", synth.get().fellBack()));
        if (chunks == 1) onFirstAudio.accept(synthMs);
        return true;
    }

    /**
     * Synthesize one utterance, or report that it could not be spoken.
     *
     * <p>Empty rather than an exception: one utterance losing its audio must not
     * abandon the rest of the turn, and must not reach the caller's handler — that
     * sends an {@code error} frame, which the client treats as fatal and tears the
     * whole session down. Text keeps streaming; the turn degrades to transcript-only.
     */
    private Optional<TtsRouter.Spoken> synthesize(String speakable) {
        try {
            return Optional.of(synthesizer.speak(speakable));
        } catch (RuntimeException e) {
            if (!audioDegraded) {
                audioDegraded = true;
                Logger.warn("voice: turn %d — audio unavailable, continuing text-only: %s",
                        turnId, e.getMessage());
            }
            return Optional.empty();
        }
    }
}
