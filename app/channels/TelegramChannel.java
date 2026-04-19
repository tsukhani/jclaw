package channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.ChannelConfig;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import services.EventLogger;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Telegram Bot API adapter backed by the {@code org.telegram:telegrambots-client}
 * SDK. Exposes both a webhook path (via {@link #parseUpdate}) and a long-polling
 * path (via {@link TelegramPollingRunner}); which one runs is decided by the
 * {@link ChannelTransport} field on the stored config.
 */
public class TelegramChannel implements Channel {

    /**
     * Parsed snapshot of the Telegram channel's {@code configJson} blob. {@code transport}
     * defaults to {@link ChannelTransport#POLLING} when the field is absent — this is the
     * zero-config path for laptop development (no public URL needed).
     */
    public record TelegramConfig(String botToken, ChannelTransport transport,
                                 String webhookSecret, String webhookUrl) {
        public static TelegramConfig load() {
            var cc = ChannelConfig.findByType("telegram");
            if (cc == null || !cc.enabled) return null;
            var json = JsonParser.parseString(cc.configJson).getAsJsonObject();
            return new TelegramConfig(
                    json.get("botToken").getAsString(),
                    ChannelTransport.parse(
                            json.has("transport") ? json.get("transport").getAsString() : null,
                            ChannelTransport.POLLING),
                    json.has("webhookSecret") ? json.get("webhookSecret").getAsString() : null,
                    json.has("webhookUrl") ? json.get("webhookUrl").getAsString() : null);
        }
    }

    /** Generic inbound shape consumed by {@link controllers.WebhookTelegramController}. */
    public record InboundMessage(String chatId, String text, String fromId, String fromUsername) {}

    private static final ObjectMapper JACKSON = new ObjectMapper();

    private static final TelegramChannel INSTANCE = new TelegramChannel();
    public static TelegramChannel instance() { return INSTANCE; }

    // Cached SDK client keyed by bot token. OkHttpTelegramClient owns a dispatcher
    // thread pool, so we reuse one per token across the lifetime of that token.
    private record ClientHolder(String botToken, TelegramClient client) {}
    private static final AtomicReference<ClientHolder> ACTIVE = new AtomicReference<>();

    // retry_after delay hint, set by trySend on 429, consumed by Channel.sendWithRetry.
    private static final ThreadLocal<Long> RETRY_HINT_MS = new ThreadLocal<>();

    @Override
    public String channelName() { return "telegram"; }

    @Override
    public long consumeRetryDelayMs() {
        Long v = RETRY_HINT_MS.get();
        RETRY_HINT_MS.remove();
        return v != null ? v : Channel.super.consumeRetryDelayMs();
    }

    // ── Static facades used by webhook controller and agent runner ──

    /** Fire-and-retry outbound message send. Returns true on success. */
    public static boolean sendMessage(String chatId, String text) {
        var config = TelegramConfig.load();
        if (config == null) {
            EventLogger.error("channel", null, "telegram", "Telegram not configured");
            return false;
        }
        // Telegram rejects sendMessage payloads over 4096 characters with HTTP 400.
        // Chunk at 4000 to leave headroom for any parse-mode expansion on the server
        // side. Each chunk is delivered via sendWithRetry so retries apply per-chunk.
        var chunks = chunk(text, 4000);
        boolean allOk = true;
        for (var part : chunks) {
            if (!INSTANCE.sendWithRetry(chatId, part)) allOk = false;
        }
        return allOk;
    }

    /**
     * Split {@code text} into chunks at most {@code maxLen} characters long, biasing
     * breaks toward paragraph → line → word boundaries before a hard cut. Markdown
     * formatting that spans a chunk boundary (e.g. an unclosed code fence) may render
     * awkwardly — acceptable for an MVP chunker; a per-channel formatter (§4.4 of
     * JCLAW-14 report) is the cleaner long-term fix.
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

    /** Register the webhook URL with Telegram. Only meaningful in {@link ChannelTransport#WEBHOOK} mode. */
    public static boolean setWebhook(TelegramConfig config) {
        if (config.webhookUrl() == null) return false;
        var builder = SetWebhook.builder().url(config.webhookUrl());
        if (config.webhookSecret() != null) builder.secretToken(config.webhookSecret());
        try {
            clientFor(config.botToken()).execute(builder.build());
            EventLogger.info("channel", null, "telegram",
                    "Webhook registered: %s".formatted(config.webhookUrl()));
            return true;
        } catch (TelegramApiException e) {
            EventLogger.error("channel", null, "telegram",
                    "Webhook registration failed: %s".formatted(e.getMessage()));
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
        var config = TelegramConfig.load();
        if (config == null) return false;
        var request = SendMessage.builder()
                .chatId(peerId)
                .text(text)
                .parseMode("Markdown")
                .build();
        try {
            clientFor(config.botToken()).execute(request);
            EventLogger.info("channel", null, "telegram",
                    "Message sent to chat %s".formatted(peerId));
            return true;
        } catch (TelegramApiRequestException e) {
            var params = e.getParameters();
            if (params != null && params.getRetryAfter() != null && params.getRetryAfter() > 0) {
                int retryAfter = params.getRetryAfter();
                RETRY_HINT_MS.set(retryAfter * 1000L);
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

    // ── Client caching ──

    /**
     * Cached {@link TelegramClient} for {@code botToken}. When the token changes the
     * prior client is dropped; OkHttp's dispatcher shuts down when garbage-collected.
     */
    public static TelegramClient clientFor(String botToken) {
        var existing = ACTIVE.get();
        if (existing != null && existing.botToken().equals(botToken)) {
            return existing.client();
        }
        var fresh = new OkHttpTelegramClient(botToken);
        ACTIVE.set(new ClientHolder(botToken, fresh));
        return fresh;
    }
}
