package channels;

import java.util.List;

/**
 * Transport-agnostic normalized inbound WhatsApp message (JCLAW-446/450). Both
 * transports' parsers produce this one record — {@link WhatsAppCloudApiParser}
 * from the Cloud-API webhook JSON, the Cobalt parser from the WhatsApp-Web SDK
 * objects — so everything downstream ({@link WhatsAppInbound} and the agent
 * dispatch) is transport-blind. This is the DRY seam: the parsers are pure data
 * translation with no business decisions; every rule (dedup, access gate,
 * attribution, peerId routing) lives in {@link WhatsAppInbound}.
 *
 * @param messageId         platform message id — the dedup key
 * @param from              sender id (E.164 phone for 1:1; participant JID in a group)
 * @param chatId            conversation peer: equals {@code from} for 1:1, the group
 *                          id/JID for a group
 * @param chatType          {@link #CHAT_DIRECT} or {@link #CHAT_GROUP} — drives the
 *                          access gate and conversation keying
 * @param phoneNumberId     Cloud-API metadata phone-number-id (used to route to a
 *                          binding); null for WhatsApp-Web (the runner already
 *                          resolved the binding)
 * @param type              the content discriminator
 * @param text              message text for {@link MessageType#TEXT}; the caption for a
 *                          media message; null otherwise
 * @param location          present for {@link MessageType#LOCATION}, else null
 * @param reaction          present for {@link MessageType#REACTION}, else null
 * @param media             media parts for image/audio/video/document/sticker, else empty
 * @param botMentioned      whether the bot was addressed (group messages); true for 1:1
 *                          so the gate logic stays uniform
 * @param quotedMessageId   the quoted message id when this is a reply, else null
 * @param senderDisplayName sender display name for group attribution, else null
 */
public record WhatsAppInboundMessage(
        String messageId,
        String from,
        String chatId,
        String chatType,
        String phoneNumberId,
        MessageType type,
        String text,
        Location location,
        Reaction reaction,
        List<PendingMedia> media,
        boolean botMentioned,
        String quotedMessageId,
        String senderDisplayName
) {

    /** A one-on-one chat. */
    public static final String CHAT_DIRECT = "direct";
    /** A group chat (any non-direct context). */
    public static final String CHAT_GROUP = "group";

    public enum MessageType { TEXT, IMAGE, AUDIO, VIDEO, DOCUMENT, STICKER, LOCATION, REACTION }

    /** A shared location pin. */
    public record Location(double latitude, double longitude, String name, String address) {}

    /** An emoji reaction on an earlier message; {@code emoji} is blank when the
     *  reaction was removed. */
    public record Reaction(String targetMessageId, String emoji) {}

    /**
     * Media metadata only — the bytes are NOT loaded here. Download is deferred to
     * {@link WhatsAppMediaDownloader} so a gate-rejected or deduped message never
     * pays for a fetch.
     *
     * @param mediaId   Cloud-API Graph media id, or the Cobalt message key used to download
     * @param mimeType  platform-declared content type
     * @param sizeBytes declared size, or 0 when unknown (Cobalt doesn't always supply it)
     * @param filename  document filename; null for images/audio
     * @param voiceNote true for a push-to-talk audio note
     */
    public record PendingMedia(String mediaId, String mimeType, long sizeBytes,
                               String filename, boolean voiceNote) {}

    /** True when this message originated in a group context. */
    public boolean isGroup() {
        return CHAT_GROUP.equals(chatType);
    }
}
