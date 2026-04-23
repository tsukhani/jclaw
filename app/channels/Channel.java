package channels;

import services.EventLogger;

/**
 * Contract for outbound message delivery to an external channel. Each implementation
 * handles its own API specifics (URL, auth headers, response parsing). The retry
 * logic is shared via the default {@link #sendWithRetry} method.
 *
 * <p>Implementations MUST NOT throw — failures are returned as {@link SendResult}.
 */
public interface Channel {

    /**
     * Result of a single delivery attempt. Carries both the success flag and,
     * when the platform surfaced a rate-limit hint (e.g. Telegram's
     * {@code retry_after}, Slack's {@code Retry-After} header), the number of
     * milliseconds {@link #sendWithRetry} should wait before the next attempt.
     * Returning the hint as a value instead of via per-thread state ensures
     * correctness on virtual-thread-per-task dispatch, where the writer and
     * the reader of the hint can live on different carriers (JCLAW-137).
     */
    record SendResult(boolean ok, long retryAfterMs) {
        public static final SendResult OK = new SendResult(true, 0L);
        public static final SendResult FAILED = new SendResult(false, 0L);
        public static SendResult rateLimited(long retryAfterMs) {
            return new SendResult(false, retryAfterMs);
        }
    }

    /** Short channel name for logging (e.g. "slack", "telegram", "whatsapp"). */
    String channelName();

    /**
     * Attempt a single delivery of {@code text} to {@code peerId}. Returns
     * {@link SendResult#OK} on success, {@link SendResult#FAILED} on generic
     * failure, or {@link SendResult#rateLimited(long)} when the platform
     * surfaced a back-off hint. Must not throw — log warnings and return a
     * failed result on transient errors.
     */
    SendResult trySend(String peerId, String text);

    /**
     * Send a message with a single retry on failure. The delay between attempts
     * is taken from the prior {@link SendResult#retryAfterMs()} when non-zero,
     * or 1 s otherwise, capped at 60 s so a buggy platform response can't stall
     * an agent response indefinitely.
     */
    default boolean sendWithRetry(String peerId, String text) {
        SendResult result = trySend(peerId, text);
        if (result.ok()) return true;
        long delayMs = Math.min(result.retryAfterMs() > 0 ? result.retryAfterMs() : 1000L, 60_000L);
        try {
            Thread.sleep(delayMs);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (trySend(peerId, text).ok()) return true;
        EventLogger.error("channel", null, channelName(),
                "Failed to send message to %s after retries".formatted(peerId));
        return false;
    }
}
