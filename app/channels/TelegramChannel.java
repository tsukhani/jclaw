package channels;

import com.google.gson.JsonObject;
import models.Agent;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;
import services.AttachmentService;

import java.io.File;
import java.util.List;

/**
 * Telegram Bot API adapter backed by the {@code org.telegram:telegrambots-client} SDK.
 * Post-JCLAW-89 each operator-managed bot token has its own {@link TelegramChannel}
 * instance (cached in {@link TelegramClientCache}) so send paths carry the correct token
 * without a global "current bot" lookup. Instances are created on demand via
 * {@link #forToken(String)} and evicted via {@link #evictToken(String)} when the
 * polling runner unregisters a binding.
 *
 * <p>JCLAW-141: each instance is per-binding (the bot token is bound at
 * construction), so the generic {@link Channel} send methods — {@link #sendText},
 * {@link #sendPhoto}, {@link #sendDocument} — carry the token implicitly and need
 * no token argument. Dispatch resolves the instance via
 * {@link ChannelRegistry#forConversation} (which looks the binding up from the
 * conversation's (agent, peerId) pair) or {@link #forToken(String)} directly,
 * then calls the interface methods — no per-type branching.
 *
 * <p>JCLAW-151 (Phase C2): the per-token outbound engine lives in
 * {@link TelegramSender}. This class is a thin adapter holding one
 * {@link TelegramSender} (built in its constructors) and delegating every
 * outbound + Bot-API call to it.
 */
public class TelegramChannel implements Channel {

    private final TelegramSender sender;

