package tools;

import agents.DangerousActionGate;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import models.Agent;
import services.EventLogger;
import services.SubagentRegistry;
import utils.GsonHolder;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JCLAW-677 / JCLAW-665: rpc-mode permission arbitration for an external
 * harness, extracted from {@link SubagentSpawnTool}. Detects a permission-request
 * frame in a streamed harness event, routes it through the operator approval
 * gate, and writes the approve/deny decision back to the harness on stdin.
 */
final class SubagentHarnessPermissions {

    /** JCLAW-665/S2445: per-run lock guarding decision-frame writes to the
     *  harness stdin — a dedicated, stable lock rather than the passed-in
     *  stream ref. Evicted when the run ends (runAcpRpc/runAcpBatch finally). */
    private static final ConcurrentHashMap<Long, Object> STDIN_WRITE_LOCKS = new ConcurrentHashMap<>();

    /** JCLAW-665: discriminator values (across a harness event's type-ish fields)
     *  that mark a line as a permission request. Tolerant of the several shapes
     *  different harnesses use — the exact protocol is not pinned. */
    private static final Set<String> PERMISSION_EVENT_TYPES = Set.of(
            "permission_request", "permission", "request_permission", "requestpermission",
            "can_use_tool", "tool_permission", "approval_request", "approval");

    private SubagentHarnessPermissions() {}

    /** JCLAW-665: a permission request parsed out of an rpc harness event. */
    record HarnessPermission(String id, String toolName, String argsJson) {}

    /**
     * JCLAW-665: handle one streamed rpc event. Non-permission events are ignored
     * here (they flow through the normal reply/rails path in
     * {@link SubagentAcpRunner}); a permission request is routed to
     * {@link DangerousActionGate#guardHarnessPermission} and the decision is
     * written back to the harness on {@code stdin}. Runs on the stdout-reader VT —
     * which touches the DB and must never be interrupted — so a routing failure is
     * caught and fails CLOSED (deny) rather than propagating.
     */
    static void arbitratePermission(HarnessEvent ev, OutputStream stdin, Long runId,
                                    Agent childAgent, Long conversationId) {
        var perm = detectPermission(ev);
        if (perm == null) return;
        // Operator deliberation is not harness inactivity — reset the idle clock so
        // the inactivity budget doesn't force-kill the run while it awaits a tap.
        SubagentRegistry.touch(runId);
        boolean approved;
        try {
            approved = DangerousActionGate.guardHarnessPermission(
                    childAgent, conversationId, perm.toolName(), perm.argsJson())
                    == DangerousActionGate.Decision.PROCEED;
        } catch (RuntimeException e) {
            approved = false;
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL, childAgent == null ? null : childAgent.name, null,
                    "Harness permission routing failed for run %s — denying: %s"
                            .formatted(runId, e.getMessage()));
        }
        writePermissionDecision(stdin, perm, approved, runId);
    }

    /**
     * JCLAW-665: recognize a harness permission-request frame in a parsed event and
     * extract what the approval gate needs — a correlation id, the tool name, and a
     * JSON args blob. Returns {@code null} for any event that is not a permission
     * request (the common case), so the rpc reader treats it as ordinary streamed
     * output. Best-effort and tolerant: the on-the-wire shape varies by harness, so
     * we probe a handful of conventional field names and never throw.
     */
    static HarnessPermission detectPermission(HarnessEvent ev) {
        var raw = ev == null ? null : ev.raw();
        if (raw == null) return null;
        var discriminator = firstJsonString(raw, "type", "kind", "event", "method", "subtype");
        if (discriminator == null
                || !PERMISSION_EVENT_TYPES.contains(discriminator.strip().toLowerCase())) {
            return null;
        }
        // The tool/args may sit at the top level or under a nested request envelope.
        var payload = raw;
        for (var envelope : List.of("params", "request", "permission", "input", "data")) {
            var nested = raw.get(envelope);
            if (nested != null && nested.isJsonObject()) {
                payload = nested.getAsJsonObject();
                break;
            }
        }
        var id = firstJsonString(raw, "id", "request_id", "requestId", "permissionId",
                "permission_id", "tool_call_id", "toolCallId");
        if (id == null) {
            id = firstJsonString(payload, "id", "request_id", "requestId", "tool_call_id");
        }
        var toolName = firstJsonString(payload, "tool", "tool_name", "toolName", "name");
        if (toolName == null) {
            toolName = firstJsonString(raw, "tool", "tool_name", "toolName", "name");
        }
        if (toolName == null) toolName = "(harness action)";
        var argsEl = firstJsonMember(payload, "input", "arguments", "args", "params");
        var argsJson = argsEl != null ? argsEl.toString() : payload.toString();
        return new HarnessPermission(id, toolName, argsJson);
    }

    /**
     * JCLAW-665: relay an approve/deny decision back to the harness on stdin as a
     * one-line JSON frame. A denial lets the harness abort just that action and keep
     * running (we never kill the process here). Best-effort: a write failure (harness
     * gone / stdin closed) is logged and swallowed — the reader VT must not be
     * interrupted. Writes are serialized on a per-run lock so a decision frame is
     * never interleaved with another writer.
     */
    private static void writePermissionDecision(OutputStream stdin, HarnessPermission perm,
                                                boolean approved, Long runId) {
        var resp = new LinkedHashMap<String, Object>();
        resp.put("type", "permission_response");
        if (perm.id() != null) resp.put("id", perm.id());
        resp.put("decision", approved ? "allow" : "deny");
        resp.put("approved", approved);
        var line = GsonHolder.INSTANCE.toJson(resp, Map.class) + "\n";
        try {
            synchronized (STDIN_WRITE_LOCKS.computeIfAbsent(runId, _ -> new Object())) {
                stdin.write(line.getBytes(StandardCharsets.UTF_8));
                stdin.flush();
            }
        } catch (IOException e) {
            EventLogger.warn(SubagentSpawnTool.SUBAGENT_CHANNEL, null, null,
                    "Failed to write permission decision for run %s: %s"
                            .formatted(runId, e.getMessage()));
        }
    }

    /** JCLAW-665: evict the per-run stdin write lock when a run ends. */
    static void evictStdinLock(Long runId) {
        STDIN_WRITE_LOCKS.remove(runId);
    }

    /** JCLAW-665: close the harness stdin, swallowing any error — the run's terminal
     *  outcome is already decided by the time we tear the pipe down. */
    static void closeQuietly(OutputStream stream) {
        if (stream == null) return;
        try {
            stream.close();
        } catch (IOException _) {
            // best-effort teardown
        }
    }

    /** First present, non-null primitive field among {@code keys}, as a string. */
    private static String firstJsonString(JsonObject obj, String... keys) {
        for (var key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && el.isJsonPrimitive()) {
                return el.getAsString();
            }
        }
        return null;
    }

    /** First present object/array member among {@code keys}, or null. */
    private static JsonElement firstJsonMember(JsonObject obj, String... keys) {
        for (var key : keys) {
            JsonElement el = obj.get(key);
            if (el != null && (el.isJsonObject() || el.isJsonArray())) {
                return el;
            }
        }
        return null;
    }
}
