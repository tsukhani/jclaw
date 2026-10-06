import channels.WhatsAppInbound;
import channels.WhatsAppInboundMessage;
import models.Agent;
import models.DeliveredMessage;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.EventLogger;
import services.Tx;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

    // ── JCLAW-1297: a quote of a delivered message carries it into the turn ──

    private static WhatsAppInboundMessage quoting(String quotedId, String chatType, String text) {
        return new WhatsAppInboundMessage(
                "mid-" + System.nanoTime(), "15550100", "15550100", chatType, null,
                WhatsAppInboundMessage.MessageType.TEXT, text, null, null, List.of(),
                true, quotedId, "Ada");
    }

    private static String delivered(String text) {
        var id = "wamid.Q" + System.nanoTime();
        Tx.run(() -> {
            DeliveredMessage.record("whatsapp", "15550100", List.of(id), "the result of task 'brief'", text);
            return null;
        });
        return id;
    }

    @Test
    void aQuoteOfADeliveredMessageCarriesItAheadOfTheReply() {
        var id = delivered("Rain from 3pm");

        assertEquals("[Replying to the result of task 'brief']\n> Rain from 3pm\n\nwill it clear?",
                WhatsAppInbound.turnText(quoting(id, WhatsAppInboundMessage.CHAT_DIRECT, "will it clear?")));
    }

    @Test
    void aGroupQuoteKeepsTheSenderOnTheReply() {
        var id = delivered("Rain from 3pm");

        assertEquals("[Replying to the result of task 'brief']\n> Rain from 3pm\n\n[Ada]: umbrella?",
                WhatsAppInbound.turnText(quoting(id, WhatsAppInboundMessage.CHAT_GROUP, "umbrella?")));
    }

    @Test
    void aQuoteOfAMessageJClawHasNoRecordOfIsTheReplyAlone() {
        assertEquals("what did you mean?", WhatsAppInbound.turnText(
                quoting("wamid.UNKNOWN" + System.nanoTime(), WhatsAppInboundMessage.CHAT_DIRECT, "what did you mean?")));
    }

    // ── JCLAW-1408: the WhatsApp-Web owner number and the Main Agent rule ──

    private static final String OWNER = "+15551234567";
    private static final String OWNER_JID = "15551234567@s.whatsapp.net";
    private static final String OTHER_JID = "15559990000@s.whatsapp.net";

    private static WhatsAppBinding binding(WhatsAppTransport transport, String ownerNumber) {
        var b = new WhatsAppBinding();
        b.transport = transport;
        b.ownerNumber = ownerNumber;
        return b;
    }

    private static WhatsAppInboundMessage dmFrom(String from) {
        return new WhatsAppInboundMessage(
                "mid-" + System.nanoTime(), from, from, WhatsAppInboundMessage.CHAT_DIRECT, null,
                WhatsAppInboundMessage.MessageType.TEXT, "hi", null, null, List.of(),
                false, null, null);
    }

    private static WhatsAppInboundMessage groupFrom(String from, boolean mentioned) {
        return new WhatsAppInboundMessage(
                "mid-" + System.nanoTime(), from, "group-1@g.us", WhatsAppInboundMessage.CHAT_GROUP, null,
                WhatsAppInboundMessage.MessageType.TEXT, "hi", null, null, List.of(),
                mentioned, null, null);
    }

    @Test
    void aWebBindingServesItsOwnersDirectMessage() {
        var web = binding(WhatsAppTransport.WHATSAPP_WEB, OWNER);
        assertTrue(WhatsAppInbound.admitted(web, dmFrom(OWNER_JID), false));
        assertTrue(WhatsAppInbound.admitted(web, dmFrom(OWNER_JID), true), "an owner set satisfies the main rule");
    }

    @Test
    void anotherNumberIsRefusedWithAnOwnerAndServedWithout() {
        assertFalse(WhatsAppInbound.admitted(binding(WhatsAppTransport.WHATSAPP_WEB, OWNER), dmFrom(OTHER_JID), false));
        assertTrue(WhatsAppInbound.admitted(binding(WhatsAppTransport.WHATSAPP_WEB, null), dmFrom(OTHER_JID), false));
    }

    @Test
    void aMainWebBindingWithNoOwnerRefusesDirectMessages() {
        assertFalse(WhatsAppInbound.admitted(binding(WhatsAppTransport.WHATSAPP_WEB, null), dmFrom(OTHER_JID), true));
    }

    @Test
    void anUpgradedBindingWhosePairedAccountWasItsOwnerHasNoOwner() {
        var upgraded = binding(WhatsAppTransport.WHATSAPP_WEB, null);
        upgraded.pairedJid = OWNER_JID;
        assertFalse(WhatsAppInbound.admitted(upgraded, dmFrom(OWNER_JID), true), "main: refused until an owner is set");
        assertTrue(WhatsAppInbound.admitted(upgraded, dmFrom(OTHER_JID), false), "non-main: open DMs");
    }

    @Test
    void webGroupsStayMentionGatedForAnyMember() {
        var web = binding(WhatsAppTransport.WHATSAPP_WEB, OWNER);
        assertTrue(WhatsAppInbound.admitted(web, groupFrom(OTHER_JID, true), true));
        assertFalse(WhatsAppInbound.admitted(web, groupFrom(OWNER_JID, false), true));
    }

    @Test
    void aCloudBindingIgnoresTheOwnerNumberAndTheMainRule() {
        assertTrue(WhatsAppInbound.admitted(binding(WhatsAppTransport.CLOUD_API, OWNER), dmFrom("15559990000"), true));
    }

    @Test
    void dispatchServesTheOwnerNumberAndDropsAnotherDirectMessage() {
        var name = "wa-owner-" + System.nanoTime();
        var agent = commitInFreshTx(() -> AgentService.create(name, "test-provider", "test-model"));
        var binding = commitInFreshTx(() -> {
            var b = new WhatsAppBinding();
            b.transport = WhatsAppTransport.WHATSAPP_WEB;
            b.agent = Agent.findById(agent.id);
            b.ownerNumber = OWNER;
            // Disabled, so processMessage returns before any agent turn.
            b.enabled = false;
            b.save();
            return b;
        });
        try {
            var fromOwner = EventLogger.captureForTest(() -> WhatsAppInbound.dispatchMessage(binding, dmFrom(OWNER_JID)));
            assertTrue(fromOwner.stream().anyMatch(e -> e.message().startsWith("Message received from " + OWNER_JID)),
                    fromOwner.toString());
            assertTrue(fromOwner.stream().noneMatch(e -> e.message().contains("dropped by access policy")),
                    fromOwner.toString());

            var fromOther = EventLogger.captureForTest(() -> WhatsAppInbound.dispatchMessage(binding, dmFrom(OTHER_JID)));
            assertTrue(fromOther.stream().anyMatch(e -> e.message().contains("dropped by access policy")),
                    fromOther.toString());
        } finally {
            commitInFreshTx(() -> {
                WhatsAppBinding b = WhatsAppBinding.findById(binding.id);
                if (b != null) b.delete();
                Agent a = Agent.findById(agent.id);
                if (a != null) AgentService.delete(a);
                return null;
            });
        }
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
