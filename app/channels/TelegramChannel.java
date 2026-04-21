package channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import models.Agent;
import models.TelegramBinding;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import services.EventLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

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

    /**
     * Generic inbound shape consumed by {@link controllers.WebhookTelegramController}
     * and {@link TelegramPollingRunner}. {@code chatType} is the Telegram Bot API
     * chat.type string ({@code "private"} / {@code "group"} / {@code "supergroup"} /
     * {@code "channel"}), used by the streaming sink to pick DRAFT vs EDIT_IN_PLACE
     * transport — JCLAW-103. Nullable for defensive parsing: if an update somehow
     * arrives without chat context, callers fall back to EDIT_IN_PLACE.
     */
    public record InboundMessage(String chatId, String chatType, String text,
                                 String fromId, String fromUsername) {}

    private static final ObjectMapper JACKSON = new ObjectMapper();

    /** Per-token instances. OkHttpTelegramClient owns a dispatcher thread pool, so
     *  we reuse one instance per token across the lifetime of that token. */
    private static final ConcurrentHashMap<String, TelegramChannel> INSTANCES = new ConcurrentHashMap<>();

    private final String botToken;
    private final TelegramClient client;

    /** retry_after delay hint (ms), set by trySend on 429, consumed by Channel.sendWithRetry. */
    private final ThreadLocal<Long> retryHintMs = new ThreadLocal<>();

    /**
     * Bot-API HTTP timeouts (JCLAW-100). OkHttp defaults to 10 s on every
     * timeout, which means a slow or hung Telegram edge can burn ~10 s of
     * critical path (inside TelegramStreamingSink.seal → final edit) before
     * the existing scheduled-retry logic engages. The values below fail
     * fast — 2 s connect, 3 s read, 2 s write — so a transient stall is
     * caught and absorbed by the sink's retry tick rather than stretching
     * the user-visible turn.
     */
    private static final int BOT_API_CONNECT_TIMEOUT_SEC = 2;
    private static final int BOT_API_READ_TIMEOUT_SEC = 3;
    private static final int BOT_API_WRITE_TIMEOUT_SEC = 2;

    private TelegramChannel(String botToken) {
        this(botToken, null);
    }

    /**
     * Test-only constructor (JCLAW-96). Accepts a {@link TelegramUrl} override
     * so the client targets a {@code MockTelegramServer} on 127.0.0.1 instead
     * of {@code api.telegram.org}. Never called from production code —
     * {@link #forToken} uses the single-arg constructor which defaults to
     * the SDK's {@code TelegramUrl.DEFAULT_URL}. Package-private so tests
     * in the {@code channels} package can reach it; other test packages
     * use {@link #installForTest}.
     */
    TelegramChannel(String botToken, org.telegram.telegrambots.meta.TelegramUrl urlOverride) {
        this.botToken = botToken;
        var okHttp = new OkHttpClient.Builder()
                .connectTimeout(BOT_API_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(BOT_API_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(BOT_API_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        this.client = urlOverride != null
                ? new OkHttpTelegramClient(okHttp, botToken, urlOverride)
                : new OkHttpTelegramClient(okHttp, botToken);
    }

    /** Resolve (or lazily create) the singleton for {@code botToken}. */
    public static TelegramChannel forToken(String botToken) {
        if (botToken == null || botToken.isBlank()) {
            throw new IllegalArgumentException("botToken required");
        }
        return INSTANCES.computeIfAbsent(botToken, TelegramChannel::new);
    }

    /**
     * Test-only: pre-install a TelegramChannel pointing at {@code urlOverride}
     * for the given {@code botToken}, bypassing the default api.telegram.org
     * target. Subsequent {@link #forToken} calls will return this instance
     * until {@link #evictToken} / {@link #clearForTest} is called. See
     * JCLAW-96 for the MockTelegramServer integration pattern.
     */
    public static void installForTest(String botToken,
                                       org.telegram.telegrambots.meta.TelegramUrl urlOverride) {
        INSTANCES.put(botToken, new TelegramChannel(botToken, urlOverride));
    }

    /** Test-only: reset the per-token cache so the next {@link #forToken} call
     *  constructs a fresh (production-targeted) instance. */
    public static void clearForTest(String botToken) {
        if (botToken != null) INSTANCES.remove(botToken);
    }

    /** Drop the cached instance for a token. Call when a binding is deleted or its token rotated. */
    public static void evictToken(String botToken) {
        if (botToken != null) INSTANCES.remove(botToken);
    }

    public String botToken() { return botToken; }
    TelegramClient client() { return client; }

    /**
     * Public helper for callers outside the {@code channels} package that
     * need to edit a specific Telegram message by id (e.g. JCLAW-95's
     * streaming-recovery job). Exceptions propagate so callers can decide
     * whether to retry or log-and-continue.
     */
    public static void editMessageText(String botToken, String chatId,
                                       Integer messageId, String text) throws Exception {
        forToken(botToken).client.execute(
                org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText.builder()
                        .chatId(chatId)
                        .messageId(messageId)
                        .text(text)
                        .build());
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
    public static void setMyCommands(String botToken,
            java.util.List<org.telegram.telegrambots.meta.api.objects.commands.BotCommand> commands) {
        if (botToken == null || commands == null || commands.isEmpty()) return;
        try {
            forToken(botToken).client.execute(
                    org.telegram.telegrambots.meta.api.methods.commands.SetMyCommands.builder()
                            .commands(commands)
                            .build());
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "setMyCommands failed: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Fire a "typing" chat-action so the user's Telegram client shows the
     * "• • • typing" indicator (JCLAW-98). The indicator lasts ~5 seconds
     * or until the bot sends a real message — callers that want a
     * continuous indicator must re-invoke this every 4 seconds.
     *
     * <p>Failures are swallowed: a typing-indicator call that can't reach
     * Telegram (network blip, token revoked, chat deleted) must never
     * abort the LLM flow that owns the actual response.
     */
    public static void sendTypingAction(String botToken, String chatId) {
        if (botToken == null || chatId == null) return;
        try {
            forToken(botToken).client.execute(
                    org.telegram.telegrambots.meta.api.methods.send.SendChatAction.builder()
                            .chatId(chatId)
                            .action("typing")
                            .build());
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "sendChatAction(typing) failed for chat %s: %s"
                            .formatted(chatId, e.getMessage()));
        }
    }

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
     * Text-only send path. Agent markdown converts to Telegram-safe HTML via
     * {@link TelegramMarkdownFormatter} and chunks at 4000 chars with HTML-tag-aware
     * splitting. No workspace file resolution is attempted — use the
     * {@code Agent}-aware overload when the caller has that context.
     */
    public static boolean sendMessage(String botToken, String chatId, String text) {
        return sendMessage(botToken, chatId, text, null);
    }

    /**
     * Agent-aware outbound dispatch (JCLAW-93). {@link TelegramOutboundPlanner}
     * splits the markdown into text and file segments; text segments flow through
     * the HTML formatter as before, while file segments are uploaded via
     * {@link #trySendPhoto} (images) or {@link #trySendDocument} (everything else).
     * Segments emit in order so prose, photo, prose sequences arrive the way the
     * agent composed them.
     */
    public static boolean sendMessage(String botToken, String chatId, String text, Agent agent) {
        if (botToken == null || chatId == null || text == null) {
            EventLogger.error("channel", null, "telegram",
                    "sendMessage called with null argument");
            return false;
        }
        var channel = forToken(botToken);
        var segments = TelegramOutboundPlanner.plan(text, agent != null ? agent.name : null);
        if (segments.isEmpty()) return true; // nothing to send

        boolean allOk = true;
        for (var segment : segments) {
            if (segment instanceof TelegramOutboundPlanner.TextSegment ts) {
                if (!sendTextSegment(channel, chatId, ts.markdown())) allOk = false;
            }
            else if (segment instanceof TelegramOutboundPlanner.FileSegment fs) {
                if (!sendFileSegment(channel, chatId, fs)) allOk = false;
            }
        }
        return allOk;
    }

    /**
     * Render a markdown text segment through the formatter and dispatch its
     * chunks. Blank segments (e.g. the whitespace between two adjacent file
     * references) are no-ops so we don't fire off empty sendMessage calls.
     */
    private static boolean sendTextSegment(TelegramChannel channel, String chatId, String markdown) {
        if (markdown == null || markdown.isBlank()) return true;
        var html = TelegramMarkdownFormatter.toHtml(markdown);
        if (html.isBlank()) return true;
        var chunks = TelegramMarkdownFormatter.chunkHtml(html, 4000);
        boolean allOk = true;
        for (var part : chunks) {
            if (!channel.sendWithRetry(chatId, part)) allOk = false;
        }
        return allOk;
    }

    /** Dispatch a file segment through sendPhoto or sendDocument depending on type. */
    private static boolean sendFileSegment(TelegramChannel channel, String chatId,
                                            TelegramOutboundPlanner.FileSegment fs) {
        try {
            if (fs.isImage()) {
                return channel.trySendPhoto(chatId, fs.file(), fs.displayName());
            }
            return channel.trySendDocument(chatId, fs.file(), fs.displayName());
        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "File send failed for %s: %s".formatted(fs.displayName(), e.getMessage()));
            return false;
        }
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
        String chatType = msg.getChat() != null ? msg.getChat().getType() : null;
        String text = msg.getText();
        String fromId = null;
        String fromUsername = null;
        if (msg.getFrom() != null) {
            fromId = String.valueOf(msg.getFrom().getId());
            fromUsername = msg.getFrom().getUserName();
        }
        return new InboundMessage(chatId, chatType, text, fromId, fromUsername);
    }

    // ── Per-instance send path ──

    /**
     * Upload {@code file} as a Telegram photo. The {@code displayName} is shown
     * to the user as the filename hint; Telegram renders images inline, so
     * captions aren't used in this MVP — prose accompanying the photo arrives as
     * a separate text message above or below it.
     */
    public boolean trySendPhoto(String peerId, java.io.File file, String displayName) {
        var request = SendPhoto.builder()
                .chatId(peerId)
                .photo(new InputFile(file, displayName != null ? displayName : file.getName()))
                .build();
        try {
            client.execute(request);
            EventLogger.info("channel", null, "telegram",
                    "Photo sent to chat %s: %s".formatted(peerId, displayName));
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn("channel", null, "telegram",
                    "Photo send failed for %s: %s".formatted(displayName, e.getMessage()));
            return false;
        }
    }

    /**
     * Upload {@code file} as a Telegram document (download attachment). Covers
     * anything that isn't one of the image extensions Telegram renders inline.
     */
    public boolean trySendDocument(String peerId, java.io.File file, String displayName) {
        var request = SendDocument.builder()
                .chatId(peerId)
                .document(new InputFile(file, displayName != null ? displayName : file.getName()))
                .build();
        try {
            client.execute(request);
            EventLogger.info("channel", null, "telegram",
                    "Document sent to chat %s: %s".formatted(peerId, displayName));
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn("channel", null, "telegram",
                    "Document send failed for %s: %s".formatted(displayName, e.getMessage()));
            return false;
        }
    }

    @Override
    public boolean trySend(String peerId, String text) {
        var request = SendMessage.builder()
                .chatId(peerId)
                .text(text)
                .parseMode("HTML")
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
