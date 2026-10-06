package channels;

import org.jspecify.annotations.Nullable;

import java.util.Set;

/**
 * Inbound access policy for WhatsApp messages (JCLAW-446/450/1408) — the WhatsApp
 * analog of {@link TelegramAccessPolicy} and {@link SlackAccessPolicy}. Direct
 * messages follow Slack's DM rule; groups stay mention-gated for any member.
 *
 * <ul>
 *   <li><b>Direct (1:1)</b> — with an owner number configured (WhatsApp-Web only),
 *       only that number is served; with none, any sender is served, unless
 *       {@code ownerRequired} (a Main Agent WhatsApp-Web binding), which serves no
 *       one until an owner is set. A Cloud-API binding passes no owner and
 *       {@code ownerRequired=false}, so its DMs stay open.</li>
 *   <li><b>Group</b> — served for any member, but only when the bot was directly
 *       addressed ({@code botMentioned}). Groups are intentionally NOT
 *       owner-restricted. (The Cloud API has no groups.)</li>
 * </ul>
 */
public final class WhatsAppAccessPolicy {

    private static final Set<String> PHONE_SERVERS = Set.of("s.whatsapp.net", "c.us");

    private WhatsAppAccessPolicy() {}

    /**
     * @param ownerNumber   the WhatsApp-Web owner's phone number (E.164; null/blank = unset)
     * @param fromId        the sender's JID
     * @param chatType      {@link WhatsAppInboundMessage#CHAT_DIRECT} or
     *                      {@link WhatsAppInboundMessage#CHAT_GROUP} (nullable →
     *                      treated as direct, the safer default for a 1:1-first bot)
     * @param botMentioned  whether the bot was directly addressed in this message
     * @param ownerRequired true when DMs need an owner (a Main Agent binding): with
     *                      none configured no DM is served (fail closed)
     * @return true to serve the message, false to silently ignore it
     */
    public static boolean isAllowed(@Nullable String ownerNumber, String fromId, @Nullable String chatType,
                                    boolean botMentioned, boolean ownerRequired) {
        if (WhatsAppInboundMessage.CHAT_GROUP.equals(chatType)) {
            return botMentioned;
        }
        if (ownerNumber != null && !ownerNumber.isBlank()) {
            return sameNumber(ownerNumber, fromId);
        }
        return !ownerRequired;
    }

    /**
     * Whether JID {@code from} is the phone number {@code owner}: a phone-number JID
     * ({@code s.whatsapp.net} / {@code c.us}, any {@code :device} or {@code .agent}
     * suffix dropped) whose user part equals the owner's digits. A LID or group JID
     * never matches.
     */
    static boolean sameNumber(String owner, String from) {
        var at = from.indexOf('@');
        if (at >= 0 && !PHONE_SERVERS.contains(from.substring(at + 1))) return false;
        var user = (at < 0 ? from : from.substring(0, at)).split("[:.]", 2)[0];
        var digits = owner.replaceAll("\\D", "");
        return !digits.isEmpty() && user.matches("\\d+") && user.equals(digits);
    }
}
