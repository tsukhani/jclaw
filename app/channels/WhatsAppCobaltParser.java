package channels;

import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.info.ContextInfo;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.model.ContextualMessage;
import it.auties.whatsapp.model.message.model.Message;
import it.auties.whatsapp.model.message.model.MessageContainer;
import it.auties.whatsapp.model.message.standard.AudioMessage;
import it.auties.whatsapp.model.message.standard.DocumentMessage;
import it.auties.whatsapp.model.message.standard.ImageMessage;
import it.auties.whatsapp.model.message.standard.LocationMessage;
import it.auties.whatsapp.model.message.standard.ReactionMessage;
import it.auties.whatsapp.model.message.standard.StickerMessage;
import it.auties.whatsapp.model.message.standard.TextMessage;
import it.auties.whatsapp.model.message.standard.VideoOrGifMessage;

import java.util.List;
import java.util.Optional;

import static channels.WhatsAppInboundMessage.CHAT_DIRECT;
import static channels.WhatsAppInboundMessage.CHAT_GROUP;
import static channels.WhatsAppInboundMessage.MessageType;

/**
 * Pure data translation from a Cobalt {@link ChatMessageInfo} (WhatsApp-Web /
 * {@code it.auties.whatsapp}) to the transport-agnostic
 * {@link WhatsAppInboundMessage} (JCLAW-449). The exact analog of
 * {@code WhatsAppCloudApiParser} for the Cloud-API webhook JSON — both feed the
 * same {@link WhatsAppInbound#dispatchMessage}.
 *
 * <p><b>Zero business decisions.</b> Per the shared-core DRY contract, every rule
 * (dedup, access gate, attribution, peerId routing, media download) lives in
 * {@link WhatsAppInbound} / {@link WhatsAppAccessPolicy} / {@link WhatsAppMediaDownloader}.
 * This class only:
 * <ul>
 *   <li>discriminates the message type ({@link MessageContainer#type()});</li>
 *   <li>sets {@code chatId} from the chat JID and {@code chatType} =
 *       {@link WhatsAppInboundMessage#CHAT_GROUP group} when that JID is a group
 *       ({@code @g.us} / {@link Jid.Type#GROUP}), else {@code direct};</li>
 *   <li>sets {@code from} = the sender JID, {@code senderDisplayName} from the
 *       message's push name / store contact name;</li>
 *   <li>computes {@code botMentioned} — for a group, whether the bot JID is in
 *       the message's mentioned-JIDs ({@link ContextInfo}); for a direct chat,
 *       always {@code true} so the gate logic stays uniform;</li>
 *   <li>carries media as {@link WhatsAppInboundMessage.PendingMedia} whose
 *       {@code mediaId} is the message id — {@link WhatsAppMediaDownloader} resolves
 *       it back to the live {@link ChatMessageInfo} on the session to pull bytes.</li>
 * </ul>
 *
 * <p>Returns {@code null} for messages we don't route (empty/unsupported content,
 * protocol/system messages) so the runner can drop them cheaply.
 */
public final class WhatsAppCobaltParser {

    private WhatsAppCobaltParser() {}

    /** WhatsApp group-JID server suffix; a chat JID ending in this is a group. */
    static final String GROUP_SERVER_SUFFIX = "@g.us";

    /** The transport-agnostic "envelope" fields shared by every routed message,
     *  independent of its content type — bundled so the per-type builders stay
     *  under the parameter limit (Sonar S107). */
    private record Envelope(String messageId, String from, String chatId, String chatType,
            boolean botMentioned, String quotedMessageId, String senderDisplayName) {}

