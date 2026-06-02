package channels;

import channels.TelegramChannel.InboundCallback;
import channels.TelegramModelCallback.Payload;
import models.Agent;
import models.Conversation;
import services.Tx;
import slash.Commands;

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

        // JCLAW-387 C2: operator scope gate. When the incoming callback's chat
        // type isn't permitted by telegram.keyboardScope, ack so Telegram
        // dismisses the spinner (still inside the three-second SLA) and stop
        // before any handler runs. This alone neutralizes a disallowed-scope
        // keyboard — taps are inert and the user is told why.
        if (!keyboardScopeAllows(cb.chatType())) {
            TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                    "Interactive buttons are disabled in this chat.", true);
            return;
        }

        // Generic approve/deny workflow (JCLAW-373). Its callback_data uses
        // the disjoint "a:" namespace, so try it before the "m:" model
        // selector. Resolution gates on the bound user id internally.
        var approval = TelegramApprovalCallback.parse(cb.data());
        if (approval.isPresent()) {
            handleApproval(botToken, cb, approval.get());
            return;
        }

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
        var conversation = Tx.run(() -> (Conversation) Conversation.findById(payload.conversationId()));
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
            case PROVIDER_PAGE -> handleProviderPage(botToken, agent, cb, conversation, payload);
            case SELECT -> handleSelect(botToken, agent, cb, conversation, payload);
            case CANCEL -> handleCancel(botToken, cb);
            case DETAILS -> handleDetails(botToken, agent, cb, conversation);
        }
    }

    /**
     * JCLAW-387 C2: is an interactive inline keyboard permitted in a chat of
     * the given Telegram {@code chatType}? Driven by
     * {@code telegram.keyboardScope} (default {@code all}):
     *
     * <ul>
     *   <li>{@code off}   — no chat type is permitted.</li>
     *   <li>{@code dm}    — only {@code private} chats.</li>
     *   <li>{@code group} — only {@code group} / {@code supergroup} chats.</li>
     *   <li>{@code all}   — any chat type (the default; also the value used
     *       for any unrecognized config string, so a typo fails open to the
     *       current behavior rather than silently disabling all keyboards).</li>
     * </ul>
     *
     * <p>For the restrictive scopes ({@code dm}, {@code group}) a null or
     * unknown {@code chatType} can't be confirmed to match, so it's rejected.
     * Under {@code all} the chat type is irrelevant and always permitted.
     *
     * <p>Exposed {@code public} so keyboard <em>send</em> sites
     * (TelegramModelSelector / approval services) can suppress offering a
     * keyboard up front; the dispatcher uses it as the inbound gate.
     */
    public static boolean keyboardScopeAllows(String chatType) {
        String scope = play.Play.configuration
                .getProperty("telegram.keyboardScope", "all")
                .trim().toLowerCase();
        return switch (scope) {
            case "off" -> false;
            case "dm" -> "private".equals(chatType);
            case "group" -> "group".equals(chatType) || "supergroup".equals(chatType);
            // "all" and any unrecognized value fail open to current behavior.
            default -> true;
        };
    }

    /**
     * Resolve a generic approve/deny tap (JCLAW-373) and answer the query.
     * Gating on the bound user happens inside
     * {@link TelegramApprovalService#resolve}; a tap from the wrong user is
     * rejected with an alert and the pending request stays open. On a
     * successful resolution the originating message's keyboard is replaced
     * with a plain confirmation so it can't be tapped twice.
     */
    private static void handleApproval(String botToken, InboundCallback cb,
                                       TelegramApprovalCallback.Payload payload) {
        var resolution = TelegramApprovalService.resolve(
                payload.approvalId(), payload.decision(), cb.fromId());
        if (!resolution.resolved()) {
            TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                    resolution.userMessage(), true);
            return;
        }
        TelegramChannel.answerCallbackQuery(botToken, cb.callbackId(),
                resolution.userMessage(), false);
        TelegramChannel.editMessageText(botToken, cb.chatId(), cb.messageId(),
                resolution.userMessage(), null);
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

    private static void handleProviderPage(String botToken, Agent agent, InboundCallback cb,
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
            int clampedPage = Math.clamp(payload.page(), 0, totalPages - 1);
            int start = clampedPage * TelegramModelKeyboard.MODELS_PER_PAGE + 1;
            int end = Math.min(start + TelegramModelKeyboard.MODELS_PER_PAGE - 1, modelCount);
            header.append("Provider: <b>").append(escape(providerLabel))
                    .append("</b> (").append(start).append('–').append(end)
                    .append(" of ").append(modelCount).append(")\n");
            header.append("Select a model:");
        }
        var keyboard = TelegramModelKeyboard.modelsKeyboard(
                conv.id, payload.providerIdx(), payload.page(),
                services.ModelOverrideResolver.modelId(conv, agent));
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
}
