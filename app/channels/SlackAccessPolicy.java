package channels;

/**
 * JCLAW-354 inbound access policy for Slack messages — the Slack analog of
 * {@link TelegramAccessPolicy}, collapsed to the per-agent binding's single owner.
 * Multi-user allowlists, per-channel user lists, group RBAC, and pairing stores are
 * out of scope by design (Personal Edition, single-owner model).
 *
 * <ul>
 *   <li><b>Direct message ({@code im})</b> — served only by the binding's owner
 *       ({@code from == binding.ownerUserId}) when an owner is configured. When no
 *       owner is set the DM stays open, so existing bindings keep working until the
 *       operator opts into owner-locking by setting the approver/owner id.</li>
 *   <li><b>Channel / private group / group DM ({@code channel}/{@code group}/
 *       {@code mpim})</b> — served only when the bot was @mentioned in the message
 *       ({@code botMentioned}). Unaddressed channel chatter is silently ignored.
 *       Channels are intentionally NOT owner-restricted (mention-gated guests).</li>
 * </ul>
 *
 * <p>Channel kind comes from the event's {@code channel_type}. Only the three
 * explicit group kinds are mention-gated; {@code im} and anything unrecognized fall
 * to the DM rule — the safer default for a bot whose primary surface is its DM /
 * Assistant pane, since an unknown type must not silently lock the owner out.
 */
public final class SlackAccessPolicy {

    private SlackAccessPolicy() {}

    /**
     * @param ownerUserId  the binding's owner Slack user id (null / blank = unset)
     * @param fromUserId   the sender's Slack user id
     * @param channelType  Slack {@code channel_type} ("im"/"channel"/"group"/"mpim"), nullable
     * @param botMentioned whether the bot was @mentioned in this message
     * @return true to serve the message, false to silently ignore it
     */
    public static boolean isAllowed(String ownerUserId, String fromUserId,
                                    String channelType, boolean botMentioned) {
        if (isGroupLike(channelType)) {
            return botMentioned;
        }
        // DM (im) or unknown: owner-only when an owner is configured, else open.
        var ownerConfigured = ownerUserId != null && !ownerUserId.isBlank();
        return !ownerConfigured || ownerUserId.equals(fromUserId);
    }

    private static boolean isGroupLike(String channelType) {
        return "channel".equals(channelType)
                || "group".equals(channelType)
                || "mpim".equals(channelType);
    }
}
