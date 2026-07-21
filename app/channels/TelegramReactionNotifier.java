package channels;

import agents.AgentRunner;
import models.Agent;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionType;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import play.Play;
import services.EventLogger;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Transport-agnostic Telegram helpers shared by the two inbound dispatch sites —
 * {@link TelegramPollingRunner} (long-polling) and
 * {@link controllers.WebhookTelegramController} (webhook): inbound reaction
 * parsing + notification gating, forwarded-message detection, and polling-error
 * classification.
 *
 * <p>JCLAW-831: extracted from {@code TelegramPollingRunner} so a webhook-only
 * deployment no longer depends on the POLLING class for this logic. Every member
 * is pure/stateless and static — no polling lifecycle state lives here.
 */
public final class TelegramReactionNotifier {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "telegram";

    private TelegramReactionNotifier() {}

    // ===== JCLAW-375: inbound reaction notifications =====

    private static final String CFG_REACTIONS_NOTIFY = "telegram.reactions.notify";
    /** Notify policy values for {@link #CFG_REACTIONS_NOTIFY}. */
    public static final String NOTIFY_OFF = "off";
    public static final String NOTIFY_OWN = "own";
    public static final String NOTIFY_ALL = "all";
    private static final String CHAT_TYPE_PRIVATE = "private";

    /**
     * JCLAW-375: a normalized inbound reaction delta extracted from a
     * {@code message_reaction} {@link Update}. Shared by the polling runner and
     * {@link controllers.WebhookTelegramController} so both surface reactions
     * identically.
     *
     * @param chatId    the chat the reacted message lives in
     * @param chatType  Telegram {@code chat.type} string (nullable → treated as group)
     * @param messageId the reacted message's id
     * @param reactorId the reacting user's id, or null (anonymous/channel actor)
     * @param reactor   the reacting user's display label for the event text
     * @param added     newly-added reaction emoji (new minus old); may be empty
     * @param removed   removed reaction emoji (old minus new); may be empty
     */
    public record ReactionDelta(String chatId, String chatType, Integer messageId,
                                String reactorId, String reactor,
                                List<String> added, List<String> removed) {}

    /**
     * Resolve the configured reaction-notify policy, normalizing unknown/blank
     * values to {@link #NOTIFY_OWN}. Public so default-package tests can assert
     * the config-read contract (matches the {@code *ForTest} convention).
     */
    public static String reactionNotifyMode() {
        var raw = Play.configuration.getProperty(CFG_REACTIONS_NOTIFY, NOTIFY_OWN);
        if (raw == null) return NOTIFY_OWN;
        var v = raw.trim().toLowerCase();
        return switch (v) {
            case NOTIFY_OFF, NOTIFY_OWN, NOTIFY_ALL -> v;
            default -> NOTIFY_OWN;
        };
    }

    /**
     * Parse a {@code message_reaction} {@link Update} into a {@link ReactionDelta},
     * or null when the update carries no reaction (every other update type) or no
     * usable message id. Computes the added/removed emoji sets from the
     * old/new reaction lists; only emoji reactions
     * ({@code ReactionTypeEmoji}) contribute (custom/paid reactions have no emoji
     * string to render). Never throws.
     */
    public static ReactionDelta parseReaction(Update update) {
        if (update == null) return null;
        var mr = update.getMessageReaction();
        if (mr == null || mr.getMessageId() == null || mr.getChat() == null) return null;

        var oldEmoji = emojiSet(mr.getOldReaction());
        var newEmoji = emojiSet(mr.getNewReaction());
        var added = new ArrayList<String>(newEmoji);
        added.removeAll(oldEmoji);
        var removed = new ArrayList<String>(oldEmoji);
        removed.removeAll(newEmoji);
        if (added.isEmpty() && removed.isEmpty()) return null; // no net change to report

        var chat = mr.getChat();
        String chatId = chat.getId() != null ? String.valueOf(chat.getId()) : null;
        String reactorId = null;
        String reactor = null;
        if (mr.getUser() != null) {
            reactorId = mr.getUser().getId() != null ? String.valueOf(mr.getUser().getId()) : null;
            reactor = displayLabel(mr.getUser());
        }
        return new ReactionDelta(chatId, chat.getType(), mr.getMessageId(),
                reactorId, reactor, added, removed);
    }

    private static LinkedHashSet<String> emojiSet(
            List<ReactionType> reactions) {
        var out = new LinkedHashSet<String>();
        if (reactions == null) return out;
        for (var r : reactions) {
            if (r instanceof ReactionTypeEmoji emoji
                    && emoji.getEmoji() != null && !emoji.getEmoji().isBlank()) {
                out.add(emoji.getEmoji());
            }
        }
        return out;
    }

    private static String displayLabel(User u) {
        if (u.getUserName() != null && !u.getUserName().isBlank()) return "@" + u.getUserName();
        if (u.getFirstName() != null && !u.getFirstName().isBlank()) return u.getFirstName();
        return u.getId() != null ? String.valueOf(u.getId()) : "someone";
    }

