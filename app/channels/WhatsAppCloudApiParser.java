package channels;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.List;

import static channels.WhatsAppInboundMessage.MessageType;

/**
 * Pure data translation of a WhatsApp Cloud-API webhook payload into a normalized
 * {@link WhatsAppInboundMessage} (JCLAW-446). It makes ZERO business decisions —
 * no dedup, no access gate, no media download, no attribution. Every rule lives in
 * {@link WhatsAppInbound}; this class only reshapes the Graph JSON into the
 * transport-agnostic record both transports share.
 *
 * <p>It reads {@code entry[0].changes[0].value.messages[0]} and maps each Cloud-API
 * message type:
 * <ul>
 *   <li>{@code text} → {@link MessageType#TEXT} ({@code text.body}).</li>
 *   <li>{@code image}/{@code video}/{@code audio}/{@code document}/{@code sticker}
 *       → the matching media type, with the Graph media {@code id} + {@code mime_type}
 *       (+ {@code filename} for documents) captured into a {@link WhatsAppInboundMessage.PendingMedia};
 *       bytes are NOT downloaded here. A {@code caption} (image/video/document)
 *       becomes the message text. Voice/PTT audio ({@code audio.voice == true})
 *       sets {@link WhatsAppInboundMessage.PendingMedia#voiceNote()}.</li>
 *   <li>{@code location} → {@link MessageType#LOCATION} with
 *       {@link WhatsAppInboundMessage.Location}.</li>
 *   <li>{@code reaction} → {@link MessageType#REACTION} with
 *       {@link WhatsAppInboundMessage.Reaction} ({@code message_id} + {@code emoji}).</li>
 *   <li>{@code interactive} button/list replies → {@link MessageType#TEXT} carrying
 *       the reply title (so the agent sees what the user tapped as plain text).</li>
 * </ul>
 *
 * <p>The Cloud API has no group chats, so {@code chatType} is always
 * {@link WhatsAppInboundMessage#CHAT_DIRECT} and {@code botMentioned} is always
 * {@code true} (a DM to a business number is implicitly addressed). The
 * {@code phoneNumberId} comes from {@code value.metadata.phone_number_id}; the
 * sender display name from {@code value.contacts[0].profile.name}; the quoted
 * message id from {@code message.context.id}.
 */
public final class WhatsAppCloudApiParser {

    private WhatsAppCloudApiParser() {}

    /**
     * Parse the first user message out of a Cloud-API webhook payload, or
     * {@code null} when the payload carries no parseable user message (status
     * updates, empty changes, unsupported types). Never throws on a malformed
     * payload — a missing/odd field yields {@code null} rather than an exception.
     */
    public static WhatsAppInboundMessage parse(JsonObject payload) {
        try {
            return parseInternal(payload);
        } catch (RuntimeException _) {
            // Defensive: any unexpected shape collapses to "no message" rather
            // than propagating — the webhook must still fast-ack.
            return null;
        }
    }

    private static WhatsAppInboundMessage parseInternal(JsonObject payload) {
        var value = firstValue(payload);
        if (value == null) return null;

        var messages = value.has("messages") ? value.getAsJsonArray("messages") : null;
        if (messages == null || messages.isEmpty()) return null;

        var msg = messages.get(0).getAsJsonObject();
        var type = optString(msg, "type");
        if (type == null) return null;

        var messageId = optString(msg, "id");
        var from = optString(msg, "from");
        if (messageId == null || from == null) return null;

        var phoneNumberId = metadataPhoneNumberId(value);
        var senderName = contactName(value);
        var quotedId = quotedMessageId(msg);

        return switch (type) {
            case "text" -> text(msg, messageId, from, phoneNumberId, senderName, quotedId);
            case "image" -> media(msg, "image", MessageType.IMAGE, false,
                    messageId, from, phoneNumberId, senderName, quotedId);
            case "video" -> media(msg, "video", MessageType.VIDEO, false,
                    messageId, from, phoneNumberId, senderName, quotedId);
            case "audio" -> audio(msg, messageId, from, phoneNumberId, senderName, quotedId);
            case "document" -> media(msg, "document", MessageType.DOCUMENT, false,
                    messageId, from, phoneNumberId, senderName, quotedId);
            case "sticker" -> media(msg, "sticker", MessageType.STICKER, false,
                    messageId, from, phoneNumberId, senderName, quotedId);
            case "location" -> location(msg, messageId, from, phoneNumberId, senderName, quotedId);
            case "reaction" -> reaction(msg, messageId, from, phoneNumberId, senderName, quotedId);
            case "interactive" -> interactive(msg, messageId, from, phoneNumberId, senderName, quotedId);
            // button (template-reply), contacts, order, system, unsupported, etc.
            // have no normalized mapping — drop them (the webhook still fast-acks).
            default -> null;
        };
    }

