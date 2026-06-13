import channels.WhatsAppCobaltParser;
import channels.WhatsAppInboundMessage;
import it.auties.whatsapp.model.info.ChatMessageInfo;
import it.auties.whatsapp.model.info.ChatMessageInfoBuilder;
import it.auties.whatsapp.model.info.ContextInfo;
import it.auties.whatsapp.model.info.ContextInfoBuilder;
import it.auties.whatsapp.model.jid.Jid;
import it.auties.whatsapp.model.message.model.Message;
import it.auties.whatsapp.model.message.model.MessageContainer;
import it.auties.whatsapp.model.message.model.ChatMessageKey;
import it.auties.whatsapp.model.message.model.ChatMessageKeyBuilder;
import it.auties.whatsapp.model.message.standard.ImageMessageBuilder;
import it.auties.whatsapp.model.message.standard.LocationMessageBuilder;
import it.auties.whatsapp.model.message.standard.ReactionMessageBuilder;
import it.auties.whatsapp.model.message.standard.TextMessageBuilder;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * Unit coverage for {@link WhatsAppCobaltParser} (JCLAW-449) — the pure
 * Cobalt-object → {@link WhatsAppInboundMessage} translation. Mirrors
 * {@code WhatsAppCloudApiParserTest}: verifies type discrimination, group vs
 * direct classification, sender attribution, mention-gating, and media metadata —
 * never any business decision (those live in {@code WhatsAppInbound}).
 *
 * <p>Fixtures are built straight from Cobalt's own public builders, so the test
 * exercises the real {@code it.auties.whatsapp} model classes (no mocks), pinning
 * the parser to the actual 0.0.10 API.
 */
class WhatsAppCobaltParserTest extends UnitTest {

    private static final Jid USER = Jid.of("15551230000@s.whatsapp.net");
    private static final Jid OTHER_USER = Jid.of("15559990000@s.whatsapp.net");
    private static final Jid GROUP = Jid.of("120363000000000000@g.us");
    private static final Jid BOT = Jid.of("15557654321@s.whatsapp.net");

    // --- pure helpers ---

    @Test
    void isGroupJidByServerType() {
        assertTrue(WhatsAppCobaltParser.isGroupJid(GROUP));
        assertFalse(WhatsAppCobaltParser.isGroupJid(USER));
        assertFalse(WhatsAppCobaltParser.isGroupJid(null));
    }

    @Test
    void isGroupJidByStringSuffix() {
        // A JID round-tripped from a string with no resolved type still classifies.
        assertTrue(WhatsAppCobaltParser.isGroupJid(Jid.of("999@g.us")));
    }

    @Test
    void mapTypeCoversRoutedSetAndDropsOthers() {
        assertEquals(WhatsAppInboundMessage.MessageType.TEXT,
                WhatsAppCobaltParser.mapType(Message.Type.TEXT));
        assertEquals(WhatsAppInboundMessage.MessageType.IMAGE,
                WhatsAppCobaltParser.mapType(Message.Type.IMAGE));
        assertEquals(WhatsAppInboundMessage.MessageType.LOCATION,
                WhatsAppCobaltParser.mapType(Message.Type.LOCATION));
        assertEquals(WhatsAppInboundMessage.MessageType.REACTION,
                WhatsAppCobaltParser.mapType(Message.Type.REACTION));
        // Unsupported / system types are dropped.
        assertNull(WhatsAppCobaltParser.mapType(Message.Type.PROTOCOL));
        assertNull(WhatsAppCobaltParser.mapType(Message.Type.POLL_CREATION));
        assertNull(WhatsAppCobaltParser.mapType(null));
    }

    // --- full parse: text ---

    @Test
    void parsesDirectTextMessage() {
        var info = info(USER, USER, "ID-1", text("hello there"), "Alice");
        var msg = WhatsAppCobaltParser.parse(info, BOT);

        assertNotNull(msg);
        assertEquals("ID-1", msg.messageId());
        assertEquals(WhatsAppInboundMessage.MessageType.TEXT, msg.type());
        assertEquals("hello there", msg.text());
        assertEquals(WhatsAppInboundMessage.CHAT_DIRECT, msg.chatType());
        assertEquals(USER.toString(), msg.chatId());
        assertEquals(USER.toString(), msg.from());
        // Direct chats are always "addressed" so the gate stays uniform.
        assertTrue(msg.botMentioned());
        assertEquals("Alice", msg.senderDisplayName());
    }

