package agents;

import channels.SlackApprovalService;
import channels.SlackMarkdownFormatter;
import channels.TelegramApprovalService;
import channels.TelegramMarkdownFormatter;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.SlackBinding;
import models.TelegramBinding;
import models.ToolApprovalGrant;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
import utils.ChannelOriginTrust;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
 *       (e.g. {@code exec}); AND</li>
 *   <li>the running agent (or an ancestor, for sub-agents) is bound to a
 *       Telegram bot via {@link TelegramBinding#findByAgentOrAncestor(Agent)}; AND</li>
 *   <li>JCLAW-423: the conversation that triggered the action is itself on
 *       Telegram, or on a Slack binding with an owner — the channels with an
 *       approve/deny surface.</li>
 * </ol>
 *
 * <p>When both hold, the gate raises an interactive approve/deny prompt in
 * the bound user's private chat and blocks the calling (virtual) thread on
 * {@link TelegramApprovalService#await} until the user taps a button or the
 * request times out. {@code APPROVED_*} proceeds; {@code DENIED} /
 * {@code TIMED_OUT} / {@code EXPIRED} aborts.
 *
 * <p>JCLAW-423: a dangerous tool on any other channel (web, WhatsApp) has no
 * interactive approval surface, so the gate applies the configured off-channel
 * policy ({@value #CFG_OFF_CHANNEL_POLICY}, default {@code allow}) instead of
 * routing a prompt to a Telegram chat the operator may not be watching — which
 * used to leave web-initiated turns blocking on a prompt nobody saw. JCLAW-709
 * adds an opt-in {@code ask} that explicitly routes the confirmation to the
 * agent's bound Telegram DM (fail-closed if there is none). A standing grant
 * still proceeds under any policy, on an operator turn. Non-dangerous tools never
 * reach the gate — it returns {@link Decision#PROCEED} before any I/O.
 *
 * <p>JCLAW-777 / VULN-001: the permissive {@code allow} default applies only to a
 * <em>trusted operator origin</em> — the web UI or its voice mode ({@link utils.ChannelOriginTrust}).
 * An <em>untrusted external channel peer</em> (whatsapp, or a telegram/slack turn
 * that fell through with no usable approval binding) has no interactive surface and
 * no authenticated caller, so it fails <em>closed</em> at the off-channel fallback
 * rather than running ungated — an external peer could otherwise prompt-inject the
 * agent into unsandboxed shell. An explicit {@code ask} still lets the operator
 * confirm such a turn on the bound DM.
 *
 * <p>JCLAW-1021: a dispatch with no conversation is <em>not</em> thereby the operator.
 * A task fire drives the tool loop on a stub, unpersisted Conversation, so its origin
 * comes from {@link #withFireOrigin} — the channel recorded on the Task when it was
 * created. With nothing bound the origin stays unknown and fails closed. That origin is
 * a <em>floor</em> for everything the fire reaches, including a dispatch that does carry
 * a conversation, so a spawned child cannot borrow the operator's web trust one hop out.
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
 * even after the in-memory set has been emptied (a fresh JVM). Because it is
 * an OR over a cache and a table, deleting the row alone can only fail open:
 * every revoke path must also call {@link #revokeGrant} (JCLAW-1226).
 *
 * <p>JCLAW-1226: a grant is spendable only by the operator — an owner-initiated
 * turn, or one whose effective origin classifies as {@code OPERATOR}. A guest on
 * the same agent and tool still takes the origin-trust branch, so an "Always" tap
 * given in the owner's DM cannot be borrowed by a group peer.
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

    /** Settings &gt; Tool Approvals. Named for Telegram, but bounds the Slack prompt too. */
    public static final String APPROVAL_TIMEOUT_KEY = "telegram.approval.timeout-seconds";

    /** Default wait for a button tap before the prompt times out (seconds). */
    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    /**
     * JCLAW-423: policy for a dangerous tool dispatched on a channel with no
     * interactive approval surface (not Telegram/Slack). {@code allow}
     * (default) runs it ungated on a <em>trusted operator origin</em> (the web UI or voice),
     * preserving the pre-423 behavior; {@code deny} fails closed;
     * JCLAW-709 {@code ask} routes the confirmation to the agent's bound Telegram
     * DM (fail-closed if there is none). An explicit standing grant still proceeds
     * under any policy. JCLAW-777: {@code allow} does <em>not</em> apply to an
     * untrusted external channel peer (see {@link utils.ChannelOriginTrust}) — such
     * an origin fails closed unless {@code ask} routes it to the operator's DM.
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

    /**
     * The task fire running on this thread, if any (JCLAW-1021). Inheritable because every
     * fork on the fire path ({@link ParallelToolExecutor}, the subagent runners) starts a
     * fresh virtual thread from the loop's thread.
     */
    private static final InheritableThreadLocal<FireScope> FIRE = new InheritableThreadLocal<>();

    /** A fire in progress. Bound-with-null-channel is a fire whose Task recorded no origin —
     *  which the unbound (no fire at all) state must not be confused with. */
    private record FireScope(@Nullable String channel) {}

    /**
     * True while this thread is serving a turn the channel's own access policy proved came
     * from the binding owner (JCLAW-1061). Inheritable for the same reason as {@link #FIRE},
     * and that inheritance is also what keeps it a floor: only an inbound dispatch ever sets
     * it, so a subagent forked from a guest's turn inherits {@code false} and has no way to
     * reach {@code true}.
     */
    private static final InheritableThreadLocal<Boolean> OWNER_INITIATED = new InheritableThreadLocal<>();

    /**
     * The channel's own id of the member whose message this turn answers (JF-237), bound beside
     * {@link #OWNER_INITIATED}. Inheritable for the same reason; the drain thread inherits none and rebinds it.
     */
    private static final InheritableThreadLocal<String> SENDER_ID = new InheritableThreadLocal<>();

    /** The gate's verdict for a single dispatch. */
    public enum Decision { PROCEED, ABORT }

    /** A verdict, with what a prompt could not show whole when that, and not a denial, is why the call is refused. */
    private record Ruling(Decision decision, @Nullable Unshown unshown) {
        static final Ruling PROCEED = new Ruling(Decision.PROCEED, null);
        static final Ruling ABORT = new Ruling(Decision.ABORT, null);
    }

    /**
     * Run {@code body} as a turn whose sender is (or is not) the binding owner.
     *
     * <p>Telegram and Slack already establish this at the door —
     * {@code TelegramAccessPolicy} serves a DM only to the owner, {@code SlackAccessPolicy}
     * only to the owner once one is configured — and then drop it, leaving the gate to
     * re-ask the operator to confirm an identity that was already proven. Binding it here
     * carries that answer to the gate rather than deriving a new one.
     *
     * <p>Unbound means <em>not</em> the owner: a channel that cannot identify its sender
     * (WhatsApp has no owner concept yet) keeps prompting, which is the safe polarity.
     */
    public static <T> T withOwnerInitiated(boolean ownerInitiated, Supplier<T> body) {
        var previous = OWNER_INITIATED.get();
        OWNER_INITIATED.set(ownerInitiated);
        try {
            return body.get();
        } finally {
            if (previous == null) {
                OWNER_INITIATED.remove();
            } else {
                OWNER_INITIATED.set(previous);
            }
        }
    }

    /**
     * Run {@code body} as the turn of the member {@code senderId}, the id the channel gives them (Telegram
     * {@code fromId}, Slack user id, WhatsApp {@code from}). The queue records it on each message that waits, so a
     * member's {@code /queue} and {@code /stop} act on their own messages only; null binds none.
     */
    public static <T> T withSender(@Nullable String senderId, Supplier<T> body) {
        var previous = SENDER_ID.get();
        if (senderId == null) {
            SENDER_ID.remove();
        } else {
            SENDER_ID.set(senderId);
        }
        try {
            return body.get();
        } finally {
            if (previous == null) {
                SENDER_ID.remove();
            } else {
                SENDER_ID.set(previous);
            }
        }
    }

    /** The member this turn answers, or null when none is bound (Chat, a task, a restored message). */
    public static @Nullable String senderId() {
        return SENDER_ID.get();
    }

    /** Whether this turn was proven to come from the binding owner. Public because the slash
     *  dispatcher gates its owner-only commands on the same answer (JCLAW-1228). */
    public static boolean ownerInitiated() {
        return Boolean.TRUE.equals(OWNER_INITIATED.get());
    }

    /** Whether the current turn on {@code conversationId} speaks for the operator: owner-initiated or of operator origin. */
    public static boolean operatorTurn(@Nullable Long conversationId) {
        return operatorTurn(ownerInitiated(), effectiveOrigin(conversationId));
    }

    /**
     * Whether only the owner reads what this turn sends to the conversation: an operator turn (the
     * channel's owner, web chat or voice) in a chat that is not a group, where the owner asks but every
     * member reads. The spawn-time and send-time checks both read this one (JCLAW-1604, JCLAW-1603), and
     * so does the offer of {@code jclaw_api} (JF-148).
     */
    public static boolean ownerReadsAlone(Long conversationId, String channelType, @Nullable String peerId) {
        return operatorTurn(conversationId)
                && (peerId == null || !Boolean.TRUE.equals(SystemPromptAssembler.isGroupChat(channelType, peerId)));
    }

    /** {@link #ownerReadsAlone(Long, String, String)} read off the stored conversation; false when it is gone. */
    public static boolean ownerReadsAlone(Long conversationId) {
        var chat = Tx.run(() -> {
            Conversation c = Conversation.findById(conversationId);
            return c == null ? null : new Chat(c.channelType, c.peerId);
        });
        return chat != null && ownerReadsAlone(conversationId, chat.channelType(), chat.peerId());
    }

    private record Chat(String channelType, @Nullable String peerId) {}

    private static boolean operatorTurn(boolean ownerInitiated, @Nullable String origin) {
        return ownerInitiated || ChannelOriginTrust.classify(origin) == ChannelOriginTrust.Trust.OPERATOR;
    }

    /**
     * Run {@code body} inside the dynamic extent of a task fire whose Task recorded
     * {@code origin} — the task-fire path, whose Conversation is a stub that was never
     * persisted. The origin bounds the trust of every dispatch in {@code body}
     * ({@link #effectiveOrigin}); a null {@code origin} classifies as {@code UNKNOWN} and
     * the off-channel fallback fails closed on it.
     */
    public static <T> T withFireOrigin(@Nullable String origin, Supplier<T> body) {
        var previous = FIRE.get();
        FIRE.set(new FireScope(origin));
        try {
            return body.get();
        } finally {
            if (previous == null) {
                FIRE.remove();
            } else {
                FIRE.set(previous);
            }
        }
    }

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
    public static Decision guard(Agent agent, @Nullable Long conversationId,
                                 @Nullable String toolName, String argsJson) {
        if (agent == null || !ToolRegistry.isDangerous(toolName, argsJson, agent)) {
            return Decision.PROCEED;
        }
        return arbitrate(agent, conversationId, toolName, argsJson).decision();
    }

    /**
     * {@link #guard} as the tool dispatcher asks it: {@code null} lets the call run, and anything else is the tool
     * result the model reads in its place. A denial reads as {@link #abortResult}; a call that was never offered
     * because a prompt could not show it whole (JF-294) names what is too long, its length and the limit, so the
     * model can shorten or split the call.
     *
     * @param displayName the name the result calls the tool by
     */
    public static @Nullable String refusal(Agent agent, @Nullable Long conversationId, @Nullable String toolName,
                                           String argsJson, @Nullable String displayName) {
        if (agent == null || !ToolRegistry.isDangerous(toolName, argsJson, agent)) {
            return null;
        }
        var ruling = arbitrate(agent, conversationId, toolName, argsJson);
        if (ruling.decision() == Decision.PROCEED) {
            return null;
        }
        var unshown = ruling.unshown();
        return unshown == null ? abortResult(displayName) : unshown.told(displayName);
    }

    /**
     * JCLAW-665: gate a permission request raised by an external coding harness
     * (the {@code runtime="acp"} runtime in its bidirectional {@code rpc} mode).
     * Unlike {@link #guard}, there is no {@link ToolRegistry#isDangerous}
     * pre-filter — the harness itself has already classified the action as needing
     * approval, so its request <em>is</em> the trigger. Everything downstream
     * (standing grants, Telegram/Slack routing, the off-channel policy) is shared
     * with {@link #guard}. Returns {@link Decision#PROCEED} to approve the action
     * or {@link Decision#ABORT} to deny it; the caller relays that decision back to
     * the harness so a denial cleanly aborts just that action.
     *
     * @param agent          the agent running the harness (sub-agents resolve their
     *                        binding via the parent chain)
     * @param conversationId the operator-facing conversation whose channel decides
     *                        whether an interactive prompt can reach the operator
     * @param toolName        the action the harness wants to run
     * @param argsJson        the harness's request payload, surfaced in the prompt
     */
    public static Decision guardHarnessPermission(Agent agent, @Nullable Long conversationId,
                                                  String toolName, @Nullable String argsJson) {
        if (agent == null) {
            return Decision.PROCEED;
        }
        return arbitrate(agent, conversationId, toolName, argsJson).decision();
    }

    /**
     * Shared arbitration: honor a standing grant, else route an interactive
     * approve/deny prompt to the conversation's channel when it has an approval
     * surface (Telegram/Slack) with a usable binding, else apply the off-channel
     * policy. Callers decide <em>whether</em> an action reaches this point;
     * arbitration decides how it is resolved.
     */
    private static Ruling arbitrate(Agent agent, @Nullable Long conversationId,
                                    @Nullable String toolName, @Nullable String argsJson) {
        // JCLAW-423/350: the interactive approve/deny prompt reaches the operator only
        // when THIS turn's effective origin is a channel that has an approval surface
        // (Telegram or Slack) AND has a usable binding. Route the prompt only there;
        // every other channel (web, or no origin at all) has no surface and must NOT
        // silently route to a bound chat — it falls through to the off-channel policy.
        var channelType = effectiveOrigin(conversationId);
        var ownerInitiated = ownerInitiated();

        // JCLAW-1226: a standing grant is the operator's own approval, so only the operator
        // may spend it. Honoring it before the origin was resolved meant an "Always" tap in
        // the owner's DM also covered a group guest's turn on that same (agent, tool).
        if (operatorTurn(ownerInitiated, channelType) && hasStandingGrant(agent, toolName)) {
            EventLogger.info(LOG_CATEGORY, agent.name, originLabel(channelType),
                    "Dangerous tool %s pre-approved for this agent; skipping prompt".formatted(subject(toolName, argsJson)));
            return Ruling.PROCEED;
        }

        // JCLAW-1061: the prompt exists to ask "is this really you?". When the channel's own
        // access policy already answered that at the door, asking again is noise — so an
        // owner-initiated turn skips the prompt and resolves as the operator surface, which
        // means an operator who set the policy to ask/deny still gets that. A guest on the
        // very same binding carries false and takes the branch below.
        if (ownerInitiated) {
            return offChannelDecision(agent, toolName, argsJson, channelType,
                    ChannelOriginTrust.Trust.OPERATOR);
        }

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
                var channelIdOpt = resolvePeerId(conversationId);
                if (channelIdOpt.isPresent()) {
                    return promptAndAwaitSlack(agent, toolName, argsJson, binding, channelIdOpt.get());
                }
            }
        }

        return offChannelDecision(agent, toolName, argsJson, channelType,
                ChannelOriginTrust.classify(channelType));
    }

    /**
     * Off-channel fallback: there is no interactive approval surface on THIS
     * dispatch's own channel, so resolve the dispatch by origin trust.
     *
     * <p>JCLAW-777 / VULN-001: an <em>untrusted external origin</em> — any named
     * inbound channel peer ({@code whatsapp}, or a {@code telegram}/{@code slack} turn
     * that fell through here with no usable approval binding) — reached this point
     * with no interactive surface AND no authenticated caller. It must never run a
     * dangerous tool ungated, so its floor is fail-closed ({@link Decision#ABORT});
     * the operator can still opt into {@code ask} to route a confirmation to the
     * bound Telegram DM. A standing grant does <em>not</em> reach here on an untrusted
     * origin — JCLAW-1226 makes it spendable by the operator only, so an untrusted turn
     * on a granted pair lands on this floor. JCLAW-1021 puts an <em>unknown</em> origin
     * on that same floor: no
     * recorded provenance is a gap, not operator authority. The permissive
     * {@code allow} default applies only to the <em>trusted operator origin</em> (the
     * web UI or voice — see {@link ChannelOriginTrust#classify}), where it preserves the
     * pre-JCLAW-423 behavior. {@code deny} fails closed on any origin; JCLAW-709
     * {@code ask} routes a confirmation to the agent's bound Telegram DM (fail-closed
     * if there is none).
     */
    private static Ruling offChannelDecision(Agent agent, @Nullable String toolName, @Nullable String argsJson,
                                             @Nullable String channelType,
                                             ChannelOriginTrust.Trust trust) {
        var chan = originLabel(channelType);
        var policy = ConfigService.get(CFG_OFF_CHANNEL_POLICY, DEFAULT_OFF_CHANNEL_POLICY);

        // Only a trusted operator origin gets the permissive "allow" default. An external
        // peer — and an origin nobody recorded, which is missing provenance rather than
        // operator authority — may still reach the DM via "ask"; else it is fail-closed.
        if (trust != ChannelOriginTrust.Trust.OPERATOR) {
            if ("ask".equalsIgnoreCase(policy)) {
                return askViaTelegram(agent, toolName, argsJson, chan);
            }
            EventLogger.warn(LOG_CATEGORY, agent.name, chan,
                    "Dangerous tool %s from %s origin '%s' has no approval surface — denying (fail-closed)"
                            .formatted(subject(toolName, argsJson), trust, chan));
            return Ruling.ABORT;
        }

        // Trusted operator origin (the web UI or voice): honor the configured policy — the
        // default "allow" preserves the pre-JCLAW-423 behavior.
        if ("deny".equalsIgnoreCase(policy)) {
            EventLogger.warn(LOG_CATEGORY, agent.name, chan,
                    "Dangerous tool %s on trusted origin '%s' has no approval surface — denying (%s=deny)"
                            .formatted(subject(toolName, argsJson), chan, CFG_OFF_CHANNEL_POLICY));
            return Ruling.ABORT;
        }
        if ("ask".equalsIgnoreCase(policy)) {
            return askViaTelegram(agent, toolName, argsJson, chan);
        }
        if (ToolRegistry.neverRunsUnasked(toolName)) {
            EventLogger.warn(LOG_CATEGORY, agent.name, chan,
                    "Dangerous tool %s on trusted origin '%s' never runs unasked and %s=allow asks no one — denying; "
                            .formatted(subject(toolName, argsJson), chan, CFG_OFF_CHANNEL_POLICY)
                            + "set it to ask to confirm in the bound Telegram DM");
            return Ruling.ABORT;
        }
        EventLogger.info(LOG_CATEGORY, agent.name, chan,
                "Dangerous tool %s on trusted origin '%s' — proceeding ungated (%s=allow)"
                        .formatted(subject(toolName, argsJson), chan, CFG_OFF_CHANNEL_POLICY));
        return Ruling.PROCEED;
    }

    /**
     * JCLAW-709: {@code offChannelPolicy=ask} — the hardened-deployment middle
     * ground between {@code allow} and {@code deny}. A dispatch with no approval
     * surface on its own channel is confirmed on the agent's bound Telegram DM
     * instead (in a private chat {@code chat.id == telegramUserId}), so a web-
     * initiated dangerous tool still reaches the operator. With no usable Telegram
     * binding there is nobody who can confirm, so it fails closed (ABORT) rather
     * than run ungated. Reuses the same blocking prompt/await as the Telegram path.
     */
    private static Ruling askViaTelegram(Agent agent, @Nullable String toolName, @Nullable String argsJson,
                                         String chan) {
        var binding = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(agent));
        if (binding != null && binding.enabled) {
            EventLogger.info(LOG_CATEGORY, agent.name, chan,
                    "Dangerous tool %s on off-channel '%s' — confirming on the bound Telegram DM (%s=ask)"
                            .formatted(subject(toolName, argsJson), chan, CFG_OFF_CHANNEL_POLICY));
            return promptAndAwait(agent, toolName, argsJson, binding);
        }
        EventLogger.warn(LOG_CATEGORY, agent.name, chan,
                ("Dangerous tool %s on off-channel '%s' but no Telegram binding to confirm on — denying "
                        + "(%s=ask, fail-closed)").formatted(subject(toolName, argsJson), chan, CFG_OFF_CHANNEL_POLICY));
        return Ruling.ABORT;
    }

    /**
     * True when an in-process session grant or a persisted always-grant
     * (JCLAW-385) covers {@code (agent, toolName)}. The persisted lookup hits
     * the DB, so it runs in its own transaction.
     */
    private static boolean hasStandingGrant(Agent agent, @Nullable String toolName) {
        return GRANTS.contains(grantKey(agent.id, toolName))
                || Tx.run(() -> ToolApprovalGrant.exists(agent.id, toolName));
    }

    /**
     * The origin a turn on this thread is attributed to: the conversation's
     * {@code channelType}, floored by the origin of the task fire it runs inside.
     * {@code null} when neither is available, which classifies as untrusted.
     *
     * <p>The fire's origin is a floor rather than a fallback because a conversation reached
     * from inside a fire says nothing about who started the fire. When a spawned child's was
     * picked by recency — typically the operator's {@code web} one — honoring it outright
     * let an untrusted fire abort a direct {@code exec}, spawn a subagent, and run
     * the same {@code exec} ungated in the child on borrowed operator trust (JCLAW-1021).
     *
     * <p>Public so the task-write path can record the same provenance the gate will later
     * judge a fire of that task by.
     */
    public static @Nullable String effectiveOrigin(@Nullable Long conversationId) {
        return effectiveOrigin(conversationId, () -> conversationChannel(conversationId));
    }

    /** {@link #effectiveOrigin(Long)} for a caller that has read the conversation's {@code channelType} already. */
    public static @Nullable String effectiveOrigin(@Nullable Long conversationId,
                                                   Supplier<@Nullable String> conversationChannel) {
        var fire = FIRE.get();
        if (fire == null) {
            return conversationChannel.get();
        }
        if (!ChannelOriginTrust.isOperatorOrigin(fire.channel())) {
            return fire.channel();
        }
        return conversationId == null ? fire.channel() : conversationChannel.get();
    }

    /** The conversation's {@code channelType}, or {@code null} when it has none / is gone. */
    private static @Nullable String conversationChannel(@Nullable Long conversationId) {
        if (conversationId == null) {
            return null;
        }
        return Tx.run(() -> {
            Conversation c = Conversation.findById(conversationId);
            return c == null ? null : c.channelType;
        });
    }

    /** The conversation's {@code peerId} (the Slack channel to prompt in), or {@link Optional#empty()}. */
    private static Optional<String> resolvePeerId(@Nullable Long conversationId) {
        if (conversationId == null) {
            return Optional.empty();
        }
        return Tx.run(() -> {
            Conversation c = Conversation.findById(conversationId);
            return Optional.ofNullable(c == null ? null : c.peerId);
        });
    }

    private static Ruling promptAndAwait(Agent agent, @Nullable String toolName, @Nullable String argsJson,
                                         TelegramBinding binding) {
        // A bot token that did not decrypt reads as null (JCLAW-1455): nobody can be asked, and the send would throw.
        if (binding.botToken == null) {
            EventLogger.warn(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                    "Dangerous tool %s cannot be confirmed — denying (fail-closed): %s"
                            .formatted(subject(toolName, argsJson), TelegramBinding.unreadableBotToken(binding.agent.name)));
            return Ruling.ABORT;
        }
        // The bound user's private chat: in a Telegram private chat
        // chat.id == user.id, so the binding's telegramUserId is both the
        // chat to prompt and the user authorized to resolve it.
        var chatId = binding.telegramUserId;
        var prompt = Prompt.of(Dialect.TELEGRAM, toolName, argsJson);
        var unshown = prompt.unshown();
        if (unshown != null) {
            return refuseUnshown(agent, toolName, CHANNEL_NAME, unshown);
        }

        EventLogger.info(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                "Dangerous tool %s requires approval; prompting user %s".formatted(subject(toolName, argsJson), chatId));

        var future = TelegramApprovalService.request(
                binding.botToken, chatId, binding.telegramUserId, prompt.parts(), true);
        var outcome = TelegramApprovalService.await(future, timeout());

        return switch (outcome) {
            case APPROVED_ONCE -> Ruling.PROCEED;
            case APPROVED_SESSION -> {
                recordSessionGrant(agent, toolName, CHANNEL_NAME, outcome.name());
                yield Ruling.PROCEED;
            }
            case APPROVED_ALWAYS -> {
                recordAlwaysGrant(agent, toolName, CHANNEL_NAME, chatId, outcome.name());
                yield Ruling.PROCEED;
            }
            case DENIED, TIMED_OUT, EXPIRED -> {
                EventLogger.warn(LOG_CATEGORY, agent.name, CHANNEL_NAME,
                        "Dangerous tool %s not approved (%s) — aborting".formatted(subject(toolName, argsJson), outcome));
                yield Ruling.ABORT;
            }
        };
    }

    /**
     * Slack analog of {@link #promptAndAwait} (JCLAW-350): post an approve/deny
     * Block Kit prompt to the conversation's channel, gated on the binding's owner
     * user id, and block until the owner taps a button (or it times out). Shares the
     * standing-grant recording and {@link #timeout()} with the Telegram path.
     */
    private static Ruling promptAndAwaitSlack(Agent agent, @Nullable String toolName, @Nullable String argsJson,
                                              SlackBinding binding, String channelId) {
        var prompt = Prompt.of(Dialect.SLACK, toolName, argsJson);
        var unshown = prompt.unshown();
        if (unshown != null) {
            return refuseUnshown(agent, toolName, SLACK_CHANNEL, unshown);
        }

        EventLogger.info(LOG_CATEGORY, agent.name, SLACK_CHANNEL,
                "Dangerous tool %s requires approval; prompting owner %s in %s"
                        .formatted(subject(toolName, argsJson), binding.ownerUserId, channelId));

        var future = SlackApprovalService.request(
                binding.botToken, channelId, null, binding.ownerUserId, prompt.parts(), true);
        var outcome = SlackApprovalService.await(future, timeout());

        return switch (outcome) {
            case APPROVED_ONCE -> Ruling.PROCEED;
            case APPROVED_SESSION -> {
                recordSessionGrant(agent, toolName, SLACK_CHANNEL, outcome.name());
                yield Ruling.PROCEED;
            }
            case APPROVED_ALWAYS -> {
                recordAlwaysGrant(agent, toolName, SLACK_CHANNEL, channelId, outcome.name());
                yield Ruling.PROCEED;
            }
            case DENIED, TIMED_OUT, EXPIRED -> {
                EventLogger.warn(LOG_CATEGORY, agent.name, SLACK_CHANNEL,
                        "Dangerous tool %s not approved (%s) — aborting".formatted(subject(toolName, argsJson), outcome));
                yield Ruling.ABORT;
            }
        };
    }

    /** Record an in-process session grant for {@code (agent, toolName)} and log it. */
    private static void recordSessionGrant(Agent agent, @Nullable String toolName, String channelName,
                                           String outcomeName) {
        GRANTS.add(grantKey(agent.id, toolName));
        EventLogger.info(LOG_CATEGORY, agent.name, channelName,
                "Dangerous tool '%s' approved (%s) — future calls won't re-prompt"
                        .formatted(toolName, outcomeName));
    }

    /**
     * Record a session grant AND persist an always-grant (JCLAW-385) so it survives
     * a restart. The upsert is idempotent on the unique {@code (agent, tool)} key.
     */
    private static void recordAlwaysGrant(Agent agent, @Nullable String toolName, String channelName,
                                          @Nullable String grantingPeer, String outcomeName) {
        GRANTS.add(grantKey(agent.id, toolName));
        Tx.run(() -> ToolApprovalGrant.upsert(agent, toolName, channelName, grantingPeer));
        EventLogger.info(LOG_CATEGORY, agent.name, channelName,
                "Dangerous tool '%s' approved (%s) — future calls won't re-prompt (persisted)"
                        .formatted(toolName, outcomeName));
    }

    /**
     * JCLAW-1226: drop the in-process grant for {@code (agentId, toolName)}. Callers that
     * delete the {@link ToolApprovalGrant} row must call this too: {@link #hasStandingGrant}
     * is an OR over this never-pruned set and the live table, so a row delete on its own
     * leaves the grant standing until the JVM restarts.
     */
    public static boolean revokeGrant(Long agentId, @Nullable String toolName) {
        return GRANTS.remove(grantKey(agentId, toolName));
    }

    /** JCLAW-1226: the {@link #revokeGrant} sweep for a whole agent, for the delete cascade. */
    public static void revokeGrantsForAgent(Long agentId) {
        GRANTS.removeIf(key -> key.startsWith(agentId + ":"));
    }

    /**
     * The tool-result text returned to the model when a dispatch is aborted
     * by a denial / timeout. Phrased so the model treats it as a hard stop
     * for this action, not a transient error to retry around.
     */
    public static String abortResult(@Nullable String toolName) {
        return ("The user denied (or did not approve in time) the request to run the '%s' action. "
                + "Do not retry this action. Acknowledge that it was not approved and continue with "
                + "whatever else you can do without it.").formatted(toolName);
    }

    /**
     * The most characters of raw arguments a call is offered for approval with (JF-294). A prompt shows arguments
     * whole or not at all, so a longer call is refused unasked: an approval never covers text the operator was not
     * shown. At this length plain text is four Telegram messages or five Slack ones.
     */
    private static final int ARGS_MAX_CHARS = 12_000;

    /**
     * The most characters the prompt's own lines above the arguments may come to, escaped: the tool's name, the call
     * it makes and its reason. They go in the first message, and a tool or a call named at greater length is refused
     * as arguments over {@link #ARGS_MAX_CHARS} are. It leaves the first Slack section some 950 characters of
     * arguments.
     */
    private static final int PROMPT_LINES_MAX_CHARS = 2_000;

    /** The most characters of a call's reason the Why line shows. The arguments below it hold the reason whole. */
    private static final int WHY_MAX_CHARS = 300;

    /** Whatever would break a line: controls, Unicode's line and paragraph separators, and the spaces around them. */
    private static final Pattern LINE_BREAKS = Pattern.compile("[\\p{Cc}\\p{Zl}\\p{Zp}\\s]+");

    /** What a prompt cannot show whole: a call's arguments, or the prompt's own lines, at {@code length} over {@code limit}. */
    private record Unshown(boolean arguments, int length, int limit) {

        /** The tool result the model reads: what is too long, by how much, and what to do about it. */
        String told(@Nullable String toolName) {
            var refused = "The '%s' action was not run, and the user was not asked to approve it: ".formatted(toolName);
            return refused + (arguments
                    ? String.format(Locale.ROOT, "its arguments are %,d characters long, and an approval prompt shows a "
                            + "call's arguments whole only up to %,d characters. Shorten the arguments, or split the work "
                            + "into several smaller calls, and try again.", length, limit)
                    : String.format(Locale.ROOT, "the lines that name the call in an approval prompt (the tool, its call "
                            + "and the reason) come to %,d characters, and the limit is %,d. Shorten the call or the "
                            + "reason and try again.", length, limit));
        }

        /** The log line: lengths only, never the text that was too long. */
        String logged(@Nullable String toolName) {
            return arguments
                    ? String.format(Locale.ROOT, "Dangerous tool '%s' not offered for approval: its arguments are %,d "
                            + "characters and a prompt shows at most %,d whole — refusing", toolName, length, limit)
                    : String.format(Locale.ROOT, "A dangerous tool was not offered for approval: the prompt's own lines "
                            + "come to %,d characters and the limit is %,d — refusing", length, limit);
        }
    }

    /** Refuses, unasked, a call a prompt cannot show whole. Nothing is sent, so there is nothing to approve. */
    private static Ruling refuseUnshown(Agent agent, @Nullable String toolName, String channelName, Unshown unshown) {
        EventLogger.warn(LOG_CATEGORY, agent.name, channelName, unshown.logged(toolName));
        return new Ruling(Decision.ABORT, unshown);
    }

    /** How one channel writes a prompt: its markup, its escapes and the most characters one message holds. */
    private enum Dialect {
        TELEGRAM(TelegramApprovalService.MESSAGE_MAX_CHARS, "⚠ <b>Approval required</b>\n", "<pre></pre>".length()) {
            @Override String bold(String text) {
                return "<b>" + text + "</b>";
            }

            @Override String escape(String raw) {
                return TelegramMarkdownFormatter.escapeHtml(raw);
            }

            @Override String block(String escaped) {
                return "<pre>" + escaped + "</pre>";
            }

            @Override String why(String oneLine) {
                return escape(oneLine);
            }
        },
        SLACK(SlackApprovalService.SECTION_MAX_CHARS, "🔐 *Approval required*\n\n", "``````".length() + 1) {
            @Override String bold(String text) {
                return "*" + text + "*";
            }

            @Override String escape(String raw) {
                return slackSafe(raw);
            }

            // A block that opened on a backtick would read as a longer fence, so it opens on a zero-width space.
            @Override String block(String escaped) {
                return "```" + (escaped.startsWith("`") ? "\u200b" : "") + escaped + "```";
            }

            // mrkdwn has no escape for its bold, italic and strike marks, so the reason goes in a code span, where
            // Slack draws none of them. A backtick would close the span, so each becomes an apostrophe.
            @Override String why(String oneLine) {
                return "`" + SlackMarkdownFormatter.escape(oneLine).replace('`', '\'') + "`";
            }
        };

        /** The most characters one message holds, markup and escapes counted. */
        final int messageMax;
        /** The prompt's first line and the gap under it. */
        final String banner;
        /** What {@link #block} adds around the escaped text, at most. */
        final int blockRoom;

        Dialect(int messageMax, String banner, int blockRoom) {
            this.messageMax = messageMax;
            this.banner = banner;
            this.blockRoom = blockRoom;
        }

        abstract String bold(String text);

        /** {@code raw} as the channel shows it literally. Each character is escaped on its own, whatever is around it. */
        abstract String escape(String raw);

        /** The block arguments are shown in, where nothing they hold reads as markup. */
        abstract String block(String escaped);

        /** A one-line reason, written so that none of it reads as markup. */
        abstract String why(String oneLine);
    }

    /**
     * An approval prompt as the messages it is sent in (JF-294): one when the arguments fit beside the prompt's own
     * lines, else as many as show them whole, each naming its part and its call, in order. The prompt's own lines
     * stay outside the argument blocks, so arguments cannot imitate them. {@code unshown} is set, and {@code parts}
     * empty, for a call that cannot be shown whole.
     */
    private record Prompt(List<String> parts, @Nullable Unshown unshown) {

        static Prompt of(Dialect dialect, @Nullable String toolName, @Nullable String argsJson) {
            var args = argsJson == null ? "" : argsJson;
            if (args.length() > ARGS_MAX_CHARS) {
                return new Prompt(List.of(), new Unshown(true, args.length(), ARGS_MAX_CHARS));
            }
            var name = dialect.bold(dialect.escape(toolName == null ? "" : toolName));
            var call = ToolRegistry.describeCall(toolName, argsJson);
            var head = dialect.banner + "The agent wants to run the " + name + " action:\n"
                    + (call == null ? "" : dialect.bold("Call:") + " " + dialect.escape(call) + "\n")
                    + whyLine(dialect, argsJson);
            if (head.length() > PROMPT_LINES_MAX_CHARS) {
                return new Prompt(List.of(), new Unshown(false, head.length(), PROMPT_LINES_MAX_CHARS));
            }
            var whole = head + dialect.block(dialect.escape(args));
            if (whole.length() <= dialect.messageMax) {
                return new Prompt(List.of(whole), null);
            }
            var continued = dialect.banner.strip() + ", continued: the " + name + " action\n";
            var tag = callTag(toolName, args);
            // Room is kept for the longest label a part could carry, so the count need not be known to cut.
            int kept = dialect.bold(partLabel(999, 999, tag)).length() + 1 + dialect.blockRoom;
            var pieces = split(args, dialect, dialect.messageMax - head.length() - kept,
                    dialect.messageMax - continued.length() - kept);
            var parts = new ArrayList<String>(pieces.size());
            for (int i = 0; i < pieces.size(); i++) {
                parts.add((i == 0 ? head : continued) + dialect.bold(partLabel(i + 1, pieces.size(), tag)) + "\n"
                        + dialect.block(dialect.escape(pieces.get(i))));
            }
            return new Prompt(List.copyOf(parts), null);
        }

        private static String partLabel(int part, int of, String tag) {
            return "Arguments, part " + part + " of " + of + ", call " + tag + ":";
        }

        /**
         * The tag every part of one prompt carries, the part with the buttons included, so the operator can tell
         * which call a part belongs to when two prompts reach one chat: the first six hex characters of SHA-256
         * over the tool's name, a newline and the raw arguments.
         */
        private static String callTag(@Nullable String toolName, String args) {
            try {
                var call = (toolName == null ? "" : toolName) + "\n" + args;
                var digest = MessageDigest.getInstance("SHA-256").digest(call.getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest, 0, 3);
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 is mandated by the JLS; unreachable on any conforming JVM.
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }

        /**
         * The Why line, or nothing for a call that gives no reason. The model writes the reason and the line sits
         * outside the argument block, so it is made one line and capped, with the cut named in the prompt's own
         * label ahead of the model's text.
         */
        private static String whyLine(Dialect dialect, @Nullable String argsJson) {
            var why = extractWhy(argsJson);
            var oneLine = why == null ? "" : LINE_BREAKS.matcher(why).replaceAll(" ").strip();
            if (oneLine.isEmpty()) {
                return "";
            }
            if (oneLine.length() <= WHY_MAX_CHARS) {
                return dialect.bold("Why:") + " " + dialect.why(oneLine) + "\n";
            }
            int shown = Character.isHighSurrogate(oneLine.charAt(WHY_MAX_CHARS - 1)) ? WHY_MAX_CHARS - 1 : WHY_MAX_CHARS;
            var label = String.format(Locale.ROOT, "Why (first %,d of %,d characters):", shown, oneLine.length());
            return dialect.bold(label) + " " + dialect.why(oneLine.substring(0, shown)) + "\n";
        }

        /**
         * Cuts {@code raw} into the pieces the messages show, in order. A piece is escaped after the cut and fits
         * its message escaped, so no piece ends inside an escape, and a cut never falls inside a surrogate pair.
         */
        private static List<String> split(String raw, Dialect dialect, int firstRoom, int laterRoom) {
            var pieces = new ArrayList<String>();
            int start = 0;
            int used = 0;
            int room = firstRoom;
            for (int i = 0; i < raw.length(); ) {
                int width = Character.charCount(raw.codePointAt(i));
                int cost = dialect.escape(raw.substring(i, i + width)).length();
                if (used + cost > room) {
                    pieces.add(raw.substring(start, i));
                    start = i;
                    used = 0;
                    room = laterRoom;
                }
                used += cost;
                i += width;
            }
            pieces.add(raw.substring(start));
            return pieces;
        }
    }

    /**
     * Slack's control characters, plus a backtick run: model-controlled args inside the
     * fence would otherwise close it and render the rest of the payload as mrkdwn in an
     * approval prompt. A zero-width space between backticks cannot open or close a fence.
     */
    private static String slackSafe(@Nullable String s) {
        return s == null ? "" : SlackMarkdownFormatter.escape(s).replace("`", "`\u200b");
    }

    /**
     * Pull a human-readable {@code why} rationale out of the args JSON, if the tool
     * supplied one (exec, and future consequential tools), for a prominent line in
     * the approval prompt above the raw args. Defensive: returns {@code null} on
     * malformed JSON or a missing/blank field, so the prompt still renders from the
     * raw args alone. Public for direct test coverage.
     */
    public static @Nullable String extractWhy(@Nullable String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return null;
        try {
            var obj = JsonParser.parseString(argsJson).getAsJsonObject();
            if (obj.has("why") && !obj.get("why").isJsonNull()) {
                var why = obj.get("why").getAsString().strip();
                return why.isEmpty() ? null : why;
            }
        } catch (RuntimeException _) {
            // malformed / non-object JSON — fall back to the raw-args-only prompt
        }
        return null;
    }

    /** The turn's origin as a decision line's channel; a prompt's own lines name the channel it went to instead. */
    private static String originLabel(@Nullable String channelType) {
        return channelType == null ? "none" : channelType;
    }

    /** The tool for a log line, with the call it makes when the tool names one ({@code jclaw_api}: method and path). */
    private static String subject(@Nullable String toolName, @Nullable String argsJson) {
        var call = ToolRegistry.describeCall(toolName, argsJson);
        return call == null ? "'" + toolName + "'" : "'%s' (%s)".formatted(toolName, call);
    }

    /** The {@link #GRANTS} key. {@link #revokeGrantsForAgent} prefix-matches on {@code agentId + ":"}. */
    private static String grantKey(Long agentId, @Nullable String toolName) {
        return agentId + ":" + toolName;
    }

    private static Duration timeout() {
        return Duration.ofSeconds(
                ConfigService.getInt(APPROVAL_TIMEOUT_KEY, DEFAULT_TIMEOUT_SECONDS));
    }

    /** Visible for testing: record the in-process grant an "approve for the session" tap leaves, with no row. */
    public static void grantSessionForTest(Long agentId, String toolName) {
        GRANTS.add(grantKey(agentId, toolName));
    }

    /** Visible for testing: drop every standing grant. */
    public static void clearGrantsForTest() {
        GRANTS.clear();
    }
}
