import channels.WhatsAppAccessPolicy;
import channels.WhatsAppInboundMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Unit coverage for {@link WhatsAppAccessPolicy} (JCLAW-446) — the WhatsApp inbound
 * gate. Owner-in-DM, mention-gated groups, and the owner-less "open DM" case for a
 * Cloud-API business number. Mirrors {@code SlackAccessPolicyTest}.
 */
class WhatsAppAccessPolicyTest extends UnitTest {

    private static final String DIRECT = WhatsAppInboundMessage.CHAT_DIRECT;
    private static final String GROUP = WhatsAppInboundMessage.CHAT_GROUP;

    @Test
    void directWithNoOwnerIsOpen() {
        // Cloud-API business number: any sender is served in a 1:1.
        assertTrue(WhatsAppAccessPolicy.isAllowed(null, "anyone", DIRECT, false));
        assertTrue(WhatsAppAccessPolicy.isAllowed("  ", "anyone", DIRECT, false));
    }

    @Test
    void directWithOwnerServesOnlyTheOwner() {
        assertTrue(WhatsAppAccessPolicy.isAllowed("owner@s.whatsapp.net", "owner@s.whatsapp.net", DIRECT, false),
                "the owner's DM is served");
        assertFalse(WhatsAppAccessPolicy.isAllowed("owner@s.whatsapp.net", "stranger@s.whatsapp.net", DIRECT, false),
                "a stranger's DM to a private binding is ignored");
    }

    @Test
    void groupRequiresAMention() {
        assertTrue(WhatsAppAccessPolicy.isAllowed(null, "member", GROUP, true),
                "a mention-addressed group message is served");
        assertFalse(WhatsAppAccessPolicy.isAllowed(null, "member", GROUP, false),
                "unaddressed group chatter is ignored");
    }

    @Test
    void groupIsMentionGatedForGuestsNotOwnerRestricted() {
        // Any member who @mentions the bot is served — groups are not owner-only.
        assertTrue(WhatsAppAccessPolicy.isAllowed("owner@s.whatsapp.net", "guest@s.whatsapp.net", GROUP, true),
                "a non-owner who addresses the bot in a group is served");
        assertFalse(WhatsAppAccessPolicy.isAllowed("owner@s.whatsapp.net", "owner@s.whatsapp.net", GROUP, false),
                "even the owner is ignored in a group without a mention");
    }

    @Test
    void nullChatTypeIsTreatedAsDirect() {
        // The safer default for a 1:1-first bot: an unknown type falls to the DM rule.
        assertTrue(WhatsAppAccessPolicy.isAllowed(null, "anyone", null, false));
        assertFalse(WhatsAppAccessPolicy.isAllowed("owner", "stranger", null, false));
    }
}
