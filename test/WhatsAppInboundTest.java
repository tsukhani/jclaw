import channels.WhatsAppInbound;
import channels.WhatsAppInboundMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

/**
 * Unit coverage for {@link WhatsAppInbound}'s pure routing helpers (JCLAW-446):
 * the conversation peerId (DM keys off the sender, group off the chat) and group
 * sender attribution. The full dispatch pipeline (dedup → gate → media → agent) is
 * exercised by the per-transport inbound tests (JCLAW-446/450).
 */
class WhatsAppInboundTest extends UnitTest {

    private static WhatsAppInboundMessage msg(String from, String chatId, String chatType,
                                              String text, String displayName) {
        return new WhatsAppInboundMessage(
                "mid-" + System.nanoTime(), from, chatId, chatType, null,
                WhatsAppInboundMessage.MessageType.TEXT, text, null, null, List.of(),
                true, null, displayName);
    }

    @Test
    void directConversationKeysOffTheSender() {
        var m = msg("447911111111", "447911111111", WhatsAppInboundMessage.CHAT_DIRECT, "hi", null);
        assertEquals("447911111111", WhatsAppInbound.conversationPeerId(m),
                "a 1:1 conversation keys off the sender");
    }

    @Test
    void groupConversationKeysOffTheChat() {
        var m = msg("447911111111", "group-123@g.us", WhatsAppInboundMessage.CHAT_GROUP, "hi", "Ada");
        assertEquals("group-123@g.us", WhatsAppInbound.conversationPeerId(m),
                "every group member shares one conversation keyed off the chat id");
    }

    @Test
    void directMessageIsNotAttributed() {
        var m = msg("447911111111", "447911111111", WhatsAppInboundMessage.CHAT_DIRECT, "hello", "Ada");
        assertEquals("hello", WhatsAppInbound.senderAttributed(m), "a DM stays unannotated");
    }

    @Test
    void groupMessageIsPrefixedWithDisplayName() {
        var m = msg("447911111111", "group-123@g.us", WhatsAppInboundMessage.CHAT_GROUP, "hello", "Ada Lovelace");
        assertEquals("[Ada Lovelace]: hello", WhatsAppInbound.senderAttributed(m),
                "a group message is prefixed with the sender so the agent knows who spoke");
    }

    @Test
    void groupMessageFallsBackToSenderIdWhenNoDisplayName() {
        var m = msg("447911111111", "group-123@g.us", WhatsAppInboundMessage.CHAT_GROUP, "hello", null);
        assertEquals("[447911111111]: hello", WhatsAppInbound.senderAttributed(m),
                "attribution falls back to the sender id when no display name is known");
    }

    @Test
    void blankGroupTextIsNotAttributed() {
        var m = msg("447911111111", "group-123@g.us", WhatsAppInboundMessage.CHAT_GROUP, "", "Ada");
        assertEquals("", WhatsAppInbound.senderAttributed(m), "blank text is left untouched");
    }
}