    @Test
    void parsesGroupTextWithChatIdAndSender() {
        var info = info(GROUP, USER, "ID-2", text("hi group"), "Bob");
        var msg = WhatsAppCobaltParser.parse(info, BOT);

        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.CHAT_GROUP, msg.chatType());
        // chatId is the group JID; from is the participant.
        assertEquals(GROUP.toString(), msg.chatId());
        assertEquals(USER.toString(), msg.from());
        assertEquals("Bob", msg.senderDisplayName());
    }

    // --- mention gating ---

    @Test
    void groupMessageNotAddressedIsNotMentioned() {
        var info = info(GROUP, USER, "ID-3", text("just chatter"), "Bob");
        var msg = WhatsAppCobaltParser.parse(info, BOT);
        assertNotNull(msg);
        assertFalse(msg.botMentioned());
    }

    @Test
    void groupMessageMentioningBotIsMentioned() {
        var mentioned = textWithMentions("@bot please help", List.of(BOT));
        var info = info(GROUP, USER, "ID-4", mentioned, "Bob");
        var msg = WhatsAppCobaltParser.parse(info, BOT);
        assertNotNull(msg);
        assertTrue(msg.botMentioned());
    }

    @Test
    void groupMentionOfSomeoneElseIsNotBotMentioned() {
        var mentioned = textWithMentions("@other hi", List.of(OTHER_USER));
        var info = info(GROUP, USER, "ID-5", mentioned, "Bob");
        var msg = WhatsAppCobaltParser.parse(info, BOT);
        assertNotNull(msg);
        assertFalse(msg.botMentioned());
    }

    @Test
    void groupMentionWithNullBotJidNeverMatches() {
        var mentioned = textWithMentions("@bot", List.of(BOT));
        var info = info(GROUP, USER, "ID-6", mentioned, "Bob");
        // No known bot JID (pre-pairing) → mentions can't match → not addressed.
        var msg = WhatsAppCobaltParser.parse(info, null);
        assertNotNull(msg);
        assertFalse(msg.botMentioned());
    }

    // --- media ---

    @Test
    void parsesImageWithCaptionAndPendingMedia() {
        // Use the raw ImageMessageBuilder (not the SimpleBuilder) so the test
        // doesn't trigger Cobalt's Medias→aspose-words thumbnail path, which is
        // intentionally absent from the classpath (see WhatsAppCobaltChannel).
        var image = new ImageMessageBuilder()
                .mimetype("image/jpeg")
                .caption("a pelican")
                .build();
        var info = info(USER, USER, "MEDIA-1", image, "Alice");
        var msg = WhatsAppCobaltParser.parse(info, BOT);

        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.IMAGE, msg.type());
        // The caption rides in text(), like the Cloud-API parser.
        assertEquals("a pelican", msg.text());
        assertEquals(1, msg.media().size());
        var part = msg.media().get(0);
        // mediaId is the message id — the downloader resolves it on the session.
        assertEquals("MEDIA-1", part.mediaId());
        assertEquals("image/jpeg", part.mimeType());
        assertFalse(part.voiceNote());
    }

    // --- location ---

    @Test
    void parsesLocation() {
        var location = new LocationMessageBuilder()
                .latitude(37.422)
                .longitude(-122.084)
                .name("Googleplex")
                .address("1600 Amphitheatre Pkwy")
                .build();
        var info = info(USER, USER, "LOC-1", location, "Alice");
        var msg = WhatsAppCobaltParser.parse(info, BOT);

        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.LOCATION, msg.type());
        assertNotNull(msg.location());
        assertEquals(37.422, msg.location().latitude(), 0.0001);
        assertEquals(-122.084, msg.location().longitude(), 0.0001);
        assertEquals("Googleplex", msg.location().name());
    }

    // --- reaction ---

    @Test
    void parsesReactionWithTargetAndEmoji() {
        var reaction = new ReactionMessageBuilder()
                .key(key(USER, USER, "TARGET-9"))
                .content("❤️")
                .build();
        var info = info(USER, USER, "RX-1", reaction, "Alice");
        var msg = WhatsAppCobaltParser.parse(info, BOT);

        assertNotNull(msg);
        assertEquals(WhatsAppInboundMessage.MessageType.REACTION, msg.type());
        assertNotNull(msg.reaction());
        assertEquals("TARGET-9", msg.reaction().targetMessageId());
        assertEquals("❤️", msg.reaction().emoji());
    }

    // --- nulls / drops ---

    @Test
    void nullInfoYieldsNull() {
        assertNull(WhatsAppCobaltParser.parse(null, BOT));
    }

    @Test
    void emptyMessageYieldsNull() {
        var info = new ChatMessageInfoBuilder()
                .key(key(USER, USER, "EMPTY-1"))
                .message(MessageContainer.empty())
                .build();
        assertNull(WhatsAppCobaltParser.parse(info, BOT));
    }

    // --- fixture helpers ---

    private static ChatMessageKey key(Jid chat, Jid sender, String id) {
        return new ChatMessageKeyBuilder()
                .chatJid(chat)
                .senderJid(sender)
                .fromMe(false)
                .id(id)
                .build();
    }

    private static ChatMessageInfo info(Jid chat, Jid sender, String id,
                                        Message content, String pushName) {
        return new ChatMessageInfoBuilder()
                .key(key(chat, sender, id))
                .senderJid(sender)
                .message(MessageContainer.of(content))
                .pushName(pushName)
                .build();
    }

    private static Message text(String body) {
        return new TextMessageBuilder().text(body).build();
    }

    private static Message textWithMentions(String body, List<Jid> mentions) {
        ContextInfo ctx = new ContextInfoBuilder().mentions(mentions).build();
        return new TextMessageBuilder().text(body).contextInfo(ctx).build();
    }
}
