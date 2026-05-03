package utils;

import java.util.function.Consumer;

/**
 * JCLAW-200: minimal char-threshold coalescer for SSE token callbacks.
 *
 * <p>Each SSE frame triggers a Netty {@code resumeChunkedTransfer} + flush,
 * costing roughly 25 ms per chunk on loopback. Buffering tokens until at
 * least {@link #threshold} characters have accumulated, then flushing one
 * larger frame, trades per-token granularity for fewer flushes. Threshold
 * of {@code 0} disables coalescing — every {@code accept} call flushes
 * immediately, matching pre-coalescer behavior.
 *
 * <p>Thread-safe: a single buffer protected by an internal monitor so
 * tokens arriving from any thread (LLM stream callback, virtual-thread
 * tool round) coalesce safely.
 *
 * <p>Caller must invoke {@link #drain()} before terminal frames so any
 * tail tokens (between the last threshold crossing and the end of the
 * stream) reach the client.
 */
public final class TokenCoalescer {

    private final int threshold;
    private final Consumer<String> flushFn;
    private final StringBuilder buf = new StringBuilder();

    public TokenCoalescer(int threshold, Consumer<String> flushFn) {
        if (flushFn == null) throw new IllegalArgumentException("flushFn must not be null");
        this.threshold = Math.max(0, threshold);
        this.flushFn = flushFn;
    }

    /** Accept one token. Flushes immediately when threshold is 0, otherwise buffers. */
    public void accept(String token) {
        if (token == null || token.isEmpty()) return;
        if (threshold == 0) {
            flushFn.accept(token);
            return;
        }
        boolean shouldFlush;
        synchronized (buf) {
            buf.append(token);
            shouldFlush = buf.length() >= threshold;
        }
        if (shouldFlush) drain();
    }

    /** Flush any pending buffered text. Idempotent — no-op when buffer is empty. */
    public void drain() {
        String pending;
        synchronized (buf) {
            if (buf.length() == 0) return;
            pending = buf.toString();
            buf.setLength(0);
        }
        flushFn.accept(pending);
    }
}