    // ===== JCLAW-387 B1: forwarded-message detection =====

    /**
     * True when {@code update} carries a forwarded message. Detection reads the
     * RAW SDK {@link Message}
     * because the parsed {@link InboundMessage} does NOT retain
     * the forward fields. Both dispatch sites
     * ({@link controllers.WebhookTelegramController} and the
     * {@link TelegramPollingRunner} poll loop) call
     * this on the same update they pass to
     * {@link TelegramChannel#parseUpdate(Update, String, String)} so a forward
     * routes through {@link TelegramForwardCoalesceBuffer} instead of the
     * text-reassembly / media-group lanes.
     *
     * <p>Bot API 7.0+ uses {@code forward_origin} (a {@code MessageOrigin}
     * variant) as the canonical forward marker; the SDK still populates the
     * legacy {@code forward_date} for backward compatibility. We treat EITHER as
     * a forward so detection holds across both payload shapes. Public so
     * default-package tests can assert the detection contract.
     */
    public static boolean isForward(Update update) {
        if (update == null) return false;
        var msg = update.getMessage();
        if (msg == null) return false;
        return msg.getForwardOrigin() != null || msg.getForwardDate() != null;
    }

    /**
     * JCLAW-375: gate an inbound reaction delta against the notify policy and,
     * when allowed, hand it to the bound agent as a synthetic system message.
     *
     * <p>Policy ({@link #reactionNotifyMode}):
     * <ul>
     *   <li>{@code off} — never notify.</li>
     *   <li>{@code own} (default) — only reactions on messages the bot sent. In a
     *       private (DM) chat the only non-owner messages are the bot's, so an
     *       owner reaction is necessarily on a bot-sent message. In a group the
     *       update doesn't carry the reacted message's author, so JCLAW-383
     *       consults {@link TelegramChannel#wasSentByBot} (the bot-sent-id cache):
     *       a hit notifies; a miss (non-bot message, or a cold cache after a
     *       restart) stays suppressed — conservative under-notify, never
     *       over-notify.</li>
     *   <li>{@code all} — every reaction delta, any chat type.</li>
     * </ul>
     *
     * <p>Shared by the polling runner and {@link controllers.WebhookTelegramController}.
     * The event is delivered through {@link AgentRunner#processInboundForAgent}
     * (non-streaming — a reaction notification is a low-volume system event), and
     * any agent reply is sent back via {@link TelegramChannel#sendMessage}. Runs
     * the agent on a virtual thread so the caller's dispatch loop isn't blocked.
     */
    public static void handleReaction(Agent agent, String botToken, String ownerTelegramUserId,
                                      ReactionDelta reaction) {
        if (agent == null || reaction == null || reaction.chatId() == null) return;
        String mode = reactionNotifyMode();
        // JCLAW-383: under mode=own in a group, the message_reaction update
        // doesn't carry the reacted message's author — so we can't tell from the
        // update alone whether it was a bot message. Consult the bot-sent-id
        // cache: a hit means the reacted message is one we sent, so own should
        // notify; a miss (or cold cache after restart) keeps own group-silent.
        // Only own consults the cache — off/all ignore it, so skip the lookup.
        boolean botSent = NOTIFY_OWN.equals(mode)
                && TelegramChannel.wasSentByBot(botToken, reaction.chatId(), reaction.messageId());
        if (!shouldNotifyReaction(mode, reaction.chatType(), botSent)) return;

        final String eventText = reactionEventText(reaction);
        final String peerId = AgentRunner.telegramConversationPeerId(
                ownerTelegramUserId, reaction.chatType(), reaction.chatId(), null);
        EventLogger.info(LOG_CATEGORY, agent.name, LOG_SOURCE,
                "Reaction notification (mode=%s): %s".formatted(mode, eventText));
        Thread.ofVirtual().name("telegram-reaction").start(() -> {
            try {
                AgentRunner.processInboundForAgent(agent, LOG_SOURCE, peerId, eventText,
                        (pid, response) -> TelegramChannel.forToken(botToken).sendText(
                                reaction.chatId(), response, agent));
            } catch (Exception e) {
                EventLogger.error(LOG_CATEGORY, agent.name, LOG_SOURCE,
                        "Reaction dispatch error: %s".formatted(e.getMessage()));
            }
        });
    }

    /**
     * The pure gate decision for an inbound reaction, split out so it's testable
     * without standing up the agent dispatch path. Two-arg form with no bot-sent
     * signal available (the JCLAW-375 shape): delegates with {@code botSent =
     * false}, so a group reaction stays suppressed under {@code own}. Retained for
     * the config-contract tests and any caller that lacks the cache.
     */
    public static boolean shouldNotifyReaction(String mode, String chatType) {
        return shouldNotifyReaction(mode, chatType, false);
    }

