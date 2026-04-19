package channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import models.TelegramBinding;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import services.EventLogger;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Telegram Bot API adapter backed by the {@code org.telegram:telegrambots-client} SDK.
 * Post-JCLAW-89 each operator-managed bot token has its own {@link TelegramChannel}
 * instance (cached in {@link #INSTANCES}) so send paths carry the correct token
 * without a global "current bot" lookup. Instances are created on demand via
 * {@link #forToken(String)} and evicted via {@link #evictToken(String)} when the
 * polling runner unregisters a binding.
 *
 * <p>{@link models.ChannelType#resolve()} returns {@code null} for Telegram because
 * the outbound path needs a per-binding bot token that the generic Channel contract
 * can't carry. Dispatch flows through {@link #sendMessage(String, String, String)}
 * or {@link #forToken(String)} directly, with the binding looked up from the
 * conversation's (agent, peerId) pair at queue-dispatch time.
 */
public class TelegramChannel implements Channel {

    /** Generic inbound shape consumed by {@link controllers.WebhookTelegramController}. */
    public record InboundMessage(String chatId, String text, String fromId, String fromUsername) {}

    private static final ObjectMapper JACKSON = new ObjectMapper();

    /** Per-token instances. OkHttpTelegramClient owns a dispatcher thread pool, so
     *  we reuse one instance per token across the lifetime of that token. */
    private static final ConcurrentHashMap<String, TelegramChannel> INSTANCES = new ConcurrentHashMap<>();

    private final String botToken;
    private final TelegramClient client;

    /** retry_after delay hint (ms), set by trySend on 429, consumed by Channel.sendWithRetry. */
    private final ThreadLocal<Long> retryHintMs = new ThreadLocal<>();

    private TelegramChannel(String botToken) {
        this.botToken = botToken;
        this.client = new OkHttpTelegramClient(botToken);
    }

    /** Resolve (or lazily create) the singleton for {@code botToken}. */
    public static TelegramChannel forToken(String botToken) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("botToken required");
        }
        return INSTANCES.computeIfAbsent(botToken, TelegramChannel::new);
    }

    /** Drop the cached instance for a token. Call when a binding is deleted or its token rotated. */
    public static void evictToken(String botToken) {
        if (botToken != null) INSTANCES.remove(botToken);
    }

    public String botToken() { return botToken; }
    TelegramClient client() { return client; }

    @Override
    public String channelName() { return "telegram"; }

    @Override
    public long consumeRetryDelayMs() {
        Long v = retryHintMs.get();
        retryHintMs.remove();
        return v != null ? v : Channel.super.consumeRetryDelayMs();
    }

    // ── Outbound sends ──

    /**
     * Fire-and-retry outbound message send for a specific bot token. Chunks at 4000
     * chars to stay under Telegram's 4096 limit and applies sendWithRetry per chunk
     * so retry hints are honored independently.
     */
    public static boolean sendMessage(String botToken, String chatId, String text) {
        if (botToken == null || chatId == null || text == null) {
            EventLogger.error("channel", null, "telegram",
                    "sendMessage called with null argument");
            return false;
        }
        var channel = forToken(botToken);
        var chunks = chunk(text, 4000);
        boolean allOk = true;
        for (var part : chunks) {
            if (!channel.sendWithRetry(chatId, part)) allOk = false;
        }
        return allOk;
    }

    /**
     * Split {@code text} into chunks at most {@code maxLen} characters long, biasing
     * breaks toward paragraph → line → word boundaries before a hard cut. Markdown
     * formatting that spans a chunk boundary may render awkwardly — acceptable for an
     * MVP chunker; a per-channel formatter is the cleaner long-term fix.
     */
    public static java.util.List<String> chunk(String text, int maxLen) {
        if (text == null || text.isEmpty()) return java.util.List.of(text == null ? "" : text);
        if (text.length() <= maxLen) return java.util.List.of(text);
        var out = new java.util.ArrayList<String>();
        int start = 0;
        while (text.length() - start > maxLen) {
            int end = start + maxLen;
            int split = text.lastIndexOf("\n\n", end);
            int skip = 2;
            if (split <= start) { split = text.lastIndexOf('\n', end); skip = 1; }
            if (split <= start) { split = text.lastIndexOf(' ', end); skip = 1; }
            if (split <= start) { split = end; skip = 0; }
            out.add(text.substring(start, split));
            start = split + skip;
        }
        if (start < text.length()) out.add(text.substring(start));
        return out;
    }

    /** Register the webhook URL with Telegram for a specific binding. */
    public static boolean setWebhook(TelegramBinding binding) {
        if (binding == null || binding.webhookUrl == null) return false;
        var builder = SetWebhook.builder().url(binding.webhookUrl);
        if (binding.webhookSecret != null) builder.secretToken(binding.webhookSecret);
        try {
            forToken(binding.botToken).client.execute(builder.build());
            EventLogger.info("channel", null, "telegram",
                    "Webhook registered for binding %d: %s".formatted(binding.id, binding.webhookUrl));
            return true;
        } catch (TelegramApiException e) {
            EventLogger.error("channel", null, "telegram",
                    "Webhook registration failed for binding %d: %s".formatted(binding.id, e.getMessage()));
            return false;
        }
    }

    /** Parse a Gson {@link JsonObject} update (webhook payload) into {@link InboundMessage}. */
    public static InboundMessage parseUpdate(JsonObject update) {
        try {
            Update sdk = JACKSON.readValue(update.toString(), Update.class);
            return parseUpdate(sdk);
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "Update parse error: %s".formatted(e.getMessage()));
            return null;
        }
    }

    /** Parse an SDK {@link Update} (polling runner source) into {@link InboundMessage}. */
    public static InboundMessage parseUpdate(Update update) {
        if (update == null || update.getMessage() == null) return null;
        Message msg = update.getMessage();
        if (!msg.hasText()) return null;
        String chatId = String.valueOf(msg.getChatId());
        String text = msg.getText();
        String fromId = null;
        String fromUsername = null;
        if (msg.getFrom() != null) {
            fromId = String.valueOf(msg.getFrom().getId());
            fromUsername = msg.getFrom().getUserName();
        }
        return new InboundMessage(chatId, text, fromId, fromUsername);
    }

    // ── Per-instance send path ──

    @Override
    public boolean trySend(String peerId, String text) {
        var request = SendMessage.builder()
                .chatId(peerId)
                .text(text)
                .parseMode("Markdown")
                .build();
        try {
            client.execute(request);
            EventLogger.info("channel", null, "telegram",
                    "Message sent to chat %s".formatted(peerId));
            return true;
        } catch (TelegramApiRequestException e) {
            var params = e.getParameters();
            if (params != null && params.getRetryAfter() != null && params.getRetryAfter() > 0) {
                int retryAfter = params.getRetryAfter();
                retryHintMs.set(retryAfter * 1000L);
                EventLogger.warn("channel", null, "telegram",
                        "Rate-limited; retry_after=%ds".formatted(retryAfter));
            } else {
                EventLogger.warn("channel", null, "telegram",
                        "Telegram API error: %s".formatted(e.getMessage()));
            }
            return false;
        } catch (TelegramApiException e) {
            EventLogger.warn("channel", null, "telegram",
                    "Send failed: %s".formatted(e.getMessage()));
            return false;
        }
    }
}