    /**
     * Translate one Cobalt {@link ChatMessageInfo} into a normalized
     * {@link WhatsAppInboundMessage}, or {@code null} when the message carries no
     * routable content. {@code botJid} is this session's own paired JID (the
     * binding owner), used only to resolve {@code botMentioned} in groups; null
     * when unknown (group mentions then never match, which is the safe default —
     * the access gate simply ignores the message rather than over-serving it).
     */
    public static WhatsAppInboundMessage parse(ChatMessageInfo info, Jid botJid) {
        if (info == null) return null;
        var container = info.message();
        if (container == null || container.isEmpty()) return null;

        var chatJid = info.chatJid();
        if (chatJid == null) return null;
        var chatId = chatJid.toString();
        boolean group = isGroupJid(chatJid);
        var chatType = group ? CHAT_GROUP : CHAT_DIRECT;

        var senderJid = info.senderJid();
        // In a 1:1 the sender == the chat; senderJid can be null on some
        // self/echo frames — fall back to the chat id so attribution never NPEs.
        var from = senderJid != null ? senderJid.toString() : chatId;

        var messageId = info.id();
        var content = container.content();
        var type = mapType(container.type());
        if (type == null) return null; // protocol / unsupported — not routed

        var senderDisplayName = displayName(info);
        var quotedMessageId = quotedId(content);
        boolean botMentioned = !group || mentionsBot(content, botJid);

        var env = new Envelope(messageId, from, chatId, chatType,
                botMentioned, quotedMessageId, senderDisplayName);

        return switch (type) {
            case TEXT -> textMessage(env, content);
            case LOCATION -> locationMessage(env, content);
            case REACTION -> reactionMessage(messageId, from, chatId, chatType, content,
                    senderDisplayName);
            case IMAGE, AUDIO, VIDEO, DOCUMENT, STICKER -> mediaMessage(env, type, content);
        };
    }

    /** A chat JID is a group when it's the {@code @g.us} server (Cobalt's
     *  {@link Jid.Type#GROUP}). Both checks are kept so a JID built without a
     *  resolved type (string round-trip) still classifies correctly. Public for
     *  the default-package test seam, mirroring Telegram's helpers. */
    public static boolean isGroupJid(Jid jid) {
        if (jid == null) return false;
        if (jid.type() == Jid.Type.GROUP) return true;
        var s = jid.toString();
        return s != null && s.endsWith(GROUP_SERVER_SUFFIX);
    }

    /** Map Cobalt's broad {@link Message.Type} to the narrow set JClaw routes.
     *  Returns null for everything we don't handle (protocol, payment, poll,
     *  buttons, …) so the parser drops them. Public for the default-package test
     *  seam. */
    public static MessageType mapType(Message.Type type) {
        if (type == null) return null;
        return switch (type) {
            case TEXT -> MessageType.TEXT;
            case IMAGE -> MessageType.IMAGE;
            case AUDIO -> MessageType.AUDIO;
            case VIDEO -> MessageType.VIDEO;
            case DOCUMENT -> MessageType.DOCUMENT;
            case STICKER -> MessageType.STICKER;
            case LOCATION -> MessageType.LOCATION;
            case REACTION -> MessageType.REACTION;
            default -> null;
        };
    }

    private static WhatsAppInboundMessage textMessage(Envelope env, Message content) {
        var text = content instanceof TextMessage tm ? tm.text() : null;
        return new WhatsAppInboundMessage(env.messageId(), env.from(), env.chatId(), env.chatType(), null,
                MessageType.TEXT, text, null, null, List.of(),
                env.botMentioned(), env.quotedMessageId(), env.senderDisplayName());
    }

    private static WhatsAppInboundMessage locationMessage(Envelope env, Message content) {
        WhatsAppInboundMessage.Location loc = null;
        if (content instanceof LocationMessage lm) {
            loc = new WhatsAppInboundMessage.Location(
                    lm.latitude(), lm.longitude(),
                    lm.name().orElse(null), lm.address().orElse(null));
        }
        return new WhatsAppInboundMessage(env.messageId(), env.from(), env.chatId(), env.chatType(), null,
                MessageType.LOCATION, null, loc, null, List.of(),
                env.botMentioned(), env.quotedMessageId(), env.senderDisplayName());
    }

    private static WhatsAppInboundMessage reactionMessage(
            String messageId, String from, String chatId, String chatType,
            Message content, String senderDisplayName) {
        WhatsAppInboundMessage.Reaction reaction = null;
        if (content instanceof ReactionMessage rm) {
            var targetId = rm.key() != null ? rm.key().id() : null;
            reaction = new WhatsAppInboundMessage.Reaction(targetId, rm.content());
        }
        // botMentioned irrelevant for reactions (dispatchMessage routes them to
        // dispatchReaction before the gate); pass true for shape consistency.
        return new WhatsAppInboundMessage(messageId, from, chatId, chatType, null,
                MessageType.REACTION, null, null, reaction, List.of(),
                true, null, senderDisplayName);
    }

