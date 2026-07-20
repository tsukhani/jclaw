package services.voice;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;

/**
 * Per-connection streaming voice pipeline (JCLAW-799). Consumes a continuous
 * PCM16 mic stream and turns it into utterances: {@link PcmWindower} reframes
 * the bytes into VAD windows, {@link VoiceVad} labels each speech/non-speech,
 * and {@link TurnEndpointer} decides when an utterance opens and closes. Turn
 * boundaries surface through a {@link Listener} so {@code VoiceController} owns
 * only the WebSocket and turn lifecycle.
 *
 * <p>This is the server-side endpointing that replaces the old client energy
 * VAD: the browser now just streams frames, and the server decides where turns
 * begin and end. A short pre-roll ring preserves the utterance onset (the
 * windows consumed while detecting speech-start), so nothing is clipped.
 *
 * <p>Not thread-safe: fed only from the WebSocket inbound thread. The finalized
 * utterance is emitted as a self-contained 16&nbsp;kHz mono WAV, ready for the
 * existing transcribe / native-audio turn path.
 */
public final class VoiceSession implements AutoCloseable {

    /** Turn-boundary callbacks, invoked on the inbound thread. */
    public interface Listener {
        /** The user just started speaking — a barge-in if a turn is in flight. */
        void onSpeechStart();

        /** A finalized utterance (16 kHz mono WAV) to transcribe and answer. */
        void onUtterance(byte[] wav);
    }

    /** Interim (pre-endpoint) transcription hook (JCLAW-798): a growing-utterance
     *  WAV to transcribe for a live partial transcript. {@code null} disables it. */
    @FunctionalInterface
    public interface Partial {
        void onInterim(byte[] wav);
    }

    private final VoiceVad vad;
    private final TurnEndpointer endpointer;
    private final PcmWindower windower;
    private final Listener listener;
    private final Partial partial;         // nullable — interim transcript hook
    private final long partialIntervalMs;  // min gap between interim emits
    private final int prerollWindows;
    private final ArrayDeque<float[]> preroll = new ArrayDeque<>();
    private final ByteArrayOutputStream utterance = new ByteArrayOutputStream();
    private long frameIndex;
    private long lastPartialMs;
    private boolean buffering;

    public VoiceSession(VoiceVad vad, TurnEndpointer endpointer, int prerollWindows, Listener listener) {
        this(vad, endpointer, prerollWindows, listener, null, 0);
    }

    public VoiceSession(VoiceVad vad, TurnEndpointer endpointer, int prerollWindows, Listener listener,
                        Partial partial, long partialIntervalMs) {
        this.vad = vad;
        this.endpointer = endpointer;
        this.windower = new PcmWindower(VoiceVad.WINDOW);
        this.prerollWindows = Math.max(0, prerollWindows);
        this.listener = listener;
        this.partial = partial;
        this.partialIntervalMs = partialIntervalMs;
    }

    /** Feed a raw binary frame of little-endian PCM16 from the browser. */
    public void onPcm(byte[] pcm, int len) {
        windower.accept(pcm, len, this::onWindow);
    }

    private void onWindow(float[] w) {
        boolean speech = vad.isSpeech(w);
        long tsMs = frameIndex++ * VoiceVad.WINDOW_MS;
        switch (endpointer.accept(speech, tsMs)) {
            case SPEECH_STARTED -> {
                buffering = true;
                utterance.reset();
                for (var p : preroll) appendWindow(p); // seed with the onset we already consumed
                preroll.clear();
                appendWindow(w);
                lastPartialMs = tsMs; // first interim comes one interval into speech
                listener.onSpeechStart();
            }
            case ENDPOINT -> {
                buffering = false;
                var pcm = utterance.toByteArray();
                utterance.reset();
                preroll.clear();
                listener.onUtterance(wrapWav(pcm)); // drops the trailing silence window
            }
            case NONE -> {
                if (buffering) {
                    appendWindow(w);
                    maybeEmitPartial(tsMs);
                }
                else {
                    preroll.addLast(w);
                    if (preroll.size() > prerollWindows) preroll.removeFirst();
                }
            }
        }
    }

    /** Emit the growing utterance for an interim transcript, throttled to at most
     *  once per {@code partialIntervalMs}. Fire-and-forget: the sink transcribes
     *  off-thread and single-flights, so this never blocks the inbound loop. */
    private void maybeEmitPartial(long tsMs) {
        if (partial == null || utterance.size() == 0) return;
        if (tsMs - lastPartialMs < partialIntervalMs) return;
        lastPartialMs = tsMs;
        partial.onInterim(wrapWav(utterance.toByteArray()));
    }

    private void appendWindow(float[] w) {
        for (float f : w) {
            int s = Math.round(Math.max(-1f, Math.min(1f, f)) * 32767f);
            utterance.write(s & 0xFF);
            utterance.write((s >> 8) & 0xFF);
        }
    }

    /** Wrap PCM16 mono samples in a 16 kHz WAV container. */
    static byte[] wrapWav(byte[] pcm) {
        int rate = VoiceVad.SAMPLE_RATE;
        var buf = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        buf.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(36 + pcm.length);
        buf.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        buf.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(16);            // PCM fmt chunk size
        buf.putShort((short) 1);   // PCM
        buf.putShort((short) 1);   // mono
        buf.putInt(rate);
        buf.putInt(rate * 2);      // byte rate (mono 16-bit)
        buf.putShort((short) 2);   // block align
        buf.putShort((short) 16);  // bits/sample
        buf.put("data".getBytes(StandardCharsets.US_ASCII));
        buf.putInt(pcm.length);
        buf.put(pcm);
        return buf.array();
    }

    @Override
    public void close() {
        vad.close();
    }
}
