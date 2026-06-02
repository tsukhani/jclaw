package agents;

import channels.TelegramApprovalService;
import channels.TelegramApprovalService.Outcome;
import channels.TelegramMarkdownFormatter;
import models.Agent;
import models.TelegramBinding;
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
 *       Telegram bot via {@link TelegramBinding#findByAgentOrAncestor}.</li>
 * </ol>
 *
 * <p>When both hold, the gate raises an interactive approve/deny prompt in
 * the bound user's private chat and blocks the calling (virtual) thread on
 * {@link TelegramApprovalService#await} until the user taps a button or the
 * request times out. {@code APPROVED_*} proceeds; {@code DENIED} /
 * {@code TIMED_OUT} / {@code EXPIRED} aborts.
 *
 * <p>Non-Telegram channels and non-dangerous tools never reach the prompt —
 * the gate returns {@link Decision#PROCEED} before any I/O.
 *
 * <h2>Session / always scope</h2>
 * <p>An {@code APPROVED_SESSION} or {@code APPROVED_ALWAYS} tap records a
 * grant keyed by {@code (agentId, toolName)} in {@link #GRANTS}, so the same
 * action isn't re-prompted on its next invocation. Both scopes behave
 * identically <em>in-process</em>: the grant registry is in-memory and dies
 * with the JVM, matching the deliberately ephemeral lifetime documented on
 * {@link TelegramApprovalService}. A durable, restart-surviving
 * {@code APPROVED_ALWAYS} would need a persisted store — left as a follow-up.
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

    /** Default wait for a button tap before the prompt times out (seconds). */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

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
     * Decide whether {@code toolName} may run for {@code agent}.
     *
     * @param agent    the executing agent (sub-agents resolve their binding
     *                  via the parent chain)
     * @param toolName the tool about to dispatch
     * @param argsJson the raw JSON arguments the model sent — surfaced in the
     *                  prompt so the user sees what they're approving
     * @return {@link Decision#PROCEED} to run the tool, {@link Decision#ABORT}
     *         to skip it and return a denial result to the model
     */
    public static Decision guard(Agent agent, String toolName, String argsJson) {
        if (agent == null || !ToolRegistry.isDangerous(toolName)) {
            return Decision.PROCEED;
        }

        // Resolve the Telegram binding (own or inherited from an ancestor).
        // The lookup queries the DB, so run it inside a transaction — the
        // tool-dispatch carrier thread is not otherwise in one.
        var binding = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(agent));
        if (binding == null || !binding.enabled) {
            // Not Telegram-bound (web, Slack, an unbound agent, …): no gate.
            return Decision.PROCEED;
        }

        if (GRANTS.contains(grantKey(agent, toolName))) {
            EventLogger.info(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                    "Dangerous tool '%s' pre-approved for this agent; skipping prompt".formatted(toolName));
            return Decision.PROCEED;
        }

        return promptAndAwait(agent, toolName, argsJson, binding);
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
            case APPROVED_SESSION, APPROVED_ALWAYS -> {
                GRANTS.add(grantKey(agent, toolName));
                EventLogger.info(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                        "Dangerous tool '%s' approved (%s) — future calls won't re-prompt"
                                .formatted(toolName, outcome));
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
