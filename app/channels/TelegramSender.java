package channels;

import channels.Channel.SendResult;
import models.Agent;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.api.objects.commands.BotCommand;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.io.File;
import java.util.List;

/**
 * Per-token Telegram outbound engine (JCLAW-151 Phase C2, extracted from
 * {@code TelegramChannel}). Owns the per-instance state — the bound bot token,
 * the text + upload {@link TelegramClient}s, and the bot-sent-message-id cache —
 * and hands them, via a shared {@link TelegramSendContext}, to four cohesive
 * send collaborators it delegates to (JCLAW-724):
 *
 * <ul>
 *   <li>{@link TelegramMessageSender} — plain-text turns, the reply/topic planner
 *       dispatch, quote replies, the low-level send + retry, and the plain edit;</li>
 *   <li>{@link TelegramMediaSender} — every native file/album upload;</li>
 *   <li>{@link TelegramAdminSender} — commands, reactions, pin/unpin/delete,
 *       webhooks, the typing indicator, and polls;</li>
 *   <li>{@link TelegramKeyboardSender} — inline-keyboard sends/edits and callback
 *       acknowledgements.</li>
 * </ul>
 *
 * <p>This class stays the single public send facade: the {@link TelegramChannel}
 * adapter holds one of these (built in its constructors) and delegates to it, so
 * external callers keep talking to {@code TelegramChannel} while the send
 * machinery lives in the collaborators. The two static text helpers
 * ({@link #chunk}, {@link #withChunkMarker}) delegate to
 * {@link TelegramMessageSender} so their outward call sites stay stable.
 */
class TelegramSender {

    /** Channel identifier used as logging source and returned by {@link #channelName()}. */
    private static final String CHANNEL_NAME = "telegram";

    private final String botToken;
    private final TelegramClient client;

    /**
     * JCLAW-383: bounded bot-sent-message-id cache (per-instance). See
     * {@link TelegramSentMessageCache} for the two-way eviction bounds and the
     * {@code notify=own} rationale.
     */
    private final TelegramSentMessageCache sentCache = new TelegramSentMessageCache();

    private final TelegramMessageSender messageSender;
    private final TelegramMediaSender mediaSender;
    private final TelegramAdminSender adminSender;
    private final TelegramKeyboardSender keyboardSender;

    TelegramSender(String botToken) {
        this(botToken, null);
    }

    TelegramSender(String botToken, TelegramUrl urlOverride) {
        this.botToken = botToken;
        this.client = TelegramBotApiHttpClients.textClient(botToken, urlOverride);
        var uploadClient = TelegramBotApiHttpClients.uploadClient(botToken, urlOverride);
        var ctx = new TelegramSendContext(botToken, client, uploadClient, sentCache);
        this.mediaSender = new TelegramMediaSender(ctx);
        this.messageSender = new TelegramMessageSender(ctx, mediaSender);
        this.adminSender = new TelegramAdminSender(ctx);
        this.keyboardSender = new TelegramKeyboardSender(ctx);
    }

    String botToken() { return botToken; }
    TelegramClient client() { return client; }

    /** Channel identifier (the "telegram" constant). */
    public String channelName() { return CHANNEL_NAME; }

    // ── JCLAW-383: bot-sent-message-id cache query ───────────────────────

    /**
     * JCLAW-383: true when {@code messageId} in {@code chatId} is a message this
     * bot sent (and is still in the bounded cache). False on any null arg, a
     * never-seen chat, an evicted id, or a cold cache after a restart — a false
     * here makes the reaction gate under-notify (conservative), never
     * over-notify.
     */
    public boolean wasSentByBot(String chatId, Integer messageId) {
        return sentCache.wasSent(chatId, messageId);
    }

    /**
     * JCLAW-383: test-only seam to populate the bot-sent-id cache directly,
     * mirroring {@link TelegramChannel#installForTest} / {@link TelegramChannel#clearForTest} — the
     * eviction-boundary tests would otherwise need hundreds of real HTTP sends
     * to reach the caps. Production never calls this; the real send paths feed
     * the cache via the shared {@link TelegramSendContext#recordSent}.
     */
    public void recordSentForTest(String chatId, Integer messageId) {
        sentCache.remember(chatId, messageId);
    }

    // ── Message sender delegation (text turns, retry, quote reply, plain edit) ──

    /**
     * Public helper for callers outside the {@code channels} package that
     * need to edit a specific Telegram message by id (e.g. JCLAW-95's
     * streaming-recovery job). Exceptions propagate so callers can decide
     * whether to retry or log-and-continue.
     */
    public void editMessageText(String chatId,
                                Integer messageId, String text) throws TelegramApiException {
        messageSender.editMessageText(chatId, messageId, text);
    }

