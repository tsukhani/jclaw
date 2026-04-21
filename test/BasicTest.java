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

    // ==================== AgentService.workspacePath defense-in-depth (JCLAW-115) ====================

    @Test
    public void workspacePathRejectsDotDotTraversal() {
        // Defense-in-depth: even if a name slips past the controller's slug
        // regex, workspacePath must refuse to return an escape-path. The
        // resolveContained helper normalizes and then asserts startsWith(root).
        assertThrows(SecurityException.class,
                () -> services.AgentService.workspacePath("../etc"));
        assertThrows(SecurityException.class,
                () -> services.AgentService.workspacePath("../../etc"));
    }

    @Test
    public void workspacePathRejectsAbsolutePath() {
        // Path.resolve with an absolute other returns other verbatim — the
        // root is ignored. Defense layer catches this and refuses.
        assertThrows(SecurityException.class,
                () -> services.AgentService.workspacePath("/etc/passwd"));
    }

    @Test
    public void workspacePathAcceptsValidSlugName() {
        // Happy path: a regular agent name resolves successfully. Exact path
        // depends on the test workspace-root config, so we just assert the
        // call doesn't throw and the result is non-null.
        var path = services.AgentService.workspacePath("normal-agent");
        assertNotNull(path);
        assertTrue(path.toString().endsWith("normal-agent"),
                "path should end with the agent name: " + path);
    }
}
