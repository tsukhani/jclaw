package channels;

import services.EventLogger;
import utils.RetryScheduler;

import java.io.File;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Contract for outbound message delivery to an external channel. Each implementation
 * handles its own API specifics (URL, auth headers, response parsing). The retry
 * logic is shared via the default {@link #sendWithRetry} method.
 *
 * <p>Implementations MUST NOT throw — failures are returned as {@link SendResult}.
 */
public interface Channel {

    /**
     * Result of a single delivery attempt. Returning the rate-limit hint as
     * a value instead of via per-thread state ensures correctness on
     * virtual-thread-per-task dispatch, where the writer and the reader of
     * the hint can live on different carriers (JCLAW-137).
     *
     * @param ok            true when the platform accepted the message
     * @param retryAfterMs  when the platform surfaced a rate-limit hint
     *                      (e.g. Telegram's {@code retry_after}, Slack's
     *                      {@code Retry-After} header), the number of
     *                      milliseconds {@link #sendWithRetry} should wait
     *                      before the next attempt; {@code 0} when no hint
     *                      was provided
     */
    // Sonar java:S1845 flags OK/FAILED constants as case-clashing with the
    // record's `ok` component, but constant-instances-named-after-the-concept
    // is canonical Java idiom (cf. Optional.empty(), Collections.emptyList())
    // and is the form every call site already uses. Renaming would be net
    // negative for readability.
    @SuppressWarnings("java:S1845")
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
     *
     * <p>The retry is scheduled via {@link RetryScheduler} on a platform-thread
     * carrier so a virtual-thread caller (the agent dispatch path) unmounts
     * cleanly during the wait — avoiding JDK-8373224 FJP starvation that
     * direct {@code Thread.sleep} on many concurrent VTs would trigger.
     */
    default boolean sendWithRetry(String peerId, String text) {
        SendResult result = trySend(peerId, text);
        if (result.ok()) return true;
        long delayMs = Math.min(result.retryAfterMs() > 0 ? result.retryAfterMs() : 1000L, 60_000L);
        try {
            // 5 s slack covers the scheduler hop + the second trySend's own latency.
            boolean ok = RetryScheduler.schedule(() -> trySend(peerId, text).ok(), delayMs)
                    .get(delayMs + 5_000L, TimeUnit.MILLISECONDS);
            if (ok) return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException _) {
            // Fall through to the error-log branch below.
        }
        EventLogger.error("channel", null, channelName(),
                "Failed to send message to %s after retries".formatted(peerId));
        return false;
    }

    /**
     * JCLAW-141: generic cross-channel text send. This is the OCP send entry
     * point dispatch sites (e.g. {@code AgentRunner.dispatchToChannel}) call
     * instead of branching on the channel type. The default applies the
     * shared single-retry {@link #sendWithRetry} policy on top of {@link #trySend},
     * surfacing the outcome as a {@link SendResult}; implementations that need
     * channel-specific formatting or chunking (e.g. Telegram's markdown→HTML
     * planner path) override this. Must not throw.
     */
    default SendResult sendText(String peerId, String text) {
        return sendWithRetry(peerId, text) ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-141: agent-aware generic text send. The agent context lets a channel
     * resolve workspace-relative file links into native file uploads (Telegram's
     * outbound planner) and apply agent-scoped formatting. The default ignores
     * {@code agent} and delegates to {@link #sendText(String, String)}, so
     * channels without agent-specific behavior (Slack, WhatsApp, web) need no
     * override. Dispatch sites that hold an agent (queue-drain, webhook reply)
     * call this so the resolved channel — whatever its type — does the right
     * thing without the caller branching. Must not throw.
     */
    default SendResult sendText(String peerId, String text, models.Agent agent) {
        return sendText(peerId, text);
    }

    /**
     * JCLAW-141: generic cross-channel photo send. {@code caption} (null/blank to
     * omit) rides with the image. Channels without a native photo-upload path
     * return {@link SendResult#FAILED} via this default so a caller can detect
     * the no-op uniformly; channels that support it (Telegram) override. Must not
     * throw.
     */
    default SendResult sendPhoto(String peerId, File file, String caption) {
        return SendResult.FAILED;
    }

    /**
     * JCLAW-141: generic cross-channel document send. {@code caption} (null/blank
     * to omit) rides with the file. Channels without a native document-upload
     * path return {@link SendResult#FAILED} via this default; channels that
     * support it (Telegram) override. Must not throw.
     */
    default SendResult sendDocument(String peerId, File file, String caption) {
        return SendResult.FAILED;
    }

    /**
     * Show a transient "typing…" / presence indicator to {@code peerId} during the
     * agent's prologue, when the platform supports one (JCLAW-450). Default no-op —
     * Cloud-API WhatsApp, Slack, Telegram, and web have no socket presence to push
     * here; WhatsApp-Web (Cobalt) overrides it with a {@code COMPOSING} presence.
     * Lets {@code WhatsAppStreamingSink} cue typing polymorphically with no transport
     * branch. Must not throw.
     */
    default void startTyping(String peerId) {
        // no-op by default
    }
}
