package agents;

import channels.SlackApprovalService;
import channels.TelegramApprovalService;
import channels.TelegramMarkdownFormatter;
import models.Agent;
import models.Conversation;
import models.SlackBinding;
import models.TelegramBinding;
import models.ToolApprovalGrant;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * JCLAW-382: gate dangerous tool/exec actions behind the Telegram
 * approve/deny flow built in JCLAW-373.
 *
 * <p>Sits between {@link ParallelToolExecutor#runToolCall} and
 * {@link ToolRegistry#executeRich}. For every tool dispatch it answers one
 * question — <em>may this action proceed?</em> — and the answer is a no-op
 * "yes" in all but one narrow case:
 *
 * <ol>
 *   <li>the tool is marked {@link ToolRegistry.Tool#dangerous() dangerous}
 *       (today: {@code exec}); AND</li>
 *   <li>the running agent (or an ancestor, for sub-agents) is bound to a
 *       Telegram bot via {@link TelegramBinding#findByAgentOrAncestor}; AND</li>
 *   <li>JCLAW-423: the conversation that triggered the action is itself on
 *       Telegram — the only channel with an interactive approve/deny surface,
 *       and the only one where a prompt actually reaches the operator.</li>
 * </ol>
 *
 * <p>When both hold, the gate raises an interactive approve/deny prompt in
 * the bound user's private chat and blocks the calling (virtual) thread on
 * {@link TelegramApprovalService#await} until the user taps a button or the
 * request times out. {@code APPROVED_*} proceeds; {@code DENIED} /
 * {@code TIMED_OUT} / {@code EXPIRED} aborts.
 *
 * <p>JCLAW-423: a dangerous tool on any other channel (web, Slack) has no
 * interactive approval surface, so the gate applies the configured off-channel
 * policy ({@value #CFG_OFF_CHANNEL_POLICY}, default {@code allow}) instead of
 * routing a prompt to a Telegram chat the operator may not be watching — which
 * used to leave web-initiated turns blocking on a prompt nobody saw. A standing
 * grant still proceeds under either policy. Non-dangerous tools never reach the
 * gate — it returns {@link Decision#PROCEED} before any I/O.
 *
 * <h2>Session / always scope</h2>
 * <p>An {@code APPROVED_SESSION} or {@code APPROVED_ALWAYS} tap records a
 * grant keyed by {@code (agentId, toolName)} in {@link #GRANTS}, so the same
 * action isn't re-prompted on its next invocation. {@code APPROVED_SESSION}
 * lives only in that in-memory set and dies with the JVM, matching the
 * deliberately ephemeral lifetime documented on
 * {@link TelegramApprovalService}.
 *
 * <p>JCLAW-385: {@code APPROVED_ALWAYS} additionally persists a
 * {@link ToolApprovalGrant} row, so the grant survives a restart. The
 * pre-prompt check consults <em>both</em> the in-process set and the
 * persisted store, so a durable always-grant keeps suppressing the prompt
 * even after the in-memory set has been emptied (a fresh JVM).
 *
 * <p>The binding lookup walks the agent's parent chain, so a dangerous call
 * made by a sub-agent surfaces the prompt on its root ancestor's bound chat
 * (the only chat the operator wired a bot to) — the same inheritance
 * {@code message(channel="telegram", …)} delivery already relies on.
 */
public final class DangerousActionGate {

    private DangerousActionGate() {}

    private static final String LOG_CATEGORY = "tool";
    private static final String CHANNEL_NAME = "telegram";
    private static final String SLACK_CHANNEL = "slack";

    /** Default wait for a button tap before the prompt times out (seconds). */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * JCLAW-423: policy for a dangerous tool dispatched on a channel with no
     * interactive approval surface (anything but Telegram). {@code allow}
     * (default) runs it ungated, preserving the pre-423 behavior where non-
     * Telegram channels never gated dangerous tools; {@code deny} fails closed.
     * An explicit standing grant still proceeds under either policy.
     */
    public static final String CFG_OFF_CHANNEL_POLICY = "tool.approval.offChannelPolicy";
    private static final String DEFAULT_OFF_CHANNEL_POLICY = "allow";

    /**
     * Per-{@code (agentId, toolName)} standing grants from an
     * {@code APPROVED_SESSION} / {@code APPROVED_ALWAYS} tap. Presence of a
     * key means "don't re-prompt for this agent+tool". Process-local and
     * in-memory by design (see class Javadoc).
     */
    private static final Set<String> GRANTS = ConcurrentHashMap.newKeySet();

    /** The gate's verdict for a single dispatch. */
    public enum Decision { PROCEED, ABORT }

    /**
     * Decide whether {@code toolName} may run for {@code agent} on the
     * conversation identified by {@code conversationId}.
     *
     * @param agent          the executing agent (sub-agents resolve their
     *                        binding via the parent chain)
     * @param conversationId the originating conversation — its
     *                        {@code channelType} decides whether an interactive
     *                        prompt can reach the operator; {@code null} when no
     *                        conversation context is available
     * @param toolName       the tool about to dispatch
     * @param argsJson       the raw JSON arguments the model sent — surfaced in
     *                        the prompt so the user sees what they're approving
     * @return {@link Decision#PROCEED} to run the tool, {@link Decision#ABORT}
     *         to skip it and return a denial result to the model
     */
    public static Decision guard(Agent agent, Long conversationId, String toolName, String argsJson) {
        if (agent == null || !ToolRegistry.isDangerous(toolName)) {
            return Decision.PROCEED;
        }

        // A standing grant (in-process session set or the JCLAW-385 persisted
        // always-store) is an explicit operator approval for this (agent, tool)
        // — honor it on ANY channel without prompting.
        if (hasStandingGrant(agent, toolName)) {
            EventLogger.info(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                    "Dangerous tool '%s' pre-approved for this agent; skipping prompt".formatted(toolName));
            return Decision.PROCEED;
        }

        // JCLAW-423/350: the interactive approve/deny prompt reaches the operator
        // only when THIS conversation is on a channel that has an approval surface
        // (Telegram or Slack) AND has a usable binding. Route the prompt only there;
        // every other channel (web, or no conversation) has no surface and must NOT
        // silently route to a bound chat — it falls through to the off-channel policy.
        var channelType = resolveChannelType(conversationId);
        if (CHANNEL_NAME.equals(channelType)) {
            var binding = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(agent));
            if (binding != null && binding.enabled) {
                return promptAndAwait(agent, toolName, argsJson, binding);
            }
            // A Telegram conversation with no usable binding can't be prompted;
            // fall through to the off-channel policy rather than block forever.
        } else if (SLACK_CHANNEL.equals(channelType)) {
            var binding = Tx.run(() -> SlackBinding.findByAgentOrAncestor(agent));
            // Slack needs an owner user id to authorize the tap (JCLAW-350); without
            // one there's nobody who can resolve the prompt, so fall through instead
            // of posting an unanswerable approval that would only ever time out.
            if (binding != null && binding.enabled
                    && binding.ownerUserId != null && !binding.ownerUserId.isBlank()) {
                var channelId = resolvePeerId(conversationId);
                if (channelId != null) {
                    return promptAndAwaitSlack(agent, toolName, argsJson, binding, channelId);
                }
            }
        }

        return offChannelDecision(agent, toolName, channelType);
    }

    /**
     * Off-Telegram fallback: there is no interactive approval surface for this
     * dispatch, so apply the operator-configured policy
     * ({@value #CFG_OFF_CHANNEL_POLICY}, default {@code allow}). {@code allow}
     * proceeds ungated (the pre-JCLAW-423 behavior); {@code deny} fails closed.
     * A standing grant has already short-circuited before this point.
     */
    private static Decision offChannelDecision(Agent agent, String toolName, String channelType) {
        var chan = channelType == null ? "none" : channelType;
        var policy = ConfigService.get(CFG_OFF_CHANNEL_POLICY, DEFAULT_OFF_CHANNEL_POLICY);
        if ("deny".equalsIgnoreCase(policy)) {
            EventLogger.warn(LOG_CATEGORY, agent.name, chan,
                    "Dangerous tool '%s' on non-Telegram channel '%s' has no approval surface — denying (%s=deny)"
                            .formatted(toolName, chan, CFG_OFF_CHANNEL_POLICY));
            return Decision.ABORT;
        }
        EventLogger.info(LOG_CATEGORY, agent.name, chan,
                "Dangerous tool '%s' on non-Telegram channel '%s' — proceeding ungated (%s=allow)"
                        .formatted(toolName, chan, CFG_OFF_CHANNEL_POLICY));
        return Decision.PROCEED;
    }

    /**
     * True when an in-process session grant or a persisted always-grant
     * (JCLAW-385) covers {@code (agent, toolName)}. The persisted lookup hits
     * the DB, so it runs in its own transaction.
     */
    private static boolean hasStandingGrant(Agent agent, String toolName) {
        return GRANTS.contains(grantKey(agent, toolName))
                || Tx.run(() -> ToolApprovalGrant.exists(agent.id, toolName));
    }

    /** The conversation's {@code channelType}, or {@code null} when unknown. */
    private static String resolveChannelType(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return Tx.run(() -> {
            Conversation c = Conversation.findById(conversationId);
            return c == null ? null : c.channelType;
        });
    }

    /** The conversation's {@code peerId} (the Slack channel to prompt in), or {@code null}. */
    private static String resolvePeerId(Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return Tx.run(() -> {
            Conversation c = Conversation.findById(conversationId);
            return c == null ? null : c.peerId;
        });
    }

    private static Decision promptAndAwait(Agent agent, String toolName, String argsJson,
                                           TelegramBinding binding) {
        // The bound user's private chat: in a Telegram private chat
        // chat.id == user.id, so the binding's telegramUserId is both the
        // chat to prompt and the user authorized to resolve it.
        var chatId = binding.telegramUserId;
        var prompt = buildPrompt(toolName, argsJson);

        EventLogger.info(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                "Dangerous tool '%s' requires approval; prompting user %s".formatted(toolName, chatId));

        var future = TelegramApprovalService.request(
                binding.botToken, chatId, binding.telegramUserId, prompt, true);
        var outcome = TelegramApprovalService.await(future, timeout());

        return switch (outcome) {
            case APPROVED_ONCE -> Decision.PROCEED;
            case APPROVED_SESSION -> {
                recordSessionGrant(agent, toolName, CHANNEL_NAME, outcome.name());
                yield Decision.PROCEED;
            }
            case APPROVED_ALWAYS -> {
                recordAlwaysGrant(agent, toolName, CHANNEL_NAME, outcome.name());
                yield Decision.PROCEED;
            }
            case DENIED, TIMED_OUT, EXPIRED -> {
                EventLogger.warn(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                        "Dangerous tool '%s' not approved (%s) — aborting".formatted(toolName, outcome));
                yield Decision.ABORT;
            }
        };
    }

    /**
     * Slack analog of {@link #promptAndAwait} (JCLAW-350): post an approve/deny
     * Block Kit prompt to the conversation's channel, gated on the binding's owner
     * user id, and block until the owner taps a button (or it times out). Shares the
     * standing-grant recording and {@link #timeout()} with the Telegram path.
     */
    private static Decision promptAndAwaitSlack(Agent agent, String toolName, String argsJson,
                                                SlackBinding binding, String channelId) {
        var prompt = buildSlackPrompt(toolName, argsJson);

        EventLogger.info(LOG_CATEGORY, agent.name, SLACK_CHANNEL,
                "Dangerous tool '%s' requires approval; prompting owner %s in %s"
                        .formatted(toolName, binding.ownerUserId, channelId));

        var future = SlackApprovalService.request(
                binding.botToken, channelId, null, binding.ownerUserId, prompt, true);
        var outcome = SlackApprovalService.await(future, timeout());

        return switch (outcome) {
            case APPROVED_ONCE -> Decision.PROCEED;
            case APPROVED_SESSION -> {
                recordSessionGrant(agent, toolName, SLACK_CHANNEL, outcome.name());
                yield Decision.PROCEED;
            }
            case APPROVED_ALWAYS -> {
                recordAlwaysGrant(agent, toolName, SLACK_CHANNEL, outcome.name());
                yield Decision.PROCEED;
            }
            case DENIED, TIMED_OUT, EXPIRED -> {
                EventLogger.warn(LOG_CATEGORY, agent.name, SLACK_CHANNEL,
                        "Dangerous tool '%s' not approved (%s) — aborting".formatted(toolName, outcome));
                yield Decision.ABORT;
            }
        };
    }

    /** Record an in-process session grant for {@code (agent, toolName)} and log it. */
    private static void recordSessionGrant(Agent agent, String toolName, String channelName, String outcomeName) {
        GRANTS.add(grantKey(agent, toolName));
        EventLogger.info(LOG_CATEGORY, agent.name, channelName,
                "Dangerous tool '%s' approved (%s) — future calls won't re-prompt"
                        .formatted(toolName, outcomeName));
    }

    /**
     * Record a session grant AND persist an always-grant (JCLAW-385) so it survives
     * a restart. The upsert is idempotent on the unique {@code (agent, tool)} key.
     */
    private static void recordAlwaysGrant(Agent agent, String toolName, String channelName, String outcomeName) {
        GRANTS.add(grantKey(agent, toolName));
        Tx.run(() -> ToolApprovalGrant.upsert(agent, toolName));
        EventLogger.info(LOG_CATEGORY, agent.name, channelName,
                "Dangerous tool '%s' approved (%s) — future calls won't re-prompt (persisted)"
                        .formatted(toolName, outcomeName));
    }

    /**
     * The tool-result text returned to the model when a dispatch is aborted
     * by a denial / timeout. Phrased so the model treats it as a hard stop
     * for this action, not a transient error to retry around.
     */
    public static String abortResult(String toolName) {
        return ("The user denied (or did not approve in time) the request to run the '%s' action. "
                + "Do not retry this action. Acknowledge that it was not approved and continue with "
                + "whatever else you can do without it.").formatted(toolName);
    }

    /**
     * HTML-safe prompt body. Telegram renders {@code parseMode=HTML}, so the
     * tool name and args are escaped before interpolation; the args are
     * length-capped so an oversized payload can't blow the 4096-char message
     * budget the keyboard send assumes.
     */
    private static String buildPrompt(String toolName, String argsJson) {
        var args = argsJson == null ? "" : argsJson;
        if (args.length() > 600) {
            args = args.substring(0, 600) + "… (truncated)";
        }
        return "⚠ <b>Approval required</b>\n"
                + "The agent wants to run the <b>" + TelegramMarkdownFormatter.escapeHtml(toolName)
                + "</b> action:\n<pre>" + TelegramMarkdownFormatter.escapeHtml(args) + "</pre>";
    }

    /**
     * Slack mrkdwn prompt body (rendered inside a Block Kit section). The args go in
     * a fenced code block so backticks/asterisks in them don't format, and are
     * length-capped like {@link #buildPrompt}.
     */
    private static String buildSlackPrompt(String toolName, String argsJson) {
        var args = argsJson == null ? "" : argsJson;
        if (args.length() > 600) {
            args = args.substring(0, 600) + "… (truncated)";
        }
        return "The agent wants to run the *" + toolName + "* action:\n```" + args + "```";
    }

    private static String grantKey(Agent agent, String toolName) {
        return agent.id + ":" + toolName;
    }

    private static Duration timeout() {
        return Duration.ofSeconds(
                ConfigService.getInt("telegram.approval.timeout-seconds", DEFAULT_TIMEOUT_SECONDS));
    }

    /** Visible for testing: drop every standing grant. */
    public static void clearGrantsForTest() {
        GRANTS.clear();
    }
}
