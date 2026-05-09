package channels;

import channels.TelegramChannel.InboundCallback;
import channels.TelegramModelCallback.Kind;
import channels.TelegramModelCallback.Payload;
import models.Agent;
import models.Conversation;
import services.ConversationService;
import services.EventLogger;
import services.Tx;
import slash.Commands;

import java.util.Optional;

/**
 * Entry point for Telegram {@code callback_query} updates from the
 * {@code /model} inline-keyboard selector (JCLAW-109). Parses the
 * callback data, routes to the right handler, acknowledges the query
 * within Telegram's three-second SLA, and edits the originating
 * message in place with the new keyboard state.
 *
 * <p>Caller (webhook controller or polling runner) is responsible for
 * verifying the {@code fromId} matches the binding's authorized user
 * before invoking this dispatcher — same pattern as inbound message
 * handling.
 */
public final class TelegramCallbackDispatcher {

    private TelegramCallbackDispatcher() {}

    /**
     * Dispatch a callback query. All paths acknowledge the query before
     * returning (Telegram's three-second SLA). Long-running work
     * (override writes) happens AFTER the ack so the user doesn't see
     * a stuck spinner. Failures during edits are logged but don't
     * re-throw — the user already saw their ack by then.
     */
    public static void dispatch(String botToken, Agent agent, InboundCallback cb) {
        if (cb == null) return;
        var parsed = TelegramModelCallback.parse(cb.data());
        if (parsed.isEmpty()) {
            // Unknown or malformed callback — ack so Telegram stops showing
            // the spinner. Use show_alert with a generic message so a stale
            // keyboard from a previous deploy doesn't leave the user stuck.
            TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                    "This button is no longer available. Send /model to refresh.", true);
            return;
        }
        var payload = parsed.get();