    // ── per-type builders ──

    private static WhatsAppInboundMessage text(JsonObject msg, String messageId, String from,
                                               String phoneNumberId, String senderName, String quotedId) {
        var body = msg.has("text") ? optString(msg.getAsJsonObject("text"), "body") : null;
        if (body == null) return null;
        return base(MessageType.TEXT, body, null, null, List.of(),
                messageId, from, phoneNumberId, senderName, quotedId);
    }

    /**
     * image / video / document / sticker. The media sub-object carries the Graph
     * {@code id} + {@code mime_type} (+ {@code filename} for documents). A
     * {@code caption} becomes the message text.
     */
    private static WhatsAppInboundMessage media(JsonObject msg, String key, MessageType type,
                                                boolean voiceNote, String messageId, String from,
                                                String phoneNumberId, String senderName, String quotedId) {
        if (!msg.has(key)) return null;
        var obj = msg.getAsJsonObject(key);
        var mediaId = optString(obj, "id");
        if (mediaId == null) return null;
        var mime = optString(obj, "mime_type");
        var filename = optString(obj, "filename");
        var caption = optString(obj, "caption");
        var pending = new WhatsAppInboundMessage.PendingMedia(mediaId, mime, 0L, filename, voiceNote);
        return base(type, caption, null, null, List.of(pending),
                messageId, from, phoneNumberId, senderName, quotedId);
    }

    /** audio — same as {@link #media} but flags voice/PTT clips
     *  ({@code audio.voice == true}). Audio carries no caption. */
    private static WhatsAppInboundMessage audio(JsonObject msg, String messageId, String from,
                                                String phoneNumberId, String senderName, String quotedId) {
        if (!msg.has("audio")) return null;
        var obj = msg.getAsJsonObject("audio");
        var mediaId = optString(obj, "id");
        if (mediaId == null) return null;
        var mime = optString(obj, "mime_type");
        var voice = obj.has("voice") && !obj.get("voice").isJsonNull() && obj.get("voice").getAsBoolean();
        var pending = new WhatsAppInboundMessage.PendingMedia(mediaId, mime, 0L, null, voice);
        return base(MessageType.AUDIO, null, null, null, List.of(pending),
                messageId, from, phoneNumberId, senderName, quotedId);
    }

    private static WhatsAppInboundMessage location(JsonObject msg, String messageId, String from,
                                                   String phoneNumberId, String senderName, String quotedId) {
        if (!msg.has("location")) return null;
        var obj = msg.getAsJsonObject("location");
        if (!obj.has("latitude") || !obj.has("longitude")) return null;
        var loc = new WhatsAppInboundMessage.Location(
                obj.get("latitude").getAsDouble(),
                obj.get("longitude").getAsDouble(),
                optString(obj, "name"),
                optString(obj, "address"));
        return base(MessageType.LOCATION, null, loc, null, List.of(),
                messageId, from, phoneNumberId, senderName, quotedId);
    }

    private static WhatsAppInboundMessage reaction(JsonObject msg, String messageId, String from,
                                                   String phoneNumberId, String senderName, String quotedId) {
        if (!msg.has("reaction")) return null;
        var obj = msg.getAsJsonObject("reaction");
        var targetId = optString(obj, "message_id");
        if (targetId == null) return null;
        // emoji is omitted when a reaction is REMOVED — normalize to "" (blank).
        var emoji = optString(obj, "emoji");
        var r = new WhatsAppInboundMessage.Reaction(targetId, emoji != null ? emoji : "");
        return base(MessageType.REACTION, null, null, r, List.of(),
                messageId, from, phoneNumberId, senderName, quotedId);
    }

