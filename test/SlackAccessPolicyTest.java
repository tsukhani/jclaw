import channels.SlackAccessPolicy;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Unit coverage for the JCLAW-354 access matrix in {@link SlackAccessPolicy}.
 *
 * <p>The owner user id is the private/shared switch: owner set → private (owner-only
 * on every surface); owner unset → shared (DM open, channels mention-gated), except a
 * binding that requires an owner ({@code ownerRequired} — the main agent) fails closed
 * without one.
 */
class SlackAccessPolicyTest extends UnitTest {

    private static final String OWNER = "U_OWNER";
    private static final String GUEST = "U_GUEST";

    // ── Owner SET → private (regardless of ownerRequired) ───────────────

    @Test
    void privateDmServesOnlyTheOwner() {
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, OWNER, "im", false, false),
                "owner reaches their private DM");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, "im", true, false),
                "a non-owner DM is rejected even if it mentions the bot");
    }

    @Test
    void privateChannelServesOnlyTheOwnerAndOnlyWithMention() {
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, OWNER, "channel", true, false),
                "the owner reaches a private binding in a channel by mentioning it");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, "channel", true, false),
                "a GUEST mention does NOT reach a private (owner-set) binding");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, OWNER, "channel", false, false),
                "the owner still needs to @mention in a channel");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, "mpim", true, false),
                "group DMs are private too when an owner is set");
    }

    @Test
    void ownerSetIsPrivateEvenForTheMainAgent() {
        // ownerRequired doesn't change the owner-set behavior — main is private the
        // same way a custom agent with an owner is.
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, OWNER, "im", false, true),
                "the owner reaches their main-agent DM");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, "channel", true, true),
                "a guest mention does not reach the main agent in a channel");
    }

    // ── Owner UNSET, non-main → shared ──────────────────────────────────

    @Test
    void sharedDmIsOpenToAnyone() {
        assertTrue(SlackAccessPolicy.isAllowed(null, GUEST, "im", false, false),
                "no owner → DM open to anyone");
        assertTrue(SlackAccessPolicy.isAllowed("", GUEST, "im", false, false),
                "blank owner → DM open to anyone");
    }

    @Test
    void sharedChannelIsMentionGatedForAnyMember() {
        assertTrue(SlackAccessPolicy.isAllowed(null, GUEST, "channel", true, false),
                "no owner → any member who @mentions is served");
        assertTrue(SlackAccessPolicy.isAllowed(null, GUEST, "mpim", true, false),
                "group DMs are mention-gated when shared");
        assertFalse(SlackAccessPolicy.isAllowed(null, GUEST, "channel", false, false),
                "unaddressed channel chatter is ignored");
    }

    // ── Owner UNSET, main (ownerRequired) → fail closed ─────────────────

    @Test
    void mainAgentFailsClosedWithoutOwner() {
        // A full-access agent with no owner configured must reach NO ONE.
        assertFalse(SlackAccessPolicy.isAllowed(null, OWNER, "im", false, true),
                "no owner configured → main-agent DM reaches no one");
        assertFalse(SlackAccessPolicy.isAllowed("", GUEST, "channel", true, true),
                "no owner configured → main-agent channel reaches no one even with a mention");
    }

    // ── Unknown / missing channel_type → DM rule ────────────────────────

    @Test
    void unknownChannelTypeFallsToDmRule() {
        // Owner set: private DM rule (owner served, others not).
        assertTrue(SlackAccessPolicy.isAllowed(OWNER, OWNER, null, false, false),
                "null channel_type from the owner is served via the DM rule");
        assertFalse(SlackAccessPolicy.isAllowed(OWNER, GUEST, null, true, false),
                "null channel_type from a non-owner is rejected via the DM rule");
        // Owner unset, non-main: open. Main: fail closed.
        assertTrue(SlackAccessPolicy.isAllowed(null, GUEST, "", false, false),
                "blank channel_type with no owner (non-main) is open");
        assertFalse(SlackAccessPolicy.isAllowed(null, GUEST, "", false, true),
                "blank channel_type with no owner (main) fails closed");
    }
}