        // Conversation look-up + binding-scope check. Prevents a leaked
        // callback_data from one conversation from mutating another.
        var conversation = Tx.run(() -> {
            var c = (Conversation) Conversation.findById(payload.conversationId());
            return c;
        });
        if (conversation == null) {
            TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                    "This conversation no longer exists.", true);
            return;
        }
        if (cb.chatId() != null && !cb.chatId().equals(conversation.peerId)) {
            TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                    "This button belongs to a different chat.", true);
            return;
        }

        switch (payload.kind()) {
            case BROWSE, BACK -> handleBrowse(botToken, agent, cb, conversation);
            case PROVIDER_PAGE -> handleProviderPage(botToken, cb, conversation, payload);
            case SELECT -> handleSelect(botToken, agent, cb, conversation, payload);
            case CANCEL -> handleCancel(botToken, cb);
            case DETAILS -> handleDetails(botToken, agent, cb, conversation);
        }
    }

    // ── Handlers ───────────────────────────────────────────────────────

    /**
     * Render the providers list — the new initial state after JCLAW redesign.
     * Both BROWSE (fresh tap) and BACK (legacy stale-button alias) route here
     * so old chat-history buttons keep working as a bridge to the new flow.
     */
    private static void handleBrowse(String botToken, Agent agent, InboundCallback cb, Conversation conv) {
        TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(), null, false);
        var text = TelegramModelSelector.summaryText(agent, conv);
        var keyboard = TelegramModelKeyboard.providersKeyboard(
                conv.id, TelegramModelSelector.currentProviderName(agent, conv));
        TelegramChannel.editMessageText(botToken, cb.chatId(), cb.messageId(), text, keyboard);
    }

    private static void handleProviderPage(String botToken, InboundCallback cb,
                                            Conversation conv, Payload payload) {
        var providers = TelegramModelSelector.userVisibleProviders();
        if (payload.providerIdx() < 0 || payload.providerIdx() >= providers.size()) {
            TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                    "That provider is no longer configured. Send /model to refresh.", true);
            return;
        }
        TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(), null, false);
        var provider = providers.get(payload.providerIdx());
        var providerLabel = TelegramModelKeyboard.providerLabel(provider.config().name());
        var modelCount = provider.config().models().size();
        var header = new StringBuilder();
        header.append("⚙️ <b>Model Configuration</b>\n\n");
        if (modelCount == 0) {
            header.append("Provider: <b>").append(escape(providerLabel)).append("</b>\n");
            header.append("<i>No models configured for this provider.</i>");
        } else {
            int totalPages = Math.max(1,
                    (modelCount + TelegramModelKeyboard.MODELS_PER_PAGE - 1)
                            / TelegramModelKeyboard.MODELS_PER_PAGE);
            int clampedPage = Math.max(0, Math.min(payload.page(), totalPages - 1));
            int start = clampedPage * TelegramModelKeyboard.MODELS_PER_PAGE + 1;
            int end = Math.min(start + TelegramModelKeyboard.MODELS_PER_PAGE - 1, modelCount);
            header.append("Provider: <b>").append(escape(providerLabel))
                    .append("</b> (").append(start).append('–').append(end)
                    .append(" of ").append(modelCount).append(")\n");
            header.append("Select a model:");
        }
        var keyboard = TelegramModelKeyboard.modelsKeyboard(
                conv.id, payload.providerIdx(), payload.page());
        TelegramChannel.editMessageText(botToken, cb.chatId(), cb.messageId(),
                header.toString(), keyboard);
    }

    /**
     * Cancel — clear the keyboard, replace the body with a one-liner so the
     * bubble doesn't leave a dead provider grid in the user's chat history.
     */
    private static void handleCancel(String botToken, InboundCallback cb) {
        TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(), null, false);
        TelegramChannel.editMessageText(botToken, cb.chatId(), cb.messageId(),
                "× Cancelled. Send <code>/model</code> to reopen.", null);
    }

    private static void handleSelect(String botToken, Agent agent, InboundCallback cb,
                                      Conversation conv, Payload payload) {
        var resolved = TelegramModelSelector.resolveByIndex(payload.providerIdx(), payload.modelIdx());
        if (resolved.isEmpty()) {
            TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                    "That model is no longer in the provider config. Send /model to refresh.", true);
            return;
        }
        var target = resolved.get();
        // Ack immediately — override write is quick but a 500ms DB hiccup
        // shouldn't push us past the three-second SLA.
        TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(), "Switching…", false);

        var confirmation = Tx.run(() ->
                Commands.performModelSwitch(agent, conv,
                        target.providerName() + "/" + target.model().id()));

        // Clear the keyboard on success — the message becomes a plain
        // confirmation bubble. No Back button here; the user can run
        // /model again to iterate.
        TelegramChannel.editMessageText(botToken, cb.chatId(), cb.messageId(),
                toHtmlSafe(confirmation), null);
        TelegramModelSelector.logEvent(agent,
                "/model selector switched conversation " + conv.id
                        + " to " + target.providerName() + "/" + target.model().id());
    }

    private static void handleDetails(String botToken, Agent agent, InboundCallback cb,
                                       Conversation conv) {
        TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(), null, false);
        var details = Commands.buildModelResponse(agent, conv);
        // Offer a Back button so the user can return to the providers grid
        // — uses encodeBrowse since the providers list IS the new "summary"
        // state. Old encodeBack callbacks resolve through the same path
        // thanks to the BROWSE/BACK switch alias above.
        var keyboard = org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup.builder()
                .keyboardRow(new org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardRow(
                        org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton.builder()
                                .text("◀ Back")
                                .callbackData(TelegramModelCallback.encodeBrowse(conv.id))
                                .build()))
                .build();
        TelegramChannel.editMessageText(botToken, cb.chatId(), cb.messageId(),
                toHtmlSafe(details), keyboard);
    }

    // ── Formatting helpers ─────────────────────────────────────────────

    /**
     * Plain-text confirmations from {@link Commands#performModelSwitch}
     * use backticks for code spans — Telegram's HTML parse mode needs
     * those replaced with {@code <code>} tags. Also escape the angle
     * brackets that appear in the shrinkage warning's "N tokens" text.
     */
    private static String toHtmlSafe(String plain) {
        if (plain == null) return "";
        var escaped = escape(plain);
        // Convert `backtick` spans to <code>spans</code>.
        var result = new StringBuilder(escaped.length());
        boolean inCode = false;
        for (int i = 0; i < escaped.length(); i++) {
            char ch = escaped.charAt(i);
            if (ch == '`') {
                result.append(inCode ? "</code>" : "<code>");
                inCode = !inCode;
            } else {
                result.append(ch);
            }
        }
        if (inCode) result.append("</code>");
        return result.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    // Suppress unused-import warnings until tests reach these symbols.
    @SuppressWarnings("unused")
    private static final Class<?> _UNUSED_IMPORT_HOLDER = Optional.class;
    @SuppressWarnings("unused")
    private static final Class<?> _UNUSED_CS_HOLDER = ConversationService.class;
    @SuppressWarnings("unused")
    private static final Class<?> _UNUSED_EL_HOLDER = EventLogger.class;
}
