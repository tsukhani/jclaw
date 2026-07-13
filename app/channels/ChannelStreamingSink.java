package channels;

import java.util.List;

/**
 * JCLAW-442: the streaming-sink contract that
 * {@link agents.AgentRunner#processInboundForAgentStreaming} drives, so any
 * channel can route inbound messages through that single higher-level entry
 * point — which owns slash-command interception, the conversation lifecycle, and
 * attachment finalization — while the sink owns the channel-specific live-reply
 * mechanics.
 *
 * <p>The five core methods are exactly what {@code processInboundForAgentStreaming}
 * invokes on its sink: a typing cue before the first token, per-token updates,
 * completion, error delivery, and cancellation. {@link TelegramStreamingSink}
 * and {@link SlackStreamingSink} implement it. {@link #toolProgress} is an
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

    /** Cancellation (e.g. /stop): quiesce any live indicator/stream. */
    void cancel();

    /** Optional (JCLAW-346): surface a completed tool call (by name) as live
     *  progress before the assistant's text turn begins. Default no-op —
     *  Slack's off-thread draft preview overrides it; Telegram ignores it. */
    default void toolProgress(String toolName) {
        // no-op by default
    }

    /** Optional: record the uuids of a completed tool call's persisted generated
     *  attachments (generate_image's image, diarize_audio's voice clips, a
     *  finished generate_video clip) so a channel that delivers media out-of-band
     *  can upload them at {@link #seal}. Fires once per tool call that produced
     *  attachments; the list is this call's uuids only. Default no-op — the web
     *  transport renders these inline from the SSE frame, and Slack has no native
     *  file-upload send, so both ignore it; {@link TelegramStreamingSink}
     *  overrides it to upload the files as native photo/voice/video messages. */
    default void collectGeneratedAttachments(List<String> attachmentUuids) {
        // no-op by default
    }
}