    /**
     * Interactive button/list reply. Maps to {@link MessageType#TEXT} carrying the
     * reply title — the agent sees the human-readable label the user tapped. The
     * reply {@code id} is the developer-defined payload; the title is what the user
     * read, so it's the natural text. Falls back to the id when no title is present.
     */
    private static WhatsAppInboundMessage interactive(JsonObject msg, String messageId, String from,
                                                      String phoneNumberId, String senderName, String quotedId) {
        if (!msg.has("interactive")) return null;
        var obj = msg.getAsJsonObject("interactive");
        JsonObject reply = null;
        if (obj.has("button_reply")) {
            reply = obj.getAsJsonObject("button_reply");
        } else if (obj.has("list_reply")) {
            reply = obj.getAsJsonObject("list_reply");
        }
        if (reply == null) return null;
        var title = optString(reply, "title");
        var id = optString(reply, "id");
        var body = title != null ? title : id;
        if (body == null) return null;
        return base(MessageType.TEXT, body, null, null, List.of(),
                messageId, from, phoneNumberId, senderName, quotedId);
    }

    // ── shared assembly ──

    /**
     * Build the normalized record with the Cloud-API invariants baked in:
     * {@code chatId == from} (no groups), {@code chatType == direct},
     * {@code botMentioned == true}.
     */
    private static WhatsAppInboundMessage base(MessageType type, String text,
                                               WhatsAppInboundMessage.Location location,
                                               WhatsAppInboundMessage.Reaction reaction,
                                               List<WhatsAppInboundMessage.PendingMedia> media,
                                               String messageId, String from, String phoneNumberId,
                                               String senderName, String quotedId) {
        return new WhatsAppInboundMessage(
                messageId,
                from,
                from,                                   // chatId == from for a 1:1
                WhatsAppInboundMessage.CHAT_DIRECT,     // Cloud API has no groups
                phoneNumberId,
                type,
                text,
                location,
                reaction,
                media,
                true,                                   // DM to a business number is implicitly addressed
                quotedId,
                senderName);
    }

    // ── payload navigation helpers ──

    /** {@code entry[0].changes[0].value}, or null when absent. */
    private static JsonObject firstValue(JsonObject payload) {
        if (payload == null || !payload.has("entry")) return null;
        JsonArray entries = payload.getAsJsonArray("entry");
        if (entries.isEmpty()) return null;
        var entry = entries.get(0).getAsJsonObject();
        if (!entry.has("changes")) return null;
        JsonArray changes = entry.getAsJsonArray("changes");
        if (changes.isEmpty()) return null;
        var change = changes.get(0).getAsJsonObject();
        return change.has("value") ? change.getAsJsonObject("value") : null;
    }

    /** {@code value.metadata.phone_number_id}, used to route to a binding. */
    static String metadataPhoneNumberId(JsonObject value) {
        if (!value.has("metadata")) return null;
        return optString(value.getAsJsonObject("metadata"), "phone_number_id");
    }

    /** {@code value.contacts[0].profile.name} — the sender's display name. */
    private static String contactName(JsonObject value) {
        if (!value.has("contacts")) return null;
        var contacts = value.getAsJsonArray("contacts");
        if (contacts.isEmpty()) return null;
        var contact = contacts.get(0).getAsJsonObject();
        if (!contact.has("profile")) return null;
        return optString(contact.getAsJsonObject("profile"), "name");
    }

    /** {@code message.context.id} — the quoted message id when this is a reply. */
    private static String quotedMessageId(JsonObject msg) {
        if (!msg.has("context")) return null;
        return optString(msg.getAsJsonObject("context"), "id");
    }

    private static String optString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return null;
        var s = obj.get(key).getAsString();
        return (s == null || s.isBlank()) ? null : s;
    }

    /**
     * Convenience exposed for the webhook controller: pull the
     * {@code phone_number_id} out of a raw payload to route to a binding before
     * full parse. Returns null when the payload has no metadata. Public because
     * the controller lives in a different package.
     */
    public static String extractPhoneNumberId(JsonObject payload) {
        var value = firstValue(payload);
        return value == null ? null : metadataPhoneNumberId(value);
    }
}