    /** JCLAW-387 (A3): reply to {@code replyToMessageId} natively quoting {@code quote}. */
    public boolean sendReplyWithQuote(String chatId, String text,
                                      Agent agent, Integer replyToMessageId, String quote) {
        return messageSender.sendReplyWithQuote(chatId, text, agent, replyToMessageId, quote);
    }

    /** JCLAW-141: generic cross-channel text send (the {@link Channel} contract). */
    public SendResult sendText(String peerId, String text) {
        return messageSender.sendText(peerId, text);
    }

    /** JCLAW-141: agent-aware generic text send (the {@link Channel} contract). */
    public SendResult sendText(String peerId, String text, Agent agent) {
        return messageSender.sendText(peerId, text, agent);
    }

    /** JCLAW-369: reply-targeting + topic-aware outbound dispatch. */
    public boolean sendTurn(String chatId, String text, Agent agent,
                            Integer replyToMessageId, Integer messageThreadId) {
        return messageSender.sendTurn(chatId, text, agent, replyToMessageId, messageThreadId);
    }

    /** JCLAW-141/369: single HTML text send (Channel contract; both extra args null → default). */
    public SendResult trySend(String peerId, String text) {
        return messageSender.trySend(peerId, text);
    }

    /** JCLAW-369: reply-targeting + topic-aware single text send. */
    public SendResult trySend(String peerId, String text,
                              ReplyParameters replyParams, Integer messageThreadId) {
        return messageSender.trySend(peerId, text, replyParams, messageThreadId);
    }

    // ── Media sender delegation (native file + album uploads) ──

    /** JCLAW-141: generic cross-channel photo send (the {@link Channel} contract). */
    public SendResult sendPhoto(String peerId, File file, String caption) {
        return mediaSender.sendPhoto(peerId, file, caption);
    }

    /** JCLAW-141: generic cross-channel document send (the {@link Channel} contract). */
    public SendResult sendDocument(String peerId, File file, String caption) {
        return mediaSender.sendDocument(peerId, file, caption);
    }

    /** Upload {@code file} as a Telegram photo (legacy 3-arg overload). */
    public boolean trySendPhoto(String peerId, File file, String displayName) {
        return mediaSender.trySendPhoto(peerId, file, displayName);
    }

