package channels;

import services.EventLogger;

/**
 * Contract for outbound message delivery to an external channel. Each implementation
 * handles its own API specifics (URL, auth headers, response parsing). The retry
 * logic is shared via the default {@link #sendWithRetry} method.
 *
 * <p>Implementations MUST NOT throw — failures are logged and returned as {@code false}.
 */
public interface Channel {

    /** Short channel name for logging (e.g. "slack", "telegram", "whatsapp"). */
    String channelName();

    /**
     * Attempt a single delivery of {@code text} to {@code peerId}. Returns true on
     * success. Must not throw — log warnings and return false on transient failures.
     */
    boolean trySend(String peerId, String text);

    /**
     * Retry delay hint (ms) from the most recent failed {@link #trySend} on THIS thread.
     * Defaults to 1000 ms. Channels with platform-specific rate-limit signals
     * (e.g. Telegram's {@code retry_after}, Slack's {@code Retry-After} header) should
     * override to publish that hint to {@link #sendWithRetry}. Reading consumes the
     * hint — a second call after a failure returns the default.
     */
    default long consumeRetryDelayMs() {
        return 1000L;
    }

    /**
     * Send a message with a single retry on failure. The delay between attempts is
     * taken from {@link #consumeRetryDelayMs()} — capped at 60 s so a buggy platform
     * response can't stall an agent response indefinitely.
     */
    default boolean sendWithRetry(String peerId, String text) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (trySend(peerId, text)) return true;
            if (attempt == 0) {
                long delayMs = Math.min(consumeRetryDelayMs(), 60_000L);
                try { Thread.sleep(delayMs); } catch (InterruptedException _) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        EventLogger.error("channel", null, channelName(),
                "Failed to send message to %s after retries".formatted(peerId));
        return false;
    }
}
