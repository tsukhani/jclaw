package channels;

import org.telegram.telegrambots.meta.generics.TelegramClient;

/**
 * Per-token send state shared by the {@link TelegramSender} collaborators
 * (JCLAW-724, extracted from that class's fields). Bundles the bound bot token,
 * the fast text {@link TelegramClient} and the tolerant upload {@link TelegramClient}
 * (both built by {@link TelegramBotApiHttpClients}), and the bot-sent-message-id
 * cache — the exact state the message / media / admin / keyboard senders reach
 * for. Immutable holder: the references are final; the cache itself is the only
 * mutable member and guards its own concurrency (see {@link TelegramSentMessageCache}).
 */
record TelegramSendContext(String botToken,
                           TelegramClient client,
                           TelegramClient uploadClient,
                           TelegramSentMessageCache sentCache) {

    /**
     * JCLAW-383: remember that this bot sent {@code messageId} into {@code chatId}
     * so a later group reaction on it is recognized as a reaction on a bot message
     * ({@code notify=own}). No-op on null/blank chat id or null message id — the
     * guard lives in {@link TelegramSentMessageCache#remember}.
     */
    void recordSent(String chatId, Integer messageId) {
        sentCache.remember(chatId, messageId);
    }
}
