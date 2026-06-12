package channels;

/**
 * JCLAW-354 inbound access policy for Slack messages — the Slack analog of
 * {@link TelegramAccessPolicy}, collapsed to the per-agent binding's single owner.
 * Multi-user allowlists, per-channel user lists, group RBAC, and pairing stores are
 * out of scope by design (Personal Edition).
 *
 * <p>The owner user id is the private/shared switch:
 *
 * <ul>
 *   <li><b>Owner set</b> → the binding is <b>private</b>: served only to the owner on
 *       every surface. A DM from the owner is served; a channel / group / group-DM
 *       message is served only when the owner {@code @}mentions the bot (the mention
 *       keeps the bot from reacting to every owner message). Anyone else is ignored —
 *       a guest mention does not reach a private binding.</li>
 *   <li><b>Owner unset</b> → the binding is <b>shared</b>: a DM from any workspace
 *       user is served, and a channel message from any member is served when it
 *       {@code @}mentions the bot (mention-gated guests). The exception is a binding
 *       that <i>requires</i> an owner ({@code ownerRequired} — the {@code main} agent,
 *       which has full filesystem / shell access): with no owner it fails
 *       <b>closed</b>, reaching no one, so a full-access agent is never open.</li>
 * </ul>
 *
 * <p>Channel kind comes from the event's {@code channel_type}. Only the three explicit
 * group kinds ({@code channel}/{@code group}/{@code mpim}) are mention-gated; {@code im}
 * and anything unrecognized fall to the DM rule — the safer default for a bot whose
 * primary surface is its DM / Assistant pane, since an unknown type must not silently
 * lock the owner out.
 */
public final class SlackAccessPolicy {

    private SlackAccessPolicy() {}

    /**
     * @param ownerUserId   the binding's owner Slack user id (null / blank = unset)
     * @param fromUserId    the sender's Slack user id
     * @param channelType   Slack {@code channel_type} ("im"/"channel"/"group"/"mpim"), nullable
     * @param botMentioned  whether the bot was @mentioned in this message
     * @param ownerRequired true when the binding must have an owner (the main agent):
     *                      with no owner configured it reaches no one (fail closed)
     * @return true to serve the message, false to silently ignore it
     */
    public static boolean isAllowed(String ownerUserId, String fromUserId, String channelType,
                                    boolean botMentioned, boolean ownerRequired) {
        var ownerConfigured = ownerUserId != null && !ownerUserId.isBlank();

        if (ownerConfigured) {
            // Private: only the owner. A channel still needs the @mention so the bot
            // isn't triggered by every owner message.
            var fromOwner = ownerUserId.equals(fromUserId);
            return isGroupLike(channelType) ? (botMentioned && fromOwner) : fromOwner;
        }
        // No owner configured.
        if (ownerRequired) {
            return false; // a full-access agent (main) must have an owner — fail closed
        }
        // Shared: DM open to anyone; channels mention-gated (any member).
        return !isGroupLike(channelType) || botMentioned;
    }

    private static boolean isGroupLike(String channelType) {
        return "channel".equals(channelType)
                || "group".equals(channelType)
                || "mpim".equals(channelType);
    }
}
