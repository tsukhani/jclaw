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
     * Send a message with a single retry on failure (1s backoff between attempts).
     * Shared across all channel implementations to eliminate the duplicated
     * retry/sleep/log skeleton.
     */
    default boolean sendWithRetry(String peerId, String text) {
        for (int attempt = 0; attempt < 2; attempt++) {
            if (trySend(peerId, text)) return true;
            if (attempt == 0) {
                try { Thread.sleep(1000); } catch (InterruptedException _) {
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
