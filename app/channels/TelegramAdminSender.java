package channels;

import org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage;
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll;
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction;
import org.telegram.telegrambots.meta.api.methods.send.SendChatAction;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.polls.input.InputPollOption;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionType;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;

/**
 * Bot-management and non-text send primitives for a single bound Telegram bot
 * token (JCLAW-724, extracted from {@code TelegramSender}). Groups the
 * fire-and-log operations that manage the bot rather than deliver prose:
 * slash-command registration ({@link #setMyCommands}), the reaction / pin /
 * unpin / delete message-admin actions, webhook lifecycle
 * ({@link #setWebhook}/{@link #deleteWebhook}), the topic-aware typing indicator
 * ({@link #sendTypingAction}), and native polls ({@link #sendPoll}). Every method
 * follows the same swallow-and-log contract — a failure is logged and mapped to
 * {@code false}/an outcome, never thrown — so an admin op can't abort the
 * caller's flow. All ride the fast text {@link TelegramSendContext#client}.
 */
final class TelegramAdminSender {

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    private final TelegramSendContext ctx;

    TelegramAdminSender(TelegramSendContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Register the bot's slash-command set with Telegram (JCLAW-99) so
     * clients show a native autocomplete dropdown when the user types
     * {@code /} in the compose field. Idempotent — safe to call on every
     * application startup; Telegram overwrites the existing list without
     * error.
     *
     * <p>Exceptions are swallowed and logged: a failed registration must
     * not abort the caller's binding-activation loop.
     */
    void setMyCommands(List<BotCommand> commands) {
        if (ctx.botToken() == null || commands == null || commands.isEmpty()) return;
        try {
            ctx.client().execute(
                    SetMyCommands.builder()
                            .commands(commands)
                            .build());
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "setMyCommands failed: %s".formatted(e.getMessage()));
        }
    }

    // ── JCLAW-364: dormant send primitives (consumed by JCLAW-374/375) ──
    //
    // Added but intentionally not wired into any dispatch path yet. They follow
    // the static-helper + execute + warn-on-fail shape of setMyCommands so the
    // next wave can call them directly.

