package services.voice;

import java.util.function.Consumer;

/**
 * Reframes a continuous little-endian 16-bit PCM byte stream into fixed-size
 * {@code float} windows (JCLAW-799). The browser streams mic audio in
 * arbitrarily-sized binary frames; the Silero VAD needs exactly
 * {@link VoiceVad#WINDOW}-sample windows, so this buffers across frame
 * boundaries — including a single odd byte that splits a sample between two
 * frames — and invokes a callback once per complete window.
 *
 * <p>Pure and stateful-but-deterministic (no audio APIs, no threads): the
 * carrier thread feeds bytes and the windows fall out, so the framing is
 * unit-tested directly. Samples are normalized to {@code [-1, 1)}.
 * Not thread-safe; drive it from one thread (the WebSocket inbound loop).
 */
public final class PcmWindower {

    private final int windowSize;
    private final float[] window;
    private int filled;
    private int pendingByte = -1; // low byte of a sample split across two frames

    public PcmWindower(int windowSize) {
        if (windowSize <= 0) throw new IllegalArgumentException("windowSize must be positive");
        this.windowSize = windowSize;
        this.window = new float[windowSize];
    }

    /**
     * Feed {@code len} bytes of little-endian PCM16; {@code onWindow} fires once
     * per full window with a fresh copy (safe to retain).
     */
    public void accept(byte[] pcm, int len, Consumer<float[]> onWindow) {
        int i = 0;
        // Complete a sample whose low byte arrived at the tail of a prior frame.
        if (pendingByte >= 0 && i < len) {
            int sample = (short) ((pcm[i++] << 8) | pendingByte);
            pendingByte = -1;
            push(sample, onWindow);
        }
        while (i + 1 < len) {
            int lo = pcm[i] & 0xFF;
            int hi = pcm[i + 1];
            i += 2;
            push((short) ((hi << 8) | lo), onWindow);
        }
        if (i < len) {
            pendingByte = pcm[i] & 0xFF; // lone low byte; wait for its high byte
        }
    }

    private void push(int sample16, Consumer<float[]> onWindow) {
        window[filled++] = sample16 / 32768f;
        if (filled == windowSize) {
            onWindow.accept(window.clone());
            filled = 0;
        }
    }

    /** Drop any partial window / split-sample state at a session boundary. */
    public void reset() {
        filled = 0;
        pendingByte = -1;
    }
}
