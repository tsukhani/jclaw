package channels;

import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import services.EventLogger;

/**
 * Inline-keyboard send + callback plumbing for a single bound Telegram bot
 * token (JCLAW-109; JCLAW-724, extracted from {@code TelegramSender}). Owns the
 * interactive-message surface: sending a keyboard-bearing message
 * ({@link #sendMessageWithKeyboard}), editing one in place to drill down / return
 * without cluttering the chat ({@link #editMessageText}), and acknowledging a
 * callback tap within Telegram's three-second window
 * ({@link #answerCallbackQuery}). Keyboard messages stay well under the 4096-char
 * limit by construction, so they bypass the chunker/planner and go out as a
 * single Bot-API call on the fast text {@link TelegramSendContext#client}.
 */
final class TelegramKeyboardSender {

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    private final TelegramSendContext ctx;

    TelegramKeyboardSender(TelegramSendContext ctx) {
        this.ctx = ctx;
    }

    /**
     * Send an HTML-formatted message with an inline keyboard attached
     * (JCLAW-109). Returns the new message id on success (so the caller
     * can later {@code editMessageText} it), or null on failure. Single
     * Bot API call — no chunking or planner pass, because keyboard
     * messages stay well under the 4096-char limit by construction.
     */
    Integer sendMessageWithKeyboard(String chatId,
                                    String htmlText, InlineKeyboardMarkup keyboard) {
        return sendMessageWithKeyboard(chatId, htmlText, keyboard, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware keyboard send. {@code replyToMessageId}
     * (null to omit) is applied per the {@link TelegramSendPolicy#replyToMode()} policy treating this
     * single message as the turn's first chunk ({@code off} → never; {@code first}
     * / {@code all} → applied, since there is exactly one message). {@code messageThreadId}
     * (null to omit) is General-stripped before being set. The shorter overload
     * preserves the legacy call sites.
     *
     * <p>JCLAW-141: was a static {@code sendMessageWithKeyboard(botToken, ...)}
     * entry point; now an instance method on the per-binding channel (token bound
     * at construction). The inline-keyboard markup itself is Telegram-specific and
     * out of scope for the generic {@link Channel} contract.
     */
    Integer sendMessageWithKeyboard(String chatId,
                                    String htmlText, InlineKeyboardMarkup keyboard,
                                    Integer replyToMessageId, Integer messageThreadId) {
        var builder = SendMessage.builder()
                .chatId(chatId)
                .text(htmlText)
                .parseMode("HTML")
                .replyMarkup(keyboard);
        var reply = TelegramSendPolicy.replyParamsFor(replyToMessageId, true, TelegramSendPolicy.effectiveReplyToMode(ctx.botToken()));
        if (reply != null) builder.replyParameters(reply);
        var threadId = TelegramSendPolicy.sendThreadId(messageThreadId);
        if (threadId != null) builder.messageThreadId(threadId);
        var linkPreview = TelegramSendPolicy.linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        try {
            var msg = ctx.client().execute(builder.build());
            ctx.recordSent(chatId, msg.getMessageId()); // JCLAW-383
            return msg.getMessageId();
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendMessageWithKeyboard failed: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /**
     * Edit an existing message in-place, optionally attaching a new
     * inline keyboard (or null to clear it). Used by the callback
     * dispatcher to drill down / return without cluttering the chat
     * with a new message per tap.
     */
    boolean editMessageText(String chatId, Integer messageId,
                            String htmlText, InlineKeyboardMarkup keyboard) {
        var builder = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(htmlText)
                .parseMode("HTML");
        if (keyboard != null) builder.replyMarkup(keyboard);
        var linkPreview = TelegramSendPolicy.linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        try {
            ctx.client().execute(builder.build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "editMessageText failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

    /**
     * Acknowledge a callback query. Telegram requires this within three
     * seconds or the user sees a spinner. Use {@code showAlert=true} for
     * validation failures that the user must read (unknown provider,
     * stale conversation); use {@code showAlert=false} and a null/short
     * text for routine taps.
     */
    boolean answerCallbackQuery(String callbackId,
                                String text, boolean showAlert) {
        var builder = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .showAlert(showAlert);
        if (text != null && !text.isEmpty()) builder.text(text);
        try {
            ctx.client().execute(builder.build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "answerCallbackQuery failed: %s".formatted(e.getMessage()));
            return false;
        }
    }
}
