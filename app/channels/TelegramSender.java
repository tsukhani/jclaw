package channels;

import channels.Channel.SendResult;
import models.Agent;
import okhttp3.OkHttpClient;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.meta.api.methods.AnswerCallbackQuery;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.PinChatMessage;
import org.telegram.telegrambots.meta.api.methods.pinnedmessages.UnpinChatMessage;
import org.telegram.telegrambots.meta.api.methods.polls.SendPoll;
import org.telegram.telegrambots.meta.api.methods.reactions.SetMessageReaction;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.DeleteMessage;
import org.telegram.telegrambots.meta.api.objects.polls.input.InputPollOption;
import org.telegram.telegrambots.meta.api.methods.send.SendAudio;
import org.telegram.telegrambots.meta.api.methods.send.SendDocument;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.send.SendPhoto;
import org.telegram.telegrambots.meta.api.methods.send.SendVideo;
import org.telegram.telegrambots.meta.api.methods.send.SendVoice;
import org.telegram.telegrambots.meta.api.methods.updates.DeleteWebhook;
import org.telegram.telegrambots.meta.api.methods.updates.SetWebhook;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.InputFile;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionType;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import services.EventLogger;

import java.util.concurrent.TimeUnit;

/**
 * Per-token Telegram outbound engine (JCLAW-151 Phase C2, extracted from
 * {@code TelegramChannel}). Owns all per-instance state — the bound bot token,
 * the text + upload {@link TelegramClient}s, and the bot-sent-message-id cache —
 * plus all outbound send logic and the Bot-API one-shot helpers. The public
 * {@link TelegramChannel} adapter holds one of these (built in its constructors)
 * and delegates to it, so external callers keep talking to {@code TelegramChannel}
 * while the send machinery lives here.
 */
class TelegramSender {

    private static final String LOG_CATEGORY = "channel";
    /** Channel identifier used as logging source and returned by {@link #channelName()}. */
    private static final String CHANNEL_NAME = "telegram";

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

    // ── JCLAW-383: bot-sent-message-id cache ─────────────────────────────
    //
    // To make telegram.reactions.notify=own work in groups (where the reacted
    // message's author is NOT carried on the message_reaction update), we
    // remember the ids of messages THIS bot sent, per chat, so the reaction
    // gate can ask "was this message one of ours?". The cache is bounded two
    // ways so it can't grow without limit on a busy multi-chat bot:
    //   - at most {@link TelegramChannel#SENT_CHATS_CAP} chats are tracked (access-ordered
    //     LRU: the least-recently-touched chat is evicted first);
    //   - within each chat, at most {@link TelegramChannel#SENT_IDS_PER_CHAT_CAP} message ids
    //     are retained (insertion-ordered FIFO: the oldest id is evicted).
    // It populates only from sends this process made, so it is cold after a
    // restart — acceptable: a cold miss under-notifies (conservative), it
    // never over-notifies. See {@link #wasSentByBot}.