    /** JCLAW-369: reply-targeting + topic-aware photo upload. */
    public boolean trySendPhoto(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return mediaSender.trySendPhoto(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** JCLAW-364: caption-aware photo upload. */
    public boolean trySendPhoto(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return mediaSender.trySendPhoto(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /** Upload {@code file} as a Telegram document (legacy 3-arg overload). */
    public boolean trySendDocument(String peerId, File file, String displayName) {
        return mediaSender.trySendDocument(peerId, file, displayName);
    }

    /** JCLAW-369: reply-targeting + topic-aware document upload. */
    public boolean trySendDocument(String peerId, File file, String displayName,
                                   ReplyParameters replyParams, Integer messageThreadId) {
        return mediaSender.trySendDocument(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** JCLAW-364: caption-aware document upload. */
    public boolean trySendDocument(String peerId, File file, String displayName,
                                   ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return mediaSender.trySendDocument(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /** Upload {@code file} as a Telegram voice note (legacy 3-arg overload). */
    public boolean trySendVoice(String peerId, File file, String displayName) {
        return mediaSender.trySendVoice(peerId, file, displayName);
    }

    /** Reply/topic-aware voice upload. */
    public boolean trySendVoice(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return mediaSender.trySendVoice(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** Caption-aware voice upload. */
    public boolean trySendVoice(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return mediaSender.trySendVoice(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /** Upload {@code file} as a Telegram audio track (legacy 3-arg overload). */
    public boolean trySendAudio(String peerId, File file, String displayName) {
        return mediaSender.trySendAudio(peerId, file, displayName);
    }

    /** Reply/topic-aware audio upload. */
    public boolean trySendAudio(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return mediaSender.trySendAudio(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** Caption-aware audio upload. */
    public boolean trySendAudio(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return mediaSender.trySendAudio(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /** Upload {@code file} as a Telegram video (legacy 3-arg overload). */
    public boolean trySendVideo(String peerId, File file, String displayName) {
        return mediaSender.trySendVideo(peerId, file, displayName);
    }

    /** Reply/topic-aware video upload. */
    public boolean trySendVideo(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId) {
        return mediaSender.trySendVideo(peerId, file, displayName, replyParams, messageThreadId);
    }

    /** Caption-aware video upload. */
    public boolean trySendVideo(String peerId, File file, String displayName,
                                ReplyParameters replyParams, Integer messageThreadId, String caption) {
        return mediaSender.trySendVideo(peerId, file, displayName, replyParams, messageThreadId, caption);
    }

    /** JCLAW-365: bundle 2–10 photos/videos into a single Telegram album. */
    public boolean sendMediaGroup(String peerId,
                                  List<TelegramOutboundPlanner.FileSegment> items,
                                  String caption, ReplyParameters replyParams, Integer messageThreadId) {
        return mediaSender.sendMediaGroup(peerId, items, caption, replyParams, messageThreadId);
    }

    // ── Admin sender delegation (commands, reactions, webhooks, typing, polls) ──

    /** JCLAW-99: register the bot's slash-command set with Telegram. */
    public void setMyCommands(List<BotCommand> commands) {
        adminSender.setMyCommands(commands);
    }

    /** JCLAW-364: set (or clear) the bot's reaction on a message. */
    public boolean setMessageReaction(String chatId, Integer messageId, String emoji) {
        return adminSender.setMessageReaction(chatId, messageId, emoji);
    }

    /** JCLAW-364: pin {@code messageId} in {@code chatId}. */
    public boolean pinChatMessage(String chatId, Integer messageId) {
        return adminSender.pinChatMessage(chatId, messageId);
    }

    /** JCLAW-364: unpin {@code messageId} in {@code chatId}. */
    public boolean unpinChatMessage(String chatId, Integer messageId) {
        return adminSender.unpinChatMessage(chatId, messageId);
    }

    /** JCLAW-374: delete {@code messageId} from {@code chatId}. */
    public boolean deleteMessage(String chatId, Integer messageId) {
        return adminSender.deleteMessage(chatId, messageId);
    }

    /** JCLAW-387 (C1): send a native Telegram poll to {@code chatId}. */
    public boolean sendPoll(String chatId, String question,
                            List<String> options, Boolean isAnonymous,
                            Boolean allowsMultipleAnswers, Integer openPeriod) {
        return adminSender.sendPoll(chatId, question, options, isAnonymous, allowsMultipleAnswers, openPeriod);
    }

    /** JCLAW-369: topic-aware typing action. */
    public TelegramChannel.TypingActionOutcome sendTypingAction(String chatId, Integer messageThreadId) {
        return adminSender.sendTypingAction(chatId, messageThreadId);
    }

    /** JCLAW-339: register {@code url} as the bot's webhook. */
    public boolean setWebhook(String url, String secretToken) {
        return adminSender.setWebhook(url, secretToken);
    }

    /** JCLAW-339: clear any registered webhook so long-poll {@code getUpdates} is allowed again. */
    public boolean deleteWebhook() {
        return adminSender.deleteWebhook();
    }

    // ── Keyboard sender delegation (inline keyboards + callback plumbing) ──

    /** JCLAW-109: send an HTML message with an inline keyboard; returns the new message id or null. */
    public Integer sendMessageWithKeyboard(String chatId,
                                           String htmlText, InlineKeyboardMarkup keyboard) {
        return keyboardSender.sendMessageWithKeyboard(chatId, htmlText, keyboard);
    }

    /** JCLAW-369: reply-targeting + topic-aware keyboard send. */
    public Integer sendMessageWithKeyboard(String chatId,
                                           String htmlText, InlineKeyboardMarkup keyboard,
                                           Integer replyToMessageId, Integer messageThreadId) {
        return keyboardSender.sendMessageWithKeyboard(chatId, htmlText, keyboard, replyToMessageId, messageThreadId);
    }

    /** Edit an existing message in-place, optionally attaching a new inline keyboard (null clears it). */
    public boolean editMessageText(String chatId, Integer messageId,
                                   String htmlText, InlineKeyboardMarkup keyboard) {
        return keyboardSender.editMessageText(chatId, messageId, htmlText, keyboard);
    }

    /** Acknowledge a callback query (Telegram requires this within three seconds). */
    public boolean answerCallbackQuery(String callbackId,
                                       String text, boolean showAlert) {
        return keyboardSender.answerCallbackQuery(callbackId, text, showAlert);
    }

    // ── Static text helpers (stable call sites; delegate to the message sender) ──

    /**
     * JCLAW-387 (A1): append a {@code (n/m)} ordering marker to a chunk when the
     * reply was split into multiple messages ({@code total > 1}); a single-chunk
     * reply is returned unchanged. Kept as a static entry point on the facade so
     * {@link TelegramChannel#withChunkMarker} (the default-package test seam) stays
     * stable; the implementation lives in {@link TelegramMessageSender}.
     */
    public static String withChunkMarker(String chunk, int index, int total) {
        return TelegramMessageSender.withChunkMarker(chunk, index, total);
    }

    /**
     * Split {@code text} into chunks at most {@code maxLen} characters long, biasing
     * breaks toward paragraph → line → word boundaries before a hard cut. Kept as a
     * static entry point on the facade so {@link TelegramChannel#chunk} stays stable;
     * the implementation lives in {@link TelegramMessageSender}.
     */
    public static List<String> chunk(String text, int maxLen) {
        return TelegramMessageSender.chunk(text, maxLen);
    }
}