    /**
     * JCLAW-364: set (or clear) the bot's reaction on a message. A non-blank
     * {@code emoji} sets a single {@link ReactionTypeEmoji} reaction; a
     * {@code null}/blank emoji sends an empty reaction list, which clears any
     * reaction the bot previously placed. Returns false (logged) on any API
     * failure — never throws. Dormant: no caller yet (JCLAW-374).
     */
    boolean setMessageReaction(String chatId, Integer messageId, String emoji) {
        if (ctx.botToken() == null || chatId == null || messageId == null) return false;
        var builder = SetMessageReaction.builder()
                .chatId(chatId)
                .messageId(messageId);
        if (emoji != null && !emoji.isBlank()) {
            builder.reactionTypes(List.<ReactionType>of(
                    ReactionTypeEmoji.builder().emoji(emoji).build()));
        } else {
            // Empty list clears the bot's reaction.
            builder.reactionTypes(List.of());
        }
        try {
            ctx.client().execute(builder.build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "setMessageReaction failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-364: pin {@code messageId} in {@code chatId}. Returns false (logged)
     * on any API failure — never throws. Dormant: no caller yet (JCLAW-375).
     */
    boolean pinChatMessage(String chatId, Integer messageId) {
        if (ctx.botToken() == null || chatId == null || messageId == null) return false;
        try {
            ctx.client().execute(PinChatMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "pinChatMessage failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-364: unpin {@code messageId} in {@code chatId}. Returns false
     * (logged) on any API failure — never throws. Dormant: no caller yet
     * (JCLAW-375).
     */
    boolean unpinChatMessage(String chatId, Integer messageId) {
        if (ctx.botToken() == null || chatId == null || messageId == null) return false;
        try {
            ctx.client().execute(UnpinChatMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "unpinChatMessage failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-374: delete {@code messageId} from {@code chatId}. Returns false
     * (logged) on any API failure — never throws.
     */
    boolean deleteMessage(String chatId, Integer messageId) {
        if (ctx.botToken() == null || chatId == null || messageId == null) return false;
        try {
            ctx.client().execute(DeleteMessage.builder()
                    .chatId(chatId)
                    .messageId(messageId)
                    .build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "deleteMessage failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-387 (C1): send a native Telegram poll to {@code chatId}. {@code question}
     * (1-300 chars) and {@code options} (2-12 entries, each 1-100 chars) are
     * required by the Bot API; the caller is expected to have validated counts
     * before calling. Optional knobs ({@code null} to leave the Bot API default):
     *
     * <ul>
     *   <li>{@code isAnonymous} — false makes voters visible; Telegram defaults
     *       to true (anonymous);</li>
     *   <li>{@code allowsMultipleAnswers} — true lets a voter pick several
     *       options; defaults to false;</li>
     *   <li>{@code openPeriod} — seconds (5-600) the poll stays open before it
     *       auto-closes; omitted leaves the poll open indefinitely.</li>
     * </ul>
     *
     * <p>Mirrors the swallow-and-log contract of the other send primitives
     * ({@link #setMessageReaction}, {@link #pinChatMessage}): returns false
     * (logged at warn) on any API failure or out-of-range option count — never
     * throws — so a poll that Telegram rejects can't abort the agent's turn.
     */
    boolean sendPoll(String chatId, String question,
                     List<String> options, Boolean isAnonymous,
                     Boolean allowsMultipleAnswers, Integer openPeriod) {
        if (ctx.botToken() == null || chatId == null || question == null || question.isBlank()
                || options == null || options.size() < 2 || options.size() > 12) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendPoll requires a non-blank question and 2-12 options; got %d option(s)"
                            .formatted(options == null ? 0 : options.size()));
            return false;
        }
        var pollOptions = new ArrayList<InputPollOption>(options.size());
        for (var opt : options) {
            pollOptions.add(InputPollOption.builder().text(opt).build());
        }
        var builder = SendPoll.builder()
                .chatId(chatId)
                .question(question)
                .options(pollOptions);
        if (isAnonymous != null) builder.isAnonymous(isAnonymous);
        if (allowsMultipleAnswers != null) builder.allowMultipleAnswers(allowsMultipleAnswers);
        if (openPeriod != null) builder.openPeriod(openPeriod);
        try {
            var sent = ctx.client().execute(builder.build());
            if (sent != null) ctx.recordSent(chatId, sent.getMessageId()); // JCLAW-383
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Poll sent to chat %s: %d options".formatted(chatId, pollOptions.size()));
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendPoll failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-369: topic-aware typing action. When {@code messageThreadId} is
     * set the indicator is scoped to that forum topic — General (thread id 1)
     * INCLUDED, unlike sends, because the chat-action API accepts the General
     * thread id. Null preserves the non-topic behavior. Existing callers route
     * through {@link TelegramChannel#sendTypingAction(String, String)} (thread id null).
     */
    TelegramChannel.TypingActionOutcome sendTypingAction(String chatId, Integer messageThreadId) {
        if (ctx.botToken() == null || chatId == null) return TelegramChannel.TypingActionOutcome.SKIPPED;
        try {
            var builder = SendChatAction.builder()
                    .chatId(chatId)
                    .action("typing");
            if (messageThreadId != null) builder.messageThreadId(messageThreadId);
            ctx.client().execute(builder.build());
            return TelegramChannel.TypingActionOutcome.SENT;
        } catch (Exception e) {
            // JCLAW-342: distinguish a 401 (revoked/invalid token — the caller
            // should stop re-firing) from a transient failure (network blip,
            // chat deleted — safe to keep trying). Still never throws.
            boolean unauthorized = e instanceof TelegramApiRequestException tare
                    && tare.getErrorCode() != null && tare.getErrorCode() == 401;
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendChatAction(typing) failed for chat %s: %s"
                            .formatted(chatId, e.getMessage()));
            return unauthorized ? TelegramChannel.TypingActionOutcome.UNAUTHORIZED : TelegramChannel.TypingActionOutcome.FAILED;
        }
    }

    /**
     * JCLAW-339: register {@code url} as the bot's webhook, passing
     * {@code secretToken} (null skips it) so Telegram echoes it back in the
     * {@code X-Telegram-Bot-Api-Secret-Token} header. Telegram stops its own
     * long polling for the bot once a webhook is set. Returns false on any API
     * error (logged).
     */
    boolean setWebhook(String url, String secretToken) {
        if (ctx.botToken() == null || url == null) return false;
        var builder = SetWebhook.builder().url(url);
        if (secretToken != null) builder.secretToken(secretToken);
        try {
            ctx.client().execute(builder.build());
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Webhook registered: %s".formatted(url));
            return true;
        } catch (TelegramApiException e) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Webhook registration failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-339: clear any webhook registered for {@code botToken} so Telegram
     * stops POSTing and long-poll {@code getUpdates} is allowed again (Telegram
     * 409s while a webhook is set). Idempotent — a no-op when none is
     * registered. Returns false on API error (logged).
     */
    boolean deleteWebhook() {
        if (ctx.botToken() == null) return false;
        try {
            ctx.client().execute(DeleteWebhook.builder().build());
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME, "Webhook deleted");
            return true;
        } catch (TelegramApiException e) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Webhook deletion failed: %s".formatted(e.getMessage()));
            return false;
        }
    }
}
