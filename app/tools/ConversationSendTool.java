package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import services.ConversationService;
import services.Tx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import utils.GsonHolder;

/**
 * JCLAW-326: bidirectional parent↔child message send for the
 * {@code conversation_send} AC. One tool, two directions, dispatched by the
 * {@code target} param (and inferred from the caller's role when omitted).
 *
 * <p><b>Direction.</b> The calling agent's relationship to any active
 * {@link SubagentRun} decides the default {@code target}: a child agent
 * (one that currently has a {@link SubagentRun.Status#RUNNING} row with
 * itself as {@code childAgent}) defaults to {@code "parent"}; otherwise
 * defaults to {@code "child"} and {@code runId} becomes required. The
 * caller can override either default by passing {@code target} explicitly.
 *
 * <p><b>Parent→child.</b> Appends a USER-role message to the named child's
 * conversation, stamped with {@code messageKind="subagent_send"} +
 * metadata carrying the source agent id. The child sees the message on its
 * next turn via the standard
 * {@link ConversationService#loadRecentMessages} path; fire-and-forget,
 * does not block the parent's tool-call loop.
 *
 * <p><b>Child→parent.</b> Appends a USER-role message to the parent
 * conversation of the calling child's active run. Same
 * {@code messageKind="subagent_send"} stamp with a {@code source: "child"}
 * discriminator. The parent's LLM sees the message on its next turn (or
 * when it next runs after a queue trigger). Does <em>not</em> wake or
 * interrupt the parent — the AC's "doesn't block" semantics map to the
 * Radarr-monitor use case: emit-and-continue, the parent picks up the
 * message naturally.
 *
 * <p><b>Why USER role for both directions.</b>
 * {@link ConversationService#loadRecentMessages} hides every
 * {@code messageKind != null} row from the LLM unless its role is USER —
 * the announce-flow's SYSTEM-vs-USER toggle for fire-and-forget vs
 * yield-resume rests on that filter. For conversation_send the calling agent's
 * intent is to deliver content the other side should see, so USER is the
 * right role for both legs.
 *
 * <p><b>{@code payloadType}.</b> Accepted and stored in metadata, but the
 * message {@code content} is the raw string verbatim — no transcoding. The
 * field exists so the AC's contract is honored without lying about
 * rendering; future stories may render JSON / markdown payloads
 * differently in the chat UI.
 */
