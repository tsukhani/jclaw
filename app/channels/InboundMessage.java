package channels;

import java.util.List;

/**
 * Generic inbound shape consumed by {@link controllers.WebhookTelegramController}
 * and {@link TelegramPollingRunner}. Extracted from {@code TelegramChannel} in
 * JCLAW-151; produced by {@link TelegramInboundParser#parseUpdate(org.telegram.telegrambots.meta.api.objects.Update)}.
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
 *                        {@link TelegramInboundParser#parseUpdate(org.telegram.telegrambots.meta.api.objects.Update)}).
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
                             List<PendingAttachment> attachments,
                             String mediaGroupId,
                             Integer messageId, Integer messageThreadId,
                             String replyContext) {
    public InboundMessage(String chatId, String chatType, String text,
                          String fromId, String fromUsername) {
        this(chatId, chatType, text, fromId, fromUsername, null, false,
                List.of(), null, null, null, null);
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
                          List<PendingAttachment> attachments,
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
                          List<PendingAttachment> attachments,
                          String mediaGroupId,
                          Integer messageId, Integer messageThreadId) {
        this(chatId, chatType, text, fromId, fromUsername, fromDisplayName,
                botMentioned, attachments, mediaGroupId, messageId,
                messageThreadId, null);
    }
}
