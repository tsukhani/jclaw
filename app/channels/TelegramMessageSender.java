package channels;

import channels.Channel.SendResult;
import models.Agent;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.methods.updatingmessages.EditMessageText;
import org.telegram.telegrambots.meta.api.objects.ReplyParameters;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import services.EventLogger;
import utils.RetryScheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Text-turn send path for a single bound Telegram bot token (JCLAW-724,
 * extracted from {@code TelegramSender}). Owns everything that turns agent prose
 * into delivered chat messages: the {@link Channel}-contract {@link #sendText}
 * entries, the reply/topic-aware {@link #sendTurn} planner dispatch, the native
 * quote reply ({@link #sendReplyWithQuote}), the low-level {@link #trySend} +
 * HTML-parse fallback + single scheduled retry, and the plain
 * {@link #editMessageText} used by the streaming-recovery job. File and album
 * segments encountered while dispatching a turn are handed to the injected
 * {@link TelegramMediaSender}, so this class stays focused on the text/turn
 * machinery. Reads its send policy from {@link TelegramSendPolicy} and its HTML
 * formatting from {@link TelegramMarkdownFormatter}.
 */
final class TelegramMessageSender {

    private static final String LOG_CATEGORY = "channel";
    private static final String CHANNEL_NAME = "telegram";

    private final TelegramSendContext ctx;
    private final TelegramMediaSender media;

    TelegramMessageSender(TelegramSendContext ctx, TelegramMediaSender media) {
        this.ctx = ctx;
        this.media = media;
    }

    /**
     * Public helper for callers outside the {@code channels} package that
     * need to edit a specific Telegram message by id (e.g. JCLAW-95's
     * streaming-recovery job). Exceptions propagate so callers can decide
     * whether to retry or log-and-continue.
     */
    void editMessageText(String chatId, Integer messageId, String text) throws TelegramApiException {
        var builder = EditMessageText.builder()
                .chatId(chatId)
                .messageId(messageId)
                .text(text);
        var linkPreview = TelegramSendPolicy.linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        ctx.client().execute(builder.build());
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
    boolean sendReplyWithQuote(String chatId, String text,
                               Agent agent, Integer replyToMessageId, String quote) {
        if (ctx.botToken() == null || chatId == null || text == null || replyToMessageId == null) {
            return false;
        }
        // No excerpt → ordinary reply path; preserves today's exact behavior.
        if (quote == null || quote.isBlank()) {
            return sendTurn(chatId, text, agent, replyToMessageId, null);
        }
        var quoteParams = ReplyParameters.builder()
                .messageId(replyToMessageId)
                .quote(quote)
                .allowSendingWithoutReply(true)
                .build();
        // First attempt: reply WITH the native quote excerpt.
        SendResult quoted = trySend(chatId, TelegramMarkdownFormatter.toHtml(text), quoteParams, null);
        if (quoted.ok()) return true;
        // Best-effort fallback (JCLAW-387 A3): a quote that isn't a verbatim
        // substring of the target makes Telegram 400 the send. Retry once as a
        // plain reply (no quote) so the user still gets the message.
        EventLogger.warn(LOG_CATEGORY, null, CHANNEL_NAME,
                "Quote reply failed (excerpt may not match target); retrying as a plain reply");
        return sendTurn(chatId, text, agent, replyToMessageId, null);
    }

    // ── Outbound sends ──

    /**
     * JCLAW-141: generic cross-channel text send (the {@link Channel} contract).
     * Delegates to the agent-aware planner path with no agent context, returning a
     * {@link SendResult} ({@code OK} when the whole turn landed, {@code FAILED}
     * otherwise). The token is the instance's bound token — no token argument.
     */
    SendResult sendText(String peerId, String text) {
        return sendTurn(peerId, text, null, null, null) ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-141: agent-aware generic text send (the {@link Channel} contract).
     * {@link TelegramOutboundPlanner} uses the agent name to resolve
     * workspace-relative file links into native uploads, so passing the agent is
     * what makes prose, photo, prose sequences arrive as the agent composed them.
     */
    SendResult sendText(String peerId, String text, Agent agent) {
        return sendTurn(peerId, text, agent, null, null) ? SendResult.OK : SendResult.FAILED;
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware outbound dispatch. Adds two
     * optional, nullable params over {@link #sendText(String, String, Agent)}:
     *
     * <ul>
     *   <li>{@code replyToMessageId} — when non-null, the turn's chunks reply to
     *       this message per the {@link TelegramSendPolicy#replyToMode()} policy
     *       ({@code telegram.replyTo.mode}: {@code first} sets it on only the
     *       first chunk, {@code all} on every chunk, {@code off} never).
     *       {@code allow_sending_without_reply=true} so a deleted target won't
     *       fail the send.</li>
     *   <li>{@code messageThreadId} — when non-null and not the General topic
     *       ({@link TelegramSendPolicy#GENERAL_TOPIC_THREAD_ID}), scopes every send to that forum
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
    boolean sendTurn(String chatId, String text, Agent agent,
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
        var firstChunk = new AtomicBoolean(true);
        Integer threadId = TelegramSendPolicy.sendThreadId(messageThreadId);
        // JCLAW-378: resolve the reply mode once per turn from the binding
        // override (?? config default) so every segment shares one decision.
        String mode = TelegramSendPolicy.effectiveReplyToMode(ctx.botToken());
        boolean allOk = true;
        for (var segment : segments) {
            if (!dispatchSegment(chatId, segment, replyToMessageId, threadId, firstChunk, mode)) {
                allOk = false;
            }
        }
        return allOk;
    }

    /** Dispatch one planner segment; returns false only when a foreground send actually fails. */
    private boolean dispatchSegment(String chatId,
                                    TelegramOutboundPlanner.Segment segment,
                                    Integer replyToMessageId, Integer threadId,
                                    AtomicBoolean firstChunk,
                                    String mode) {
        if (segment instanceof TelegramOutboundPlanner.TextSegment(String markdown)) {
            return sendTextSegment(chatId, markdown, replyToMessageId, threadId, firstChunk, mode);
        }
        if (segment instanceof TelegramOutboundPlanner.MediaGroupSegment mg) {
            return sendMediaGroupSegment(chatId, mg, replyToMessageId, threadId, firstChunk, mode);
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
                        .start(() -> backgroundSendFile(chatId, fs,
                                replyToMessageId, threadId, ownsFirst, mode));
                return true;
            }
            return sendFileSegment(chatId, fs, replyToMessageId, threadId,
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
    private void backgroundSendFile(String chatId,
                                    TelegramOutboundPlanner.FileSegment fs,
                                    Integer replyToMessageId, Integer threadId,
                                    boolean firstChunk, String mode) {
        try {
            if (!sendFileSegment(chatId, fs, replyToMessageId, threadId, firstChunk, mode)) {
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
    private boolean sendTextSegment(String chatId, String markdown,
                                    Integer replyToMessageId, Integer threadId,
                                    AtomicBoolean firstChunk,
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
            var reply = TelegramSendPolicy.replyParamsFor(replyToMessageId, ownsFirst, mode);
            if (!sendTextWithRetry(chatId, part, reply, threadId)) allOk = false;
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
     * <p>Reachable from default-package tests through
     * {@link TelegramSender#withChunkMarker}, matching the convention used by
     * {@link TelegramSendPolicy#replyToMode()} /
     * {@link TelegramSendPolicy#suppressLinkPreview()}.
     */
    static String withChunkMarker(String chunk, int index, int total) {
        if (total <= 1) return chunk;
        return chunk + "\n\n(" + index + "/" + total + ")";
    }

    /**
     * Dispatch a file segment through the native send method matching its
     * {@link TelegramOutboundPlanner.MediaKind} (JCLAW-364), forwarding the
     * planner-folded caption. Unknown types route through sendDocument.
     */
    private boolean sendFileSegment(String chatId,
                                    TelegramOutboundPlanner.FileSegment fs,
                                    Integer replyToMessageId, Integer threadId,
                                    boolean firstChunk, String mode) {
        var reply = TelegramSendPolicy.replyParamsFor(replyToMessageId, firstChunk, mode);
        var file = fs.file();
        var name = fs.displayName();
        var caption = fs.caption();
        try {
            return switch (fs.kind()) {
                case PHOTO -> media.trySendPhoto(chatId, file, name, reply, threadId, caption);
                case VOICE -> media.trySendVoice(chatId, file, name, reply, threadId, caption);
                case AUDIO -> media.trySendAudio(chatId, file, name, reply, threadId, caption);
                case VIDEO -> media.trySendVideo(chatId, file, name, reply, threadId, caption);
                case DOCUMENT -> media.trySendDocument(chatId, file, name, reply, threadId, caption);
            };
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                    "File send failed for %s: %s".formatted(name, e.getMessage()));
            return false;
        }
    }

    /**
     * JCLAW-365: dispatch a coalesced photo/video run as a single Telegram album
     * via {@link TelegramMediaSender#sendMediaGroup}. The album reply badge / topic
     * follows the same first-chunk policy as the other segment paths: the group
     * counts as one chunk of the turn. On a media-group failure, fall back to
     * individual sends (one per item) so the user still receives every file —
     * preserving the caption on the first item to mirror the album's caption
     * convention.
     */
    private boolean sendMediaGroupSegment(String chatId,
                                          TelegramOutboundPlanner.MediaGroupSegment mg,
                                          Integer replyToMessageId, Integer threadId,
                                          AtomicBoolean firstChunk,
                                          String mode) {
        boolean ownsFirst = firstChunk.getAndSet(false);
        var reply = TelegramSendPolicy.replyParamsFor(replyToMessageId, ownsFirst, mode);
        if (media.sendMediaGroup(chatId, mg.items(), mg.caption(), reply, threadId)) {
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
            if (!sendFileSegment(chatId, withCaption, replyToMessageId, threadId, itemFirst, mode)) {
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
    static List<String> chunk(String text, int maxLen) {
        if (text == null || text.isEmpty()) return List.of(text == null ? "" : text);
        if (text.length() <= maxLen) return List.of(text);
        var out = new ArrayList<String>();
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

    // ── Per-instance text send path ──

    SendResult trySend(String peerId, String text) {
        return trySend(peerId, text, null, null);
    }

    /**
     * JCLAW-369: reply-targeting + topic-aware single text send. {@code replyParams}
     * (null to omit) attaches {@code reply_parameters}; {@code messageThreadId}
     * (already General-stripped by the caller; null to omit) scopes the send to a
     * forum topic. The {@link Channel#trySend(String, String)} override delegates
     * here with both null so the interface contract is unchanged.
     */
    SendResult trySend(String peerId, String text,
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
            if (TelegramSendPolicy.isParseEntitiesError(e)) {
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
        var linkPreview = TelegramSendPolicy.linkPreviewOptions();
        if (linkPreview != null) builder.linkPreviewOptions(linkPreview);
        var sent = ctx.client().execute(builder.build());
        // JCLAW-383: remember the id so notify=own recognizes a later group
        // reaction on this message as a reaction on a bot message.
        if (sent != null) ctx.recordSent(peerId, sent.getMessageId());
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
            boolean ok = RetryScheduler.schedule(
                            () -> trySend(chatId, text, replyParams, messageThreadId).ok(), delayMs)
                    .get(delayMs + 5_000L, TimeUnit.MILLISECONDS);
            if (ok) return true;
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException _) {
            // Fall through to the error-log branch below.
        }
        EventLogger.error(LOG_CATEGORY, null, CHANNEL_NAME,
                "Failed to send message to %s after retries".formatted(chatId));
        return false;
    }
}