    /**
     * JCLAW-383: the pure gate decision, now able to recognize a group reaction
     * on a bot-sent message via {@code botSentMessage} (resolved from
     * {@link TelegramChannel#wasSentByBot}). {@code off} → never; {@code all} →
     * always; {@code own} → a private (DM) chat (its only non-owner messages are
     * the bot's), OR a group/supergroup reaction on a message the bot actually
     * sent. A group reaction on a non-bot message (or one missing from the cache)
     * stays suppressed under {@code own} — conservative under-notify, never
     * over-notify.
     */
    public static boolean shouldNotifyReaction(String mode, String chatType, boolean botSentMessage) {
        if (NOTIFY_OFF.equals(mode)) return false;
        if (NOTIFY_ALL.equals(mode)) return true;
        // NOTIFY_OWN (and any normalized-to-own default): a DM is always the
        // bot's conversation; a group reaction qualifies only when it lands on
        // a message the bot sent.
        return CHAT_TYPE_PRIVATE.equals(chatType) || botSentMessage;
    }

    /**
     * Render a {@link ReactionDelta} into the system-event text the agent sees.
     * Public for default-package tests. Examples:
     * {@code "[system] @ada reacted 👍 to message 42."} /
     * {@code "[system] @ada removed reaction 👍 from message 42."}
     */
    public static String reactionEventText(ReactionDelta r) {
        var who = r.reactor() != null ? r.reactor() : "Someone";
        var sb = new StringBuilder("[system] ").append(who).append(' ');
        if (!r.added().isEmpty()) {
            sb.append("reacted ").append(String.join(" ", r.added()))
              .append(" to message ").append(r.messageId());
            if (!r.removed().isEmpty()) {
                sb.append(" (and removed ").append(String.join(" ", r.removed())).append(')');
            }
        } else {
            sb.append("removed reaction ").append(String.join(" ", r.removed()))
              .append(" from message ").append(r.messageId());
        }
        return sb.append('.').toString();
    }

    // ===== JCLAW-387 D2: own the polling-error classification (NOT the backoff) =====
    //
    // Scope note: the pengrad/telegrambots long-polling SDK owns the getUpdates
    // network loop AND its own backoff/retry — JClaw never sees a per-poll network
    // error to retry. We deliberately do NOT add a competing retry loop (that would
    // fight the SDK's own backoff and risk double-polling → HTTP 409). What JClaw
    // CAN own cleanly is the *classification* of the polling-related errors that DO
    // surface on its side — registration (registerInternal) and app.start() — into
    // recoverable vs non-recoverable, plus a log line that names the recovery curve.
    // This is observability over the SDK's curve, not a replacement for it.

    /** Recoverable: a transient condition the SDK's own retry/backoff (or the
     *  cooldown reconcile) will clear without operator action. */
    public static final String ERR_RECOVERABLE = "recoverable";
    /** Non-recoverable: a config/auth condition that won't clear by retrying;
     *  needs an operator (bad token, bot blocked, binding misconfigured). */
    public static final String ERR_NON_RECOVERABLE = "non-recoverable";

    /**
     * Classify a polling-related {@link Throwable} as {@link #ERR_RECOVERABLE} or
     * {@link #ERR_NON_RECOVERABLE}. Non-recoverable means an operator must act —
     * auth/permission failures (HTTP 401/403) and not-found (404) on a bad token;
     * everything else (timeouts, connection resets, 5xx, 429 rate-limit, the
     * 409 stale-poll conflict the cooldown already handles) is treated as
     * recoverable. Conservative by design: when in doubt, recoverable — so we
     * never tell an operator to act on a transient blip.
     *
     * <p>Pure + public so default-package tests can assert the contract without
     * standing up the SDK poll loop.
     */
    public static String classifyPollingError(Throwable t) {
        if (t == null) return ERR_RECOVERABLE;
        Integer code = telegramErrorCode(t);
        if (code != null && (code == 401 || code == 403 || code == 404)) {
            return ERR_NON_RECOVERABLE;
        }
        return ERR_RECOVERABLE;
    }

    /**
     * The Telegram HTTP error code carried by {@code t} (or a cause in its chain),
     * or null when none is present. Walks the cause chain because the SDK may wrap
     * a {@code TelegramApiRequestException} inside a registration failure.
     */
    private static Integer telegramErrorCode(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TelegramApiRequestException req) {
                return req.getErrorCode();
            }
            if (c.getCause() == c) break; // self-referential chain guard
        }
        return null;
    }

    /**
     * One-line description of how a classified polling error recovers, for the
     * log. Recoverable errors recover on the SDK's own backoff curve (network /
     * 5xx / 429) or, for registration, on the next reconcile; non-recoverable
     * errors need operator action. Public for the classification-contract test.
     */
    public static String describePollingErrorCurve(Throwable t) {
        String cls = classifyPollingError(t);
        if (ERR_NON_RECOVERABLE.equals(cls)) {
            return "non-recoverable (auth/config) — will NOT clear on retry; operator action required";
        }
        return "recoverable — SDK owns the getUpdates backoff curve; "
                + "registration retries on the next reconcile";
    }

}
