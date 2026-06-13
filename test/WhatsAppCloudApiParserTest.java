import channels.WhatsAppCloudApiParser;
import channels.WhatsAppInboundMessage;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * Unit coverage for {@link WhatsAppCloudApiParser} (JCLAW-446) — pure data
 * translation of the Cloud-API webhook JSON into a {@link WhatsAppInboundMessage}.
 * No HTTP, no DB; every test feeds a representative {@code entry[].changes[].value}
 * payload and asserts the normalized record. The Cloud-API invariants
 * ({@code chatType == direct}, {@code botMentioned == true}, {@code chatId == from})
 * are asserted alongside the per-type mapping.
 */
class WhatsAppCloudApiParserTest extends UnitTest {

    private static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    /** Wrap a single message object in the full entry/changes/value envelope. */
    private static JsonObject envelope(String messageJson) {
        return json("""
                {"object":"whatsapp_business_account","entry":[{"id":"WABA","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp",
                  "metadata":{"display_phone_number":"15550100","phone_number_id":"PNID-1"},
                  "contacts":[{"profile":{"name":"Ada Lovelace"},"wa_id":"447900000001"}],
                  "messages":[%s]
                }}]}]}
                """.formatted(messageJson));
    }

    @Test
    void parsesTextMessageWithCloudApiInvariants() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.TEXT","timestamp":"1","type":"text","text":{"body":"hello there"}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.TEXT, msg.type());
        assertEquals("hello there", msg.text());
        assertEquals("wamid.TEXT", msg.messageId());
        assertEquals("447900000001", msg.from());
        assertEquals("447900000001", msg.chatId(), "1:1 chatId equals from");
        assertEquals(WhatsAppInboundMessage.CHAT_DIRECT, msg.chatType());
        assertTrue(msg.botMentioned(), "a DM to a business number is implicitly addressed");
        assertEquals("PNID-1", msg.phoneNumberId());
        assertEquals("Ada Lovelace", msg.senderDisplayName());
        assertTrue(msg.media().isEmpty());
    }

    @Test
    void parsesImageWithCaptionIntoPendingMedia() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.IMG","timestamp":"1","type":"image",
                 "image":{"id":"MEDIA-9","mime_type":"image/jpeg","sha256":"x","caption":"a cat"}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.IMAGE, msg.type());
        assertEquals("a cat", msg.text(), "caption becomes the message text");
        assertEquals(1, msg.media().size());
        var media = msg.media().get(0);
        assertEquals("MEDIA-9", media.mediaId());
        assertEquals("image/jpeg", media.mimeType());
        assertFalse(media.voiceNote());
        assertNull(media.filename());
    }

    @Test
    void parsesDocumentCapturesFilename() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.DOC","timestamp":"1","type":"document",
                 "document":{"id":"MEDIA-DOC","mime_type":"application/pdf","filename":"invoice.pdf","caption":"see attached"}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.DOCUMENT, msg.type());
        assertEquals("see attached", msg.text());
        assertEquals("invoice.pdf", msg.media().get(0).filename());
    }

    @Test
    void parsesVoiceNoteAudioFlagsVoiceNote() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.PTT","timestamp":"1","type":"audio",
                 "audio":{"id":"MEDIA-PTT","mime_type":"audio/ogg; codecs=opus","voice":true}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.AUDIO, msg.type());
        assertTrue(msg.media().get(0).voiceNote(), "voice:true must flag the PendingMedia as a voice note");
        assertNull(msg.text(), "audio carries no caption");
    }

    @Test
    void parsesNonVoiceAudioWithoutVoiceFlag() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.AUD","timestamp":"1","type":"audio",
                 "audio":{"id":"MEDIA-AUD","mime_type":"audio/mpeg"}}
                """));
        assertNotNull(msg);
        assertFalse(msg.media().get(0).voiceNote(), "absent voice flag → not a voice note");
    }

    @Test
    void parsesLocation() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.LOC","timestamp":"1","type":"location",
                 "location":{"latitude":51.5007,"longitude":-0.1246,"name":"Big Ben","address":"London"}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.LOCATION, msg.type());
        assertNotNull(msg.location());
        assertEquals(51.5007, msg.location().latitude(), 1e-6);
        assertEquals(-0.1246, msg.location().longitude(), 1e-6);
        assertEquals("Big Ben", msg.location().name());
        assertEquals("London", msg.location().address());
    }

    @Test
    void parsesReaction() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.RXN","timestamp":"1","type":"reaction",
                 "reaction":{"message_id":"wamid.TARGET","emoji":"\\uD83D\\uDC4D"}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.REACTION, msg.type());
        assertNotNull(msg.reaction());
        assertEquals("wamid.TARGET", msg.reaction().targetMessageId());
        assertEquals("👍", msg.reaction().emoji());
    }

    @Test
    void parsesReactionRemovalAsBlankEmoji() {
        // A removed reaction omits the emoji field.
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.RXN2","timestamp":"1","type":"reaction",
                 "reaction":{"message_id":"wamid.TARGET"}}
                """));
        assertNotNull(msg);
        assertEquals("", msg.reaction().emoji(), "a removed reaction normalizes to a blank emoji");
    }

    @Test
    void parsesInteractiveButtonReplyAsTextWithTitle() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.BTN","timestamp":"1","type":"interactive",
                 "interactive":{"type":"button_reply","button_reply":{"id":"opt_yes","title":"Yes please"}}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.TEXT, msg.type());
        assertEquals("Yes please", msg.text(), "button reply maps to TEXT carrying the tapped title");
    }

    @Test
    void parsesInteractiveListReplyAsTextWithTitle() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.LST","timestamp":"1","type":"interactive",
                 "interactive":{"type":"list_reply","list_reply":{"id":"row1","title":"Option One","description":"d"}}}
                """));
        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.TEXT, msg.type());
        assertEquals("Option One", msg.text());
    }

    @Test
    void capturesQuotedMessageIdFromContext() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.REPLY","timestamp":"1","type":"text",
                 "context":{"from":"15550100","id":"wamid.QUOTED"},"text":{"body":"replying"}}
                """));
        assertNotNull(msg);
        assertEquals("wamid.QUOTED", msg.quotedMessageId());
    }

    @Test
    void statusUpdatePayloadReturnsNull() {
        // Delivery/read status callbacks carry "statuses", not "messages".
        var status = json("""
                {"entry":[{"id":"WABA","changes":[{"field":"messages","value":{
                  "messaging_product":"whatsapp",
                  "metadata":{"phone_number_id":"PNID-1"},
                  "statuses":[{"id":"wamid.X","status":"delivered","recipient_id":"447900000001"}]
                }}]}]}
                """);
        assertNull(WhatsAppCloudApiParser.parse(status), "a status callback has no user message");
    }

    @Test
    void unsupportedTypeReturnsNull() {
        var msg = WhatsAppCloudApiParser.parse(envelope("""
                {"from":"447900000001","id":"wamid.SYS","timestamp":"1","type":"system",
                 "system":{"body":"user changed number"}}
                """));
        assertNull(msg, "an unmapped type (system) drops to null");
    }

    @Test
    void malformedPayloadReturnsNullInsteadOfThrowing() {
        assertNull(WhatsAppCloudApiParser.parse(json("{}")));
        assertNull(WhatsAppCloudApiParser.parse(json("{\"entry\":[]}")));
    }

    @Test
    void extractPhoneNumberIdReadsMetadata() {
        var payload = envelope("""
                {"from":"447900000001","id":"wamid.T","timestamp":"1","type":"text","text":{"body":"x"}}
                """);
        assertEquals("PNID-1", WhatsAppCloudApiParser.extractPhoneNumberId(payload));
        assertNull(WhatsAppCloudApiParser.extractPhoneNumberId(json("{}")));
    }
}