public class ConversationSendTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "conversation_send";

    /** {@link Message#messageKind} discriminator the chat UI keys off to
     *  render conversation_send rows distinctly from organic user input. */
    public static final String MESSAGE_KIND = "subagent_send";

    private static final String PARAM_TARGET = "target";
    private static final String PARAM_RUN_ID = "runId";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_PAYLOAD_TYPE = "payloadType";

    private static final String TARGET_PARENT = "parent";
    private static final String TARGET_CHILD = "child";
    private static final Set<String> ALLOWED_TARGETS = Set.of(TARGET_PARENT, TARGET_CHILD);

    private static final String DEFAULT_PAYLOAD_TYPE = "text";
    private static final Set<String> ALLOWED_PAYLOAD_TYPES = Set.of("text", "json", "markdown");

    private static final String ERR_RUN_ID = "Error: runId ";

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "chat-bubble"; }

    @Override
    public String shortDescription() {
        return "Send a message between a parent agent and one of its running subagents.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction("send",
                        "Append a message to a parent or child conversation; fire-and-forget."));
    }

    @Override
    public String description() {
        return """
                Send a message between this agent and a related subagent run, without blocking. \
                Use this from a PARENT to nudge a running child (target="child" + the runId from \
                subagent_spawn) — the message is appended to the child's conversation as user input \
                and the child sees it on its next turn. Use this from a CHILD to push a status \
                update back to its parent (target="parent" — the runId is inferred from the active \
                SubagentRun where this agent is the child). The parent sees the message on its \
                next turn; it is not woken or interrupted. \
                Required: `message` (the text to deliver). \
                Optional: `target` (parent|child; defaults to "parent" when the caller is a \
                running child, "child" otherwise), `runId` (required when target="child"), \
                `payloadType` (text|json|markdown; recorded in metadata, content is delivered \
                verbatim).""";
    }

    @Override
    public String summary() {
        return "Send a fire-and-forget message between parent and subagent.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(PARAM_TARGET, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.ENUM, List.of(TARGET_PARENT, TARGET_CHILD),
                SchemaKeys.DESCRIPTION,
                "Delivery direction. \"parent\" appends to the parent conversation of "
                        + "this child's active run; \"child\" appends to the named child's "
                        + "conversation (runId required). When omitted, inferred from the "
                        + "calling agent's role."));
        props.put(PARAM_RUN_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Run id of the target child (required when target=\"child\"; ignored "
                        + "when target=\"parent\")."));
        props.put(PARAM_MESSAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "The message content to deliver (required)."));
        props.put(PARAM_PAYLOAD_TYPE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.ENUM, List.copyOf(ALLOWED_PAYLOAD_TYPES),
                SchemaKeys.DESCRIPTION,
                "Hint about the message's structure (recorded in metadata, content "
                        + "is still delivered verbatim). Defaults to \"text\"."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of(PARAM_MESSAGE)
        );
    }

    /** Mutates Message + Conversation rows on a target the caller may share
     *  with concurrent runs; keep serial within a turn to avoid interleaved
     *  appends. */
    @Override
    public boolean parallelSafe() { return false; }

    /** Subagent-lifecycle group: shared with {@link SubagentSpawnTool} so a
     *  same-turn {@code subagent_spawn} + {@code conversation_send} pair
     *  serializes. Without this, send's SubagentRun lookup races spawn's
     *  INSERT commit — surfacing as "no SubagentRun found for runId X" or a
     *  "no active child run" error for a row the same turn just created. */
    @Override
    public String serializationGroup() { return "subagent_lifecycle"; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var message = optString(args, PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            return "Error: 'message' is required.";
        }
        var explicitTarget = optString(args, PARAM_TARGET);
        if (explicitTarget != null && !explicitTarget.isBlank()
                && !ALLOWED_TARGETS.contains(explicitTarget.toLowerCase())) {
            return "Error: 'target' must be one of " + ALLOWED_TARGETS
                    + " (got '" + explicitTarget + "').";
        }
        var payloadType = optString(args, PARAM_PAYLOAD_TYPE);
        if (payloadType == null || payloadType.isBlank()) {
            payloadType = DEFAULT_PAYLOAD_TYPE;
        } else if (!ALLOWED_PAYLOAD_TYPES.contains(payloadType.toLowerCase())) {
            return "Error: 'payloadType' must be one of " + ALLOWED_PAYLOAD_TYPES
                    + " (got '" + payloadType + "').";
        }
        var runIdStr = optString(args, PARAM_RUN_ID);
        Long explicitRunId = null;
        if (runIdStr != null && !runIdStr.isBlank()) {
            try {
                explicitRunId = Long.parseLong(runIdStr);
            } catch (NumberFormatException _) {
                return "Error: 'runId' must be numeric (got '" + runIdStr + "').";
            }
        }
        final var finalMessage = message;
        final var finalPayloadType = payloadType.toLowerCase();
        final var finalExplicitTarget = explicitTarget != null ? explicitTarget.toLowerCase() : null;
        final var finalExplicitRunId = explicitRunId;
        final var callingAgentId = callingAgent.id;
        return Tx.run(() -> dispatch(callingAgentId, finalExplicitTarget, finalExplicitRunId,
                finalMessage, finalPayloadType));
    }

    /** Resolve target direction + invoke the appropriate writer. Caller-side
     *  inference: a calling agent that is a currently-RUNNING child defaults
     *  to {@code "parent"}; otherwise defaults to {@code "child"}. Must run
     *  inside an active Tx. */
    private static String dispatch(Long callingAgentId, String explicitTarget, Long explicitRunId,
                                    String message, String payloadType) {
        SubagentRun callerAsChildRun = findCallerActiveChildRun(callingAgentId);
        String resolvedTarget;
        if (explicitTarget != null) {
            resolvedTarget = explicitTarget;
        } else if (callerAsChildRun != null) {
            resolvedTarget = TARGET_PARENT;
        } else {
            resolvedTarget = TARGET_CHILD;
        }
        if (TARGET_PARENT.equals(resolvedTarget)) {
            if (callerAsChildRun == null) {
                return "Error: target=\"parent\" requires the calling agent to be an active child "
                        + "of a running SubagentRun; this agent is not currently a child of any run.";
            }
            return sendToParent(callerAsChildRun, callingAgentId, message, payloadType);
        }
        // target == "child"
        if (explicitRunId == null) {
            return "Error: target=\"child\" requires 'runId'.";
        }
        return sendToChild(explicitRunId, callingAgentId, message, payloadType);
    }

    /** Most-recent RUNNING SubagentRun where this agent is the child, or null
     *  if there isn't one. */
    private static SubagentRun findCallerActiveChildRun(Long callingAgentId) {
        return SubagentRun.find(
                "childAgent.id = ?1 AND status = ?2 ORDER BY startedAt DESC",
                callingAgentId, SubagentRun.Status.RUNNING).first();
    }

    /** Parent→child: append USER message to the child conversation. */
    private static String sendToChild(Long runId, Long callingAgentId,
                                       String message, String payloadType) {
        var run = (SubagentRun) SubagentRun.findById(runId);
        if (run == null) {
            return "Error: no SubagentRun found for runId " + runId + ".";
        }
        if (run.parentAgent == null || !callingAgentId.equals(run.parentAgent.id)) {
            return ERR_RUN_ID + runId + " is not owned by the calling agent.";
        }
        if (run.status != SubagentRun.Status.RUNNING) {
            return "Error: cannot send to child of runId " + runId + " — run is "
                    + run.status.name().toLowerCase() + ", not RUNNING.";
        }
        var childConv = run.childConversation;
        if (childConv == null) {
            return ERR_RUN_ID + runId + " has no child conversation (audit row is malformed).";
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", TARGET_PARENT);
        metadata.put(PARAM_RUN_ID, run.id);
        metadata.put("parentAgentId", run.parentAgent.id);
        if (!DEFAULT_PAYLOAD_TYPE.equals(payloadType)) metadata.put(PARAM_PAYLOAD_TYPE, payloadType);
        var msg = stampAsSubagentSend(childConv, message, metadata);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", "sent");
        payload.put("direction", "parent_to_child");
        payload.put(PARAM_RUN_ID, String.valueOf(run.id));
        payload.put("childConversationId", String.valueOf(childConv.id));
        payload.put("messageId", String.valueOf(msg.id));
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /** Child→parent: append USER message to the parent conversation. */
    private static String sendToParent(SubagentRun run, Long callingAgentId,
                                        String message, String payloadType) {
        var parentConv = run.parentConversation;
        if (parentConv == null) {
            return ERR_RUN_ID + run.id + " has no parent conversation (audit row is malformed).";
        }
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("source", TARGET_CHILD);
        metadata.put(PARAM_RUN_ID, run.id);
        metadata.put("childAgentId", callingAgentId);
        if (!DEFAULT_PAYLOAD_TYPE.equals(payloadType)) metadata.put(PARAM_PAYLOAD_TYPE, payloadType);
        var msg = stampAsSubagentSend(parentConv, message, metadata);
        var payload = new LinkedHashMap<String, Object>();
        payload.put("action", "sent");
        payload.put("direction", "child_to_parent");
        payload.put(PARAM_RUN_ID, String.valueOf(run.id));
        payload.put("parentConversationId", String.valueOf(parentConv.id));
        payload.put("messageId", String.valueOf(msg.id));
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    /** Append a USER-role message via the shared bookkeeping path, then
     *  stamp {@link Message#messageKind} + metadata in a follow-up save.
     *  Same pattern as {@link ConversationService#appendAssistantMessage}'s
     *  truncated-flag overload. */
    private static Message stampAsSubagentSend(Conversation conv, String message,
                                                Map<String, Object> metadata) {
        var msg = ConversationService.appendMessage(conv, MessageRole.USER, message,
                null, null, null);
        msg.messageKind = MESSAGE_KIND;
        msg.metadata = GsonHolder.INSTANCE.toJson(metadata, Map.class);
        msg.save();
        return msg;
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }
}
