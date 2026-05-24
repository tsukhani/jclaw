package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import services.DeliveryDispatcher;
import services.Tx;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JCLAW-327: agent-facing {@code message} tool for mid-flight delivery to
 * an external chat channel (Telegram, Slack, WhatsApp). Companion to the
 * existing JCLAW-326 {@link ConversationSendTool} (which targets a
 * subagent's parent/child within JClaw's own conversation graph) — this
 * tool reaches out to the external chat platform the agent is bound to.
 *
 * <p><b>Primary use case (Radarr-monitor pattern).</b> A subagent watching
 * a long-running download can emit progress mid-flight:
 *
 * <pre>{@code
 *   message(action="send", message="⬇ Twisters: 45%")
 * }</pre>
 *
 * <p>No {@code channel} or {@code target} needed — the tool reads the calling
 * agent's active conversation, picks up its {@code channelType} +
 * {@code peerId}, and dispatches there. AC-5 of JCLAW-327: subagent
 * conversations inherit the parent's channel context at spawn time (see
 * {@link SubagentSpawnTool#resolveChildConversation}), so the child's
 * own conversation already points at the right Telegram chat.
 *
 * <p>Explicit overrides are accepted for the "talk to a specific target"
 * case: pass {@code channel} + {@code target} to override the inferred
 * delivery context. Cross-channel send (e.g. agent in Telegram pushing to
 * Slack) is allowed by construction — the dispatcher routes by the
 * explicit {@code channel} value when given.
 *
 * <p><b>Scope of this commit (P0).</b> Only {@code action="send"} is
 * implemented. {@code reply}, {@code edit}, {@code delete}, {@code react}
 * (AC-3 P1/P2) are deferred to follow-up tickets; the action enum lists
 * only {@code send} for now so the LLM doesn't try the others and get a
 * 400.
 */
public class MessageTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "message";

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_CHANNEL = "channel";
    private static final String PARAM_TARGET = "target";

    private static final String ACTION_SEND = "send";
    private static final Set<String> ALLOWED_ACTIONS = Set.of(ACTION_SEND);

    @Override
    public String name() { return TOOL_NAME; }

    @Override
    public String category() { return "System"; }

    @Override
    public String icon() { return "send"; }

    @Override
    public String shortDescription() {
        return "Send a message to an external chat channel (Telegram / Slack / WhatsApp) mid-turn.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_SEND,
                        "Send a text message to the configured channel + target."));
    }

    @Override
    public String description() {
        return """
                Send a message to an external chat channel (Telegram, Slack, or WhatsApp) at any \
                point during your turn — not just at task / subagent completion. Useful for \
                pushing progress updates from long-running work (downloads, builds, scans) \
                back to the user who started the conversation. \
                Required: `action` ("send"), `message` (the text to deliver). \
                Optional: `channel` (telegram | slack | whatsapp; defaults to the calling \
                agent's active conversation channel), `target` (channel-specific peer id — \
                Telegram chat id, Slack channel id, WhatsApp e.164 phone; defaults to the \
                active conversation's peer). Subagents spawned in a channel-bound conversation \
                inherit the parent's channel + target, so they can call this with just \
                `action` and `message` to reply where the user is. Cross-channel sends are \
                allowed when both `channel` and `target` are explicit.""";
    }

    @Override
    public String summary() {
        return "Send a message to an external chat channel mid-turn.";
    }

    @Override
    public Map<String, Object> parameters() {
        var props = new LinkedHashMap<String, Object>();
        props.put(PARAM_ACTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.ENUM, List.copyOf(ALLOWED_ACTIONS),
                SchemaKeys.DESCRIPTION,
                "What to do. Currently only \"send\" is supported."));
        props.put(PARAM_MESSAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION, "The message body to deliver (required)."));
        props.put(PARAM_CHANNEL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.ENUM, List.of("telegram", "slack", "whatsapp"),
                SchemaKeys.DESCRIPTION,
                "Channel to deliver on. Defaults to the calling agent's active "
                        + "conversation channel — e.g. a subagent spawned from a Telegram "
                        + "thread inherits \"telegram\" and doesn't need to pass this."));
        props.put(PARAM_TARGET, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Channel-specific peer id (Telegram chat id, Slack channel id, "
                        + "WhatsApp e.164 phone). Defaults to the active conversation's "
                        + "peer id."));
        return Map.of(
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, props,
                SchemaKeys.REQUIRED, List.of(PARAM_ACTION, PARAM_MESSAGE)
        );
    }

    /** Network I/O via {@link DeliveryDispatcher}; keep serial within a turn
     *  to avoid hammering the channel API with concurrent sends. */
    @Override
    public boolean parallelSafe() { return false; }

    @Override
    public String execute(String argsJson, Agent callingAgent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var action = optString(args, PARAM_ACTION);
        if (action == null || action.isBlank()) {
            return "Error: 'action' is required.";
        }
        if (!ALLOWED_ACTIONS.contains(action.toLowerCase())) {
            return "Error: 'action' must be one of " + ALLOWED_ACTIONS
                    + " (got '" + action + "'). Other actions (reply, edit, delete, react) "
                    + "are not yet supported in this build.";
        }
        var message = optString(args, PARAM_MESSAGE);
        if (message == null || message.isBlank()) {
            return "Error: 'message' is required.";
        }
        var explicitChannel = optString(args, PARAM_CHANNEL);
        var explicitTarget = optString(args, PARAM_TARGET);
        final var finalCallerId = callingAgent.id;
        final var finalChannel = explicitChannel;
        final var finalTarget = explicitTarget;
        final var finalMessage = message;
        return Tx.run(() -> dispatch(finalCallerId, finalChannel, finalTarget, finalMessage));
    }

    /** Resolve channel + target (explicit overrides win; otherwise infer from
     *  the calling agent's active conversation), then hand off to the
     *  dispatcher. Must run inside an active Tx. */
    private static String dispatch(Long callingAgentId, String explicitChannel,
                                    String explicitTarget, String message) {
        var agent = (Agent) Agent.findById(callingAgentId);
        if (agent == null) {
            return "Error: calling agent " + callingAgentId + " not found.";
        }
        String channel = explicitChannel;
        String target = explicitTarget;
        // Inference: when channel or target is missing, read them off the
        // calling agent's most-recently-updated conversation. Mirrors
        // SubagentSpawnTool.resolveParentConversation's "most recent wins"
        // rule. Subagents inherit the parent's channel + peerId at spawn
        // time (JCLAW-327 AC-5), so this lookup transparently routes a
        // subagent's send to the user's original channel.
        if (channel == null || channel.isBlank() || target == null || target.isBlank()) {
            var conv = (Conversation) Conversation.find(
                    "agent = ?1 ORDER BY updatedAt DESC", agent).first();
            if (conv == null) {
                return "Error: no active conversation for agent '" + agent.name
                        + "'; pass explicit 'channel' and 'target' to send anyway.";
            }
            if (channel == null || channel.isBlank()) channel = conv.channelType;
            if (target == null || target.isBlank()) target = conv.peerId;
        }
        if (target == null || target.isBlank()) {
            return "Error: no 'target' inferred from the active conversation "
                    + "(channel '" + channel + "' has no peerId on the current conversation row) "
                    + "and none was passed. Provide 'target' explicitly.";
        }
        if (!DeliveryDispatcher.isSupported(channel)) {
            return "Error: channel '" + channel + "' is not a deliverable channel "
                    + "(supported: telegram, slack, whatsapp). The active conversation may be "
                    + "a web chat (channel='web') with no external delivery target — pass "
                    + "explicit 'channel' and 'target' if you want to reach a different chat.";
        }
        var result = DeliveryDispatcher.dispatch(agent, channel, target, message);
        if (result.ok()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put("action", "sent");
            payload.put("channel", channel);
            payload.put("target", target);
            return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
        }
        return "Error: " + result.reason();
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }
}
