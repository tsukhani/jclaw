package channels;

/**
 * JCLAW-371 inbound access policy for Telegram message updates, shared by the
 * polling runner ({@link TelegramPollingRunner}) and the webhook controller so
 * both gate identically.
 *
 * <ul>
 *   <li><b>Private chat (DM)</b> — served only when the sender is the binding's
 *       owner ({@code from.id == binding.telegramUserId}). Unchanged DM behavior.</li>
 *   <li><b>Group / supergroup</b> — served for any member, but only when the bot
 *       was directly addressed ({@code botMentioned}: an {@code @mention} /
 *       {@code text_mention} / {@code /cmd@botname} / reply-to-bot). Unaddressed
 *       group chatter is silently ignored. Groups are intentionally NOT
 *       owner-restricted (mention-gated guests) and there is no multi-owner
 *       allowlist (out of scope by design).</li>
 * </ul>
 *
 * <p>Chat kind is taken from the inbound {@code chat.type} string; anything that
 * isn't {@code "private"} is treated as a group — the more restrictive default,
 * since a non-DM context requires the bot to be explicitly addressed.
 *
 * <p>The callback ({@code callback_query}) gate is unaffected by this policy and
 * stays owner-only in both call sites.
 */
public final class TelegramAccessPolicy {

    private static final String CHAT_TYPE_PRIVATE = "private";

    private TelegramAccessPolicy() {}

    /**
     * @param ownerMatches  whether the sender's id equals the binding owner's id
     * @param chatType      Telegram {@code chat.type} string (nullable)
     * @param botMentioned  whether the bot was directly addressed in this message
     * @return true when the message should be served, false to silently ignore
     */
    public static boolean isAllowed(boolean ownerMatches, String chatType, boolean botMentioned) {
        if (CHAT_TYPE_PRIVATE.equals(chatType)) {
            return ownerMatches;
        }
        return botMentioned;
    }
}
