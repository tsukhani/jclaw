import channels.SlackAccessPolicy;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Unit coverage for the JCLAW-354 access matrix in {@link SlackAccessPolicy}.
 *
 * <ul>
 *   <li>DM ({@code im}) from owner → allowed; from non-owner → rejected (when an
 *       owner is configured)</li>
 *   <li>DM with NO owner configured → open (backward-compatible)</li>
 *   <li>channel/group/mpim WITH mention → allowed (any member); WITHOUT → ignored</li>
 *   <li>unknown channel_type → falls to the DM rule (safe default)</li>
 * </ul>
 *
 * <p>The pure predicate keeps these network-free; the HTTP wiring through the
 * webhook is covered by {@code WebhookSlackControllerTest}.
 */
class SlackAccessPolicyTest extends UnitTest {

    private static final String OWNER = "U_OWNER";
    private static final String GUEST = "U_GUEST";

    // ── DM (im) ─────────────────────────────────────────────────────────

    @Test
    void dmFromOwnerIsAllowed() {
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, OWNER, "im", false),
                "owner DM is served, mention irrelevant");
    }

    @Test
    void dmFromNonOwnerIsRejectedWhenOwnerSet() {
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, "im", true),
                "a non-owner DM is rejected even if it mentions the bot");
    }

    @Test
    void dmIsOpenWhenNoOwnerConfigured() {
        // Backward-compat: a binding with no owner set serves DMs from anyone.
        assertTrue(SlackAccessPolicy.isAllowed(null, GUEST, "im", false),
                "null owner → DM open to anyone");
        assertTrue(SlackAccessPolicy.isAllowed("", GUEST, "im", false),
                "blank owner → DM open to anyone");
    }

    // ── Channels / groups / mpim ────────────────────────────────────────

    @Test
    void channelMessageWithMentionIsAllowed() {
        // Owner-restriction does NOT apply in channels — any member may address the bot.
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, GUEST, "channel", true),
                "a mention-addressed channel message is served regardless of sender");
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, GUEST, "group", true),
                "private groups behave like channels");
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, GUEST, "mpim", true),
                "group DMs behave like channels");
    }

    @Test
    void channelMessageWithoutMentionIsIgnored() {
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, OWNER, "channel", false),
                "unaddressed channel chatter is ignored even from the owner");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, "mpim", false),
                "unaddressed group-DM chatter is ignored");
    }

    // ── Unknown / missing channel_type ──────────────────────────────────

    @Test
    void unknownChannelTypeFallsToDmRule() {
        // An unrecognized/missing type must NOT lock the owner out of their primary
        // (DM/Assistant) surface — it uses the DM rule, not the mention gate.
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, OWNER, null, false),
                "null channel_type from the owner is served via the DM rule");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, null, true),
                "null channel_type from a non-owner is rejected via the DM rule (owner set)");
        assertTrue(SlackAccessPolicy.isAllowed(null, GUEST, "", false),
                "blank channel_type with no owner is open");
    }
}
