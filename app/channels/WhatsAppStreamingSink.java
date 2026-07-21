package channels;

import models.Agent;
import services.EventLogger;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link ChannelStreamingSink} for WhatsApp (JCLAW-446). WhatsApp has no live
 * message-edit / streaming API on either transport, so — unlike Telegram's
 * throttled edit-loop — this sink simply buffers and sends the reply once, at
 * {@link #seal(String)}. It is transport-blind: it holds a resolved
 * {@link Channel} (the Cloud-API {@link WhatsAppChannel} or the WhatsApp-Web
 * channel) and calls the generic {@link Channel#sendText(String, String, Agent)},
 * so no transport conditional lives here — polymorphism handles the difference.
 *
 * <p>Per-token {@link #update(String)} batches are ignored (there is nothing to
 * stream into); {@code seal} receives the complete text and delivers it. The
 * typing heartbeat is a no-op in this foundation — a presence/typing indicator is
 * wired per transport in JCLAW-447 (Cloud-API: none) / JCLAW-450 (WhatsApp-Web:
 * {@code COMPOSING}).
 */
public final class WhatsAppStreamingSink implements ChannelStreamingSink {

    private static final String LOG_CATEGORY = "channel";

    private final Channel channel;
    private final String peerId;
    private final Agent agent;
    private final AtomicBoolean sealed = new AtomicBoolean(false);

    public WhatsAppStreamingSink(Channel channel, String peerId, Agent agent) {
        this.channel = channel;
        this.peerId = peerId;
        this.agent = agent;
    }

    @Override
    public void startTypingHeartbeat() {
        // Cue the transport's presence indicator (COMPOSING on WhatsApp-Web; a no-op
        // on Cloud-API, which has no typing API). Polymorphic — no transport branch.
        if (channel != null) {
            channel.startTyping(peerId);
        }
    }

    @Override
    public void update(String token) {
        // WhatsApp can't stream a partial reply — the full text is delivered once
        // at seal(). Intentionally a no-op.
    }

    @Override
    public void seal(String fullText) {
        if (!sealed.compareAndSet(false, true)) return;
        if (fullText == null || fullText.isBlank()) return;
        if (channel == null) {
            EventLogger.warn(LOG_CATEGORY, agentName(), channelName(),
                    "No channel resolved for %s — reply dropped".formatted(peerId));
            return;
        }
        channel.sendText(peerId, fullText, agent);
    }

    @Override
    public void errorFallback(Exception e) {
        if (!sealed.compareAndSet(false, true)) return;
        if (channel != null) {
            channel.sendText(peerId, "Sorry, an error occurred processing your message.", agent);
        }
        EventLogger.error(LOG_CATEGORY, agentName(), channelName(),
                "Streaming error: " + (e != null ? e.getMessage() : "(null)"));
    }

    @Override
    public void cancel() {
        // Quiesce: mark sealed so a late seal()/update() is a no-op. Nothing to
        // send — /stop already acknowledged on its own sink.
        sealed.compareAndSet(false, true);
    }

    private String agentName() {
        return Agent.nameOf(agent);
    }

    private String channelName() {
        return channel != null ? channel.channelName() : "whatsapp";
    }
}
