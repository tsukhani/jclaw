package channels;

import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * JCLAW-442: the streaming-sink contract that
 * {@link agents.AgentRunner#processInboundForAgentStreaming} drives, so any
 * channel can route inbound messages through that single higher-level entry
 * point — which owns slash-command interception, the conversation lifecycle, and
 * attachment finalization — while the sink owns the channel-specific live-reply
 * mechanics.
 *
 * <p>The six core methods are exactly what {@code processInboundForAgentStreaming}
 * invokes on its sink: a typing cue before the first token, per-token updates,
 * completion, error delivery, cancellation, and a stop. {@link TelegramStreamingSink},
 * {@link SlackStreamingSink} and {@link WhatsAppStreamingSink} implement it. {@link #toolProgress} is an
 * optional hook (default no-op) a channel can override to surface tool activity.
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

    /** Cancellation with no stop behind it, as interrupt mode's: quiesce any live indicator/stream. */
    void cancel();

    /**
     * A stop ended the turn (JF-197b): quiesce as {@link #cancel} does, leaving the partial reply as the person saw it,
     * then send {@code notice}, when there is one, where the reply would have gone. Nothing else follows: no final
     * edit, no generated media, no later token. A sink that already delivered the reply sends nothing.
     */
    void stop(@Nullable String notice);

    /** Optional (JCLAW-346): surface a completed tool call (by name) as live
     *  progress before the assistant's text turn begins. Default no-op —
     *  Slack's off-thread draft preview overrides it; Telegram ignores it. */
    default void toolProgress(@Nullable String toolName) {
        // no-op by default
    }

    /** Optional: record the uuids of a completed tool call's persisted generated
     *  attachments (generate_image's image, diarize_audio's voice clips, a
     *  finished generate_video clip) so a channel that delivers media out-of-band
     *  can upload them at {@link #seal}. Fires once per tool call that produced
     *  attachments; the list is this call's uuids only. Default no-op — the web
     *  transport renders these inline from the SSE frame, so it ignores it;
     *  {@link TelegramStreamingSink} and {@link SlackStreamingSink} override it
     *  to upload the files. */
    default void collectGeneratedAttachments(List<String> attachmentUuids) {
        // no-op by default
    }
}
