package channels;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonObject;
import models.Agent;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage;
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionType;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.MessageEntity;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
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
     * and {@link TelegramPollingRunner}.
     *
     * @param chatId       Telegram chat id (used as the conversation peer key)
     * @param chatType     Telegram Bot API chat.type string ({@code "private"}
     *                     / {@code "group"} / {@code "supergroup"} /
     *                     {@code "channel"}), recorded for structured logging
     *                     and possible future routing. Nullable when an
     *                     update arrives without chat context.
     * @param text         message body text; may be null for media-only updates
     * @param fromId          sender's Telegram user id (used for binding
     *                        authorization)
     * @param fromUsername    sender's Telegram @-handle if set
     * @param fromDisplayName sender's display name for transcript attribution
     *                        (JCLAW-367): first + last name when available,
     *                        falling back to the @-handle, else null. Distinct
     *                        from {@code fromUsername} so the UI can show a
     *                        human label even for users who never set a handle.
     * @param botMentioned    JCLAW-367 access-policy signal: true when the bot
     *                        was directly addressed in this message — via an
     *                        {@code @botusername} mention, a {@code text_mention}
     *                        resolving to the bot's own user id, a
     *                        {@code /cmd@botusername} bot_command suffix, or a
     *                        reply to one of the bot's own messages. A later
     *                        group-gating story consumes this; parsing here does
     *                        NOT itself gate or drop anything. Best-effort when
     *                        the bot identity is unknown (see
     *                        {@link #parseUpdate(Update)}).
     * @param attachments     inbound file attachments (resolved lazily by the
     *                        webhook handler)
     * @param mediaGroupId    Telegram media-group identifier when multiple
     *                        attachments are part of one user upload; null for
     *                        single-attachment / text-only messages
     * @param messageId       JCLAW-368: the inbound {@code message_id}, copied
     *                        verbatim from the Update so replies/edits can
     *                        target the originating message. Null when the
     *                        Update carries no message id.
     * @param messageThreadId JCLAW-368: the forum-topic thread id
     *                        ({@code message_thread_id}) when the message lands
     *                        in a topic ({@code is_topic_message} true); null
     *                        for plain non-topic messages so a thread id is
     *                        only carried when Telegram actually scopes the
     *                        message to a topic.
     * @param replyContext    JCLAW-366: a supplemental "in reply to: …" context
     *                        block when this message replies to (and/or natively
     *                        quotes) an earlier message. Carries the quoted
     *                        substring (preferred when present) or the
     *                        replied-to message's text/snippet, plus a media-type
     *                        note when the replied-to message is media-only.
     *                        Null when the message is not a reply / has no
     *                        usable reply context. The runner folds this into
     *                        the turn the agent sees; it is NOT part of
     *                        {@code text} so callers can render it distinctly.
     */
    public record InboundMessage(String chatId, String chatType, String text,
                                 String fromId, String fromUsername,
                                 String fromDisplayName, boolean botMentioned,
                                 java.util.List<PendingAttachment> attachments,
                                 String mediaGroupId,
                                 Integer messageId, Integer messageThreadId,
                                 String replyContext) {
        public InboundMessage(String chatId, String chatType, String text,
                              String fromId, String fromUsername) {
            this(chatId, chatType, text, fromId, fromUsername, null, false,
                    java.util.List.of(), null, null, null, null);
        }

        /**
         * JCLAW-367: pre-sender-capture convenience overload. Callers that
         * carry attachments + media-group context but no per-message sender
         * display name / addressed-bot signal (e.g. the media-group reassembler)
         * use this; {@code fromDisplayName} defaults to null and
         * {@code botMentioned} to false. JCLAW-368: {@code messageId} and
         * {@code messageThreadId} default to null — the merge path that uses
         * this overload synthesizes one inbound from many and has no single
         * message id / thread id to attribute. JCLAW-366: {@code replyContext}
         * defaults to null for the same reason.
         */
        public InboundMessage(String chatId, String chatType, String text,
                              String fromId, String fromUsername,
                              java.util.List<PendingAttachment> attachments,
                              String mediaGroupId) {
            this(chatId, chatType, text, fromId, fromUsername, null, false,
                    attachments, mediaGroupId, null, null, null);
        }

        /**
         * JCLAW-368 convenience overload preserved for callers that carry the
         * full sender/attachment shape but pre-date the {@code replyContext}
         * field (JCLAW-366) — defaults it to null so those call sites compile
         * unchanged.
         */
        public InboundMessage(String chatId, String chatType, String text,
                              String fromId, String fromUsername,
                              String fromDisplayName, boolean botMentioned,
                              java.util.List<PendingAttachment> attachments,
                              String mediaGroupId,
                              Integer messageId, Integer messageThreadId) {
            this(chatId, chatType, text, fromId, fromUsername, fromDisplayName,
                    botMentioned, attachments, mediaGroupId, messageId,
                    messageThreadId, null);
        }
    }

    /**
     * Inbound file attachment extracted from a Telegram Update before the actual
     * bytes are downloaded (JCLAW-136). The webhook handler returns the 200 fast;
     * a virtual thread then resolves each {@code telegramFileId} via the Bot API
     * {@code getFile} call, streams the payload into workspace staging, and
     * produces an {@link services.AttachmentService.Input} the runner can feed
     * into the existing JCLAW-25 multimodal assembly path.
     *
     * @param telegramFileId    opaque Telegram file id used with the Bot API
     *                          {@code getFile} call to resolve a download URL
     * @param suggestedFilename filename suggested by Telegram (may be null /
     *                          empty for voice notes etc.)
     * @param mimeType          MIME type reported by Telegram
     * @param sizeBytes         size in bytes reported by Telegram
     * @param kind              derived at parse time from which Telegram
     *                          field the attachment came from (photo →
     *                          IMAGE, voice/audio → AUDIO, document/video →
     *                          FILE). Authoritative for the inbound
     *                          modality gate; the stored MessageAttachment
     *                          row's kind is re-sniffed from disk by
     *                          {@code finalizeAttachment}.
     */
    public record PendingAttachment(String telegramFileId,
                                    String suggestedFilename,
                                    String mimeType,
                                    long sizeBytes,
                                    String kind) {}

    /**
     * Inbound callback_query payload (JCLAW-109). Emitted by
     * {@link #parseUpdate(Update)} when the update is a tap on an inline
     * keyboard button.
     *
     * @param callbackId callback id passed back to
     *                   {@code answerCallbackQuery} to dismiss the spinner
     *                   on the user's button tap
     * @param chatId     chat the button was tapped in (used for binding
     *                   authorization)
     * @param chatType   {@code "private"} or {@code "group"} from the
     *                   inbound chat
     * @param fromId     user id of the tapper (used for binding
     *                   authorization)
     * @param messageId  original message id carrying the inline keyboard,
     *                   so the handler can edit-in-place
     * @param data       opaque data string parsed by the kind-specific
     *                   dispatcher
     */
    public record InboundCallback(String callbackId, String chatId, String chatType,
                                  String fromId, Integer messageId, String data) {}

    private static final ObjectMapper JACKSON = new ObjectMapper();

    private static final String LOG_CATEGORY = "channel";
    /** Channel identifier used as logging source and returned by {@link #channelName()}. */
    private static final String CHANNEL_NAME = "telegram";

    /** Per-token instances. OkHttpTelegramClient owns a dispatcher thread pool, so
     *  we reuse one instance per token across the lifetime of that token. */
    private static final ConcurrentHashMap<String, TelegramChannel> INSTANCES = new ConcurrentHashMap<>();

    private final String botToken;
    private final TelegramClient client;

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
                                       Integer messageId, String text) throws TelegramApiException {
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
    public static boolean setMessageReaction(String botToken, String chatId, Integer messageId, String emoji) {
        if (botToken == null || chatId == null || messageId == null) return false;
        var builder = SetMessageReaction.builder()
                .chatId(chatId)
                .messageId(messageId);
        if (emoji != null && !emoji.isBlank()) {
            builder.reactionTypes(java.util.List.<ReactionType>of(
                    ReactionTypeEmoji.builder().emoji(emoji).build()));
        } else {
            // Empty list clears the bot's reaction.
            builder.reactionTypes(java.util.List.of());
        }
        try {
            forToken(botToken).client.execute(builder.build());
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
    public static boolean pinChatMessage(String botToken, String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        try {
            forToken(botToken).client.execute(PinChatMessage.builder()
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
    public static boolean unpinChatMessage(String botToken, String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        try {
            forToken(botToken).client.execute(UnpinChatMessage.builder()
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
    public static boolean deleteMessage(String botToken, String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        try {
            forToken(botToken).client.execute(DeleteMessage.builder()
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
     * Telegram Bot API base URL used by the raw-HTTP helpers. Package-visible
     * so tests targeting {@code MockTelegramServer} can redirect traffic
     * without touching the SDK's {@code TelegramUrl} override (which only
     * affects {@link OkHttpTelegramClient} calls).
     */
    public static String TELEGRAM_API_BASE = "https://api.telegram.org";

    // ── JCLAW-369: outbound reply targeting + topic-aware sends ──────────

    /**
     * JCLAW-369: reply-targeting policy. Controls whether — and how often —
     * an inbound {@code replyToMessageId} is applied to the turn's outbound
     * messages, read from {@code telegram.replyTo.mode} via
     * {@link play.Play#configuration} (default {@link #REPLY_MODE_FIRST} when
     * unset/blank/unrecognized):
     *
     * <ul>
     *   <li>{@link #REPLY_MODE_OFF} — never set {@code reply_parameters};</li>
     *   <li>{@link #REPLY_MODE_FIRST} — set it on only the first chunk/message
     *       of a turn (the natural "this is my answer to that" affordance
     *       without spamming a reply badge on every chunk);</li>
     *   <li>{@link #REPLY_MODE_ALL} — set it on every chunk/message.</li>
     * </ul>
     *
     * <p>Dormant until a caller threads a non-null {@code replyToMessageId}
     * into one of the send overloads; the no-arg behavior is unchanged.
     */
    private static final String CFG_REPLY_TO_MODE = "telegram.replyTo.mode";
    static final String REPLY_MODE_OFF = "off";
    static final String REPLY_MODE_FIRST = "first";
    static final String REPLY_MODE_ALL = "all";

    /**
     * The Telegram "General" forum topic always has {@code message_thread_id == 1},
     * and the Bot API rejects a send that names it explicitly — so JCLAW-369
     * OMITS {@code message_thread_id} on sends to General (a bare send already
     * lands there). Typing actions, by contrast, accept it.
     */
    static final int GENERAL_TOPIC_THREAD_ID = 1;

    // ── JCLAW-359: link-preview suppression ──────────────────────────────

    /**
     * JCLAW-359: link-preview policy, read from {@code telegram.linkPreview} via
     * {@link play.Play#configuration}. Telegram auto-renders a preview card for
     * the first URL in a message; some operators want that off so a chat full of
     * agent-cited links stays compact. The flag is a coarse on/off:
     *
     * <ul>
     *   <li>{@code on} (default; also any unset/blank/unrecognized value) — leave
     *       {@code link_preview_options} unset, preserving Telegram's default
     *       preview-on behavior;</li>
     *   <li>{@code off} — attach {@code LinkPreviewOptions(is_disabled=true)} to
     *       every text send so no preview card is generated.</li>
     * </ul>
     */
    private static final String CFG_LINK_PREVIEW = "telegram.linkPreview";
    static final String LINK_PREVIEW_ON = "on";
    static final String LINK_PREVIEW_OFF = "off";

    /**
     * JCLAW-359: marker substring identifying a Telegram "can't parse entities"
     * rejection. Telegram returns it inside a 400 {@code Bad Request} description
     * (e.g. {@code Bad Request: can't parse entities: Unsupported start tag ...})
     * when the HTML payload is malformed — typically a revoked / mangled entity
     * the markdown formatter emitted. Matched case-insensitively so a wording
     * tweak in the apostrophe / casing doesn't slip the detection.
     */
    private static final String PARSE_ENTITIES_MARKER = "can't parse entities";

    /**
     * JCLAW-359: true when link previews should be suppressed on outbound text
     * sends ({@code telegram.linkPreview = off}). Public so default-package tests
     * can assert the config-read contract, mirroring {@link #replyToMode()}.
     */
    public static boolean suppressLinkPreview() {
        var raw = play.Play.configuration.getProperty(CFG_LINK_PREVIEW, LINK_PREVIEW_ON);
        return raw != null && raw.trim().equalsIgnoreCase(LINK_PREVIEW_OFF);
    }

    /**
     * JCLAW-359: the {@link org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions}
     * to attach to a text send, or null to leave Telegram's default preview-on
     * behavior. Returns a disabled-preview options object only when
     * {@link #suppressLinkPreview()} is true.
     */
    private static org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions linkPreviewOptions() {
        if (!suppressLinkPreview()) return null;
        return org.telegram.telegrambots.meta.api.objects.LinkPreviewOptions.builder()
                .isDisabled(true)
                .build();
    }

    /**
     * JCLAW-359: true when {@code e} is a 400 "can't parse entities" rejection —
     * the HTML payload was malformed and the SAME send should be retried once as
     * plain text. Distinct from the 429 rate-limit path (handled separately) and
     * from other 400s (genuine bad requests that a plain-text retry can't fix).
     */
    private static boolean isParseEntitiesError(TelegramApiRequestException e) {
        Integer code = e.getErrorCode();
        if (code == null || code != 400) return false;
        var desc = e.getApiResponse();
        return desc != null && desc.toLowerCase().contains(PARSE_ENTITIES_MARKER);
    }

    /**
     * Resolve the configured reply mode, normalizing unknown/blank values to
     * {@link #REPLY_MODE_FIRST}. Public so default-package tests can assert the
     * config-read contract (matches the {@code *ForTest} convention used
     * elsewhere in this class for test-reachable surface).
     */
    public static String replyToMode() {
        var raw = play.Play.configuration.getProperty(CFG_REPLY_TO_MODE, REPLY_MODE_FIRST);
        if (raw == null) return REPLY_MODE_FIRST;
        var v = raw.trim().toLowerCase();
        return switch (v) {
            case REPLY_MODE_OFF, REPLY_MODE_FIRST, REPLY_MODE_ALL -> v;
            default -> REPLY_MODE_FIRST;
        };
    }

    /**
     * Build the {@link ReplyParameters} to apply on a given outbound chunk, or
     * null when none should be set. Honors the {@link #replyToMode()} policy:
     * {@code off} → never; {@code first} → only when {@code firstChunk}; {@code all}
     * → always (given a non-null target). {@code allow_sending_without_reply=true}
     * so a since-deleted target degrades to a plain send instead of a 400.
     */
    private static ReplyParameters replyParamsFor(Integer replyToMessageId, boolean firstChunk) {
        if (replyToMessageId == null) return null;
        var mode = replyToMode();
        boolean apply = switch (mode) {
            case REPLY_MODE_ALL -> true;
            case REPLY_MODE_FIRST -> firstChunk;
            default -> false; // off
        };
        if (!apply) return null;
        return ReplyParameters.builder()
                .messageId(replyToMessageId)
                .allowSendingWithoutReply(true)
                .build();
    }

    /**
     * The {@code message_thread_id} to set on an outbound send, or null to omit
     * it. Returns null when {@code messageThreadId} is null or names the General
     * topic ({@link #GENERAL_TOPIC_THREAD_ID}) — a bare send already lands in
     * General, and naming it explicitly is rejected by the Bot API.
     */
    private static Integer sendThreadId(Integer messageThreadId) {
        if (messageThreadId == null || messageThreadId == GENERAL_TOPIC_THREAD_ID) return null;
        return messageThreadId;
    }

    /**
     * JCLAW-369: package-private bridge for {@link TelegramStreamingSink}. The
     * streaming placeholder is the turn's first (and only live) message, so the
     * sink always evaluates the reply policy as the first chunk. Returns null
     * when no badge should be applied ({@code off}, or a null target).
     */
    static ReplyParameters replyParamsForSink(Integer replyToMessageId) {
        return replyParamsFor(replyToMessageId, true);
    }

    /** JCLAW-369: package-private bridge so the sink shares the General-topic strip rule. */
    static Integer sendThreadIdForSink(Integer messageThreadId) {
        return sendThreadId(messageThreadId);
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
        sendTypingAction(botToken, chatId, null);
    }

    /**
     * JCLAW-369: topic-aware typing action. When {@code messageThreadId} is
     * set the indicator is scoped to that forum topic — General (thread id 1)
     * INCLUDED, unlike sends, because the chat-action API accepts the General
     * thread id. Null preserves the non-topic behavior. Existing callers route
     * through {@link #sendTypingAction(String, String)} (thread id null).
     */
    public static void sendTypingAction(String botToken, String chatId, Integer messageThreadId) {
        if (botToken == null || chatId == null) return;
        try {
            var builder = org.telegram.telegrambots.meta.api.methods.send.SendChatAction.builder()
                    .chatId(chatId)
                    .action("typing");
            if (messageThreadId != null) builder.messageThreadId(messageThreadId);
            forToken(botToken).client.execute(builder.build());
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendChatAction(typing) failed for chat %s: %s"
                            .formatted(chatId, e.getMessage()));
        }
    }

    @Override
    public String channelName() { return CHANNEL_NAME; }

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
        return sendMessage(botToken, chatId, text, agent, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware outbound dispatch. Adds two
     * optional, nullable params over {@link #sendMessage(String, String, String, Agent)}:
     *
     * <ul>
     *   <li>{@code replyToMessageId} — when non-null, the turn's chunks reply to
     *       this message per the {@link #replyToMode()} policy
     *       ({@code telegram.replyTo.mode}: {@code first} sets it on only the
     *       first chunk, {@code all} on every chunk, {@code off} never).
     *       {@code allow_sending_without_reply=true} so a deleted target won't
     *       fail the send.</li>
     *   <li>{@code messageThreadId} — when non-null and not the General topic
     *       ({@link #GENERAL_TOPIC_THREAD_ID}), scopes every send to that forum
     *       topic; General is omitted (a bare send already lands there).</li>
     * </ul>
     *
     * <p>Both null reproduces the legacy behavior exactly — this is the dormant
     * mechanism JCLAW-369 ships; the dispatch sites wire the values in later.
     */
    public static boolean sendMessage(String botToken, String chatId, String text, Agent agent,
                                      Integer replyToMessageId, Integer messageThreadId) {
        if (botToken == null || chatId == null || text == null) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendMessage called with null argument");
            return false;
        }
        var channel = forToken(botToken);
        var segments = TelegramOutboundPlanner.plan(text, agent != null ? agent.name : null);
        if (segments.isEmpty()) return true; // nothing to send

        // JCLAW-369: track "first chunk of the turn" across all segments so the
        // `first` reply-mode applies the reply badge once, not once per segment.
        var firstChunk = new java.util.concurrent.atomic.AtomicBoolean(true);
        Integer threadId = sendThreadId(messageThreadId);
        boolean allOk = true;
        for (var segment : segments) {
            if (!dispatchSegment(channel, chatId, segment, replyToMessageId, threadId, firstChunk)) {
                allOk = false;
            }
        }
        return allOk;
    }

    /** Dispatch one planner segment; returns false only when a foreground send actually fails. */
    private static boolean dispatchSegment(TelegramChannel channel, String chatId,
                                           TelegramOutboundPlanner.Segment segment,
                                           Integer replyToMessageId, Integer threadId,
                                           java.util.concurrent.atomic.AtomicBoolean firstChunk) {
        if (segment instanceof TelegramOutboundPlanner.TextSegment(String markdown)) {
            return sendTextSegment(channel, chatId, markdown, replyToMessageId, threadId, firstChunk);
        }
        if (segment instanceof TelegramOutboundPlanner.MediaGroupSegment mg) {
            return sendMediaGroupSegment(channel, chatId, mg, replyToMessageId, threadId, firstChunk);
        }
        if (segment instanceof TelegramOutboundPlanner.FileSegment fs) {
            // JCLAW-126: the quality-duplicate document emit (same file as
            // a just-sent photo) fires on a virtual thread so slow Telegram
            // document uploads — which we've observed stalling 2+ minutes
            // for a 1.5 MB screenshot right after the photo sent in 65 s —
            // don't block text or subsequent segments from reaching the user.
            // Failures there log at warn and never regress allOk; the reply
            // has already been delivered by the time the background upload
            // might fail, so a late error can't retroactively fail the turn.
            if (fs.isBackground()) {
                // JCLAW-369: snapshot whether this background segment owns the
                // first chunk before the VT detaches — the AtomicBoolean is
                // shared turn state and would otherwise race with later
                // foreground segments.
                boolean ownsFirst = firstChunk.getAndSet(false);
                Thread.ofVirtual().name("telegram-bg-send")
                        .start(() -> backgroundSendFile(channel, chatId, fs,
                                replyToMessageId, threadId, ownsFirst));
                return true;
            }
            return sendFileSegment(channel, chatId, fs, replyToMessageId, threadId,
                    firstChunk.getAndSet(false));
        }
        return true;
    }

    // Catches Throwable on purpose: this runs at a virtual-thread root, so an
    // unhandled Error (OOM in SDK, AssertionError, etc.) would kill the worker
    // silently and leak the chat's outbound queue. The reply text has already
    // been delivered by the time this fires, so late failures cannot regress
    // the turn's success — we just log and drop.
    @SuppressWarnings("java:S1181")
    private static void backgroundSendFile(TelegramChannel channel, String chatId,
                                            TelegramOutboundPlanner.FileSegment fs,
                                            Integer replyToMessageId, Integer threadId,
                                            boolean firstChunk) {
        try {
            if (!sendFileSegment(channel, chatId, fs, replyToMessageId, threadId, firstChunk)) {
                EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                        "Background file send failed (non-blocking): %s"
                                .formatted(fs.displayName()));
            }
        } catch (Throwable t) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Background file send threw (non-blocking) for %s: %s"
                            .formatted(fs.displayName(), t.getMessage()));
        }
    }

    /**
     * Render a markdown text segment through the formatter and dispatch its
     * chunks. Blank segments (e.g. the whitespace between two adjacent file
     * references) are no-ops so we don't fire off empty sendMessage calls.
     *
     * <p>JCLAW-369: each chunk carries the turn's reply target + topic thread;
     * {@code firstChunk} is consumed (set false) on the first non-blank chunk
     * actually put on the wire so the {@code first} reply-mode badges exactly
     * one message of the turn.
     */
    private static boolean sendTextSegment(TelegramChannel channel, String chatId, String markdown,
                                           Integer replyToMessageId, Integer threadId,
                                           java.util.concurrent.atomic.AtomicBoolean firstChunk) {
        if (markdown == null || markdown.isBlank()) return true;
        var html = TelegramMarkdownFormatter.toHtml(markdown);
        if (html.isBlank()) return true;
        var chunks = TelegramMarkdownFormatter.chunkHtml(html, 4000);
        boolean allOk = true;
        for (var part : chunks) {
            boolean ownsFirst = firstChunk.getAndSet(false);
            var reply = replyParamsFor(replyToMessageId, ownsFirst);
            if (!channel.sendTextWithRetry(chatId, part, reply, threadId)) allOk = false;
        }
        return allOk;
    }

    /**
     * Dispatch a file segment through the native send method matching its
     * {@link TelegramOutboundPlanner.MediaKind} (JCLAW-364), forwarding the
     * planner-folded caption. Unknown types route through sendDocument.
     */
    private static boolean sendFileSegment(TelegramChannel channel, String chatId,
                                            TelegramOutboundPlanner.FileSegment fs,
                                            Integer replyToMessageId, Integer threadId,
                                            boolean firstChunk) {
        var reply = replyParamsFor(replyToMessageId, firstChunk);
        var file = fs.file();
        var name = fs.displayName();
        var caption = fs.caption();
        try {
            return switch (fs.kind()) {
                case PHOTO -> channel.trySendPhoto(chatId, file, name, reply, threadId, caption);
                case VOICE -> channel.trySendVoice(chatId, file, name, reply, threadId, caption);
                case AUDIO -> channel.trySendAudio(chatId, file, name, reply, threadId, caption);
                case VIDEO -> channel.trySendVideo(chatId, file, name, reply, threadId, caption);
                case DOCUMENT -> channel.trySendDocument(chatId, file, name, reply, threadId, caption);
            };
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "File send failed for %s: %s".formatted(name, e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-365: dispatch a coalesced photo/video run as a single Telegram album
     * via {@link #sendMediaGroup}. The album reply badge / topic follows the same
     * first-chunk policy as the other segment paths: the group counts as one
     * chunk of the turn. On a media-group failure, fall back to individual sends
     * (one per item) so the user still receives every file — preserving the
     * caption on the first item to mirror the album's caption convention.
     */
    private static boolean sendMediaGroupSegment(TelegramChannel channel, String chatId,
                                                 TelegramOutboundPlanner.MediaGroupSegment mg,
                                                 Integer replyToMessageId, Integer threadId,
                                                 java.util.concurrent.atomic.AtomicBoolean firstChunk) {
        boolean ownsFirst = firstChunk.getAndSet(false);
        var reply = replyParamsFor(replyToMessageId, ownsFirst);
        if (channel.sendMediaGroup(chatId, mg.items(), mg.caption(), reply, threadId)) {
            return true;
        }
        // Album send failed — fall back to one individual send per item so the
        // user still gets the media. The album caption rides the first item only.
        EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                "Media group failed; falling back to %d individual sends".formatted(mg.items().size()));
        boolean allOk = true;
        for (int i = 0; i < mg.items().size(); i++) {
            var item = mg.items().get(i);
            var withCaption = i == 0 ? item.withCaption(mg.caption()) : item.withCaption(null);
            // Each fallback item is the first (and only) message of its own send;
            // reuse ownsFirst for item 0 so the reply badge still lands once.
            boolean itemFirst = i == 0 && ownsFirst;
            if (!sendFileSegment(channel, chatId, withCaption, replyToMessageId, threadId, itemFirst)) {
                allOk = false;
            }
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

    /**
     * JCLAW-339: register {@code url} as the bot's webhook, passing
     * {@code secretToken} (null skips it) so Telegram echoes it back in the
     * {@code X-Telegram-Bot-Api-Secret-Token} header. Telegram stops its own
     * long polling for the bot once a webhook is set. Returns false on any API
     * error (logged).
     */
    public static boolean setWebhook(String botToken, String url, String secretToken) {
        if (botToken == null || url == null) return false;
        var builder = SetWebhook.builder().url(url);
        if (secretToken != null) builder.secretToken(secretToken);
        try {
            forToken(botToken).client.execute(builder.build());
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
    public static boolean deleteWebhook(String botToken) {
        if (botToken == null) return false;
        try {
            forToken(botToken).client.execute(DeleteWebhook.builder().build());
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME, "Webhook deleted");
            return true;
        } catch (TelegramApiException e) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Webhook deletion failed: %s".formatted(e.getMessage()));
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
    // S1168: null is the deliberate "rejected, helper already replied + logged"
    // sentinel — callers explicitly `if (inputs == null) return` to abort the
    // turn. An empty list, by contrast, means "no attachments to process,
    // continue with text only" — they are semantically distinct outcomes.
    @SuppressWarnings("java:S1168")
    public static java.util.List<services.AttachmentService.Input> prepareInboundAttachments(
            String sendToken, String sendChatId, Agent sendAgent, InboundMessage message) {
        if (message.attachments().isEmpty()) return java.util.List.of();

        boolean hasImage = message.attachments().stream().anyMatch(
                a -> models.MessageAttachment.KIND_IMAGE.equalsIgnoreCase(a.kind()));
        if (hasImage && !services.AgentService.supportsVision(sendAgent)) {
            sendMessage(sendToken, sendChatId,
                    "I can't handle images with the current model. Try a vision-capable model.");
            EventLogger.warn(LOG_CATEGORY, sendAgent.name, CHANNEL_NAME,
                    "Rejected image upload: model does not support vision");
            return null;
        }
        // JCLAW-165: audio is universally accepted — text-only models get
        // a transcript text part via the transcription pipeline, audio-
        // capable models get native input_audio. No model-side gate.

        var inputs = new java.util.ArrayList<services.AttachmentService.Input>(
                message.attachments().size());
        for (var pending : message.attachments()) {
            var result = TelegramFileDownloader.download(sendToken, pending, sendAgent.name);
            if (result instanceof TelegramFileDownloader.Ok(var input)) {
                inputs.add(input);
            } else if (result instanceof TelegramFileDownloader.SizeExceeded(var actualBytes, var limit)) {
                sendMessage(sendToken, sendChatId,
                        "That file is too large — Telegram bots can only accept up to %d MB.".formatted(
                                TelegramFileDownloader.MAX_FILE_BYTES / (1024 * 1024)));
                EventLogger.warn(LOG_CATEGORY, sendAgent.name, CHANNEL_NAME,
                        "Rejected upload: %d bytes exceeds %d limit".formatted(actualBytes, limit));
                return null;
            } else if (result instanceof TelegramFileDownloader.DownloadFailed(var reason)) {
                sendMessage(sendToken, sendChatId,
                        "Sorry, I couldn't download your file from Telegram.");
                EventLogger.warn(LOG_CATEGORY, sendAgent.name, CHANNEL_NAME,
                        "Download failed: %s".formatted(reason));
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
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
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
     *
     * <p>JCLAW-367: this single-arg form has no bot identity to resolve
     * {@code @mention} / {@code text_mention} / {@code /cmd@botname} against,
     * so {@link InboundMessage#botMentioned()} only fires on the
     * identity-independent signal (a reply to a bot message that the SDK can
     * see). Prefer {@link #parseUpdate(Update, String, Long)} where the
     * caller knows the bot's username/id; the kept single-arg overload exists
     * so existing call sites compile unchanged.
     */
    public static InboundMessage parseUpdate(Update update) {
        return parseUpdate(update, null, null);
    }

    /**
     * JCLAW-367: identity-aware parse. {@code botUsername} (the bot's
     * {@code @}-handle, without the leading {@code @}; null if unknown) and
     * {@code botUserId} (the bot's numeric Telegram user id; null if unknown)
     * let the entity scan decide whether a {@code mention} / {@code text_mention}
     * / {@code bot_command@suffix} addresses <i>this</i> bot. Entity OFFSETS are
     * used, never substring search, so an {@code @handle} or {@code /cmd}
     * appearing inside a URL or code span never false-positives.
     *
     * <p>The bot user id is derivable from the bot token (Telegram tokens are
     * {@code <bot_id>:<hash>}) by the caller; the username is only known after
     * a {@code getMe} call. When neither is supplied this degrades exactly to
     * {@link #parseUpdate(Update)}'s best-effort behavior.
     */
    public static InboundMessage parseUpdate(Update update, String botUsername, Long botUserId) {
        if (update == null || update.getMessage() == null) return null;
        Message msg = update.getMessage();

        String chatId = String.valueOf(msg.getChatId());
        String chatType = msg.getChat() != null ? msg.getChat().getType() : null;
        String fromId = null;
        String fromUsername = null;
        String fromDisplayName = null;
        if (msg.getFrom() != null) {
            fromId = String.valueOf(msg.getFrom().getId());
            fromUsername = msg.getFrom().getUserName();
            fromDisplayName = displayNameOf(msg.getFrom());
        }

        var attachments = new java.util.ArrayList<PendingAttachment>();
        collectPhotoAttachment(msg, attachments);
        collectAudioAttachments(msg, attachments);
        collectFileAttachments(msg, attachments);
        // JCLAW-366: a static WEBP sticker stages as an image attachment;
        // animated/video stickers stage nothing (placeholder note only).
        collectStickerAttachment(msg, attachments);

        // Caption wins over plain-text when both exist is impossible per
        // Telegram's shape — a given Message carries either text or caption,
        // never both. Pick whichever is populated; empty string is fine
        // (means "user attached a file with no prose context").
        String text = pickInboundText(msg);

        // JCLAW-366: stickers, location, and venue messages carry no text and
        // (for animated/video stickers, location, venue) no downloadable
        // attachment, so they previously hit the empty-drop below. Surface
        // each as a text context note so the turn is no longer discarded.
        String note = inboundContextNote(msg);
        if (!note.isEmpty()) {
            text = text.isEmpty() ? note : text + "\n" + note;
        }

        boolean botMentioned = detectBotAddressed(msg, botUsername, botUserId);

        String mediaGroupId = msg.getMediaGroupId();

        // JCLAW-368: capture the inbound message id verbatim, and the
        // forum-topic thread id only when this message is actually scoped to
        // a topic. Telegram populates message_thread_id on non-topic replies
        // too; gating on isTopicMessage keeps the field null for plain
        // (non-topic) messages, which is the contract this record promises.
        Integer messageId = msg.getMessageId();
        Integer messageThreadId = msg.isTopicMessage() ? msg.getMessageThreadId() : null;

        // JCLAW-366: fold the replied-to / natively-quoted context into a
        // supplemental block carried alongside (not inside) text.
        String replyContext = buildReplyContext(msg);

        // Fully empty updates (no text, no caption, no attachment, no
        // sticker/location/venue note) are nothing we can act on — drop as
        // before. A reply with no body of its own (replyContext only) is not
        // actionable on its own, so it does not keep the turn alive.
        if (text.isEmpty() && attachments.isEmpty()) return null;

        return new InboundMessage(chatId, chatType, text, fromId, fromUsername,
                fromDisplayName, botMentioned, attachments, mediaGroupId,
                messageId, messageThreadId, replyContext);
    }

    /**
     * Build a human-readable display name from a Telegram {@link User} for
     * transcript attribution (JCLAW-367): "First Last" when names are present,
     * falling back to the {@code @}-handle, else null. Telegram guarantees a
     * non-blank first name on real users, but a defensive trim keeps the
     * result clean for edge shapes.
     */
    private static String displayNameOf(User user) {
        var sb = new StringBuilder();
        if (user.getFirstName() != null && !user.getFirstName().isBlank()) {
            sb.append(user.getFirstName().strip());
        }
        if (user.getLastName() != null && !user.getLastName().isBlank()) {
            if (!sb.isEmpty()) sb.append(' ');
            sb.append(user.getLastName().strip());
        }
        if (!sb.isEmpty()) return sb.toString();
        return (user.getUserName() != null && !user.getUserName().isBlank())
                ? user.getUserName().strip() : null;
    }

    /**
     * JCLAW-367: decide whether the bot is directly addressed by {@code msg}.
     * Uses entity offsets (never substring search) so {@code @handle}/{@code /cmd}
     * inside a URL, {@code code} span, or {@code text_link} can't false-positive.
     * Fires on any of:
     * <ul>
     *   <li>a {@code mention} entity whose {@code @handle} (case-insensitively)
     *       equals {@code botUsername};</li>
     *   <li>a {@code text_mention} entity whose embedded {@link User} id equals
     *       {@code botUserId};</li>
     *   <li>a {@code bot_command} entity carrying a {@code @botusername} suffix
     *       that matches {@code botUsername};</li>
     *   <li>a reply to a message authored by the bot itself (matched by
     *       {@code botUserId}, or — when the id is unknown — by the reply
     *       target being authored by any bot, the best-effort fallback).</li>
     * </ul>
     */
    private static boolean detectBotAddressed(Message msg, String botUsername, Long botUserId) {
        if (entitiesAddressBot(msg.getText(), msg.getEntities(), botUsername, botUserId)
                || entitiesAddressBot(msg.getCaption(), msg.getCaptionEntities(), botUsername, botUserId)) {
            return true;
        }
        return isReplyToBot(msg, botUserId);
    }

    /** Scan one text/entity pair for a mention, text_mention, or bot_command suffix addressing the bot. */
    private static boolean entitiesAddressBot(String body, java.util.List<MessageEntity> entities,
                                              String botUsername, Long botUserId) {
        if (body == null || entities == null) return false;
        for (var entity : entities) {
            if (entityAddressesBot(body, entity, botUsername, botUserId)) return true;
        }
        return false;
    }

    private static boolean entityAddressesBot(String body, MessageEntity entity,
                                              String botUsername, Long botUserId) {
        var type = entity.getType();
        if (type == null) return false;
        return switch (type) {
            case "mention" -> botUsername != null
                    && botUsername.equalsIgnoreCase(stripLeadingAt(entitySlice(body, entity)));
            case "text_mention" -> botUserId != null
                    && entity.getUser() != null
                    && botUserId.equals(entity.getUser().getId());
            case "bot_command" -> commandSuffixMatchesBot(entitySlice(body, entity), botUsername);
            default -> false;
        };
    }

    /**
     * Safe offset-based slice of the entity text. {@link MessageEntity#computeText}
     * is only populated by the SDK's getters in some shapes, so we slice from
     * the offset/length ourselves and clamp to the body bounds to stay robust
     * against malformed offsets.
     */
    private static String entitySlice(String body, MessageEntity entity) {
        if (entity.getOffset() == null || entity.getLength() == null) return "";
        int start = Math.max(0, entity.getOffset());
        int end = Math.min(body.length(), start + Math.max(0, entity.getLength()));
        return start <= end ? body.substring(start, end) : "";
    }

    private static String stripLeadingAt(String s) {
        return s.startsWith("@") ? s.substring(1) : s;
    }

    /**
     * A bot_command entity slice is shaped {@code /cmd} or {@code /cmd@botname}.
     * Returns true only when a {@code @suffix} is present AND matches the bot's
     * own username — a bare {@code /cmd} (no suffix) is not a direct address in
     * a group, so it does not fire the signal here.
     */
    private static boolean commandSuffixMatchesBot(String slice, String botUsername) {
        if (botUsername == null) return false;
        int at = slice.indexOf('@');
        if (at < 0) return false;
        return botUsername.equalsIgnoreCase(slice.substring(at + 1));
    }

    /**
     * True when {@code msg} replies to a message the bot itself authored. When
     * {@code botUserId} is known, match the reply target's author by id; when it
     * is unknown (single-arg parse), fall back to "the reply target was authored
     * by a bot" — best-effort, since in a 1:1 binding the only bot in the chat
     * is ours.
     */
    private static boolean isReplyToBot(Message msg, Long botUserId) {
        var replyTo = msg.getReplyToMessage();
        if (replyTo == null || replyTo.getFrom() == null) return false;
        var author = replyTo.getFrom();
        if (botUserId != null) return botUserId.equals(author.getId());
        return Boolean.TRUE.equals(author.getIsBot());
    }

    /**
     * Highest-resolution PhotoSize wins — the array is sorted ascending by
     * Telegram, so the last element is the full-quality original.
     */
    private static void collectPhotoAttachment(Message msg, java.util.List<PendingAttachment> attachments) {
        if (!msg.hasPhoto() || msg.getPhoto() == null || msg.getPhoto().isEmpty()) return;
        var sizes = msg.getPhoto();
        var best = sizes.get(sizes.size() - 1);
        long bytes = best.getFileSize() != null ? best.getFileSize() : 0L;
        attachments.add(new PendingAttachment(
                best.getFileId(), null, "image/jpeg", bytes,
                models.MessageAttachment.KIND_IMAGE));
    }

    /**
     * Voice notes (OGG Opus) and audio files (mp3/m4a/etc.) both map to
     * KIND_AUDIO. getMimeType is nullable — finalizeAttachment re-sniffs
     * with Tika so we're not relying on Telegram's self-report anyway.
     */
    private static void collectAudioAttachments(Message msg, java.util.List<PendingAttachment> attachments) {
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
    }

    /** Documents, videos, and video notes — all map to KIND_FILE, except image/* docs which stay KIND_IMAGE. */
    private static void collectFileAttachments(Message msg, java.util.List<PendingAttachment> attachments) {
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
    }

    private static String pickInboundText(Message msg) {
        if (msg.hasText()) return msg.getText();
        if (msg.getCaption() != null) return msg.getCaption();
        return "";
    }

    /**
     * JCLAW-366: stage a STATIC (non-animated, non-video) sticker's WEBP as an
     * image attachment so the model can see the picture, reusing the same
     * {@code getFile} download path as photos. Animated (TGS) and video (WEBM)
     * stickers are NOT staged — they aren't a still image the vision path can
     * consume, and we deliberately don't convert them — the
     * {@link #stickerNote(org.telegram.telegrambots.meta.api.objects.stickers.Sticker)}
     * placeholder is the only surfacing for those. Telegram's WEBP is sniffed
     * to {@code image/webp} on disk by {@code finalizeAttachment}; the reported
     * MIME here is a best-effort hint.
     */
    private static void collectStickerAttachment(Message msg, java.util.List<PendingAttachment> attachments) {
        if (!msg.hasSticker() || msg.getSticker() == null) return;
        var s = msg.getSticker();
        boolean animated = Boolean.TRUE.equals(s.getIsAnimated());
        boolean video = Boolean.TRUE.equals(s.getIsVideo());
        if (animated || video) return; // placeholder note only
        long bytes = s.getFileSize() != null ? s.getFileSize() : 0L;
        attachments.add(new PendingAttachment(
                s.getFileId(), null, "image/webp", bytes,
                models.MessageAttachment.KIND_IMAGE));
    }

    /**
     * JCLAW-366: build a text context note for the non-attachment "rich" inbound
     * types this story surfaces — sticker, location, venue. Returns "" when none
     * are present. A sticker yields {@code [sticker: 😀 (set X)]}; a venue yields
     * its title/address; a bare location yields its lat/long. Only one of these
     * is ever present on a given Message, but they're checked independently so a
     * future combined shape still degrades gracefully.
     */
    private static String inboundContextNote(Message msg) {
        var parts = new java.util.ArrayList<String>(1);
        if (msg.hasSticker() && msg.getSticker() != null) {
            parts.add(stickerNote(msg.getSticker()));
        }
        // Venue wraps a location, so prefer the richer venue note and skip the
        // bare-location branch when a venue is present.
        if (msg.getVenue() != null) {
            parts.add(venueNote(msg.getVenue()));
        } else if (msg.hasLocation() && msg.getLocation() != null) {
            parts.add(locationNote(msg.getLocation()));
        }
        return String.join("\n", parts);
    }

    /**
     * {@code [sticker: <emoji> (set <name>)]} — emoji and set name are both
     * optional on the Bot API object, so each is omitted when absent. A sticker
     * with neither degrades to a bare {@code [sticker]}.
     */
    private static String stickerNote(org.telegram.telegrambots.meta.api.objects.stickers.Sticker s) {
        var sb = new StringBuilder("[sticker");
        boolean hasEmoji = s.getEmoji() != null && !s.getEmoji().isBlank();
        if (hasEmoji) sb.append(": ").append(s.getEmoji().strip());
        if (s.getSetName() != null && !s.getSetName().isBlank()) {
            sb.append(hasEmoji ? " " : ": ").append("(set ").append(s.getSetName().strip()).append(')');
        }
        return sb.append(']').toString();
    }

    /** {@code [location: <lat>, <long>]} from the location's coordinates. */
    private static String locationNote(org.telegram.telegrambots.meta.api.objects.location.Location loc) {
        return "[location: %s, %s]".formatted(loc.getLatitude(), loc.getLongitude());
    }

    /**
     * {@code [venue: <title> — <address> (<lat>, <long>)]}. Title/address are
     * appended when present; the embedded location's coordinates ride in
     * parentheses when the venue carries a location (it always should).
     */
    private static String venueNote(org.telegram.telegrambots.meta.api.objects.Venue v) {
        var sb = new StringBuilder("[venue");
        if (v.getTitle() != null && !v.getTitle().isBlank()) sb.append(": ").append(v.getTitle().strip());
        if (v.getAddress() != null && !v.getAddress().isBlank()) sb.append(" — ").append(v.getAddress().strip());
        var loc = v.getLocation();
        if (loc != null) sb.append(" (%s, %s)".formatted(loc.getLatitude(), loc.getLongitude()));
        return sb.append(']').toString();
    }

    /**
     * JCLAW-366: build the "in reply to: …" supplemental context block, or null
     * when this message neither replies to another nor carries a native quote.
     * Prefers the native {@code quote} substring (the user explicitly selected
     * that span) over the full replied-to body. When neither text is available
     * but the replied-to message is media, notes the media type instead so the
     * agent still knows what was referenced.
     */
    private static String buildReplyContext(Message msg) {
        var quote = msg.getQuote();
        if (quote != null && quote.getText() != null && !quote.getText().isBlank()) {
            return "in reply to (quoted): " + quote.getText().strip();
        }
        var replyTo = msg.getReplyToMessage();
        if (replyTo == null) return null;
        String body = replyToText(replyTo);
        if (!body.isEmpty()) return "in reply to: " + body;
        String media = replyToMediaType(replyTo);
        if (media != null) return "in reply to: [" + media + "]";
        return null;
    }

    /** Text/caption of the replied-to message, trimmed; "" when it carries neither. */
    private static String replyToText(Message replyTo) {
        if (replyTo.hasText() && replyTo.getText() != null) return replyTo.getText().strip();
        if (replyTo.getCaption() != null && !replyTo.getCaption().isBlank()) return replyTo.getCaption().strip();
        return "";
    }

    /**
     * A short media-type label for a media-only replied-to message, or null when
     * it isn't one of the recognized media shapes. Used only when the replied-to
     * message has no text/caption of its own.
     */
    private static String replyToMediaType(Message replyTo) {
        if (replyTo.hasPhoto()) return "photo";
        if (replyTo.hasSticker()) return "sticker";
        if (replyTo.hasVoice()) return "voice";
        if (replyTo.hasAudio()) return "audio";
        if (replyTo.hasVideo()) return "video";
        if (replyTo.hasVideoNote()) return "video note";
        if (replyTo.hasDocument()) return "document";
        if (replyTo.getVenue() != null) return "venue";
        if (replyTo.hasLocation()) return "location";
        return null;
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
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
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
        return trySendPhoto(peerId, file, displayName, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware photo upload. {@code replyParams}
     * (null to omit) attaches {@code reply_parameters}; {@code messageThreadId}
     * (already General-stripped by the caller; null to omit) scopes the upload
     * to a forum topic. The no-extra-args {@link #trySendPhoto(String, java.io.File, String)}
     * overload preserves the legacy call sites. Delegates to the caption-aware
     * overload with a null caption.
     */
    public boolean trySendPhoto(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return trySendPhoto(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /**
     * JCLAW-364: caption-aware photo upload. {@code caption} (null/blank to
     * omit) rides as the photo's {@code caption} so prose adjacent to the file
     * reference arrives attached to the image instead of as a separate text
     * message. Other params as
     * {@link #trySendPhoto(String, java.io.File, String, ReplyParameters, Integer)}.
     */
    public boolean trySendPhoto(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendPhoto.builder()
                .chatId(peerId)
                .photo(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
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
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Photo sent to chat %s: %s (elapsedMs=%d, bytes=%d)"
                            .formatted(peerId, displayName, elapsedMs, fileSize));
            return true;
        } catch (TelegramApiException e) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
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
        return trySendDocument(peerId, file, displayName, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware document upload. Mirrors
     * {@link #trySendPhoto(String, java.io.File, String, ReplyParameters, Integer)} —
     * {@code replyParams} / {@code messageThreadId} (both null to omit) attach
     * {@code reply_parameters} / {@code message_thread_id}. The no-extra-args
     * overload preserves the legacy call sites. Delegates to the caption-aware
     * overload with a null caption.
     */
    public boolean trySendDocument(String peerId, java.io.File file, String displayName,
                                   ReplyParameters replyParams, Integer messageThreadId) {
        return trySendDocument(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /**
     * JCLAW-364: caption-aware document upload. {@code caption} (null/blank to
     * omit) rides as the document's {@code caption}. Other params as
     * {@link #trySendDocument(String, java.io.File, String, ReplyParameters, Integer)}.
     */
    public boolean trySendDocument(String peerId, java.io.File file, String displayName,
                                   ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendDocument.builder()
                .chatId(peerId)
                .document(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            // JCLAW-122: upload via uploadClient (60 s r/w) rather than client
            // (2 s w). Same rationale as trySendPhoto above — document bodies
            // are often larger than photos (PDFs, reports, zips).
            uploadClient.execute(request);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Document sent to chat %s: %s (elapsedMs=%d, bytes=%d)"
                            .formatted(peerId, displayName, elapsedMs, fileSize));
            return true;
        } catch (TelegramApiException e) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Document send failed for %s after %dms: %s"
                            .formatted(displayName, elapsedMs, e.getMessage()));
            return false;
        }
    }

    // ── JCLAW-364: native media send paths ──
    //
    // Each mirrors trySendPhoto/trySendDocument: a legacy 3-arg overload, a
    // JCLAW-369 reply/topic 5-arg overload (caption null), and a caption-aware
    // 6-arg overload that builds + uploads the request. All upload via
    // uploadClient (60 s r/w) — voice/audio/video bodies routinely exceed the
    // text-path timeouts. The execute call is inlined per method because the
    // SDK's TelegramClient exposes a distinct, concretely-typed execute()
    // overload per send class (no shared PartialBotApiMethod entry point).

    /** Upload {@code file} as a Telegram voice note (.ogg/opus). */
    public boolean trySendVoice(String peerId, java.io.File file, String displayName) {
        return trySendVoice(peerId, file, displayName, null, null);
    }

    /** Reply/topic-aware voice upload; delegates with a null caption. */
    public boolean trySendVoice(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return trySendVoice(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /** Caption-aware voice upload. {@code caption} null/blank to omit. */
    public boolean trySendVoice(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendVoice.builder()
                .chatId(peerId)
                .voice(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            uploadClient.execute(request);
            logMediaSent("Voice", peerId, displayName, startNs, fileSize);
            return true;
        } catch (TelegramApiException e) {
            logMediaFailed("Voice", displayName, startNs, e);
            return false;
        }
    }

    /** Upload {@code file} as a Telegram audio track (.mp3 and other audio). */
    public boolean trySendAudio(String peerId, java.io.File file, String displayName) {
        return trySendAudio(peerId, file, displayName, null, null);
    }

    /** Reply/topic-aware audio upload; delegates with a null caption. */
    public boolean trySendAudio(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return trySendAudio(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /** Caption-aware audio upload. {@code caption} null/blank to omit. */
    public boolean trySendAudio(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendAudio.builder()
                .chatId(peerId)
                .audio(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            uploadClient.execute(request);
            logMediaSent("Audio", peerId, displayName, startNs, fileSize);
            return true;
        } catch (TelegramApiException e) {
            logMediaFailed("Audio", displayName, startNs, e);
            return false;
        }
    }

    /** Upload {@code file} as a Telegram video. */
    public boolean trySendVideo(String peerId, java.io.File file, String displayName) {
        return trySendVideo(peerId, file, displayName, null, null);
    }

    /** Reply/topic-aware video upload; delegates with a null caption. */
    public boolean trySendVideo(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return trySendVideo(peerId, file, displayName, replyParams, messageThreadId, null);
    }

    /** Caption-aware video upload. {@code caption} null/blank to omit. */
    public boolean trySendVideo(String peerId, java.io.File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        var builder = SendVideo.builder()
                .chatId(peerId)
                .video(new InputFile(file, displayName != null ? displayName : file.getName()));
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        if (caption != null && !caption.isBlank()) builder.caption(caption);
        var request = builder.build();
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            uploadClient.execute(request);
            logMediaSent("Video", peerId, displayName, startNs, fileSize);
            return true;
        } catch (TelegramApiException e) {
            logMediaFailed("Video", displayName, startNs, e);
            return false;
        }
    }

    /**
     * JCLAW-365: bundle 2–10 photos/videos into a single Telegram album via
     * {@code sendMediaGroup}. Each item in {@code items} becomes an
     * {@link org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto}
     * (for {@link TelegramOutboundPlanner.MediaKind#PHOTO}) or
     * {@link org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo}
     * (everything else the caller passes — the planner only ever groups PHOTO
     * and VIDEO). The {@code caption} (null/blank to omit) rides on the FIRST
     * item only, matching Telegram's album-caption convention. {@code replyParams}
     * / {@code messageThreadId} (both null to omit) mirror the single-send
     * methods for JCLAW-369 reply/thread consistency.
     *
     * <p>Returns false (logged) on any API failure or an out-of-range item count
     * — never throws — so the caller can fall back to individual sends. Uploads
     * via {@link #uploadClient} (60 s r/w) like the other file paths.
     */
    public boolean sendMediaGroup(String peerId,
                                  java.util.List<TelegramOutboundPlanner.FileSegment> items,
                                  String caption, ReplyParameters replyParams, Integer messageThreadId) {
        if (items == null || items.size() < 2 || items.size() > 10) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendMediaGroup requires 2-10 items; got %d"
                            .formatted(items == null ? 0 : items.size()));
            return false;
        }
        var medias = new java.util.ArrayList<
                org.telegram.telegrambots.meta.api.objects.media.InputMedia>(items.size());
        for (int i = 0; i < items.size(); i++) {
            var fs = items.get(i);
            var file = fs.file();
            var name = fs.displayName() != null ? fs.displayName() : file.getName();
            // Caption rides the first item only — Telegram surfaces it as the
            // album caption. Subsequent items carry none.
            String itemCaption = i == 0 && caption != null && !caption.isBlank() ? caption : null;
            medias.add(buildInputMedia(fs.kind(), file, name, itemCaption));
        }
        var builder = org.telegram.telegrambots.meta.api.methods.send.SendMediaGroup.builder()
                .chatId(peerId)
                .medias(medias);
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        var request = builder.build();
        long startNs = System.nanoTime();
        try {
            uploadClient.execute(request);
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Media group sent to chat %s: %d items (elapsedMs=%d)"
                            .formatted(peerId, medias.size(), elapsedMs));
            return true;
        } catch (TelegramApiException e) {
            long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Media group send failed for %d items after %dms: %s"
                            .formatted(medias.size(), elapsedMs, e.getMessage()));
            return false;
        }
    }

    /**
     * Build the {@link org.telegram.telegrambots.meta.api.objects.media.InputMedia}
     * for one album item: a photo for {@link TelegramOutboundPlanner.MediaKind#PHOTO},
     * otherwise a video (the planner only groups photos + videos). The local file
     * is attached via {@code media(File, name)} so the SDK streams it in the
     * multipart body. {@code caption} null to omit.
     */
    private static org.telegram.telegrambots.meta.api.objects.media.InputMedia buildInputMedia(
            TelegramOutboundPlanner.MediaKind kind, java.io.File file, String name, String caption) {
        if (kind == TelegramOutboundPlanner.MediaKind.PHOTO) {
            var b = org.telegram.telegrambots.meta.api.objects.media.InputMediaPhoto.builder()
                    .media(file, name);
            if (caption != null) b.caption(caption);
            return b.build();
        }
        var b = org.telegram.telegrambots.meta.api.objects.media.InputMediaVideo.builder()
                .media(file, name);
        if (caption != null) b.caption(caption);
        return b.build();
    }

    /** Shared success-log for the native media send methods. {@code kind} labels the line. */
    private static void logMediaSent(String kind, String peerId, String displayName,
                                     long startNs, long fileSize) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                "%s sent to chat %s: %s (elapsedMs=%d, bytes=%d)"
                        .formatted(kind, peerId, displayName, elapsedMs, fileSize));
    }

    /** Shared failure-log for the native media send methods. */
    private static void logMediaFailed(String kind, String displayName,
                                       long startNs, TelegramApiException e) {
        long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                "%s send failed for %s after %dms: %s"
                        .formatted(kind, displayName, elapsedMs, e.getMessage()));
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        return trySend(peerId, text, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware single text send. {@code replyParams}
     * (null to omit) attaches {@code reply_parameters}; {@code messageThreadId}
     * (already General-stripped by the caller; null to omit) scopes the send to a
     * forum topic. The {@link Channel#trySend(String, String)} override delegates
     * here with both null so the interface contract is unchanged.
     */
    public SendResult trySend(String peerId, String text,
                              ReplyParameters replyParams, Integer messageThreadId) {
        try {
            executeTextSend(peerId, text, replyParams, messageThreadId, "HTML");
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Message sent to chat %s".formatted(peerId));
            return SendResult.OK;
        } catch (TelegramApiRequestException e) {
            var params = e.getParameters();
            if (params != null && params.getRetryAfter() != null && params.getRetryAfter() > 0) {
                int retryAfter = params.getRetryAfter();
                EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                        "Rate-limited; retry_after=%ds".formatted(retryAfter));
                return SendResult.rateLimited(retryAfter * 1000L);
            }
            // JCLAW-359: a 400 "can't parse entities" means the HTML payload was
            // malformed (a revoked/bad entity the formatter emitted). Retry the
            // SAME send once as plain text — no parse_mode — so the user gets the
            // content instead of nothing. Only return FAILED if the plain-text
            // retry also fails. Other 400s (and any non-parse request error) fall
            // straight through to FAILED with no retry, so a genuine bad request
            // can't spin.
            if (isParseEntitiesError(e)) {
                EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                        "HTML parse rejected; retrying as plain text: %s".formatted(e.getMessage()));
                return retryPlainText(peerId, text, replyParams, messageThreadId);
            }
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Telegram API error: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Send failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    /**
     * JCLAW-359: build + execute one text {@code sendMessage}. {@code parseMode}
     * null sends plain text (no {@code parse_mode}); a non-null value (e.g.
     * {@code "HTML"}) sets it. Link-preview suppression and the reply/topic params
     * ride along uniformly so the plain-text fallback retry is otherwise identical
     * to the rejected HTML send. Throws on any API failure for the caller to map.
     */
    private void executeTextSend(String peerId, String text, ReplyParameters replyParams,
                                 Integer messageThreadId, String parseMode) throws TelegramApiException {
        var builder = SendMessage.builder()
                .chatId(peerId)
                .text(text);
        if (parseMode != null) builder.parseMode(parseMode);
        if (replyParams != null) builder.replyParameters(replyParams);
        if (messageThreadId != null) builder.messageThreadId(messageThreadId);
        var linkPreview = linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        client.execute(builder.build());
    }

    /**
     * JCLAW-359: plain-text fallback after an HTML parse rejection. Re-sends the
     * same text with no {@code parse_mode}; returns {@link SendResult#OK} when the
     * retry lands, {@link SendResult#FAILED} otherwise (never recurses — a parse
     * error is impossible without {@code parse_mode}).
     */
    private SendResult retryPlainText(String peerId, String text,
                                      ReplyParameters replyParams, Integer messageThreadId) {
        try {
            executeTextSend(peerId, text, replyParams, messageThreadId, null);
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Plain-text fallback sent to chat %s".formatted(peerId));
            return SendResult.OK;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Plain-text fallback also failed: %s".formatted(e.getMessage()));
            return SendResult.FAILED;
        }
    }

    /**
     * JCLAW-369: reply/topic-aware mirror of {@link Channel#sendWithRetry(String, String)}.
     * The {@link Channel} default carries only (peerId, text), so the Telegram
     * text path needs its own single-retry wrapper to forward
     * {@code reply_parameters} + {@code message_thread_id} on both the first
     * attempt and the retry. Same back-off policy: the prior
     * {@link SendResult#retryAfterMs()} when non-zero, else 1 s, capped at 60 s,
     * scheduled on a platform-thread carrier (JDK-8373224). When both extra args
     * are null this is behaviorally identical to the inherited default.
     */
    boolean sendTextWithRetry(String chatId, String text,
                              ReplyParameters replyParams, Integer messageThreadId) {
        SendResult result = trySend(chatId, text, replyParams, messageThreadId);
        if (result.ok()) return true;
        long delayMs = Math.min(result.retryAfterMs() > 0 ? result.retryAfterMs() : 1000L, 60_000L);
        try {
            // 5 s slack covers the scheduler hop + the second trySend's own latency.
            boolean ok = utils.RetryScheduler.schedule(
                            () -> trySend(chatId, text, replyParams, messageThreadId).ok(), delayMs)
                    .get(delayMs + 5_000L, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (ok) return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException _) {
            // Fall through to the error-log branch below.
        }
        EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                "Failed to send message to %s after retries".formatted(chatId));
        return false;
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
        return sendMessageWithKeyboard(botToken, chatId, htmlText, keyboard, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware keyboard send. {@code replyToMessageId}
     * (null to omit) is applied per the {@link #replyToMode()} policy treating this
     * single message as the turn's first chunk ({@code off} → never; {@code first}
     * / {@code all} → applied, since there is exactly one message). {@code messageThreadId}
     * (null to omit) is General-stripped before being set. The four-arg overload
     * preserves the legacy call sites.
     */
    public static Integer sendMessageWithKeyboard(String botToken, String chatId,
                                                   String htmlText, InlineKeyboardMarkup keyboard,
                                                   Integer replyToMessageId, Integer messageThreadId) {
        var channel = forToken(botToken);
        var builder = SendMessage.builder()
                .chatId(chatId)
                .text(htmlText)
                .parseMode("HTML")
                .replyMarkup(keyboard);
        var reply = replyParamsFor(replyToMessageId, true);
        if (reply != null) builder.replyParameters(reply);
        var threadId = sendThreadId(messageThreadId);
        if (threadId != null) builder.messageThreadId(threadId);
        try {
            var msg = channel.client.execute(builder.build());
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
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "answerCallbackQuery failed: %s".formatted(e.getMessage()));
            return false;
        }
    }
}