    private static WhatsAppInboundMessage mediaMessage(Envelope env, MessageType type, Message content) {
        var caption = mediaCaption(content);
        var media = pendingMediaFor(env.messageId(), content);
        return new WhatsAppInboundMessage(env.messageId(), env.from(), env.chatId(), env.chatType(), null,
                type, caption, null, null, media,
                env.botMentioned(), env.quotedMessageId(), env.senderDisplayName());
    }

    /** The caption that rides with a media message (image/video/document), or
     *  null for audio/sticker which carry none. */
    static String mediaCaption(Message content) {
        return switch (content) {
            case ImageMessage im -> im.caption().orElse(null);
            case VideoOrGifMessage vm -> vm.caption().orElse(null);
            case DocumentMessage dm -> dm.caption().orElse(null);
            default -> null;
        };
    }

    /**
     * Build the single {@link WhatsAppInboundMessage.PendingMedia} for a media
     * message. {@code mediaId} is the message id — {@link WhatsAppMediaDownloader}'s
     * Cobalt path resolves it to the live {@link ChatMessageInfo} on the session
     * and calls {@code Whatsapp.downloadMedia}. Cobalt doesn't expose a reliable
     * declared size here, so {@code sizeBytes} is 0 (the downloader stream-caps).
     */
    static List<WhatsAppInboundMessage.PendingMedia> pendingMediaFor(
            String messageId, Message content) {
        String mime = mediaMime(content);
        String filename = content instanceof DocumentMessage dm
                ? dm.fileName().orElse(dm.title().orElse(null)) : null;
        boolean voiceNote = content instanceof AudioMessage am && am.voiceMessage();
        return List.of(new WhatsAppInboundMessage.PendingMedia(
                messageId, mime, 0L, filename, voiceNote));
    }

    private static String mediaMime(Message content) {
        return switch (content) {
            case ImageMessage im -> im.mimetype().orElse(null);
            case VideoOrGifMessage vm -> vm.mimetype().orElse(null);
            case AudioMessage am -> am.mimetype().orElse(null);
            case DocumentMessage dm -> dm.mimetype().orElse(null);
            case StickerMessage sm -> sm.mimetype().orElse(null);
            default -> null;
        };
    }

    /** Sender display name for group attribution: prefer the per-message push
     *  name, fall back to the resolved store contact's name, else null. */
    static String displayName(ChatMessageInfo info) {
        var push = info.pushName().filter(s -> !s.isBlank()).orElse(null);
        if (push != null) return push;
        var name = info.senderName();
        return name != null && !name.isBlank() ? name : null;
    }

    /** The quoted/replied-to message id when this message is a reply, else null. */
    static String quotedId(Message content) {
        return contextInfo(content)
                .flatMap(ContextInfo::quotedMessageId)
                .orElse(null);
    }

    /** True when {@code botJid} appears in the message's mentioned JIDs. The bot
     *  is "addressed" in a group only by an explicit @-mention. */
    static boolean mentionsBot(Message content, Jid botJid) {
        if (botJid == null) return false;
        return contextInfo(content)
                .map(ctx -> mentionsContain(ctx, botJid))
                .orElse(false);
    }

    private static boolean mentionsContain(ContextInfo ctx, Jid botJid) {
        var mentions = ctx.mentions();
        if (mentions == null) return false;
        // Match on the bare user part as well as the full JID string: a mention
        // is stored as the user's JID, and the bot's own JID may carry a device
        // suffix the mention won't.
        var botUser = botJid.user();
        var botStr = botJid.toString();
        for (var m : mentions) {
            if (m == null) continue;
            var ms = m.toString();
            if (ms.equals(botStr)) return true;
            if (botUser != null && m.user() != null && m.user().equals(botUser)) return true;
        }
        return false;
    }

    private static Optional<ContextInfo> contextInfo(Message content) {
        if (content instanceof ContextualMessage<?> cm) {
            return cm.contextInfo();
        }
        return Optional.empty();
    }
}
