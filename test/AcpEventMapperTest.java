import agents.DangerousActionGate;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigOptionCategory;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelect;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionConfigSelectOptions;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.UsageUpdate;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.AcpEventMapper;
import tools.HarnessEvent;

import java.util.List;

/**
 * Stage-2 ACP mapping (pure, no live harness): ACP session/update → HarnessEvent
 * and the session/request_permission ⇄ DangerousActionGate decision→outcome
 * bridge. The full round-trip through a real harness is proven separately via
 * the acp-core SDK spike; this pins the translation logic deterministically.
 */
class AcpEventMapperTest extends UnitTest {

    @Test
    void agentMessageChunkMapsToTokenWithText() {
        var ev = AcpEventMapper.toHarnessEvent(new AgentMessageChunk(new TextContent("hello")));
        assertNotNull(ev);
        assertEquals(HarnessEvent.TOKEN, ev.kind());
        assertEquals("hello", ev.text());
    }

    @Test
    void agentThoughtChunkMapsToStep() {
        var ev = AcpEventMapper.toHarnessEvent(new AgentThoughtChunk(new TextContent("thinking")));
        assertNotNull(ev);
        assertEquals(HarnessEvent.STEP, ev.kind());
        assertEquals("thinking", ev.text());
    }

    @Test
    void toolCallMapsToToolCallLabelledWithItsWireStatus() {
        var call = new ToolCall(null, "tc-1", "Read build.gradle.kts", null, ToolKind.READ, ToolCallStatus.IN_PROGRESS,
                null, null, null, null, null);
        var ev = AcpEventMapper.toHarnessEvent(call);
        assertNotNull(ev);
        assertEquals(HarnessEvent.TOOL_CALL, ev.kind());
        assertEquals("Read build.gradle.kts (in_progress)", ev.text());
    }

    @Test
    void theModelOptionIsTheSelectInTheModelCategory() {
        var mode = select("mode", SessionConfigOptionCategory.MODE, "default");
        var model = select("model", SessionConfigOptionCategory.MODEL, "sonnet");
        assertSame(model, AcpEventMapper.modelOption(List.of(mode, model)));
        assertNull(AcpEventMapper.modelOption(List.of(mode)));
        assertNull(AcpEventMapper.modelOption(null));
    }

    private static SessionConfigSelect select(String id, String category, String current) {
        return new SessionConfigSelect("select", id, id, null, category, current,
                SessionConfigSelectOptions.ungrouped(List.of(new SessionConfigSelectOption(current, current))), null);
    }

    @Test
    void bookkeepingAndNullUpdatesAreDropped() {
        assertNull(AcpEventMapper.toHarnessEvent(new UsageUpdate(10L, 20L)));
        assertNull(AcpEventMapper.toHarnessEvent(null));
    }

    @Test
    void proceedSelectsAnAllowOption() {
        var req = new RequestPermissionRequest("s", null, List.of(
                new PermissionOption("allow-1", "Allow", PermissionOptionKind.ALLOW_ONCE),
                new PermissionOption("reject-1", "Reject", PermissionOptionKind.REJECT_ONCE)));
        var resp = AcpEventMapper.permissionResponse(req, DangerousActionGate.Decision.PROCEED);
        assertTrue(resp.outcome() instanceof PermissionSelected);
        assertEquals("allow-1", ((PermissionSelected) resp.outcome()).optionId());
    }

    @Test
    void abortSelectsARejectOption() {
        var req = new RequestPermissionRequest("s", null, List.of(
                new PermissionOption("allow-1", "Allow", PermissionOptionKind.ALLOW_ONCE),
                new PermissionOption("reject-1", "Reject", PermissionOptionKind.REJECT_ONCE)));
        var resp = AcpEventMapper.permissionResponse(req, DangerousActionGate.Decision.ABORT);
        assertTrue(resp.outcome() instanceof PermissionSelected);
        assertEquals("reject-1", ((PermissionSelected) resp.outcome()).optionId());
    }

    @Test
    void abortWithNoRejectOptionCancels() {
        var req = new RequestPermissionRequest("s", null, List.of(
                new PermissionOption("allow-1", "Allow", PermissionOptionKind.ALLOW_ONCE)));
        var resp = AcpEventMapper.permissionResponse(req, DangerousActionGate.Decision.ABORT);
        assertTrue(resp.outcome() instanceof PermissionCancelled);
    }

    @Test
    void permissionToolNameFallsBackWithoutAToolCall() {
        var req = new RequestPermissionRequest("s", null, List.of());
        assertEquals("harness_tool", AcpEventMapper.permissionToolName(req));
    }
}
