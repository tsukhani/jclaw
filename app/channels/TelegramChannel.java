package channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import models.Agent;
import models.TelegramBinding;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
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
                                 String fromId, String fromUsername,
                                 java.util.List<PendingAttachment> attachments,
                                 String mediaGroupId) {
        public InboundMessage(String chatId, String chatType, String text,
                              String fromId, String fromUsername) {
            this(chatId, chatType, text, fromId, fromUsername, java.util.List.of(), null);
        }
    }

    /**
     * Inbound file attachment extracted from a Telegram Update before the actual
     * bytes are downloaded (JCLAW-136). The webhook handler returns the 200 fast;
     * a virtual thread then resolves each {@code telegramFileId} via the Bot API
     * {@code getFile} call, streams the payload into workspace staging, and
     * produces an {@link services.AttachmentService.Input} the runner can feed
     * into the existing JCLAW-25 multimodal assembly path. {@code kind} is
     * derived at parse time from which Telegram field the attachment came from
     * (photo → IMAGE, voice/audio → AUDIO, document/video → FILE) and is
     * authoritative for the inbound modality gate; the stored MessageAttachment
     * row's kind is re-sniffed from disk by {@code finalizeAttachment}.
     */
    public record PendingAttachment(String telegramFileId,
                                    String suggestedFilename,
                                    String mimeType,
                                    long sizeBytes,
                                    String kind) {}

    /**
     * Inbound callback_query payload (JCLAW-109). Emitted by
     * {@link #parseUpdate(Update)} when the update is a tap on an inline
     * keyboard button. Carries the callback id (for answerCallbackQuery),
     * the chat + user identity (for binding authorization), the original
     * message id (so the handler can edit-in-place), and the opaque data
     * string (parsed by the kind-specific dispatcher).
     */
    public record InboundCallback(String callbackId, String chatId, String chatType,
                                  String fromId, Integer messageId, String data) {}

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

    /**
     * Timeouts for {@code sendPhoto} / {@code sendDocument} (JCLAW-122). The
     * 2 s write timeout the text paths use is far too tight for a multi-MB
     * upload — a 1–2 MB screenshot on a typical home connection needs
     * several seconds of write bandwidth, plus Telegram server processing.
     * Before this split, every photo upload silently timed out into a
     * {@code TelegramApiException("Unable to execute sendphoto method")}
     * and the planner dropped the file segment. Generous read and write
     * accommodate the largest files the planner will deliver (10 MB photo
     * limit, 50 MB document limit in practice).
     */
    private static final int BOT_API_UPLOAD_CONNECT_TIMEOUT_SEC = 5;
    private static final int BOT_API_UPLOAD_READ_TIMEOUT_SEC = 60;
    private static final int BOT_API_UPLOAD_WRITE_TIMEOUT_SEC = 60;

    /**
     * Dedicated upload client for {@link #trySendPhoto} / {@link #trySendDocument}.
     * Distinct from {@link #client} so file uploads don't share the aggressive
     * text-message timeouts. Configured in the constructor alongside {@code client}.
     */
    private final TelegramClient uploadClient;

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
        // Fast client for text paths: sendMessage, editMessageText, typing,
        // callback answers, etc. Tight timeouts fail fast into the
        // streaming-sink retry tick (JCLAW-98).
        var textOkHttp = new OkHttpClient.Builder()
                .connectTimeout(BOT_API_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(BOT_API_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(BOT_API_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .build();
        this.client = urlOverride != null
                ? new OkHttpTelegramClient(textOkHttp, botToken, urlOverride)
                : new OkHttpTelegramClient(textOkHttp, botToken);
        // Upload client for sendPhoto / sendDocument (JCLAW-122). Must be
        // tolerant of multi-second upload bodies and Telegram's slower
        // file-processing path.
        //
        // JCLAW-126: retryOnConnectionFailure set to false. OkHttp's default
        // of true silently retries on any transient connection issue —
        // including a server-side RST mid-upload — with no upper bound on
        // total attempts until the write timeout expires. Combined with our
        // 60 s write timeout, one transient reset can compound into 60-120 s
        // of invisible wait. Disabling auto-retry makes a failure visible
        // (the SDK throws, we log a warn, the planner surfaces it) instead
        // of hiding in socket-level replay. The streaming-sink and
        // outbound-planner retry at a higher level where we control the
        // policy.
        var uploadOkHttp = new OkHttpClient.Builder()
                .connectTimeout(BOT_API_UPLOAD_CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
                .readTimeout(BOT_API_UPLOAD_READ_TIMEOUT_SEC, TimeUnit.SECONDS)
                .writeTimeout(BOT_API_UPLOAD_WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false)
                .build();
        this.uploadClient = urlOverride != null
                ? new OkHttpTelegramClient(uploadOkHttp, botToken, urlOverride)
                : new OkHttpTelegramClient(uploadOkHttp, botToken);
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
     * Clear a pending {@code sendMessageDraft} by posting empty text via
     * raw HTTP (JCLAW-121). The telegrambots-meta 9.5.0 SDK rejects
     * empty-text {@code SendMessageDraft} with a pre-submit validator, so
     * we can't use {@link OkHttpTelegramClient#execute}. Telegram itself
     * accepts the request — OpenClaw's grammY-backed streamer clears
     * drafts this way in {@code extensions/telegram/src/draft-stream.ts:445}.
     *
     * <p>Best-effort: returns {@code false} on any failure and logs a
     * warning. Caller (the streaming sink's {@code clearDraftBestEffort})
     * swallows the result — a failed clear leaves a stale draft but
     * doesn't prevent the final HTML message from being delivered.
     */
    public static boolean clearMessageDraft(String botToken, String chatId, int draftId) {
        if (botToken == null || botToken.isBlank() || chatId == null) return false;
        try {
            var url = TELEGRAM_API_BASE + "/bot" + botToken + "/sendMessageDraft";
            var body = "{\"chat_id\":%s,\"draft_id\":%d,\"text\":\"\"}".formatted(chatId, draftId);
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(url))
                    .header("Content-Type", "application/json")
                    .timeout(java.time.Duration.ofSeconds(BOT_API_READ_TIMEOUT_SEC))
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var resp = utils.HttpClients.GENERAL.send(
                    req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                EventLogger.warn("channel", null, "telegram",
                        "clearMessageDraft returned HTTP %d for chat %s"
                                .formatted(resp.statusCode(), chatId));
                return false;
            }
            return true;
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "clearMessageDraft failed for chat %s: %s"
                            .formatted(chatId, e.getMessage()));
            return false;
        }
    }

    /**
     * Telegram Bot API base URL used by the raw-HTTP helpers. Package-visible
     * so tests targeting {@code MockTelegramServer} can redirect traffic
     * without touching the SDK's {@code TelegramUrl} override (which only
     * affects {@link OkHttpTelegramClient} calls).
     */
    public static String TELEGRAM_API_BASE = "https://api.telegram.org";

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
                // JCLAW-126: the quality-duplicate document emit (same file as
                // a just-sent photo) fires on a virtual thread so slow Telegram
                // document uploads — which we've observed stalling 2+ minutes
                // for a 1.5 MB screenshot right after the photo sent in 65 s —
                // don't block text or subsequent segments from reaching the user.
                // Failures there log at warn and never regress allOk; the reply
                // has already been delivered by the time the background upload
                // might fail, so a late error can't retroactively fail the turn.
                if (fs.isBackground()) {
                    Thread.ofVirtual().start(() -> {
                        try {
                            if (!sendFileSegment(channel, chatId, fs)) {
                                EventLogger.warn("channel", null, "telegram",
                                        "Background file send failed (non-blocking): %s"
                                                .formatted(fs.displayName()));
                            }
                        } catch (Throwable t) {
                            EventLogger.warn("channel", null, "telegram",
                                    "Background file send threw (non-blocking) for %s: %s"
                                            .formatted(fs.displayName(), t.getMessage()));
                        }
                    });
                } else {
                    if (!sendFileSegment(channel, chatId, fs)) allOk = false;
                }
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

    /**
     * JCLAW-136: apply the modality + size gates to {@code message.attachments()},
     * then download each pending attachment into the agent's workspace
     * staging directory. Shared by the webhook controller and polling
     * runner — both route user uploads through the same rejection + stage
     * pipeline so behavior stays identical regardless of delivery mode.
     *
     * <p>On any rejection (modality mismatch, size exceeded, network/API
     * failure) sends a user-visible reply, logs at warn, and returns
     * {@code null} so the caller can bail out. On success returns a
     * (possibly empty) list of {@link services.AttachmentService.Input}
     * whose shape is identical to what the web upload path produces — the
     * runner handles both origins uniformly.
     */
    public static java.util.List<services.AttachmentService.Input> prepareInboundAttachments(
            String sendToken, String sendChatId, Agent sendAgent, InboundMessage message) {
        if (message.attachments().isEmpty()) return java.util.List.of();

        boolean hasImage = message.attachments().stream().anyMatch(
                a -> models.MessageAttachment.KIND_IMAGE.equalsIgnoreCase(a.kind()));
        boolean hasAudio = message.attachments().stream().anyMatch(
                a -> models.MessageAttachment.KIND_AUDIO.equalsIgnoreCase(a.kind()));
        if (hasImage && !services.AgentService.supportsVision(sendAgent)) {
            sendMessage(sendToken, sendChatId,
                    "I can't handle images with the current model. Try a vision-capable model.");
            EventLogger.warn("channel", sendAgent.name, "telegram",
                    "Rejected image upload: model does not support vision");
            return null;
        }
        if (hasAudio && !services.AgentService.supportsAudio(sendAgent)) {
            sendMessage(sendToken, sendChatId,
                    "I can't handle audio files with the current model. Try an audio-capable model.");
            EventLogger.warn("channel", sendAgent.name, "telegram",
                    "Rejected audio upload: model does not support audio");
            return null;
        }

        var inputs = new java.util.ArrayList<services.AttachmentService.Input>(
                message.attachments().size());
        for (var pending : message.attachments()) {
            var result = TelegramFileDownloader.download(sendToken, pending, sendAgent.name);
            if (result instanceof TelegramFileDownloader.Ok ok) {
                inputs.add(ok.input());
            } else if (result instanceof TelegramFileDownloader.SizeExceeded se) {
                sendMessage(sendToken, sendChatId,
                        "That file is too large — Telegram bots can only accept up to %d MB.".formatted(
                                TelegramFileDownloader.MAX_FILE_BYTES / (1024 * 1024)));
                EventLogger.warn("channel", sendAgent.name, "telegram",
                        "Rejected upload: %d bytes exceeds %d limit".formatted(
                                se.actualBytes(), se.limit()));
                return null;
            } else if (result instanceof TelegramFileDownloader.DownloadFailed df) {
                sendMessage(sendToken, sendChatId,
                        "Sorry, I couldn't download your file from Telegram.");
                EventLogger.warn("channel", sendAgent.name, "telegram",
                        "Download failed: %s".formatted(df.reason()));
                return null;
            }
        }
        return inputs;
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

    /**
     * Parse an SDK {@link Update} (polling runner source) into {@link InboundMessage}.
     *
     * <p>JCLAW-136: accepts messages with attachments (photo, voice, audio,
     * document, video, video_note) in addition to plain text. When the user
     * uploads a file, {@code msg.hasText()} is false — the typed prose lives
     * in {@code msg.getCaption()} instead. The returned InboundMessage
     * populates {@code text} from caption-then-text (empty when neither) and
     * populates {@code attachments} with one {@link PendingAttachment} per
     * media field present. Returns {@code null} only when the update has
     * neither text nor caption nor any recognizable attachment.
     */
    public static InboundMessage parseUpdate(Update update) {
        if (update == null || update.getMessage() == null) return null;
        Message msg = update.getMessage();

        String chatId = String.valueOf(msg.getChatId());
        String chatType = msg.getChat() != null ? msg.getChat().getType() : null;
        String fromId = null;
        String fromUsername = null;
        if (msg.getFrom() != null) {
            fromId = String.valueOf(msg.getFrom().getId());
            fromUsername = msg.getFrom().getUserName();
        }

        var attachments = new java.util.ArrayList<PendingAttachment>();
        // Highest-resolution PhotoSize wins — the array is sorted ascending by
        // Telegram, so the last element is the full-quality original.
        if (msg.hasPhoto() && msg.getPhoto() != null && !msg.getPhoto().isEmpty()) {
            var sizes = msg.getPhoto();
            var best = sizes.get(sizes.size() - 1);
            long bytes = best.getFileSize() != null ? best.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    best.getFileId(), null, "image/jpeg", bytes,
                    models.MessageAttachment.KIND_IMAGE));
        }
        // Voice notes (OGG Opus) and audio files (mp3/m4a/etc.) both map to
        // KIND_AUDIO. getMimeType is nullable — finalizeAttachment re-sniffs
        // with Tika so we're not relying on Telegram's self-report anyway.
        if (msg.getVoice() != null) {
            var v = msg.getVoice();
            long bytes = v.getFileSize() != null ? v.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    v.getFileId(), null, v.getMimeType(), bytes,
                    models.MessageAttachment.KIND_AUDIO));
        }
        if (msg.hasAudio() && msg.getAudio() != null) {
            var a = msg.getAudio();
            long bytes = a.getFileSize() != null ? a.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    a.getFileId(), a.getFileName(), a.getMimeType(), bytes,
                    models.MessageAttachment.KIND_AUDIO));
        }
        if (msg.hasDocument() && msg.getDocument() != null) {
            var d = msg.getDocument();
            long bytes = d.getFileSize() != null ? d.getFileSize() : 0L;
            // A document whose MIME starts with image/ is still an inline
            // image (user uploaded via "File" rather than "Photo" to avoid
            // Telegram's compression). Classify as IMAGE so the multimodal
            // gate applies correctly and the model receives an image part.
            String kind = d.getMimeType() != null && d.getMimeType().startsWith("image/")
                    ? models.MessageAttachment.KIND_IMAGE
                    : models.MessageAttachment.KIND_FILE;
            attachments.add(new PendingAttachment(
                    d.getFileId(), d.getFileName(), d.getMimeType(), bytes, kind));
        }
        if (msg.hasVideo() && msg.getVideo() != null) {
            var v = msg.getVideo();
            long bytes = v.getFileSize() != null ? v.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    v.getFileId(), v.getFileName(), v.getMimeType(), bytes,
                    models.MessageAttachment.KIND_FILE));
        }
        if (msg.hasVideoNote() && msg.getVideoNote() != null) {
            var vn = msg.getVideoNote();
            long bytes = vn.getFileSize() != null ? vn.getFileSize() : 0L;
            attachments.add(new PendingAttachment(
                    vn.getFileId(), null, null, bytes,
                    models.MessageAttachment.KIND_FILE));
        }

        // Caption wins over plain-text when both exist is impossible per
        // Telegram's shape — a given Message carries either text or caption,
        // never both. Pick whichever is populated; empty string is fine
        // (means "user attached a file with no prose context").
        String text;
        if (msg.hasText()) {
            text = msg.getText();
        } else if (msg.getCaption() != null) {
            text = msg.getCaption();
        } else {
            text = "";
        }

        String mediaGroupId = msg.getMediaGroupId();

        // Fully empty updates (no text, no caption, no attachments) are
        // nothing we can act on — drop as before.
        if (text.isEmpty() && attachments.isEmpty()) return null;

        return new InboundMessage(chatId, chatType, text, fromId, fromUsername,
                attachments, mediaGroupId);
    }

    /**
     * JCLAW-109: parse an SDK {@link Update} for an inline-keyboard
     * callback query. Returns null when the update isn't a callback,
     * when the callback has no data field (Telegram theoretically allows
     * games without data — we don't use that), or when identity fields
     * required for authorization are missing. Separate entry point from
     * {@link #parseUpdate(Update)} so callers can cleanly distinguish
     * "text message arrived" from "keyboard tap arrived."
     */
    public static InboundCallback parseCallback(Update update) {
        if (update == null || update.getCallbackQuery() == null) return null;
        CallbackQuery cq = update.getCallbackQuery();
        if (cq.getData() == null || cq.getData().isBlank()) return null;
        if (cq.getFrom() == null) return null;

        String callbackId = cq.getId();
        String fromId = String.valueOf(cq.getFrom().getId());
        String chatId = null;
        String chatType = null;
        Integer messageId = null;
        var origin = cq.getMessage();
        if (origin != null) {
            messageId = origin.getMessageId();
            if (origin instanceof Message mm) {
                chatId = mm.getChatId() != null ? String.valueOf(mm.getChatId()) : null;
                chatType = mm.getChat() != null ? mm.getChat().getType() : null;
            }
        }
        return new InboundCallback(callbackId, chatId, chatType, fromId, messageId, cq.getData());
    }

    /** Parse a Gson {@link JsonObject} update (webhook payload) into an {@link InboundCallback}. */
    public static InboundCallback parseCallback(JsonObject update) {
        try {
            Update sdk = JACKSON.readValue(update.toString(), Update.class);
            return parseCallback(sdk);
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "Callback parse error: %s".formatted(e.getMessage()));
            return null;
        }
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
        // JCLAW-126: explicit upload timing so we can pinpoint slowdowns.
        // Format: elapsedMs, file size in bytes. Together with the matching
        // warn on failure, this bounds the upload wall-clock in the log.
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            // JCLAW-122: upload via uploadClient (60 s r/w) rather than client
            // (2 s w) — a 1–2 MB screenshot reliably times out on the text-path
            // timeouts.
            uploadClient.execute(request);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.info("channel", null, "telegram",
                    "Photo sent to chat %s: %s (elapsedMs=%d, bytes=%d)"
                            .formatted(peerId, displayName, elapsedMs, fileSize));
            return true;
        } catch (TelegramApiException e) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.warn("channel", null, "telegram",
                    "Photo send failed for %s after %dms: %s"
                            .formatted(displayName, elapsedMs, e.getMessage()));
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
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            // JCLAW-122: upload via uploadClient (60 s r/w) rather than client
            // (2 s w). Same rationale as trySendPhoto above — document bodies
            // are often larger than photos (PDFs, reports, zips).
            uploadClient.execute(request);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.info("channel", null, "telegram",
                    "Document sent to chat %s: %s (elapsedMs=%d, bytes=%d)"
                            .formatted(peerId, displayName, elapsedMs, fileSize));
            return true;
        } catch (TelegramApiException e) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.warn("channel", null, "telegram",
                    "Document send failed for %s after %dms: %s"
                            .formatted(displayName, elapsedMs, e.getMessage()));
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

    // ── JCLAW-109: inline-keyboard send + callback plumbing ────────────

    /**
     * Send an HTML-formatted message with an inline keyboard attached
     * (JCLAW-109). Returns the new message id on success (so the caller
     * can later {@code editMessageText} it), or null on failure. Single
     * Bot API call — no chunking or planner pass, because keyboard
     * messages stay well under the 4096-char limit by construction.
     */
    public static Integer sendMessageWithKeyboard(String botToken, String chatId,
                                                   String htmlText, InlineKeyboardMarkup keyboard) {
        var channel = forToken(botToken);
        var request = SendMessage.builder()
                .chatId(chatId)
                .text(htmlText)
                .parseMode("HTML")
                .replyMarkup(keyboard)
                .build();
        try {
            var msg = channel.client.execute(request);
            return msg.getMessageId();
        } catch (TelegramApiException e) {
            EventLogger.warn("channel", null, "telegram",
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
    public static boolean editMessageText(String botToken, String chatId, Integer messageId,
                                           String htmlText, InlineKeyboardMarkup keyboard) {
        var channel = forToken(botToken);
        var builder = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(htmlText)
                .parseMode("HTML");
        if (keyboard != null) builder.replyMarkup(keyboard);
        try {
            channel.client.execute(builder.build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn("channel", null, "telegram",
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
    public static boolean answerCallbackQuery(String botToken, String callbackId,
                                               String text, boolean showAlert) {
        var channel = forToken(botToken);
        var builder = AnswerCallbackQuery.builder()
                .callbackQueryId(callbackId)
                .showAlert(showAlert);
        if (text != null && !text.isEmpty()) builder.text(text);
        try {
            channel.client.execute(builder.build());
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn("channel", null, "telegram",
                    "answerCallbackQuery failed: %s".formatted(e.getMessage()));
            return false;
        }
    }
}
