package channels;

/**
 * Inbound access policy for WhatsApp messages (JCLAW-446/450) — the WhatsApp
 * analog of {@link TelegramAccessPolicy} and {@link SlackAccessPolicy}. Per the
 * codebase's per-channel convention, WhatsApp has its own policy rather than
 * sharing another channel's (the access models genuinely differ). Its shape is
 * closest to Telegram's (owner-in-DM, mention-gated groups), with one addition:
 * a binding with no owner configured serves its DMs openly — the Cloud-API case,
 * where the bound number is a business number anyone may message.
 *
 * <ul>
 *   <li><b>Direct (1:1)</b> — when an owner is configured (a WhatsApp-Web binding
 *       knows the JID of the user who paired it), only that owner is served;
 *       with no owner (a Cloud-API business number) any sender is served.</li>
 *   <li><b>Group</b> — served for any member, but only when the bot was directly
 *       addressed ({@code botMentioned}). Unaddressed group chatter is ignored.
 *       Groups are intentionally NOT owner-restricted (mention-gated guests),
 *       mirroring Telegram. (The Cloud API has no groups, so this path is
 *       WhatsApp-Web only.)</li>
 * </ul>
 */
public final class WhatsAppAccessPolicy {

    private WhatsAppAccessPolicy() {}

    /**
     * @param ownerId      the binding owner id (the paired user's JID for
     *                     WhatsApp-Web; null/blank when unset — a Cloud-API
     *                     business number)
     * @param fromId       the sender's id
     * @param chatType     {@link WhatsAppInboundMessage#CHAT_DIRECT} or
     *                     {@link WhatsAppInboundMessage#CHAT_GROUP} (nullable →
     *                     treated as direct, the safer default for a 1:1-first bot)
     * @param botMentioned whether the bot was directly addressed in this message
     * @return true to serve the message, false to silently ignore it
     */
    public static boolean isAllowed(String ownerId, String fromId, String chatType,
                                    boolean botMentioned) {
        if (WhatsAppInboundMessage.CHAT_GROUP.equals(chatType)) {
            // Group: mention-gated guests — any member who addresses the bot.
            return botMentioned;
        }
        // Direct: owner-only when an owner is configured, else open (business number).
        var ownerConfigured = ownerId != null && !ownerId.isBlank();
        return !ownerConfigured || ownerId.equals(fromId);
    }
}
