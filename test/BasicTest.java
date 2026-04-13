import org.junit.jupiter.api.*;
import play.test.*;
import models.*;
import utils.GsonHolder;

public class BasicTest extends UnitTest {

    // ==================== MessageRole ====================

    @Test
    public void messageRoleFromValueMapsAllValues() {
        assertEquals(MessageRole.USER, MessageRole.fromValue("user"));
        assertEquals(MessageRole.ASSISTANT, MessageRole.fromValue("assistant"));
        assertEquals(MessageRole.TOOL, MessageRole.fromValue("tool"));
        assertEquals(MessageRole.SYSTEM, MessageRole.fromValue("system"));
    }

    @Test
    public void messageRoleFromValueReturnsNullForUnknown() {
        assertNull(MessageRole.fromValue("admin"));
        assertNull(MessageRole.fromValue(""));
        assertNull(MessageRole.fromValue(null));
    }

    @Test
    public void messageRoleToStringReturnsWireValue() {
        assertEquals("user", MessageRole.USER.toString());
        assertEquals("assistant", MessageRole.ASSISTANT.toString());
        assertEquals("tool", MessageRole.TOOL.toString());
        assertEquals("system", MessageRole.SYSTEM.toString());
    }

    @Test
    public void messageRoleValueFieldMatchesToString() {
        for (var role : MessageRole.values()) {
            assertEquals(role.value, role.toString());
        }
    }

    // ==================== ChannelType ====================

    @Test
    public void channelTypeFromValueMapsAllValues() {
        assertEquals(ChannelType.WEB, ChannelType.fromValue("web"));
        assertEquals(ChannelType.SLACK, ChannelType.fromValue("slack"));
        assertEquals(ChannelType.TELEGRAM, ChannelType.fromValue("telegram"));
        assertEquals(ChannelType.WHATSAPP, ChannelType.fromValue("whatsapp"));
    }

    @Test
    public void channelTypeFromValueReturnsNullForUnknown() {
        assertNull(ChannelType.fromValue("email"));
        assertNull(ChannelType.fromValue(""));
        assertNull(ChannelType.fromValue(null));
    }

    @Test
    public void channelTypeToStringReturnsWireValue() {
        for (var type : ChannelType.values()) {
            assertEquals(type.value, type.toString());
        }
    }

    // ==================== GsonHolder ====================

    @Test
    public void gsonHolderInstanceIsNotNull() {
        assertNotNull(GsonHolder.INSTANCE);
    }

    @Test
    public void gsonHolderCanSerialize() {
        var json = GsonHolder.INSTANCE.toJson(java.util.Map.of("key", "value"));
        assertTrue(json.contains("key"));
        assertTrue(json.contains("value"));
    }

    @Test
    public void gsonHolderIsSingleton() {
        assertSame(GsonHolder.INSTANCE, GsonHolder.INSTANCE);
    }
}
