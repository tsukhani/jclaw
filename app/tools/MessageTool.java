package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import channels.TelegramChannel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.TelegramBinding;
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
 * <p><b>Send (JCLAW-327).</b> {@code action="send"} delivers text to the
 * inferred-or-explicit channel + target via {@link DeliveryDispatcher}.
 *
 * <p><b>Telegram message actions (JCLAW-374).</b> {@code delete}, {@code pin},
 * {@code unpin}, and {@code react} operate on an existing Telegram message
 * by {@code message_id}, calling the corresponding
 * {@link channels.TelegramChannel} primitive. The bot token is resolved from
 * the calling agent's (or an ancestor's) {@link models.TelegramBinding}, and
 * the chat id from the explicit {@code target} or the agent's active
 * conversation peer — the same resolution {@code send} uses. Each is gated
 * behind a per-action capability toggle (see {@link #actionEnabled}); a
 * disabled action returns a structured {@code not-enabled} result rather than
 * throwing. {@code reply} / {@code edit} remain deferred.
 */
public class MessageTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "message";

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_CHANNEL = "channel";
    private static final String PARAM_TARGET = "target";
    private static final String PARAM_MESSAGE_ID = "message_id";
    private static final String PARAM_EMOJI = "emoji";

    private static final String ACTION_SEND = "send";
    private static final String ACTION_DELETE = "delete";
    private static final String ACTION_PIN = "pin";
    private static final String ACTION_UNPIN = "unpin";
    private static final String ACTION_REACT = "react";
    private static final Set<String> ALLOWED_ACTIONS =
            Set.of(ACTION_SEND, ACTION_DELETE, ACTION_PIN, ACTION_UNPIN, ACTION_REACT);

    // JCLAW-374: per-action capability toggles, read from play.Play.configuration
    // (same mechanism as TelegramChannel.replyToMode). Sensible defaults: react
    // and delete are low-blast-radius and commonly wanted, so they default ON;
    // pin/unpin mutate chat-wide pinned state for everyone in the chat, so it
    // defaults OFF and the operator opts in. unpin shares the pin toggle.
    private static final String CFG_ACTION_DELETE = "telegram.actions.delete";
    private static final String CFG_ACTION_PIN = "telegram.actions.pin";
    private static final String CFG_ACTION_REACT = "telegram.actions.react";

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
                        "Send a text message to the configured channel + target."),
                new ToolAction(ACTION_DELETE,
                        "Delete a Telegram message by message_id."),
                new ToolAction(ACTION_PIN,
                        "Pin a Telegram message by message_id."),
                new ToolAction(ACTION_UNPIN,
                        "Unpin a Telegram message by message_id."),
                new ToolAction(ACTION_REACT,
                        "Set (or clear) the bot's reaction on a Telegram message by message_id."));
    }

    @Override
    public String description() {
        return """
                Send a message to an external chat channel (Telegram, Slack, or WhatsApp) at any \
                point during your turn — not just at task / subagent completion. Useful for \
                pushing progress updates from long-running work (downloads, builds, scans) \
                back to the user who started the conversation. \
                For `action="send"`: required `message` (the text to deliver); optional \
                `channel` (telegram | slack | whatsapp; defaults to the calling agent's \
                active conversation channel) and `target` (channel-specific peer id — \
                Telegram chat id, Slack channel id, WhatsApp e.164 phone; defaults to the \
                active conversation's peer). Subagents spawned in a channel-bound conversation \
                inherit the parent's channel + target, so they can call this with just \
                `action` and `message` to reply where the user is. Cross-channel sends are \
                allowed when both `channel` and `target` are explicit. \
                For Telegram message actions `delete` / `pin` / `unpin` / `react`: required \
                `message_id` (the Telegram message to act on); the chat is taken from `target` \
                or the active conversation's peer, and the bot token from the agent's Telegram \
                binding. `react` takes an optional `emoji` (a blank/omitted emoji clears the \
                bot's reaction). These actions may be disabled by the operator, in which case \
                you get a `not-enabled` result.""";
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
                "What to do: \"send\" a new message, or (Telegram only) \"delete\" / "
                        + "\"pin\" / \"unpin\" / \"react\" on an existing message."));
        props.put(PARAM_MESSAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "The message body to deliver (required for action=\"send\")."));
        props.put(PARAM_MESSAGE_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Telegram message id to act on (required for delete / pin / unpin / react)."));
        props.put(PARAM_EMOJI, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Reaction emoji for action=\"react\" (e.g. \"👍\"). "
                        + "Blank or omitted clears the bot's reaction."));
        props.put(PARAM_CHANNEL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.ENUM, List.of("telegram", "slack", "whatsapp", "web"),
                SchemaKeys.DESCRIPTION,
                "Channel to deliver on. Defaults to the calling agent's active "
                        + "conversation channel — e.g. a subagent spawned from a Telegram "
                        + "thread inherits \"telegram\" and doesn't need to pass this. "
                        + "\"web\" routes to the in-app chat (the JClaw conversation the "
                        + "user is viewing); use this for users who started the chat "
                        + "from the web UI rather than an external messenger."));
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
        var normalized = action.toLowerCase();
        if (!ALLOWED_ACTIONS.contains(normalized)) {
            return "Error: 'action' must be one of " + ALLOWED_ACTIONS
                    + " (got '" + action + "'). Other actions (reply, edit) "
                    + "are not yet supported in this build.";
        }
        final var finalCallerId = callingAgent.id;
        if (ACTION_SEND.equals(normalized)) {
            var message = optString(args, PARAM_MESSAGE);
            if (message == null || message.isBlank()) {
                return "Error: 'message' is required.";
            }
            final var finalChannel = optString(args, PARAM_CHANNEL);
            final var finalTarget = optString(args, PARAM_TARGET);
            final var finalMessage = message;
            return Tx.run(() -> dispatch(finalCallerId, finalChannel, finalTarget, finalMessage));
        }
        // JCLAW-374: delete / pin / unpin / react — Telegram message actions.
        var messageId = optInteger(args, PARAM_MESSAGE_ID);
        if (messageId == null) {
            return "Error: 'message_id' is required for action '" + normalized + "'.";
        }
        final var finalTarget = optString(args, PARAM_TARGET);
        final var finalEmoji = optString(args, PARAM_EMOJI);
        final var finalMessageId = messageId;
        return Tx.run(() ->
                telegramAction(finalCallerId, normalized, finalTarget, finalMessageId, finalEmoji));
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
        // Target is required for external channels (telegram/slack/whatsapp)
        // because it's the platform-specific peer id (chat id / channel id /
        // phone number). Web is routed by the dispatcher to the calling
        // agent's parent-chain root conversation, so target is unused there
        // and we don't require it.
        var needsTarget = channel != null && !"web".equalsIgnoreCase(channel);
        if (needsTarget && (target == null || target.isBlank())) {
            return "Error: no 'target' inferred from the active conversation "
                    + "(channel '" + channel + "' has no peerId on the current conversation row) "
                    + "and none was passed. Provide 'target' explicitly.";
        }
        if (!DeliveryDispatcher.isSupported(channel)) {
            return "Error: channel '" + channel + "' is not a deliverable channel "
                    + "(supported: telegram, slack, whatsapp, web).";
        }
        var result = DeliveryDispatcher.dispatch(agent, channel, target, message);
        if (result.ok()) {
            var payload = new LinkedHashMap<String, Object>();
            payload.put(PARAM_ACTION, "sent");
            payload.put(PARAM_CHANNEL, channel);
            payload.put(PARAM_TARGET, target);
            return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
        }
        return "Error: " + result.reason();
    }

    /**
     * JCLAW-374: execute a Telegram message action (delete / pin / unpin /
     * react) on {@code messageId}. Resolves the bot token from the agent's
     * (or an ancestor's) Telegram binding and the chat id from {@code target}
     * or the agent's active conversation peer, then calls the matching
     * {@link TelegramChannel} primitive. Gated per-action by
     * {@link #actionEnabled}: a disabled action returns a {@code not-enabled}
     * envelope without touching the API. Must run inside an active Tx.
     */
    private static String telegramAction(Long callingAgentId, String action,
                                          String explicitTarget, int messageId, String emoji) {
        if (!actionEnabled(action)) {
            return resultJson(action, "not-enabled",
                    "Action '" + action + "' is disabled by configuration "
                            + "(" + cfgKeyFor(action) + ").");
        }
        var agent = (Agent) Agent.findById(callingAgentId);
        if (agent == null) {
            return "Error: calling agent " + callingAgentId + " not found.";
        }
        var binding = TelegramBinding.findByAgentOrAncestor(agent);
        if (binding == null) {
            return "Error: no Telegram bot is connected for agent '" + agent.name
                    + "' (or any of its ancestors); cannot " + action + " a message.";
        }
        if (!binding.enabled) {
            return "Error: Telegram binding for agent '" + binding.agent.name + "' is disabled.";
        }
        String chatId = resolveChatId(agent, explicitTarget);
        if (chatId == null || chatId.isBlank()) {
            return "Error: no Telegram chat 'target' was passed and none could be "
                    + "inferred from the active conversation.";
        }
        boolean ok = switch (action) {
            case ACTION_DELETE -> TelegramChannel.deleteMessage(binding.botToken, chatId, messageId);
            case ACTION_PIN -> TelegramChannel.pinChatMessage(binding.botToken, chatId, messageId);
            case ACTION_UNPIN -> TelegramChannel.unpinChatMessage(binding.botToken, chatId, messageId);
            case ACTION_REACT -> TelegramChannel.setMessageReaction(binding.botToken, chatId, messageId, emoji);
            default -> false;
        };
        if (ok) {
            return resultJson(action, "ok", null);
        }
        return resultJson(action, "failed",
                "Telegram API rejected the " + action + " (see logs for details).");
    }

    /** Chat id for a Telegram action: explicit {@code target} wins, else the
     *  peer of the agent's most-recently-updated conversation (matches the
     *  {@code send} inference rule). */
    private static String resolveChatId(Agent agent, String explicitTarget) {
        if (explicitTarget != null && !explicitTarget.isBlank()) return explicitTarget;
        var conv = (Conversation) Conversation.find(
                "agent = ?1 ORDER BY updatedAt DESC", agent).first();
        return conv == null ? null : conv.peerId;
    }

    /** Per-action capability toggle, read from {@code play.Play.configuration}
     *  with safe defaults (react/delete ON, pin/unpin OFF). */
    static boolean actionEnabled(String action) {
        var key = cfgKeyFor(action);
        boolean defaultOn = !ACTION_PIN.equals(action) && !ACTION_UNPIN.equals(action);
        var raw = play.Play.configuration.getProperty(key, Boolean.toString(defaultOn));
        if (raw == null || raw.isBlank()) return defaultOn;
        return Boolean.parseBoolean(raw.trim());
    }

    /** Map an action to its config toggle key (unpin shares the pin toggle). */
    private static String cfgKeyFor(String action) {
        return switch (action) {
            case ACTION_DELETE -> CFG_ACTION_DELETE;
            case ACTION_PIN, ACTION_UNPIN -> CFG_ACTION_PIN;
            case ACTION_REACT -> CFG_ACTION_REACT;
            default -> "telegram.actions." + action;
        };
    }

    /** Structured tool result: {@code {action, status[, reason]}}. Mirrors the
     *  send path's JSON envelope shape. */
    private static String resultJson(String action, String status, String reason) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put(PARAM_ACTION, action);
        payload.put("status", status);
        if (reason != null) payload.put("reason", reason);
        return utils.GsonHolder.INSTANCE.toJson(payload, Map.class);
    }

    private static String optString(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        return el.getAsString();
    }

    private static Integer optInteger(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsInt();
        } catch (NumberFormatException | IllegalStateException e) {
            return null;
        }
    }
}
