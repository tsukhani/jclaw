package channels;

import agents.AgentRunner;
import models.Agent;
import models.WhatsAppBinding;
import services.EventLogger;
import services.Tx;

/**
 * Shared WhatsApp inbound dispatch (JCLAW-446/450), independent of how the message
 * arrived — the direct analog of {@link SlackInbound}. Both transports normalize
 * their platform payload into a {@link WhatsAppInboundMessage} and hand it here:
 *
 * <ul>
 *   <li>The Cloud-API webhook ({@link controllers.WebhookWhatsAppController})
 *       verifies the HMAC, parses the JSON, and calls {@link #dispatchMessage}.</li>
 *   <li>WhatsApp-Web (the Cobalt runner) receives messages over its socket, parses
 *       the SDK objects, and calls the same {@link #dispatchMessage}.</li>
 * </ul>
 *
 * <p>Every business rule — dedup, access gate, group attribution, peerId routing,
 * media staging, agent dispatch — lives here, never in the transport parsers, so
 * the two transports can never drift.
 */
public final class WhatsAppInbound {

    private WhatsAppInbound() {}

    static final String CHANNEL_WHATSAPP = "whatsapp";
    static final String CATEGORY_CHANNEL = "channel";

    private record ResolvedBinding(WhatsAppBinding binding, Agent agent) {}

    /**
     * Handle one normalized inbound message: dedup it, apply the access gate, and
     * dispatch the bound agent off-thread. Duplicate, reaction, and gate-rejected
     * messages are handled/dropped here. {@code binding}'s simple columns are read
     * directly (works on a detached entity from the socket/webhook thread); the
     * lazy agent association is re-resolved on the virtual thread.
     */
    public static void dispatchMessage(WhatsAppBinding binding, WhatsAppInboundMessage msg) {
        if (binding == null || msg == null) return;

        // Drop redelivered events (Meta retries the webhook; a reconnecting socket
        // can replay) so the agent processes each message exactly once.
        if (!InboundEventDedup.firstSeen(msg.messageId())) {
            EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                    "Duplicate WhatsApp event %s dropped".formatted(msg.messageId()));
            return;
        }
        // Reactions take their own path (routing them to the agent is out of scope here).
        if (msg.type() == WhatsAppInboundMessage.MessageType.REACTION) {
            dispatchReaction(binding, msg);
            return;
        }
        // Access gate: owner-in-DM, mention-gated groups; an owner-less (Cloud-API
        // business) binding serves its DMs openly.
        if (!WhatsAppAccessPolicy.isAllowed(binding.ownerJid, msg.from(), msg.chatType(), msg.botMentioned())) {
            EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                    "Message from %s in %s (%s) dropped by access policy".formatted(
                            msg.from(), msg.chatId(), msg.chatType()));
            return;
        }
        EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                "Message received from %s in %s".formatted(msg.from(), msg.chatId()));

        var bindingId = binding.id;
        Thread.ofVirtual().name("whatsapp-inbound").start(() -> processMessage(bindingId, msg));
    }

    /**
     * Handle an inbound reaction. Routing it to the agent is out of scope for the
     * foundation (JCLAW-450) — recorded here so the path exists and is observable.
     */
    public static void dispatchReaction(WhatsAppBinding binding, WhatsAppInboundMessage msg) {
        var r = msg.reaction();
        EventLogger.info(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                "Reaction %s on %s from %s".formatted(
                        r != null ? r.emoji() : "?",
                        r != null ? r.targetMessageId() : "?",
                        msg.from()));
    }

    private static void processMessage(Long bindingId, WhatsAppInboundMessage msg) {
        try {
            // Re-resolve binding + agent on this virtual thread; bail if the binding
            // was deleted/disabled between accept and dispatch.
            var resolved = Tx.run(() -> {
                WhatsAppBinding b = WhatsAppBinding.findById(bindingId);
                if (b == null || !b.enabled) return null;
                return new ResolvedBinding(b, b.agent);
            });
            if (resolved == null || resolved.agent() == null) return;
            var binding = resolved.binding();
            var agent = resolved.agent();

            var channel = WhatsAppChannelFactory.forBinding(binding);
            if (channel == null) {
                EventLogger.warn(CATEGORY_CHANNEL, agent.name, CHANNEL_WHATSAPP,
                        "No outbound channel for binding %d (transport %s) — message not processed"
                                .formatted(bindingId, binding.transport));
                return;
            }

            var attachments = WhatsAppMediaDownloader.downloadAll(binding, msg, agent.name);
            var peerId = conversationPeerId(msg);
            var text = senderAttributed(msg);
            AgentRunner.processInboundForAgentStreaming(
                    agent, CHANNEL_WHATSAPP, peerId, text,
                    _ -> new WhatsAppStreamingSink(channel, peerId, agent),
                    attachments, msg.chatType());
        } catch (Exception e) {
            EventLogger.error(CATEGORY_CHANNEL, null, CHANNEL_WHATSAPP,
                    "Error processing message: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Conversation peer key: a 1:1 keys off the sender (DM), a group keys off the
     * chat id so every allowed member shares ONE conversation per group. Mirrors
     * {@link AgentRunner#telegramConversationPeerId} but kept local (no
     * WhatsApp-specifics in {@code AgentRunner}, per the shared-core design).
     * Public for the default-package test seam, mirroring Telegram's equivalents.
     */
    public static String conversationPeerId(WhatsAppInboundMessage msg) {
        return msg.isGroup() ? msg.chatId() : msg.from();
    }

    /**
     * Prefix sender attribution onto a group message so the agent knows who spoke
     * in a shared group conversation. No-op for a DM or blank text. The local
     * analog of {@link AgentRunner#telegramSenderAttributed}.
     */
    public static String senderAttributed(WhatsAppInboundMessage msg) {
        var text = msg.text();
        if (!msg.isGroup() || text == null || text.isEmpty()) {
            return text;
        }
        var who = msg.senderDisplayName() != null && !msg.senderDisplayName().isBlank()
                ? msg.senderDisplayName() : msg.from();
        return "[%s]: %s".formatted(who, text);
    }
}
