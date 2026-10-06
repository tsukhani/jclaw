import channels.WhatsAppAccessPolicy;
import channels.WhatsAppInboundMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Unit coverage for {@link WhatsAppAccessPolicy} (JCLAW-446/1408): owner-number DMs,
 * the owner-less open DM, the Main Agent fail-closed rule, and mention-gated groups.
 */
class WhatsAppAccessPolicyTest extends UnitTest {

    private static final String DIRECT = WhatsAppInboundMessage.CHAT_DIRECT;
    private static final String GROUP = WhatsAppInboundMessage.CHAT_GROUP;
    private static final String OWNER = "+15551234567";

    private static boolean dm(String owner, String from) {
        return WhatsAppAccessPolicy.isAllowed(owner, from, DIRECT, false, false);
    }

    @Test
    void directWithNoOwnerIsOpen() {
        assertTrue(WhatsAppAccessPolicy.isAllowed(null, "15559990000@s.whatsapp.net", DIRECT, false, false));
        assertTrue(WhatsAppAccessPolicy.isAllowed("  ", "15559990000@s.whatsapp.net", DIRECT, false, false));
    }

    @Test
    void directWithNoOwnerOnAMainBindingIsRefused() {
        assertFalse(WhatsAppAccessPolicy.isAllowed(null, "15559990000@s.whatsapp.net", DIRECT, false, true));
        assertFalse(WhatsAppAccessPolicy.isAllowed("", "15551234567@s.whatsapp.net", DIRECT, false, true));
    }

    @Test
    void directWithOwnerServesOnlyTheOwnerEvenOnMain() {
        assertTrue(WhatsAppAccessPolicy.isAllowed(OWNER, "15551234567@s.whatsapp.net", DIRECT, false, true));
        assertFalse(WhatsAppAccessPolicy.isAllowed(OWNER, "15559990000@s.whatsapp.net", DIRECT, false, true));
        assertTrue(dm(OWNER, "15551234567@s.whatsapp.net"));
        assertFalse(dm(OWNER, "15559990000@s.whatsapp.net"));
    }

    @Test
    void theOwnerMatchesAcrossDeviceSuffixServerAndFormatting() {
        assertTrue(dm(OWNER, "15551234567:3@s.whatsapp.net"), "device suffix");
        assertTrue(dm(OWNER, "15551234567.0:3@s.whatsapp.net"), "agent and device suffix");
        assertTrue(dm(OWNER, "15551234567@c.us"), "legacy c.us server");
        assertTrue(dm("+1 555-123-4567", "15551234567@s.whatsapp.net"), "formatted owner");
        assertTrue(dm("+1 (555) 123-4567", "15551234567@s.whatsapp.net"), "formatted owner with parens");
    }

    @Test
    void nearMissesAreNotTheOwner() {
        assertFalse(dm(OWNER, "155512345670@s.whatsapp.net"), "an extra digit");
        assertFalse(dm(OWNER, "5551234567@s.whatsapp.net"), "a missing country code digit");
        assertFalse(dm(OWNER, "15551234567@lid"), "a LID with the same digits");
        assertFalse(dm(OWNER, "15551234567@g.us"), "a group JID");
        assertFalse(dm(OWNER, "abc15551234567@s.whatsapp.net"), "non-digit user part");
        assertFalse(dm("+", "@s.whatsapp.net"), "an owner with no digits never matches");
    }

    @Test
    void groupRequiresAMentionWhateverTheOwner() {
        for (var owner : new String[] {null, OWNER}) {
            for (var main : new boolean[] {false, true}) {
                assertTrue(WhatsAppAccessPolicy.isAllowed(owner, "15559990000@s.whatsapp.net", GROUP, true, main),
                        "mentioned group message, owner=" + owner + " main=" + main);
                assertFalse(WhatsAppAccessPolicy.isAllowed(owner, "15551234567@s.whatsapp.net", GROUP, false, main),
                        "unmentioned group message, owner=" + owner + " main=" + main);
            }
        }
    }

    @Test
    void nullChatTypeIsTreatedAsDirect() {
        assertTrue(WhatsAppAccessPolicy.isAllowed(null, "15559990000@s.whatsapp.net", null, false, false));
        assertFalse(WhatsAppAccessPolicy.isAllowed(OWNER, "15559990000@s.whatsapp.net", null, true, false));
    }
}
