package tools;

import agents.DangerousActionGate;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.ContentBlock;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionCancelled;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOption;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionOptionKind;
import com.agentclientprotocol.sdk.spec.AcpSchema.PermissionSelected;
import com.agentclientprotocol.sdk.spec.AcpSchema.Plan;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionRequest;
import com.agentclientprotocol.sdk.spec.AcpSchema.RequestPermissionResponse;
import com.agentclientprotocol.sdk.spec.AcpSchema.SessionUpdate;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import utils.GsonHolder;

import java.util.LinkedHashMap;
import java.util.List;

/**
 * Pure translation between the ACP SDK's protocol objects and JClaw's runtime:
 * an ACP {@code session/update} → a {@link HarnessEvent} for the run rails, and
 * an ACP {@code session/request_permission} ⇄ {@link DangerousActionGate}. Kept
 * side-effect-free so it's unit-testable without a live harness (see {@code
 * AcpEventMapperTest}); the orchestration that calls it lives in
 * {@link SubagentAcpRunner#runAcpSdk}. Public only so the default-package test
 * can reach it — not part of any external API.
 */
public final class AcpEventMapper {

    private AcpEventMapper() {}

    /**
     * Map one ACP {@code session/update} to a {@link HarnessEvent}, or {@code
     * null} to drop it. Agent message chunks are the reply ({@code TOKEN});
     * thoughts + plan updates are progress ({@code STEP}); tool calls are {@code
     * TOOL_CALL}. Bookkeeping updates (usage, available-commands, mode,
     * user-echo) aren't run steps and are dropped.
     */
    public static HarnessEvent toHarnessEvent(SessionUpdate update) {
        return switch (update) {
            case AgentMessageChunk c -> new HarnessEvent(HarnessEvent.TOKEN, text(c.content()), null);
            case AgentThoughtChunk c -> new HarnessEvent(HarnessEvent.STEP, text(c.content()), null);
            case ToolCall t -> new HarnessEvent(HarnessEvent.TOOL_CALL, toolLabel(t.title(), t.kind(), t.status()), null);
            case Plan _ -> new HarnessEvent(HarnessEvent.STEP, "updated its plan", null);
            case null, default -> null;
        };
    }

    private static String text(ContentBlock content) {
        return content instanceof TextContent t && t.text() != null ? t.text() : "";
    }

    private static String toolLabel(String title, Object kind, Object status) {
        String label;
        if (title != null && !title.isBlank()) {
            label = title;
        } else {
            label = kind != null ? kind.toString() : "tool";
        }
        return status != null ? label + " (" + status + ")" : label;
    }

    // ---- permission bridge (session/request_permission ⇄ DangerousActionGate) ----

    /** The tool name shown to the operator and keyed by the gate. */
    public static String permissionToolName(RequestPermissionRequest req) {
        var tc = req.toolCall();
        if (tc == null) return "harness_tool";
        if (tc.title() != null && !tc.title().isBlank()) return tc.title();
        return tc.kind() != null ? tc.kind().toString() : "harness_tool";
    }

    /** A compact JSON blob of the requested action for the approval prompt. */
    public static String permissionArgsJson(RequestPermissionRequest req) {
        var tc = req.toolCall();
        var m = new LinkedHashMap<String, Object>();
        if (tc != null) {
            m.put("toolCallId", tc.toolCallId());
            m.put("title", tc.title());
            m.put("kind", tc.kind() == null ? null : tc.kind().toString());
        }
        return GsonHolder.INSTANCE.toJson(m);
    }

    /**
     * Turn the gate's decision into an ACP permission outcome: on {@code
     * PROCEED} pick an allow option, on {@code ABORT} a reject option; when
     * neither kind is offered, cancel (the safe default — the harness aborts
     * just this action, not the whole run).
     */
    public static RequestPermissionResponse permissionResponse(RequestPermissionRequest req,
                                                        DangerousActionGate.Decision decision) {
        var allow = decision == DangerousActionGate.Decision.PROCEED;
        var wanted = allow
                ? List.of(PermissionOptionKind.ALLOW_ONCE, PermissionOptionKind.ALLOW_ALWAYS)
                : List.of(PermissionOptionKind.REJECT_ONCE, PermissionOptionKind.REJECT_ALWAYS);
        var options = req.options() == null ? List.<PermissionOption>of() : req.options();
        var pick = options.stream().filter(o -> wanted.contains(o.kind())).findFirst().orElse(null);
        return pick != null
                ? new RequestPermissionResponse(new PermissionSelected(pick.optionId()))
                : new RequestPermissionResponse(new PermissionCancelled());
    }
}
