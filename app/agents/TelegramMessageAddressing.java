package agents;

/**
 * JCLAW-370: pure, side-effect-free Telegram conversation-keying and
 * sender-attribution helpers. Extracted from {@link AgentRunner} (JCLAW-678);
 * {@code AgentRunner.telegramConversationPeerId} / {@code telegramSenderAttributed}
 * remain as public delegators for the channel callers.
 */
final class TelegramMessageAddressing {

    private TelegramMessageAddressing() {}

    /** Telegram {@code chat.type} for a one-on-one DM; everything else is a group context. */
    private static final String TELEGRAM_CHAT_TYPE_PRIVATE = "private";

    /**
     * JCLAW-370: compute the conversation peer key for an inbound Telegram
     * message. A DM keys off the binding owner ({@code ownerKey} — the
     * binding's {@code telegramUserId}, unchanged from today; in a private chat
     * {@code chat.id == user.id} so DMs are identical to the old behavior). A
     * group / supergroup keys off the chat id so every allowed member shares ONE
     * conversation per chat — owned by the binding's JClaw peer, not per member.
     * A forum-topic message gains a {@code ":topic:<threadId>"} suffix so each
     * topic is its own conversation within the chat. The topic is encoded in the
     * peerId string — no DB schema change ({@code ConversationService} already
     * keys on the {@code (agent, channelType, peerId)} tuple).
     *
     * @param ownerKey        the binding owner key (binding's telegramUserId)
     * @param chatType        Telegram {@code chat.type} string (nullable → treated as group)
     * @param chatId          Telegram chat id
     * @param messageThreadId forum-topic thread id, or null for a non-topic message
     * @return the composite conversation peer key
     */
    static String telegramConversationPeerId(String ownerKey, String chatType, String chatId,
                                             Integer messageThreadId) {
        if (TELEGRAM_CHAT_TYPE_PRIVATE.equals(chatType)) {
            return ownerKey;
        }
        return messageThreadId != null
                ? chatId + ":topic:" + messageThreadId
                : chatId;
    }

    /**
     * JCLAW-370: prefix sender attribution onto a group message so the agent
     * knows WHO spoke in a shared group conversation. No-op for a DM
     * ({@code chat.type == "private"}) and for blank text — DMs stay unannotated
     * exactly as before. The prefix carries the sender's display name (falling
     * back to the id when no name is set) plus the numeric id, e.g.
     * {@code "[Ada Lovelace (id 42)]: hello"}.
     *
     * @param text            the raw inbound message text
     * @param chatType        Telegram {@code chat.type} string (nullable → treated as group)
     * @param fromDisplayName sender's display name, or null
     * @param fromId          sender's Telegram user id
     * @return the (possibly attributed) message text
     */
    static String telegramSenderAttributed(String text, String chatType,
                                           String fromDisplayName, String fromId) {
        if (TELEGRAM_CHAT_TYPE_PRIVATE.equals(chatType) || text == null || text.isEmpty()) {
            return text;
        }
        var who = fromDisplayName != null && !fromDisplayName.isBlank() ? fromDisplayName : fromId;
        return "[%s (id %s)]: %s".formatted(who, fromId, text);
    }
}