    /**
     * chatId → ring of recently bot-sent message ids in that chat. Outer map is
     * access-ordered (LRU on the chat key); each inner set is a bounded
     * insertion-ordered FIFO. All access is guarded by synchronizing on
     * {@code sentByChat} itself — sends and the reaction-gate read happen on
     * different threads.
     */
    private final java.util.LinkedHashMap<String, java.util.LinkedHashSet<Integer>> sentByChat =
            new java.util.LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<String, java.util.LinkedHashSet<Integer>> eldest) {
                    return size() > TelegramChannel.SENT_CHATS_CAP;
                }
            };

    TelegramSender(String botToken) {
        this(botToken, null);
    }

    TelegramSender(String botToken, org.telegram.telegrambots.meta.TelegramUrl urlOverride) {
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

    String botToken() { return botToken; }
    TelegramClient client() { return client; }

    // ── JCLAW-383: bot-sent-message-id cache record + query ──────────────

    /**
     * JCLAW-383: record that this bot sent {@code messageId} into {@code chatId},
     * so a later reaction on it can be recognized as a reaction on a bot message
     * (the signal {@code notify=own} needs in a group). No-op on null/blank chat
     * id or null message id. The per-chat ring is bounded to
     * {@link TelegramChannel#SENT_IDS_PER_CHAT_CAP} (oldest id evicted) and the number of chats
     * to {@link TelegramChannel#SENT_CHATS_CAP} (coldest chat evicted).
     */
    private void recordSentMessage(String chatId, Integer messageId) {
        if (chatId == null || chatId.isBlank() || messageId == null) return;
        synchronized (sentByChat) {
            var ring = sentByChat.computeIfAbsent(chatId, _ ->
                    new java.util.LinkedHashSet<>() {
                        @Override
                        public boolean add(Integer id) {
                            boolean added = super.add(id);
                            // Insertion-ordered FIFO: drop the oldest id once over cap.
                            if (size() > TelegramChannel.SENT_IDS_PER_CHAT_CAP) {
                                var it = iterator();
                                it.next();
                                it.remove();
                            }
                            return added;
                        }
                    });
            ring.add(messageId);
        }
    }

    /**
     * JCLAW-383: true when {@code messageId} in {@code chatId} is a message this
     * bot sent (and is still in the bounded cache). False on any null arg, a
     * never-seen chat, an evicted id, or a cold cache after a restart — a false
     * here makes the reaction gate under-notify (conservative), never
     * over-notify.
     */
    public boolean wasSentByBot(String chatId, Integer messageId) {
        if (chatId == null || messageId == null) return false;
        synchronized (sentByChat) {
            var ring = sentByChat.get(chatId);
            return ring != null && ring.contains(messageId);
        }
    }

    /**
     * JCLAW-383: test-only seam to populate the bot-sent-id cache directly,
     * mirroring {@link TelegramChannel#installForTest} / {@link TelegramChannel#clearForTest} — the
     * eviction-boundary tests would otherwise need hundreds of real HTTP sends
     * to reach the caps. Production never calls this; the real send paths feed
     * the cache via the private {@link #recordSentMessage}.
     */
    public void recordSentForTest(String chatId, Integer messageId) {
        recordSentMessage(chatId, messageId);
    }

    /**
     * Public helper for callers outside the {@code channels} package that
     * need to edit a specific Telegram message by id (e.g. JCLAW-95's
     * streaming-recovery job). Exceptions propagate so callers can decide
     * whether to retry or log-and-continue.
     */
    public void editMessageText(String chatId,
                                       Integer messageId, String text) throws TelegramApiException {
        var builder = org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text);
        var linkPreview = linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        client.execute(builder.build());
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
    public void setMyCommands(
            java.util.List<org.telegram.telegrambots.meta.api.objects.commands.BotCommand> commands) {
        if (botToken == null || commands == null || commands.isEmpty()) return;
        try {
            client.execute(
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
    public boolean setMessageReaction(String chatId, Integer messageId, String emoji) {
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
            client.execute(builder.build());
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
    public boolean pinChatMessage(String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        try {
            client.execute(PinChatMessage.builder()
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
    public boolean unpinChatMessage(String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        try {
            client.execute(UnpinChatMessage.builder()
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
    public boolean deleteMessage(String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        try {
            client.execute(DeleteMessage.builder()
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
     * JCLAW-387 (A3): send {@code text} as a reply to {@code replyToMessageId},
     * natively quoting the {@code quote} excerpt of the replied-to message via
     * {@code reply_parameters.quote}. The excerpt must be a verbatim substring of
     * the target message (matched after entity parsing by Telegram) or the Bot
     * API rejects the send with a 400 ({@code message to be replied not found} /
     * {@code QUOTE_NOT_FOUND}). To stay best-effort this method falls back to a
     * plain reply (same target, no quote) on a quote-related failure, so a
     * stale/mistyped excerpt never drops the message.
     *
     * <p>A blank/null {@code quote} is treated as "no excerpt" and routes through
     * the ordinary reply path ({@link #sendTurn}) so the absent-quote behavior is
     * exactly today's. The
     * reply target is attached unconditionally (the caller explicitly asked to
     * reply-with-quote) with {@code allow_sending_without_reply=true} so a since-
     * deleted target degrades to a plain send rather than 400-ing.
     *
     * <p>Returns true when the message landed (with or without the quote),
     * false when even the plain-reply fallback failed. Never throws.
     */
    public boolean sendReplyWithQuote(String chatId, String text,
                                             Agent agent, Integer replyToMessageId, String quote) {
        if (botToken == null || chatId == null || text == null || replyToMessageId == null) {
            return false;
        }
        var channel = this;
        // No excerpt → ordinary reply path; preserves today's exact behavior.
        if (quote == null || quote.isBlank()) {
            return channel.sendTurn(chatId, text, agent, replyToMessageId, null);
        }
        var quoteParams = ReplyParameters.builder()
                .messageId(replyToMessageId)
                .quote(quote)
                .allowSendingWithoutReply(true)
                .build();
        // First attempt: reply WITH the native quote excerpt.
        SendResult quoted = channel.trySend(chatId, TelegramMarkdownFormatter.toHtml(text), quoteParams, null);
        if (quoted.ok()) return true;
        // Best-effort fallback (JCLAW-387 A3): a quote that isn't a verbatim
        // substring of the target makes Telegram 400 the send. Retry once as a
        // plain reply (no quote) so the user still gets the message.
        EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                "Quote reply failed (excerpt may not match target); retrying as a plain reply");
        return channel.sendTurn(chatId, text, agent, replyToMessageId, null);
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
    public boolean sendPoll(String chatId, String question,
                                   java.util.List<String> options, Boolean isAnonymous,
                                   Boolean allowsMultipleAnswers, Integer openPeriod) {
        if (botToken == null || chatId == null || question == null || question.isBlank()
                || options == null || options.size() < 2 || options.size() > 12) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendPoll requires a non-blank question and 2-12 options; got %d option(s)"
                            .formatted(options == null ? 0 : options.size()));
            return false;
        }
        var pollOptions = new java.util.ArrayList<InputPollOption>(options.size());
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
            var sent = client.execute(builder.build());
            if (sent != null) recordSentMessage(chatId, sent.getMessageId()); // JCLAW-383
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Poll sent to chat %s: %d options".formatted(chatId, pollOptions.size()));
            return true;
        } catch (TelegramApiException e) {
            EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendPoll failed: %s".formatted(e.getMessage()));
            return false;
        }
    }

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
        return normalizeReplyMode(raw, REPLY_MODE_FIRST);
    }

    /** Normalize a raw reply-mode value (config or per-binding override) to a
     *  known constant, falling back to {@code fallback} on null/blank/unknown. */
    private static String normalizeReplyMode(String raw, String fallback) {
        if (raw == null) return fallback;
        var v = raw.trim().toLowerCase();
        return switch (v) {
            case REPLY_MODE_OFF, REPLY_MODE_FIRST, REPLY_MODE_ALL -> v;
            default -> fallback;
        };
    }

    /**
     * JCLAW-378: the effective reply mode for a bot token: the per-binding
     * {@link models.TelegramBinding#replyToMode} override when set (and valid),
     * otherwise the JVM-wide {@link #replyToMode()} config default. A blank /
     * unrecognized override is ignored and the config default applies. Public so
     * default-package tests can assert the override-wins / null-falls-back
     * contract, matching the {@code *ForTest} convention.
     */
    public static String effectiveReplyToMode(String botToken) {
        var override = models.TelegramBinding.overridesForToken(botToken).replyToMode();
        if (override == null || override.isBlank()) return replyToMode();
        return normalizeReplyMode(override, replyToMode());
    }

    /**
     * Build the {@link ReplyParameters} to apply on a given outbound chunk, or
     * null when none should be set. Honors the {@link #replyToMode()} policy:
     * {@code off} → never; {@code first} → only when {@code firstChunk}; {@code all}
     * → always (given a non-null target). {@code allow_sending_without_reply=true}
     * so a since-deleted target degrades to a plain send instead of a 400.
     */
    private static ReplyParameters replyParamsFor(Integer replyToMessageId, boolean firstChunk, String mode) {
        if (replyToMessageId == null) return null;
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
    static ReplyParameters replyParamsForSink(String botToken, Integer replyToMessageId) {
        return replyParamsFor(replyToMessageId, true, effectiveReplyToMode(botToken));
    }

    /** JCLAW-369: package-private bridge so the sink shares the General-topic strip rule. */
    static Integer sendThreadIdForSink(Integer messageThreadId) {
        return sendThreadId(messageThreadId);
    }

    /**
     * JCLAW-369: topic-aware typing action. When {@code messageThreadId} is
     * set the indicator is scoped to that forum topic — General (thread id 1)
     * INCLUDED, unlike sends, because the chat-action API accepts the General
     * thread id. Null preserves the non-topic behavior. Existing callers route
     * through {@link TelegramChannel#sendTypingAction(String, String)} (thread id null).
     */
    public TelegramChannel.TypingActionOutcome sendTypingAction(String chatId, Integer messageThreadId) {
        if (botToken == null || chatId == null) return TelegramChannel.TypingActionOutcome.SKIPPED;
        try {
            var builder = org.telegram.telegrambots.meta.api.methods.send.SendChatAction.builder()
                    .chatId(chatId)
                    .action("typing");
            if (messageThreadId != null) builder.messageThreadId(messageThreadId);
            client.execute(builder.build());
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

    /** Channel identifier (the "telegram" constant). */
    public String channelName() { return CHANNEL_NAME; }

    // ── Outbound sends ──

    /**
     * JCLAW-141: generic cross-channel text send (the {@link Channel} contract).
     * Delegates to the agent-aware planner path with no agent context, returning a
     * {@link SendResult} ({@code OK} when the whole turn landed, {@code FAILED}
     * otherwise). The token is the instance's bound token — no token argument.
     */
    public SendResult sendText(String peerId, String text) {
        return sendTurn(peerId, text, null, null, null) ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-141: agent-aware generic text send (the {@link Channel} contract).
     * {@link TelegramOutboundPlanner} uses the agent name to resolve
     * workspace-relative file links into native uploads, so passing the agent is
     * what makes prose, photo, prose sequences arrive as the agent composed them.
     */
    public SendResult sendText(String peerId, String text, Agent agent) {
        return sendTurn(peerId, text, agent, null, null) ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware outbound dispatch. Adds two
     * optional, nullable params over {@link #sendText(String, String, Agent)}:
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
     * <p>JCLAW-141: was the static {@code sendMessage(botToken, ...)} 6-arg entry
     * point; now an instance method on the per-binding channel (token bound at
     * construction). Distinct name from the generic {@link #sendText} interface
     * methods because it carries Telegram-specific reply/topic params and returns
     * the per-chunk boolean the streaming-sink / planner callers expect. Returns
     * true when the whole turn landed.
     */
    public boolean sendTurn(String chatId, String text, Agent agent,
                            Integer replyToMessageId, Integer messageThreadId) {
        if (chatId == null || text == null) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "sendTurn called with null argument");
            return false;
        }
        var segments = TelegramOutboundPlanner.plan(text, agent != null ? agent.name : null);
        if (segments.isEmpty()) return true; // nothing to send

        // JCLAW-369: track "first chunk of the turn" across all segments so the
        // `first` reply-mode applies the reply badge once, not once per segment.
        var firstChunk = new java.util.concurrent.atomic.AtomicBoolean(true);
        Integer threadId = sendThreadId(messageThreadId);
        // JCLAW-378: resolve the reply mode once per turn from the binding
        // override (?? config default) so every segment shares one decision.
        String mode = effectiveReplyToMode(botToken);
        boolean allOk = true;
        for (var segment : segments) {
            if (!dispatchSegment(this, chatId, segment, replyToMessageId, threadId, firstChunk, mode)) {
                allOk = false;
            }
        }
        return allOk;
    }

    /**
     * JCLAW-141: generic cross-channel photo send (the {@link Channel} contract).
     * Delegates to {@link #trySendPhoto(String, java.io.File, String, ReplyParameters,
     * Integer, String)} (no reply/topic context) so a caller routing through the
     * uniform interface still uploads via the dedicated upload client.
     */
    public SendResult sendPhoto(String peerId, java.io.File file, String caption) {
        return trySendPhoto(peerId, file, file != null ? file.getName() : null, null, null, caption)
                ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-141: generic cross-channel document send (the {@link Channel} contract).
     * Delegates to {@link #trySendDocument(String, java.io.File, String,
     * ReplyParameters, Integer, String)} (no reply/topic context).
     */
    public SendResult sendDocument(String peerId, java.io.File file, String caption) {
        return trySendDocument(peerId, file, file != null ? file.getName() : null, null, null, caption)
                ? SendResult.OK : SendResult.FAILED;
    }

    /** Dispatch one planner segment; returns false only when a foreground send actually fails. */
    private static boolean dispatchSegment(TelegramSender channel, String chatId,
                                           TelegramOutboundPlanner.Segment segment,
                                           Integer replyToMessageId, Integer threadId,
                                           java.util.concurrent.atomic.AtomicBoolean firstChunk,
                                           String mode) {
        if (segment instanceof TelegramOutboundPlanner.TextSegment(String markdown)) {
            return sendTextSegment(channel, chatId, markdown, replyToMessageId, threadId, firstChunk, mode);
        }
        if (segment instanceof TelegramOutboundPlanner.MediaGroupSegment mg) {
            return sendMediaGroupSegment(channel, chatId, mg, replyToMessageId, threadId, firstChunk, mode);
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
                                replyToMessageId, threadId, ownsFirst, mode));
                return true;
            }
            return sendFileSegment(channel, chatId, fs, replyToMessageId, threadId,
                    firstChunk.getAndSet(false), mode);
        }
        return true;
    }

    // Catches Throwable on purpose: this runs at a virtual-thread root, so an
    // unhandled Error (OOM in SDK, AssertionError, etc.) would kill the worker
    // silently and leak the chat's outbound queue. The reply text has already
    // been delivered by the time this fires, so late failures cannot regress
    // the turn's success — we just log and drop.
    @SuppressWarnings("java:S1181")
    private static void backgroundSendFile(TelegramSender channel, String chatId,
                                            TelegramOutboundPlanner.FileSegment fs,
                                            Integer replyToMessageId, Integer threadId,
                                            boolean firstChunk, String mode) {
        try {
            if (!sendFileSegment(channel, chatId, fs, replyToMessageId, threadId, firstChunk, mode)) {
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
     *
     * <p>JCLAW-387 (A1): when this segment splits into more than one chunk, a
     * {@code (n/m)} ordering marker is appended to each chunk via
     * {@link #withChunkMarker(String, int, int)} so the user can see the order.
     * A single-chunk segment gets nothing. The marker is plain ASCII on its own
     * trailing line — HTML-parse-safe under {@code parse_mode=HTML} — and the
     * chunker's 4000-char budget (vs Telegram's 4096 cap) leaves ample headroom
     * for the few extra characters.
     */
    private static boolean sendTextSegment(TelegramSender channel, String chatId, String markdown,
                                           Integer replyToMessageId, Integer threadId,
                                           java.util.concurrent.atomic.AtomicBoolean firstChunk,
                                           String mode) {
        if (markdown == null || markdown.isBlank()) return true;
        var html = TelegramMarkdownFormatter.toHtml(markdown);
        if (html.isBlank()) return true;
        var chunks = TelegramMarkdownFormatter.chunkHtml(html, CHUNK_BUDGET);
        boolean allOk = true;
        int total = chunks.size();
        for (int i = 0; i < total; i++) {
            String part = withChunkMarker(chunks.get(i), i + 1, total);
            boolean ownsFirst = firstChunk.getAndSet(false);
            var reply = replyParamsFor(replyToMessageId, ownsFirst, mode);
            if (!channel.sendTextWithRetry(chatId, part, reply, threadId)) allOk = false;
        }
        return allOk;
    }

    /**
     * JCLAW-387 (A1): per-chunk character budget handed to
     * {@link TelegramMarkdownFormatter#chunkHtml}. Held below Telegram's 4096
     * hard cap so the {@code (n/m)} ordering marker appended by
     * {@link #withChunkMarker} can never push a chunk over the limit (the marker
     * is at most a few characters).
     */
    static final int CHUNK_BUDGET = 4000;

    /**
     * JCLAW-387 (A1): append a {@code (n/m)} ordering marker to a chunk when the
     * reply was split into multiple messages ({@code total > 1}); a single-chunk
     * reply is returned unchanged. The marker is appended on its own trailing
     * line as plain ASCII text — it contains no HTML metacharacters, so it can't
     * break {@code parse_mode=HTML} parsing — and is tiny relative to the
     * {@link #CHUNK_BUDGET}-to-4096 headroom, so it never risks the 4096 cap.
     *
     * <p>Public so default-package tests can assert the marker contract directly,
     * matching the convention used by {@link #replyToMode()} /
     * {@link #suppressLinkPreview()}.
     */
    public static String withChunkMarker(String chunk, int index, int total) {
        if (total <= 1) return chunk;
        return chunk + "\n\n(" + index + "/" + total + ")";
    }

    /**
     * Dispatch a file segment through the native send method matching its
     * {@link TelegramOutboundPlanner.MediaKind} (JCLAW-364), forwarding the
     * planner-folded caption. Unknown types route through sendDocument.
     */
    private static boolean sendFileSegment(TelegramSender channel, String chatId,
                                            TelegramOutboundPlanner.FileSegment fs,
                                            Integer replyToMessageId, Integer threadId,
                                            boolean firstChunk, String mode) {
        var reply = replyParamsFor(replyToMessageId, firstChunk, mode);
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
    private static boolean sendMediaGroupSegment(TelegramSender channel, String chatId,
                                                 TelegramOutboundPlanner.MediaGroupSegment mg,
                                                 Integer replyToMessageId, Integer threadId,
                                                 java.util.concurrent.atomic.AtomicBoolean firstChunk,
                                                 String mode) {
        boolean ownsFirst = firstChunk.getAndSet(false);
        var reply = replyParamsFor(replyToMessageId, ownsFirst, mode);
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
            if (!sendFileSegment(channel, chatId, withCaption, replyToMessageId, threadId, itemFirst, mode)) {
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
    public boolean setWebhook(String url, String secretToken) {
        if (botToken == null || url == null) return false;
        var builder = SetWebhook.builder().url(url);
        if (secretToken != null) builder.secretToken(secretToken);
        try {
            client.execute(builder.build());
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
    public boolean deleteWebhook() {
        if (botToken == null) return false;
        try {
            client.execute(DeleteWebhook.builder().build());
            EventLogger.info(LOG_CATEGORY, null, CHANNEL_NAME, "Webhook deleted");
            return true;
        } catch (TelegramApiException e) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "Webhook deletion failed: %s".formatted(e.getMessage()));
            return false;
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
        // JCLAW-122: upload via uploadClient (60 s r/w) rather than client (2 s w)
        // — a 1–2 MB screenshot reliably times out on the text-path timeouts.
        return uploadVia("Photo", peerId, file, displayName, () -> uploadClient.execute(request));
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
        // JCLAW-122: upload via uploadClient (60 s r/w) — document bodies are
        // often larger than photos (PDFs, reports, zips).
        return uploadVia("Document", peerId, file, displayName, () -> uploadClient.execute(request));
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
        return uploadVia("Voice", peerId, file, displayName, () -> uploadClient.execute(request));
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
        return uploadVia("Audio", peerId, file, displayName, () -> uploadClient.execute(request));
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
        return uploadVia("Video", peerId, file, displayName, () -> uploadClient.execute(request));
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
            // sendMediaGroup returns a List<Message> — one per album item.
            var sent = uploadClient.execute(request);
            if (sent != null) {
                for (var m : sent) {
                    if (m != null) recordSentMessage(peerId, m.getMessageId()); // JCLAW-383
                }
            }
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

    /**
     * Upload call for the single-file trySend* paths. The SDK exposes a distinct
     * concretely-typed {@code execute()} per send class (no shared
     * {@code PartialBotApiMethod} entry point), so each caller supplies its own
     * {@code () -> uploadClient.execute(typedRequest)}; the checked
     * {@link TelegramApiException} is why this isn't a plain {@code Supplier}.
     */
    @FunctionalInterface
    private interface UploadCall {
        Message execute() throws TelegramApiException;
    }

    /**
     * JCLAW-408: shared upload tail for the five single-file trySend* methods —
     * times the upload, runs {@code exec}, records the sent message id for
     * reactions (JCLAW-383), and logs success/failure uniformly via
     * {@link #logMediaSent}/{@link #logMediaFailed}. Returns false (never throws)
     * on {@link TelegramApiException} so callers can fall back. Each trySend*
     * builds its typed request (chat/file/reply/thread/caption) and passes the
     * execute lambda here.
     */
    private boolean uploadVia(String kind, String peerId, java.io.File file,
                              String displayName, UploadCall exec) {
        long startNs = System.nanoTime();
        long fileSize = file.length();
        try {
            var sent = exec.execute();
            if (sent != null) recordSentMessage(peerId, sent.getMessageId()); // JCLAW-383
            logMediaSent(kind, peerId, displayName, startNs, fileSize);
            return true;
        } catch (TelegramApiException e) {
            logMediaFailed(kind, displayName, startNs, e);
            return false;
        }
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
        var sent = client.execute(builder.build());
        // JCLAW-383: remember the id so notify=own recognizes a later group
        // reaction on this message as a reaction on a bot message.
        if (sent != null) recordSentMessage(peerId, sent.getMessageId());
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
    public Integer sendMessageWithKeyboard(String chatId,
                                            String htmlText, InlineKeyboardMarkup keyboard) {
        return sendMessageWithKeyboard(chatId, htmlText, keyboard, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware keyboard send. {@code replyToMessageId}
     * (null to omit) is applied per the {@link #replyToMode()} policy treating this
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
    public Integer sendMessageWithKeyboard(String chatId,
                                           String htmlText, InlineKeyboardMarkup keyboard,
                                           Integer replyToMessageId, Integer messageThreadId) {
        var builder = SendMessage.builder()
                .chatId(chatId)
                .text(htmlText)
                .parseMode("HTML")
                .replyMarkup(keyboard);
        var reply = replyParamsFor(replyToMessageId, true, effectiveReplyToMode(botToken));
        if (reply != null) builder.replyParameters(reply);
        var threadId = sendThreadId(messageThreadId);
        if (threadId != null) builder.messageThreadId(threadId);
        var linkPreview = linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        try {
            var msg = client.execute(builder.build());
            recordSentMessage(chatId, msg.getMessageId()); // JCLAW-383
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
    public boolean editMessageText(String chatId, Integer messageId,
                                           String htmlText, InlineKeyboardMarkup keyboard) {
        var channel = this;
        var builder = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(htmlText)
                .parseMode("HTML");
        if (keyboard != null) builder.replyMarkup(keyboard);
        var linkPreview = linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
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
    public boolean answerCallbackQuery(String callbackId,
                                               String text, boolean showAlert) {
        var channel = this;
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
