package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import channels.TelegramChannel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.SlackBinding;
import models.TelegramBinding;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import play.Play;
import services.DeliveryDispatcher;
import services.Tx;
import utils.GsonHolder;

import java.util.ArrayList;
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
 * <p><b>Telegram message actions (JCLAW-374, JCLAW-381).</b> {@code reply},
 * {@code edit}, {@code delete}, {@code pin}, {@code unpin}, and {@code react}
 * operate on an existing Telegram message by {@code message_id}, calling the
 * corresponding {@link channels.TelegramChannel} primitive. {@code reply}
 * sends a fresh message that quotes the target, and {@code edit} revises a
 * bot-sent message's text in place (JCLAW-381). The bot token is resolved from
 * the calling agent's (or an ancestor's) {@link models.TelegramBinding}, and
 * the chat id from the explicit {@code target} or the agent's active
 * conversation peer — the same resolution {@code send} uses. Each is gated
 * behind a per-action capability toggle (see {@link #actionEnabled}); a
 * disabled action returns a structured {@code not-enabled} result rather than
 * throwing.
 */
public class MessageTool implements ToolRegistry.Tool {

    public static final String TOOL_NAME = "message";

    private static final String PARAM_ACTION = "action";
    private static final String PARAM_MESSAGE = "message";
    private static final String PARAM_CHANNEL = "channel";
    private static final String PARAM_TARGET = "target";
    private static final String PARAM_MESSAGE_ID = "message_id";
    private static final String PARAM_EMOJI = "emoji";
    // JCLAW-387 (A3): optional excerpt to natively quote on a reply.
    private static final String PARAM_QUOTE = "quote";
    // JCLAW-387 (C1): poll question + answer options + optional knobs.
    private static final String PARAM_QUESTION = "question";
    private static final String PARAM_OPTIONS = "options";
    private static final String PARAM_ANONYMOUS = "anonymous";
    private static final String PARAM_MULTIPLE = "allow_multiple";
    private static final String PARAM_OPEN_PERIOD = "open_period";

    private static final String ERR_CALLING_AGENT = "Error: calling agent ";
    private static final String ERR_NOT_FOUND = " not found.";

    // Channel discriminators used in the schema enum + per-channel target resolution.
    private static final String CHANNEL_SLACK = "slack";
    private static final String CHANNEL_WHATSAPP = "whatsapp";

    private static final String ACTION_SEND = "send";
    private static final String ACTION_DELETE = "delete";
    private static final String ACTION_PIN = "pin";
    private static final String ACTION_UNPIN = "unpin";
    private static final String ACTION_REACT = "react";
    private static final String ACTION_REPLY = "reply";
    private static final String ACTION_EDIT = "edit";
    private static final String ACTION_POLL = "poll";
    private static final Set<String> ALLOWED_ACTIONS =
            Set.of(ACTION_SEND, ACTION_DELETE, ACTION_PIN, ACTION_UNPIN,
                    ACTION_REACT, ACTION_REPLY, ACTION_EDIT, ACTION_POLL);

    // JCLAW-387 (C1): a native poll must carry 2-10 options. Telegram's own cap
    // is 12, but a 10-option ceiling keeps the agent-facing surface conservative.
    private static final int MIN_POLL_OPTIONS = 2;
    private static final int MAX_POLL_OPTIONS = 10;

    // JCLAW-374: per-action capability toggles, read from play.Play.configuration
    // (same mechanism as TelegramChannel.replyToMode). Sensible defaults: react
    // and delete are low-blast-radius and commonly wanted, so they default ON;
    // pin/unpin mutate chat-wide pinned state for everyone in the chat, so it
    // defaults OFF and the operator opts in. unpin shares the pin toggle.
    // JCLAW-381: reply (a fresh message that quotes a target) and edit (revise a
    // bot-sent message in place) are both low-blast-radius — they only add or
    // amend the bot's own output, never delete anyone's message or mutate
    // chat-wide pinned state — so they default ON like react/delete.
    private static final String CFG_ACTION_DELETE = "telegram.actions.delete";
    private static final String CFG_ACTION_PIN = "telegram.actions.pin";
    private static final String CFG_ACTION_REACT = "telegram.actions.react";
    private static final String CFG_ACTION_REPLY = "telegram.actions.reply";
    private static final String CFG_ACTION_EDIT = "telegram.actions.edit";
    // JCLAW-387 (C1): poll posts a native poll to the chat — low blast radius
    // (it only adds the bot's own message, never mutates anyone else's content),
    // so it defaults ON like react/delete/reply/edit.
    private static final String CFG_ACTION_POLL = "telegram.actions.poll";

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
                        "Set (or clear) the bot's reaction on a Telegram message by message_id."),
                new ToolAction(ACTION_REPLY,
                        "Send a Telegram message as a reply to an existing message_id."),
                new ToolAction(ACTION_EDIT,
                        "Edit the text of a bot-sent Telegram message by message_id."),
                new ToolAction(ACTION_POLL,
                        "Post a native Telegram poll (question + 2-10 options) to the chat."));
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
                For Telegram message actions `reply` / `edit` / `delete` / `pin` / `unpin` / \
                `react`: required `message_id` (the Telegram message to act on); the chat is \
                taken from `target` or the active conversation's peer, and the bot token from \
                the agent's Telegram binding. `reply` sends `message` as a reply to \
                `message_id`, with an optional `quote` excerpt (a verbatim substring of the \
                replied-to message) that Telegram highlights above your reply — a non-matching \
                excerpt is silently dropped and the reply still sends; `edit` replaces a \
                bot-sent message's text with `message`; both require `message`. `react` takes \
                an optional `emoji` (a blank/omitted emoji clears the bot's reaction). \
                For `action="poll"` (Telegram): post a native poll with a required `question` \
                and `options` (2-10 strings), plus optional `anonymous` (default true), \
                `allow_multiple` (default false), and `open_period` (seconds, 5-600, before \
                auto-close). These actions may be disabled by the operator, in which case you \
                get a `not-enabled` result.""";
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
                "What to do: \"send\" a new message, or (Telegram only) \"reply\" / "
                        + "\"edit\" / \"delete\" / \"pin\" / \"unpin\" / \"react\" on an "
                        + "existing message."));
        props.put(PARAM_MESSAGE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "The message body to deliver (required for action=\"send\", \"reply\", "
                        + "and \"edit\")."));
        props.put(PARAM_MESSAGE_ID, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Telegram message id to act on (required for reply / edit / delete / "
                        + "pin / unpin / react)."));
        props.put(PARAM_EMOJI, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Reaction emoji for action=\"react\" (e.g. \"👍\"). "
                        + "Blank or omitted clears the bot's reaction."));
        props.put(PARAM_QUOTE, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Optional for action=\"reply\": a verbatim excerpt of the replied-to "
                        + "message to natively quote (Telegram highlights it above your "
                        + "reply). Must be an exact substring of the target message; if it "
                        + "isn't, the reply is still sent without the quote. Omit to reply "
                        + "without a highlighted excerpt."));
        props.put(PARAM_QUESTION, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.DESCRIPTION,
                "Poll question for action=\"poll\" (required; 1-300 chars)."));
        props.put(PARAM_OPTIONS, Map.of(SchemaKeys.TYPE, SchemaKeys.ARRAY,
                SchemaKeys.ITEMS, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING),
                SchemaKeys.DESCRIPTION,
                "Poll answer options for action=\"poll\" (required; 2-10 strings, "
                        + "each 1-100 chars)."));
        props.put(PARAM_ANONYMOUS, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                SchemaKeys.DESCRIPTION,
                "Optional for action=\"poll\": whether votes are anonymous "
                        + "(default true). Pass false to show who voted for what."));
        props.put(PARAM_MULTIPLE, Map.of(SchemaKeys.TYPE, SchemaKeys.BOOLEAN,
                SchemaKeys.DESCRIPTION,
                "Optional for action=\"poll\": allow voters to pick multiple options "
                        + "(default false)."));
        props.put(PARAM_OPEN_PERIOD, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                SchemaKeys.DESCRIPTION,
                "Optional for action=\"poll\": seconds (5-600) the poll stays open "
                        + "before auto-closing. Omit to leave it open indefinitely."));
        props.put(PARAM_CHANNEL, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING,
                SchemaKeys.ENUM, List.of("telegram", CHANNEL_SLACK, CHANNEL_WHATSAPP, "web"),
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
                    + " (got '" + action + "').";
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
        // JCLAW-387 (C1): poll — a native poll, keyed on question + options
        // rather than a message_id, so it's handled before the message_id gate.
        if (ACTION_POLL.equals(normalized)) {
            return executePoll(finalCallerId, args);
        }
        // JCLAW-374/381: reply / edit / delete / pin / unpin / react — Telegram
        // message actions, all keyed on message_id.
        var messageId = optInteger(args, PARAM_MESSAGE_ID);
        if (messageId == null) {
            return "Error: 'message_id' is required for action '" + normalized + "'.";
        }
        // JCLAW-381: reply and edit carry a text body in `message`.
        var text = optString(args, PARAM_MESSAGE);
        if ((ACTION_REPLY.equals(normalized) || ACTION_EDIT.equals(normalized))
                && (text == null || text.isBlank())) {
            return "Error: 'message' is required for action '" + normalized + "'.";
        }
        final var finalTarget = optString(args, PARAM_TARGET);
        final var finalEmoji = optString(args, PARAM_EMOJI);
        final var finalMessage = text;
        final var finalMessageId = messageId;
        // JCLAW-387 (A3): optional verbatim excerpt to natively quote on a reply.
        final var finalQuote = optString(args, PARAM_QUOTE);
        return Tx.run(() -> telegramAction(
                finalCallerId, normalized, finalTarget, finalMessageId, finalEmoji,
                finalMessage, finalQuote));
    }

    /**
     * JCLAW-387 (C1): validate + dispatch a {@code poll} action. Gated by
     * {@link #actionEnabled} like the other Telegram actions (a disabled poll
     * returns the {@code not-enabled} envelope). Validates a non-blank question
     * and {@value #MIN_POLL_OPTIONS}-{@value #MAX_POLL_OPTIONS} options before
     * touching the API, then resolves the bot token + chat id exactly as the
     * message actions do and calls {@link TelegramChannel#sendPoll}.
     */
    private static String executePoll(Long callingAgentId, JsonObject args) {
        if (!actionEnabled(ACTION_POLL)) {
            return resultJson(ACTION_POLL, "not-enabled",
                    "Action '" + ACTION_POLL + "' is disabled by configuration "
                            + "(" + cfgKeyFor(ACTION_POLL) + ").");
        }
        var question = optString(args, PARAM_QUESTION);
        if (question == null || question.isBlank()) {
            return "Error: 'question' is required for action 'poll'.";
        }
        var options = optStringList(args, PARAM_OPTIONS);
        if (options.size() < MIN_POLL_OPTIONS || options.size() > MAX_POLL_OPTIONS) {
            return "Error: 'poll' requires between " + MIN_POLL_OPTIONS + " and "
                    + MAX_POLL_OPTIONS + " options (got " + options.size() + ").";
        }
        final var finalTarget = optString(args, PARAM_TARGET);
        final var finalQuestion = question;
        final var finalOptions = options;
        final var finalAnonymous = optBoolean(args, PARAM_ANONYMOUS);
        final var finalMultiple = optBoolean(args, PARAM_MULTIPLE);
        final var finalOpenPeriod = optInteger(args, PARAM_OPEN_PERIOD);
        return Tx.run(() -> poll(callingAgentId, finalTarget, finalQuestion, finalOptions,
                finalAnonymous, finalMultiple, finalOpenPeriod));
    }

    /** Resolve channel + target (explicit overrides win; otherwise infer from
     *  the calling agent's active conversation), then hand off to the
     *  dispatcher. Must run inside an active Tx. */
    private static String dispatch(Long callingAgentId, String explicitChannel,
                                    String explicitTarget, String message) {
        var agent = (Agent) Agent.findById(callingAgentId);
        if (agent == null) {
            return ERR_CALLING_AGENT + callingAgentId + ERR_NOT_FOUND;
        }
        String channel = explicitChannel;
        String target = explicitTarget;
        // Channel inference: when the channel isn't given, read it off the calling
        // agent's most-recently-updated conversation ("the channel I'm operating on").
        // Subagents inherit the parent's channel at spawn (JCLAW-327 AC-5). The
        // most-recent-conversation lookup is shared with TaskTool via DeliveryResolver.
        if (channel == null || channel.isBlank()) {
            var conv = DeliveryResolver.mostRecentConversation(agent).orElse(null);
            if (conv == null) {
                return "Error: no active conversation for agent '" + agent.name
                        + "'; pass explicit 'channel' and 'target' to send anyway.";
            }
            channel = conv.channelType;
        }
        // Target resolution (when not passed explicitly):
        //   - telegram: the agent's (or an ancestor's) Telegram binding chat id — the
        //     authoritative "telegram channel setting", with NO dependency on a prior
        //     conversation, so a proactive send (e.g. a scheduled task firing in a
        //     web/internal context) reaches the user even with zero telegram history.
        //   - slack / whatsapp: the most-recent conversation peer on that channel
        //     (reply where the user is), then — JCLAW-425 — the agent's per-agent
        //     binding destination as a fallback, so a proactive send from an agent
        //     with no chat history on that channel still reaches the owner.
        //   - web: the most-recent conversation peer (also identifies the
        //     conversation the dispatcher fires back to); no binding fallback.
        if (target == null || target.isBlank()) {
            if ("telegram".equalsIgnoreCase(channel)) {
                var binding = TelegramBinding.findByAgentOrAncestor(agent);
                if (binding == null) {
                    return "Error: Telegram is not configured for agent '" + agent.name
                            + "' (no Telegram binding). Connect a Telegram bot in Channels "
                            + "settings, or pass an explicit 'target' chat id.";
                }
                target = binding.telegramUserId;
            } else {
                var cConv = (Conversation) Conversation.find(
                        "agent = ?1 AND channelType = ?2 ORDER BY updatedAt DESC", agent, channel).first();
                if (cConv != null) target = cConv.peerId;
                // JCLAW-425: no live conversation peer — fall back to the agent's
                // authoritative per-agent destination for slack/whatsapp (null for
                // web, or when no binding/destination is configured).
                if (target == null || target.isBlank()) {
                    target = perAgentBindingDestination(agent, channel);
                }
            }
        }
        // Target is required for external channels (telegram/slack/whatsapp)
        // because it's the platform-specific peer id (chat id / channel id /
        // phone number). Web is routed by the dispatcher to the calling
        // agent's parent-chain root conversation, so target is unused there
        // and we don't require it.
        var needsTarget = channel != null && !"web".equalsIgnoreCase(channel);
        if (needsTarget && (target == null || target.isBlank())) {
            return noDestinationError(agent, channel);
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
            return GsonHolder.INSTANCE.toJson(payload, Map.class);
        }
        return "Error: " + result.reason();
    }

    /**
     * JCLAW-425: the agent's authoritative per-agent outbound destination for an
     * external channel, walking the {@link Agent#parentAgent} chain so a subagent
     * inherits the spawning ancestor's binding (never a sibling top-level agent's).
     * Used only as a fallback when no explicit {@code target} and no live
     * conversation peer is available, so a proactive send from an agent with no
     * chat history on that channel still reaches the owner:
     * <ul>
     *   <li>{@code slack} → {@link SlackBinding#ownerUserId}.</li>
     *   <li>{@code whatsapp} → {@link WhatsAppBinding#ownerJid} for the WhatsApp-Web
     *       transport (the paired user), {@link WhatsAppBinding#defaultTarget} for
     *       Cloud-API (the operator-configured recipient — a Cloud-API business
     *       number has no inherent "owner").</li>
     *   <li>anything else (e.g. {@code web}) → {@code null}; web is routed by the
     *       dispatcher to the parent-chain root conversation, not by a peer id.</li>
     * </ul>
     * Returns {@code null} when no binding exists or it carries no destination
     * (e.g. a Cloud-API binding with no {@code defaultTarget} set) — the caller
     * then surfaces {@link #noDestinationError}. Binding {@code enabled} state is
     * irrelevant here: that gates delivery (in {@link DeliveryDispatcher}), not
     * which destination the agent owns.
     */
    private static String perAgentBindingDestination(Agent agent, String channel) {
        if (CHANNEL_SLACK.equalsIgnoreCase(channel)) {
            var binding = SlackBinding.findByAgentOrAncestor(agent);
            return binding == null ? null : binding.ownerUserId;
        }
        if (CHANNEL_WHATSAPP.equalsIgnoreCase(channel)) {
            var binding = WhatsAppBinding.findByAgentOrAncestor(agent);
            if (binding == null) return null;
            return binding.transport == WhatsAppTransport.WHATSAPP_WEB
                    ? binding.ownerJid
                    : binding.defaultTarget;
        }
        return null;
    }

    /**
     * JCLAW-425: the "no destination resolvable" error for a {@code send}, named
     * by channel and agent so the operator knows exactly what to configure. Slack
     * and WhatsApp point at their per-agent binding destination (and the explicit
     * {@code target} escape hatch); any other external channel falls back to the
     * legacy missing-target message.
     */
    private static String noDestinationError(Agent agent, String channel) {
        if (CHANNEL_SLACK.equalsIgnoreCase(channel)) {
            return "Error: no Slack destination configured for agent '" + agent.name
                    + "'. Set the owner on the agent's Slack binding (Channels settings), "
                    + "or pass an explicit 'target' (Slack channel or user id).";
        }
        if (CHANNEL_WHATSAPP.equalsIgnoreCase(channel)) {
            return "Error: no WhatsApp destination configured for agent '" + agent.name
                    + "'. Set the WhatsApp-Web owner, or the Cloud-API default recipient, "
                    + "on the agent's WhatsApp binding (Channels settings), or pass an "
                    + "explicit 'target' (E.164 phone).";
        }
        return "Error: no 'target' inferred from the active conversation "
                + "(channel '" + channel + "' has no peerId on the current conversation row) "
                + "and none was passed. Provide 'target' explicitly.";
    }

    /**
     * JCLAW-374/381: execute a Telegram message action (reply / edit / delete /
     * pin / unpin / react) on {@code messageId}. Resolves the bot token from the
     * agent's (or an ancestor's) Telegram binding and the chat id from
     * {@code target} or the agent's active conversation peer, then calls the
     * matching {@link TelegramChannel} primitive. {@code reply} sends
     * {@code message} via {@link TelegramChannel#sendMessage} quoting
     * {@code messageId}; {@code edit} replaces a bot-sent message's text via
     * {@link TelegramChannel#editMessageText}. Gated per-action by
     * {@link #actionEnabled}: a disabled action returns a {@code not-enabled}
     * envelope without touching the API. Must run inside an active Tx.
     *
     * <p>JCLAW-387 (A3): when {@code action="reply"} and {@code quote} is a
     * non-blank excerpt, the reply natively quotes that span of the target via
     * {@link TelegramChannel#sendReplyWithQuote} (best-effort: a non-matching
     * excerpt falls back to a plain reply rather than dropping the message). A
     * blank/null {@code quote} reproduces today's reply behavior exactly.
     */
    private static String telegramAction(Long callingAgentId, String action,
                                          String explicitTarget, int messageId,
                                          String emoji, String message, String quote) {
        if (!actionEnabled(action)) {
            return resultJson(action, "not-enabled",
                    "Action '" + action + "' is disabled by configuration "
                            + "(" + cfgKeyFor(action) + ").");
        }
        var agent = (Agent) Agent.findById(callingAgentId);
        if (agent == null) {
            return ERR_CALLING_AGENT + callingAgentId + ERR_NOT_FOUND;
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
            // JCLAW-381: reply = sendMessage with replyToMessageId set (null thread);
            // edit = editMessageText with the new text (null keyboard, no change to markup).
            // JCLAW-387 (A3): a non-blank `quote` routes through the quote-aware
            // reply path (which itself falls back to a plain reply on a quote
            // mismatch); a blank/null quote keeps the legacy sendMessage path.
            case ACTION_REPLY -> TelegramChannel.sendReplyWithQuote(
                    binding.botToken, chatId, message, agent, messageId, quote);
            case ACTION_EDIT -> TelegramChannel.editMessageText(
                    binding.botToken, chatId, messageId, message, null);
            default -> false;
        };
        if (ok) {
            return resultJson(action, "ok", null);
        }
        return resultJson(action, "failed",
                "Telegram API rejected the " + action + " (see logs for details).");
    }

    /**
     * JCLAW-387 (C1): resolve the bot token + chat id (same rules as
     * {@link #telegramAction}) and dispatch a native poll via
     * {@link TelegramChannel#sendPoll}. The {@code not-enabled} gate and input
     * validation (non-blank question, 2-10 options) are already applied in
     * {@link #executePoll} before this runs. Must run inside an active Tx.
     */
    private static String poll(Long callingAgentId, String explicitTarget, String question,
                               List<String> options, Boolean isAnonymous,
                               Boolean allowsMultiple, Integer openPeriod) {
        var agent = (Agent) Agent.findById(callingAgentId);
        if (agent == null) {
            return ERR_CALLING_AGENT + callingAgentId + ERR_NOT_FOUND;
        }
        var binding = TelegramBinding.findByAgentOrAncestor(agent);
        if (binding == null) {
            return "Error: no Telegram bot is connected for agent '" + agent.name
                    + "' (or any of its ancestors); cannot send a poll.";
        }
        if (!binding.enabled) {
            return "Error: Telegram binding for agent '" + binding.agent.name + "' is disabled.";
        }
        String chatId = resolveChatId(agent, explicitTarget);
        if (chatId == null || chatId.isBlank()) {
            return "Error: no Telegram chat 'target' was passed and none could be "
                    + "inferred from the active conversation.";
        }
        boolean ok = TelegramChannel.sendPoll(binding.botToken, chatId, question, options,
                isAnonymous, allowsMultiple, openPeriod);
        if (ok) {
            return resultJson(ACTION_POLL, "ok", null);
        }
        return resultJson(ACTION_POLL, "failed",
                "Telegram API rejected the poll (see logs for details).");
    }

    /** Chat id for a Telegram action: explicit {@code target} wins, else the
     *  peer of the agent's most-recently-updated conversation (the same shared
     *  {@link DeliveryResolver} lookup the {@code send} inference rule uses). */
    private static String resolveChatId(Agent agent, String explicitTarget) {
        if (explicitTarget != null && !explicitTarget.isBlank()) return explicitTarget;
        return DeliveryResolver.mostRecentConversation(agent).map(c -> c.peerId).orElse(null);
    }

    /** Per-action capability toggle, read from {@code play.Play.configuration}
     *  with safe defaults (react/delete ON, pin/unpin OFF). */
    static boolean actionEnabled(String action) {
        var key = cfgKeyFor(action);
        boolean defaultOn = !ACTION_PIN.equals(action) && !ACTION_UNPIN.equals(action);
        var raw = Play.configuration.getProperty(key, Boolean.toString(defaultOn));
        if (raw == null || raw.isBlank()) return defaultOn;
        return Boolean.parseBoolean(raw.trim());
    }

    /** Map an action to its config toggle key (unpin shares the pin toggle). */
    private static String cfgKeyFor(String action) {
        return switch (action) {
            case ACTION_DELETE -> CFG_ACTION_DELETE;
            case ACTION_PIN, ACTION_UNPIN -> CFG_ACTION_PIN;
            case ACTION_REACT -> CFG_ACTION_REACT;
            case ACTION_REPLY -> CFG_ACTION_REPLY;
            case ACTION_EDIT -> CFG_ACTION_EDIT;
            case ACTION_POLL -> CFG_ACTION_POLL;
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
        return GsonHolder.INSTANCE.toJson(payload, Map.class);
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
        } catch (NumberFormatException | IllegalStateException _) {
            return null;
        }
    }

    /** Optional boolean, null when the key is absent/null (so an unset poll knob
     *  leaves the Bot API default in {@link TelegramChannel#sendPoll}). */
    private static Boolean optBoolean(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull()) return null;
        try {
            return el.getAsBoolean();
        } catch (IllegalStateException | UnsupportedOperationException _) {
            return null;
        }
    }

    /**
     * JCLAW-387 (C1): read {@code key} as a list of non-blank, trimmed strings.
     * A missing key, a null, or a non-array value yields an empty list so the
     * caller's 2-10 count check surfaces the clean validation error rather than
     * a parse exception. Non-string / blank array elements are skipped.
     */
    private static List<String> optStringList(JsonObject obj, String key) {
        var el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonArray()) return List.of();
        var out = new ArrayList<String>();
        for (var item : el.getAsJsonArray()) {
            if (item == null || item.isJsonNull()) continue;
            try {
                var s = item.getAsString();
                if (s != null && !s.isBlank()) out.add(s.strip());
            } catch (IllegalStateException | UnsupportedOperationException _) {
                // Non-primitive array element (e.g. nested object) — skip it.
            }
        }
        return out;
    }
}
