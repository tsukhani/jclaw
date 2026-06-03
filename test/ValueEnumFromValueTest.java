import models.ChannelType;
import models.MessageRole;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-408: parity guard for the {@code fromValue} reverse-lookup that
 * replaced the hand-written switch on {@link ChannelType} and
 * {@link MessageRole}. Pins the exact prior semantics: every constant
 * round-trips through its {@code value}, {@code null} input yields
 * {@code null}, and an unrecognised string yields {@code null} (the old
 * {@code default} arm) rather than throwing.
 */
class ValueEnumFromValueTest extends UnitTest {

    @Test
    void channelTypeRoundTripsEveryConstant() {
        for (var ct : ChannelType.values()) {
            assertEquals(ct, ChannelType.fromValue(ct.value));
        }
    }

    @Test
    void channelTypeKnownStrings() {
        assertEquals(ChannelType.WEB, ChannelType.fromValue("web"));
        assertEquals(ChannelType.SLACK, ChannelType.fromValue("slack"));
        assertEquals(ChannelType.TELEGRAM, ChannelType.fromValue("telegram"));
        assertEquals(ChannelType.WHATSAPP, ChannelType.fromValue("whatsapp"));
    }

    @Test
    void channelTypeNullAndUnknownYieldNull() {
        assertNull(ChannelType.fromValue(null));
        assertNull(ChannelType.fromValue("discord"));
        assertNull(ChannelType.fromValue("WEB")); // case-sensitive, as before
        assertNull(ChannelType.fromValue(""));
    }

    @Test
    void messageRoleRoundTripsEveryConstant() {
        for (var role : MessageRole.values()) {
            assertEquals(role, MessageRole.fromValue(role.value));
        }
    }

    @Test
    void messageRoleKnownStrings() {
        assertEquals(MessageRole.USER, MessageRole.fromValue("user"));
        assertEquals(MessageRole.ASSISTANT, MessageRole.fromValue("assistant"));
        assertEquals(MessageRole.TOOL, MessageRole.fromValue("tool"));
        assertEquals(MessageRole.SYSTEM, MessageRole.fromValue("system"));
    }

    @Test
    void messageRoleNullAndUnknownYieldNull() {
        assertNull(MessageRole.fromValue(null));
        assertNull(MessageRole.fromValue("developer"));
        assertNull(MessageRole.fromValue("USER")); // case-sensitive, as before
        assertNull(MessageRole.fromValue(""));
    }
}
