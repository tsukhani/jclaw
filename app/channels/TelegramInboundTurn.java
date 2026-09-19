package channels;

import agents.AgentRunner;
import agents.DangerousActionGate;
import models.Agent;
import models.TelegramBinding;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.Tx;

/**
 * JCLAW-1231: the inbound Telegram turn, once. The webhook controller and the polling
 * runner each carried a copy — attachment staging, conversation keying, sender
 * attribution, topic routing, the owner-initiated binding and the streaming sink — and
 * the copies had drifted: only the webhook told the sender when the turn failed.
 */
public final class TelegramInboundTurn {

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    private TelegramInboundTurn() {}

    /**
     * Run one inbound message as an agent turn. Both transports call this on an
     * off-request virtual thread, after their own access gate has accepted the
     * message and any coalescing lane has merged it.
     *
     * @param ownerTelegramUserId the binding owner — the DM conversation key, and the
     *                            id {@code message.fromId()} is compared against for
     *                            {@link DangerousActionGate#withOwnerInitiated}
     * @param bindingAgent        the binding's default agent; a forum topic may route
     *                            the turn to an override agent instead
     */
    public static void run(Long bindingId, String botToken, String ownerTelegramUserId,
                           Agent bindingAgent, InboundMessage message) {
        final String chatId = message.chatId();
        try {
            // JCLAW-136: gate attachments against the agent's model capabilities, then
            // download each into workspace staging. Rejections (modality mismatch, size
            // exceeded, network failures) reply to the user rather than dropping silently.
            var inputs = TelegramChannel.prepareInboundAttachments(
                    botToken, chatId, bindingAgent, message);
            if (inputs == null) return; // prepareInboundAttachments already replied + logged

            final String chatType = message.chatType();
            // JCLAW-370: a DM keys off the binding owner; an allowed group/supergroup keys
            // off the chat id (one shared conversation per chat, per forum topic) so members
            // share one transcript owned by the binding's JClaw peer. Sender attribution is
            // prefixed onto group messages so the agent can tell members apart. The outbound
            // sink still routes to the chat id.
            final String peerId = AgentRunner.telegramConversationPeerId(
                    ownerTelegramUserId, chatType, chatId, message.messageThreadId());
            final String attributedText = AgentRunner.telegramSenderAttributed(
                    message.text(), chatType, message.fromDisplayName(), message.fromId());
            // JCLAW-377: a forum-topic message runs on its per-topic override agent when one
            // is mapped; peerId and sink are unchanged — only which agent runs the turn.
            final Agent runAgent = resolveTopicAgent(
                    botToken, chatId, message.messageThreadId(), bindingAgent);
            // JCLAW-1061: bound HERE, not at the access check — the turn runs on this thread,
            // which the check's thread had already left. Recomputed rather than carried: every
            // coalescing lane keys its bucket per sender, so `message` is one person's and its
            // fromId is the value that was checked at the door.
            final boolean ownerInitiated = ownerTelegramUserId.equals(message.fromId());
            // JCLAW-94/95: the sink owns send/edit/delete of the preview message and delegates
            // to the planner on seal; the factory defers construction until AgentRunner has
            // resolved the conversation id. JCLAW-387 B4: the chat.type stamps the new
            // conversation (plain DM vs group history caps).
            DangerousActionGate.withOwnerInitiated(ownerInitiated, () -> {
                AgentRunner.processInboundForAgentStreaming(
                        runAgent, CHANNEL_NAME, peerId, attributedText,
                        convId -> new TelegramStreamingSink(
                                botToken, chatId, bindingAgent, convId, chatType,
                                message.messageId(), message.messageThreadId()),
                        inputs, chatType);
                return null;
            });
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, Agent.nameOf(bindingAgent), CHANNEL_NAME,
                    "Error processing message for binding %d: %s".formatted(bindingId, e.getMessage()));
            // The sender is owed an answer on every transport: the polling path used to log
            // only, leaving them waiting on a turn that had already died (JCLAW-1231).
            TelegramChannel.forToken(botToken).sendText(chatId,
                    "Sorry, an error occurred processing your message.");
        }
    }

    /**
     * JCLAW-377: which agent runs a turn for {@code (chatId, threadId)} — the per-topic
     * override when mapped, otherwise {@code defaultAgent}. The read runs in a
     * {@link Tx} (the caller's virtual thread has no ambient JPA transaction) and the
     * resolved agent's name is touched eagerly to avoid detached-proxy access on the
     * streaming path. Falls back to {@code defaultAgent} when the binding cannot be
     * found (e.g. removed between receive and dispatch).
     */
    public static Agent resolveTopicAgent(String botToken, String chatId, @Nullable Integer threadId,
                                          Agent defaultAgent) {
        return Tx.run(() -> {
            TelegramBinding binding = TelegramBinding.findByBotToken(botToken);
            if (binding == null) return defaultAgent;
            Agent resolved = binding.resolveAgentForTopic(chatId, threadId);
            if (resolved != null) {
                var _ = resolved.name; // touch inside tx to avoid detached-proxy access later
            }
            // Fall back to the binding default if a topic-override's agent FK was orphaned
            // (agent deleted): resolveAgentForTopic returns null then, and a null agent NPEs
            // in ConversationService downstream.
            return resolved != null ? resolved : defaultAgent;
        });
    }
}
