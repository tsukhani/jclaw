package channels;

/**
 * JCLAW-442: the streaming-sink contract that
 * {@link agents.AgentRunner#processInboundForAgentStreaming} drives, so any
 * channel can route inbound messages through that single higher-level entry
 * point — which owns slash-command interception, the conversation lifecycle, and
 * attachment finalization — while the sink owns the channel-specific live-reply
 * mechanics.
 *
 * <p>The five methods are exactly what {@code processInboundForAgentStreaming}
 * invokes on its sink: a typing cue before the first token, per-token updates,
 * completion, error delivery, and cancellation. {@link TelegramStreamingSink}
 * and {@link SlackStreamingSink} implement it.
 */
public interface ChannelStreamingSink {

    /** Show a "thinking…" cue during the prologue gap (request received → first
     *  token). Self-cancels on the first {@link #update}, {@link #seal}, or
     *  {@link #errorFallback}. */
    void startTypingHeartbeat();

    /** Per-token-batch hook: progressively render the reply. */
    void update(String token);

    /** Completion: finalize the reply with the full text. */
    void seal(String fullText);

    /** Error: deliver a failure notice and finalize. */
    void errorFallback(Exception e);

    /** Cancellation (e.g. /stop): quiesce any live indicator/stream. */
    void cancel();
}