    TelegramChannel(String botToken) {
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
    TelegramChannel(String botToken, TelegramUrl urlOverride) {
        this.sender = new TelegramSender(botToken, urlOverride);
    }

    /** Resolve (or lazily create) the singleton for {@code botToken}.
     *  Delegates to {@link TelegramClientCache#forToken} (JCLAW-151). */
    public static TelegramChannel forToken(String botToken) {
        return TelegramClientCache.forToken(botToken);
    }

    /** Test-only: pre-install a mock-targeted instance. Delegates to
     *  {@link TelegramClientCache#installForTest} (JCLAW-151). */
    public static void installForTest(String botToken,
                                       TelegramUrl urlOverride) {
        TelegramClientCache.installForTest(botToken, urlOverride);
    }

    /** Test-only: reset the per-token cache. Delegates to
     *  {@link TelegramClientCache#clearForTest} (JCLAW-151). */
    public static void clearForTest(String botToken) {
        TelegramClientCache.clearForTest(botToken);
    }

    /** Drop the cached instance for a token. Delegates to
     *  {@link TelegramClientCache#evictToken} (JCLAW-151). */
    public static void evictToken(String botToken) {
        TelegramClientCache.evictToken(botToken);
    }

    public String botToken() { return sender.botToken(); }
    TelegramClient client() { return sender.client(); }

    // ── JCLAW-383: bot-sent-message-id cache record + query ──────────────
    //
    // To make telegram.reactions.notify=own work in groups (where the reacted
    // message's author is NOT carried on the message_reaction update), we
    // remember the ids of messages THIS bot sent, per chat, so the reaction
    // gate can ask "was this message one of ours?". The cache is bounded two
    // ways so it can't grow without limit on a busy multi-chat bot:
    //   - at most {@link #SENT_CHATS_CAP} chats are tracked (access-ordered
    //     LRU: the least-recently-touched chat is evicted first);
    //   - within each chat, at most {@link #SENT_IDS_PER_CHAT_CAP} message ids
    //     are retained (insertion-ordered FIFO: the oldest id is evicted).
    // It populates only from sends this process made, so it is cold after a
    // restart — acceptable: a cold miss under-notifies (conservative), it
    // never over-notifies. See {@link #wasSentByBot}.

    /** Max number of distinct chats tracked before LRU eviction of the coldest chat. */
    public static final int SENT_CHATS_CAP = 256;
    /** Max bot-sent message ids retained per chat before FIFO eviction of the oldest. */
    public static final int SENT_IDS_PER_CHAT_CAP = 512;

    /**
     * JCLAW-384: package-private static bridge for {@link TelegramStreamingSink}.
     * The streamed reply bubble is sent by the sink (its placeholder send +
     * edits + final), NOT through this class's direct send methods, so without
     * this entrypoint the streamed reply — the common case — never lands in the
     * bot-sent-id cache and {@code notify=own} under-notifies on it. Resolves the
     * per-token instance and feeds the bot-sent-id cache so the
     * sink's reply message id is treated identically to a direct send. Null-safe:
     * a null/blank token, null/blank chat id, or null message id is a no-op.
     */
    static void recordSentMessage(String botToken, String chatId, Integer messageId) {
        if (botToken == null || botToken.isBlank()) return;
        forToken(botToken).recordSentForTest(chatId, messageId);
    }

    /**
     * JCLAW-383: true when {@code messageId} in {@code chatId} is a message this
     * bot sent (and is still in the bounded cache). False on any null arg, a
     * never-seen chat, an evicted id, or a cold cache after a restart — a false
     * here makes the reaction gate under-notify (conservative), never
     * over-notify. Static convenience that resolves the per-token instance for
     * callers (e.g. {@link TelegramPollingRunner#handleReaction}) that hold the
     * bot token.
     */
    public static boolean wasSentByBot(String botToken, String chatId, Integer messageId) {
        if (botToken == null || botToken.isBlank()) return false;
        // Read-only lookup: reuse the existing instance only. Do NOT forToken(...) —
        // that constructs a TelegramChannel (+ OkHttp clients) for an unknown/garbled
        // token, contradicting the "false on a never-seen token" contract.
        var ch = TelegramClientCache.peek(botToken);
        return ch != null && ch.wasSentByBot(chatId, messageId);
    }

    /** Instance form of {@link #wasSentByBot(String, String, Integer)}. */
    public boolean wasSentByBot(String chatId, Integer messageId) {
        return sender.wasSentByBot(chatId, messageId);
    }

    /**
     * JCLAW-383: test-only seam to populate the bot-sent-id cache directly,
     * mirroring {@link #installForTest} / {@link #clearForTest} — the
     * eviction-boundary tests would otherwise need hundreds of real HTTP sends
     * to reach the caps. Production never calls this; the real send paths feed
     * the cache.
     */
    public void recordSentForTest(String chatId, Integer messageId) {
        sender.recordSentForTest(chatId, messageId);
    }

    /**
     * Public helper for callers outside the {@code channels} package that
     * need to edit a specific Telegram message by id (e.g. JCLAW-95's
     * streaming-recovery job). Exceptions propagate so callers can decide
     * whether to retry or log-and-continue.
     */
    public static void editMessageText(String botToken, String chatId,
                                       Integer messageId, String text) throws TelegramApiException {
        forToken(botToken).sender.editMessageText(chatId, messageId, text);
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
            List<BotCommand> commands) {
        if (botToken == null || commands == null || commands.isEmpty()) return;
        forToken(botToken).sender.setMyCommands(commands);
    }

    // ── JCLAW-364: dormant send primitives (consumed by JCLAW-374/375) ──

    /**
     * JCLAW-364: set (or clear) the bot's reaction on a message. A non-blank
     * {@code emoji} sets a single reaction; a {@code null}/blank emoji sends an
     * empty reaction list, which clears any reaction the bot previously placed.
     * Returns false (logged) on any API failure — never throws. Dormant: no
     * caller yet (JCLAW-374).
     */
    public static boolean setMessageReaction(String botToken, String chatId, Integer messageId, String emoji) {
        if (botToken == null || chatId == null || messageId == null) return false;
        return forToken(botToken).sender.setMessageReaction(chatId, messageId, emoji);
    }

    /**
     * JCLAW-364: pin {@code messageId} in {@code chatId}. Returns false (logged)
     * on any API failure — never throws. Dormant: no caller yet (JCLAW-375).
     */
    public static boolean pinChatMessage(String botToken, String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        return forToken(botToken).sender.pinChatMessage(chatId, messageId);
    }

    /**
     * JCLAW-364: unpin {@code messageId} in {@code chatId}. Returns false
     * (logged) on any API failure — never throws. Dormant: no caller yet
     * (JCLAW-375).
     */
    public static boolean unpinChatMessage(String botToken, String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        return forToken(botToken).sender.unpinChatMessage(chatId, messageId);
    }

    /**
     * JCLAW-374: delete {@code messageId} from {@code chatId}. Returns false
     * (logged) on any API failure — never throws.
     */
    public static boolean deleteMessage(String botToken, String chatId, Integer messageId) {
        if (botToken == null || chatId == null || messageId == null) return false;
        return forToken(botToken).sender.deleteMessage(chatId, messageId);
    }

    /**
     * JCLAW-387 (A3): send {@code text} as a reply to {@code replyToMessageId},
     * natively quoting the {@code quote} excerpt of the replied-to message via
     * {@code reply_parameters.quote}. Falls back to a plain reply on a
     * quote-related failure. Returns true when the message landed (with or
     * without the quote), false when even the plain-reply fallback failed. Never
     * throws.
     */
    public static boolean sendReplyWithQuote(String botToken, String chatId, String text,
                                             Agent agent, Integer replyToMessageId, String quote) {
        if (botToken == null || chatId == null || text == null || replyToMessageId == null) {
            return false;
        }
        return forToken(botToken).sender.sendReplyWithQuote(chatId, text, agent, replyToMessageId, quote);
    }

    /**
     * JCLAW-387 (C1): send a native Telegram poll to {@code chatId}. Mirrors the
     * swallow-and-log contract of the other send primitives: returns false
     * (logged at warn) on any API failure or out-of-range option count — never
     * throws.
     */
    public static boolean sendPoll(String botToken, String chatId, String question,
                                   List<String> options, Boolean isAnonymous,
                                   Boolean allowsMultipleAnswers, Integer openPeriod) {
        if (botToken == null) return false;
        return forToken(botToken).sender.sendPoll(chatId, question, options, isAnonymous,
                allowsMultipleAnswers, openPeriod);
    }

    /**
     * Telegram Bot API base URL used by the raw-HTTP helpers. Package-visible
     * so tests targeting {@code MockTelegramServer} can redirect traffic
     * without touching the SDK's {@code TelegramUrl} override (which only
     * affects {@code OkHttpTelegramClient} calls).
     */
    public static String TELEGRAM_API_BASE = "https://api.telegram.org";

    // ── JCLAW-369: outbound reply targeting + topic-aware sends ──────────

    /**
     * JCLAW-359: true when link previews should be suppressed on outbound text
     * sends ({@code telegram.linkPreview = off}). Public so default-package tests
     * can assert the config-read contract, mirroring {@link #replyToMode()}.
     */
    public static boolean suppressLinkPreview() {
        return TelegramSender.suppressLinkPreview();
    }

    /**
     * Resolve the configured reply mode, normalizing unknown/blank values to
     * {@code first}. Public so default-package tests can assert the config-read
     * contract (matches the {@code *ForTest} convention used elsewhere for
     * test-reachable surface).
     */
    public static String replyToMode() {
        return TelegramSender.replyToMode();
    }

    /**
     * JCLAW-378: the effective reply mode for a bot token: the per-binding
     * {@link models.TelegramBinding#replyToMode} override when set (and valid),
     * otherwise the JVM-wide {@link #replyToMode()} config default.
     */
    public static String effectiveReplyToMode(String botToken) {
        return TelegramSender.effectiveReplyToMode(botToken);
    }

    /**
     * JCLAW-369: package-private bridge for {@link TelegramStreamingSink}. The
     * streaming placeholder is the turn's first (and only live) message, so the
     * sink always evaluates the reply policy as the first chunk. Returns null
     * when no badge should be applied ({@code off}, or a null target).
     */
    static ReplyParameters replyParamsForSink(String botToken, Integer replyToMessageId) {
        return TelegramSender.replyParamsForSink(botToken, replyToMessageId);
    }

    /** JCLAW-369: package-private bridge so the sink shares the General-topic strip rule. */
    static Integer sendThreadIdForSink(Integer messageThreadId) {
        return TelegramSender.sendThreadIdForSink(messageThreadId);
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
    public static TypingActionOutcome sendTypingAction(String botToken, String chatId, Integer messageThreadId) {
        if (botToken == null || chatId == null) return TypingActionOutcome.SKIPPED;
        return forToken(botToken).sender.sendTypingAction(chatId, messageThreadId);
    }

    /**
     * JCLAW-342: outcome of a {@link #sendTypingAction} call, so the streaming
     * sink's typing heartbeat can stop re-firing after repeated 401s instead of
     * spamming {@code sendChatAction} every tick for a revoked/invalid token.
     */
    public enum TypingActionOutcome { SENT, UNAUTHORIZED, FAILED, SKIPPED }

    @Override
    public String channelName() { return sender.channelName(); }

    // ── Outbound sends ──

    /**
     * JCLAW-141: generic cross-channel text send (the {@link Channel} contract).
     * Delegates to the agent-aware planner path with no agent context, returning a
     * {@link SendResult} ({@code OK} when the whole turn landed, {@code FAILED}
     * otherwise). The token is the instance's bound token — no token argument.
     */
    @Override
    public SendResult sendText(String peerId, String text) {
        return sender.sendText(peerId, text);
    }

    /**
     * JCLAW-141: agent-aware generic text send (the {@link Channel} contract).
     * {@link TelegramOutboundPlanner} uses the agent name to resolve
     * workspace-relative file links into native uploads, so passing the agent is
     * what makes prose, photo, prose sequences arrive as the agent composed them.
     */
    @Override
    public SendResult sendText(String peerId, String text, Agent agent) {
        return sender.sendText(peerId, text, agent);
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
     *   <li>{@code messageThreadId} — when non-null and not the General topic,
     *       scopes every send to that forum topic; General is omitted (a bare
     *       send already lands there).</li>
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
        return sender.sendTurn(chatId, text, agent, replyToMessageId, messageThreadId);
    }

    /**
     * JCLAW-141: generic cross-channel photo send (the {@link Channel} contract).
     * Delegates to {@link #trySendPhoto(String, java.io.File, String, ReplyParameters,
     * Integer, String)} (no reply/topic context) so a caller routing through the
     * uniform interface still uploads via the dedicated upload client.
     */
    @Override
    public SendResult sendPhoto(String peerId, File file, String caption) {
        return sender.sendPhoto(peerId, file, caption);
    }

    /**
     * JCLAW-141: generic cross-channel document send (the {@link Channel} contract).
     * Delegates to {@link #trySendDocument(String, java.io.File, String,
     * ReplyParameters, Integer, String)} (no reply/topic context).
     */
    @Override
    public SendResult sendDocument(String peerId, File file, String caption) {
        return sender.sendDocument(peerId, file, caption);
    }

    /**
     * JCLAW-387 (A1): append a {@code (n/m)} ordering marker to a chunk when the
     * reply was split into multiple messages ({@code total > 1}); a single-chunk
     * reply is returned unchanged.
     *
     * <p>Public so default-package tests can assert the marker contract directly,
     * matching the convention used by {@link #replyToMode()} /
     * {@link #suppressLinkPreview()}.
     */
    public static String withChunkMarker(String chunk, int index, int total) {
        return TelegramSender.withChunkMarker(chunk, index, total);
    }

    /**
     * Split {@code text} into chunks at most {@code maxLen} characters long, biasing
     * breaks toward paragraph → line → word boundaries before a hard cut. Markdown
     * formatting that spans a chunk boundary may render awkwardly — acceptable for an
     * MVP chunker; a per-channel formatter is the cleaner long-term fix.
     */
    public static List<String> chunk(String text, int maxLen) {
        return TelegramSender.chunk(text, maxLen);
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
        return forToken(botToken).sender.setWebhook(url, secretToken);
    }

    /**
     * JCLAW-339: clear any webhook registered for {@code botToken} so Telegram
     * stops POSTing and long-poll {@code getUpdates} is allowed again (Telegram
     * 409s while a webhook is set). Idempotent — a no-op when none is
     * registered. Returns false on API error (logged).
     */
    public static boolean deleteWebhook(String botToken) {
        if (botToken == null) return false;
        return forToken(botToken).sender.deleteWebhook();
    }

    // ── Inbound parsing — delegates to TelegramInboundParser (JCLAW-151) ──

    /** @see TelegramInboundParser#prepareInboundAttachments */
    public static List<AttachmentService.Input> prepareInboundAttachments(
            String sendToken, String sendChatId, Agent sendAgent, InboundMessage message) {
        return TelegramInboundParser.prepareInboundAttachments(sendToken, sendChatId, sendAgent, message);
    }

    /** @see TelegramInboundParser#parseUpdate(JsonObject) */
    public static InboundMessage parseUpdate(JsonObject update) {
        return TelegramInboundParser.parseUpdate(update);
    }

    /** @see TelegramInboundParser#parseUpdate(Update) */
    public static InboundMessage parseUpdate(Update update) {
        return TelegramInboundParser.parseUpdate(update);
    }

    /** @see TelegramInboundParser#parseUpdate(Update, String, Long) */
    public static InboundMessage parseUpdate(Update update, String botUsername, Long botUserId) {
        return TelegramInboundParser.parseUpdate(update, botUsername, botUserId);
    }

    /** @see TelegramInboundParser#matchesWakeWord(String) */
    public static boolean matchesWakeWord(String body) {
        return TelegramInboundParser.matchesWakeWord(body);
    }

    /** @see TelegramInboundParser#parseCallback(Update) */
    public static InboundCallback parseCallback(Update update) {
        return TelegramInboundParser.parseCallback(update);
    }

    /** @see TelegramInboundParser#parseCallback(JsonObject) */
    public static InboundCallback parseCallback(JsonObject update) {
        return TelegramInboundParser.parseCallback(update);
    }

    // ── Per-instance send path ──

    /**
     * Upload {@code file} as a Telegram photo. The {@code displayName} is shown
     * to the user as the filename hint; Telegram renders images inline, so
     * captions aren't used in this MVP — prose accompanying the photo arrives as
     * a separate text message above or below it.
     */
    public boolean trySendPhoto(String peerId, File file, String displayName) {
        return sender.trySendPhoto(peerId, file, displayName);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware photo upload. {@code replyParams}
     * (null to omit) attaches {@code reply_parameters}; {@code messageThreadId}
     * (already General-stripped by the caller; null to omit) scopes the upload
     * to a forum topic. The no-extra-args {@link #trySendPhoto(String, java.io.File, String)}
     * overload preserves the legacy call sites. Delegates to the caption-aware
     * overload with a null caption.
     */
    public boolean trySendPhoto(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return sender.trySendPhoto(peerId, file, displayName, replyParams, messageThreadId);
    }

    /**
     * JCLAW-364: caption-aware photo upload. {@code caption} (null/blank to
     * omit) rides as the photo's {@code caption} so prose adjacent to the file
     * reference arrives attached to the image instead of as a separate text
     * message. Other params as
     * {@link #trySendPhoto(String, java.io.File, String, ReplyParameters, Integer)}.
     */
    public boolean trySendPhoto(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return sender.trySendPhoto(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /**
     * Upload {@code file} as a Telegram document (download attachment). Covers
     * anything that isn't one of the image extensions Telegram renders inline.
     */
    public boolean trySendDocument(String peerId, File file, String displayName) {
        return sender.trySendDocument(peerId, file, displayName);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware document upload. Mirrors
     * {@link #trySendPhoto(String, java.io.File, String, ReplyParameters, Integer)} —
     * {@code replyParams} / {@code messageThreadId} (both null to omit) attach
     * {@code reply_parameters} / {@code message_thread_id}. The no-extra-args
     * overload preserves the legacy call sites. Delegates to the caption-aware
     * overload with a null caption.
     */
    public boolean trySendDocument(String peerId, File file, String displayName,
                                   ReplyParameters replyParams, Integer messageThreadId) {
        return sender.trySendDocument(peerId, file, displayName, replyParams, messageThreadId);
    }

    /**
     * JCLAW-364: caption-aware document upload. {@code caption} (null/blank to
     * omit) rides as the document's {@code caption}. Other params as
     * {@link #trySendDocument(String, java.io.File, String, ReplyParameters, Integer)}.
     */
    public boolean trySendDocument(String peerId, File file, String displayName,
                                   ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return sender.trySendDocument(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    // ── JCLAW-364: native media send paths ──

    /** Upload {@code file} as a Telegram voice note (.ogg/opus). */
    public boolean trySendVoice(String peerId, File file, String displayName) {
        return sender.trySendVoice(peerId, file, displayName);
    }

    /** Reply/topic-aware voice upload; delegates with a null caption. */
    public boolean trySendVoice(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return sender.trySendVoice(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** Caption-aware voice upload. {@code caption} null/blank to omit. */
    public boolean trySendVoice(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return sender.trySendVoice(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /** Upload {@code file} as a Telegram audio track (.mp3 and other audio). */
    public boolean trySendAudio(String peerId, File file, String displayName) {
        return sender.trySendAudio(peerId, file, displayName);
    }

    /** Reply/topic-aware audio upload; delegates with a null caption. */
    public boolean trySendAudio(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return sender.trySendAudio(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** Caption-aware audio upload. {@code caption} null/blank to omit. */
    public boolean trySendAudio(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return sender.trySendAudio(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /** Upload {@code file} as a Telegram video. */
    public boolean trySendVideo(String peerId, File file, String displayName) {
        return sender.trySendVideo(peerId, file, displayName);
    }

    /** Reply/topic-aware video upload; delegates with a null caption. */
    public boolean trySendVideo(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return sender.trySendVideo(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** Caption-aware video upload. {@code caption} null/blank to omit. */
    public boolean trySendVideo(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return sender.trySendVideo(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /**
     * JCLAW-365: bundle 2–10 photos/videos into a single Telegram album via
     * {@code sendMediaGroup}. The {@code caption} (null/blank to omit) rides on
     * the FIRST item only, matching Telegram's album-caption convention.
     * {@code replyParams} / {@code messageThreadId} (both null to omit) mirror the
     * single-send methods for JCLAW-369 reply/thread consistency.
     *
     * <p>Returns false (logged) on any API failure or an out-of-range item count
     * — never throws — so the caller can fall back to individual sends.
     */
    public boolean sendMediaGroup(String peerId,
                                  List<TelegramOutboundPlanner.FileSegment> items,
                                  String caption, ReplyParameters replyParams, Integer messageThreadId) {
        return sender.sendMediaGroup(peerId, items, caption, replyParams, messageThreadId);
    }

    @Override
    public SendResult trySend(String peerId, String text) {
        return sender.trySend(peerId, text);
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
        return sender.trySend(peerId, text, replyParams, messageThreadId);
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
        return sender.sendMessageWithKeyboard(chatId, htmlText, keyboard);
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
        return sender.sendMessageWithKeyboard(chatId, htmlText, keyboard, replyToMessageId, messageThreadId);
    }

    /**
     * Edit an existing message in-place, optionally attaching a new
     * inline keyboard (or null to clear it). Used by the callback
     * dispatcher to drill down / return without cluttering the chat
     * with a new message per tap.
     */
    public static boolean editMessageText(String botToken, String chatId, Integer messageId,
                                           String htmlText, InlineKeyboardMarkup keyboard) {
        return forToken(botToken).sender.editMessageText(chatId, messageId, htmlText, keyboard);
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
        return forToken(botToken).sender.answerCallbackQuery(callbackId, text, showAlert);
    }
}
