import agents.DangerousActionGate;
import channels.TelegramChannel;
import channels.TelegramInboundTurn;
import channels.WhatsAppInbound;
import channels.WhatsAppInboundMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import models.Agent;
import models.Conversation;
import models.PendingInbound;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.telegram.telegrambots.meta.api.objects.Update;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationQueue;
import services.ConversationQueue.QueuedMessage;
import services.ConversationService;
import slash.Commands;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JF-237: commands parse in Telegram and WhatsApp groups, and a member's {@code /queue} and {@code /stop} act on
 * that member's own messages only.
 */
class GroupChatCommandsTest extends UnitTest {

    private static final String NOT_YOURS = "In group chats, you can only stop a reply to your own message.";

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = AgentService.create("group-agent", "openrouter", "gpt-4.1");
    }

    // --- the turn text keeps a command bare ---

    @Test
    void aTelegramGroupCommandReachesTheDispatcherWithoutTheSenderPrefix() throws Exception {
        var update = """
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"/queue clear"}}
                """;
        var message = TelegramChannel.parseUpdate(new ObjectMapper().readValue(update, Update.class), "jclaw_bot", 555L);

        assertEquals("/queue clear", TelegramInboundTurn.turnText(message));
    }

    @Test
    void aTelegramGroupMessageThatIsNotACommandStaysAttributed() throws Exception {
        var update = """
                {"update_id":1,"message":{"message_id":2,
                  "from":{"id":42,"is_bot":false,"first_name":"Ada"},
                  "chat":{"id":-100,"type":"supergroup"},"date":2,
                  "text":"please /stop that"}}
                """;
        var message = TelegramChannel.parseUpdate(new ObjectMapper().readValue(update, Update.class), "jclaw_bot", 555L);

        assertEquals("[Ada (id 42)]: please /stop that", TelegramInboundTurn.turnText(message));
    }

    @Test
    void aWhatsAppGroupCommandReachesTheDispatcherWithoutTheSenderPrefix() {
        assertEquals("/queue", WhatsAppInbound.turnText(whatsApp("/queue")));
        assertEquals("[Ada]: hello", WhatsAppInbound.turnText(whatsApp("hello")));
    }

    private static WhatsAppInboundMessage whatsApp(String text) {
        return new WhatsAppInboundMessage("mid-" + System.nanoTime(), "447911111111", "group-123@g.us",
                WhatsAppInboundMessage.CHAT_GROUP, null, WhatsAppInboundMessage.MessageType.TEXT, text, null, null,
                List.of(), true, null, "Ada");
    }

    // --- /queue ---

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"telegram, -1001234567", "telegram, -1001234567:topic:7", "whatsapp, 120363000000000000@g.us",
            "slack, C0JF237"})
    void aMembersQueueListsTheirOwnWaitingMessagesOnly(String channelType, String groupPeer) {
        var convo = running(channelType, groupPeer, "B", "bea running", "A", "ada one", "B", "bea two", "A", "ada three");
        try {
            assertEquals("2 messages are waiting, answered in this order:\n1. ada one\n2. ada three",
                    as("A", () -> Commands.execute(Commands.Command.QUEUE, agent, channelType, groupPeer, convo, null,
                            false).responseText()));
            assertEquals("2 messages are waiting, answered in this order:\n1. ada one\n2. ada three",
                    as("A", () -> Commands.execute(Commands.Command.QUEUE, agent, channelType, groupPeer, convo, null,
                            true).responseText()), "the owner's own queue is their own messages too");
            assertEquals("1 message is waiting:\n1. bea two",
                    as("B", () -> Commands.execute(Commands.Command.QUEUE, agent, channelType, groupPeer, convo, null,
                            false).responseText()));
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"telegram, -1001234567", "telegram, -1001234567:topic:7", "whatsapp, 120363000000000000@g.us",
            "slack, C0JF237"})
    void aMembersQueueClearDropsOnlyTheirOwnMessagesAndTheOthersStillRun(String channelType, String groupPeer) {
        var convo = running(channelType, groupPeer, "B", "bea running", "A", "ada one", "B", "bea two", "A", "ada three");
        try {
            assertEquals("Dropped 2 waiting messages.",
                    as("A", () -> Commands.execute(Commands.Command.QUEUE, agent, channelType, groupPeer, convo,
                            "clear", false).responseText()));

            assertEquals(List.of("bea two"), waitingTexts(convo));
            assertEquals(1, PendingInbound.count("conversation.id = ?1", convo.id), "the dropped rows are gone too");
            assertEquals("Nothing is waiting.",
                    as("A", () -> Commands.execute(Commands.Command.QUEUE, agent, channelType, groupPeer, convo, null,
                            false).responseText()));
            assertEquals("Dropped 1 waiting message.",
                    as("B", () -> Commands.execute(Commands.Command.QUEUE, agent, channelType, groupPeer, convo,
                            "clear", false).responseText()));
            assertEquals(List.of(), waitingTexts(convo));
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    @Test
    void aMessageWithNoSenderRecordedBelongsToNoMemberAndOnlyChatClearsIt() {
        var convo = running("telegram", "-1001234567", "B", "bea running", null, "from before senders", "A", "ada one");
        try {
            assertEquals("1 message is waiting:\n1. ada one",
                    as("A", () -> Commands.execute(Commands.Command.QUEUE, agent, "telegram", "-1001234567", convo,
                            null, false).responseText()));
            assertEquals("Dropped 1 waiting message.",
                    as("A", () -> Commands.execute(Commands.Command.QUEUE, agent, "telegram", "-1001234567", convo,
                            "clear", false).responseText()));
            assertEquals("Nothing is waiting.",
                    Commands.execute(Commands.Command.QUEUE, agent, "telegram", "-1001234567", convo, null, false)
                            .responseText(), "a turn with no sender bound owns no message");
            assertEquals(List.of("from before senders"), waitingTexts(convo));
            assertEquals("Dropped 1 waiting message.",
                    Commands.execute(Commands.Command.QUEUE, agent, "web", "admin", convo, "clear", true).responseText());
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    @Test
    void eachWaitingMessageKeepsItsSenderThroughARestart() {
        var convo = running("telegram", "-1001234567", "B", "bea running", "A", "ada one", "B", "bea two");
        try {
            List<PendingInbound> rows = PendingInbound.find("conversation.id = ?1 ORDER BY id", convo.id).fetch();

            assertEquals(List.of("A", "B"), rows.stream().map(r -> r.senderId).toList());
            assertEquals(List.of("A", "B"), rows.stream().map(r -> ConversationQueue.restore(r).senderId()).toList());
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    // --- /stop ---

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"telegram, -1001234567", "telegram, -1001234567:topic:7", "whatsapp, 120363000000000000@g.us",
            "slack, C0JF237"})
    void aMembersStopLeavesAReplyToAnotherMemberRunningAndTheirOwnReplyStops(String channelType, String groupPeer) {
        var convo = running(channelType, groupPeer, "B", "bea running");
        var flag = flagOf(convo);
        try {
            assertEquals(NOT_YOURS, as("A", () -> Commands.execute(Commands.Command.STOP, agent, channelType,
                    groupPeer, convo, null, false).responseText()));
            assertFalse(flag.get(), "the reply keeps running");
            assertTrue(ConversationQueue.isBusy(convo.id));

            assertEquals("Stopped.", as("B", () -> Commands.execute(Commands.Command.STOP, agent, channelType,
                    groupPeer, convo, null, false).responseText()));
            assertTrue(flag.get(), "the member whose message it answers stopped it");
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    @Test
    void theOwnersStopStopsAReplyToAnyMember() {
        var convo = running("telegram", "-1001234567", "B", "bea running");
        var flag = flagOf(convo);
        try {
            assertEquals("Stopped.", as("99", () -> Commands.execute(Commands.Command.STOP, agent, "telegram",
                    "-1001234567", convo, null, true).responseText()));
            assertTrue(flag.get());
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    @Test
    void aMembersStopWhenTheRunningReplyHasNoSenderRecordedLeavesItRunning() {
        var convo = running("telegram", "-1001234567", null, "from before senders");
        var flag = flagOf(convo);
        try {
            assertEquals(NOT_YOURS, as("A", () -> Commands.execute(Commands.Command.STOP, agent, "telegram",
                    "-1001234567", convo, null, false).responseText()));
            assertFalse(flag.get());
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    @Test
    void aMembersStopFollowsTheMessageThatTakesTheConversationNext() {
        var convo = running("telegram", "-1001234567", "B", "bea running", "A", "ada one");
        try {
            assertEquals(List.of("ada one"), ConversationQueue.drain(convo.id).stream().map(QueuedMessage::text).toList());
            var nextFlag = flagOf(convo);

            assertEquals("Stopped.", as("A", () -> Commands.execute(Commands.Command.STOP, agent, "telegram",
                    "-1001234567", convo, null, false).responseText()), "A's message is the one that runs now");
            assertTrue(nextFlag.get());
        } finally {
            ConversationQueue.forgetForTest(convo.id);
        }
    }

    @Test
    void aMembersStopWithNothingRunningAnswersNothingToStop() {
        var convo = ConversationService.findOrCreate(agent, "telegram", "-1001234567");

        assertEquals("Nothing to stop.", as("A", () -> Commands.execute(Commands.Command.STOP, agent, "telegram",
                "-1001234567", convo, null, false).responseText()));
    }

    // --- the commands that reshape the shared conversation ---

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({"telegram, -1001234567", "whatsapp, 120363000000000000@g.us"})
    void aGroupMemberOtherThanTheOwnerCannotResetOrCompactTheSharedConversation(String channelType, String groupPeer) {
        var convo = ConversationService.findOrCreate(agent, channelType, groupPeer);
        for (var cmd : List.of(Commands.Command.NEW, Commands.Command.RESET, Commands.Command.COMPACT,
                Commands.Command.THINK)) {
            assertEquals("Only the operator can use this command.",
                    as("A", () -> Commands.execute(cmd, agent, channelType, groupPeer, convo, null, false)
                            .responseText()), cmd.literal);
        }
    }

    // --- helpers ---

    private static <T> T as(String senderId, java.util.function.Supplier<T> body) {
        return DangerousActionGate.withSender(senderId, body);
    }

    /**
     * Holds a conversation as a running turn does: the first {@code sender, text} pair is the running turn's, each
     * further pair waits behind it. A null sender is a message stored before senders were recorded.
     */
    private Conversation running(String channelType, String peer, String... senderAndText) {
        var convo = ConversationService.findOrCreate(agent, channelType, peer);
        // A queued channel message's row is written on a thread of its own, which must find the conversation committed.
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        for (int i = 0; i < senderAndText.length; i += 2) {
            var text = senderAndText[i + 1];
            var queued = DangerousActionGate.withSender(senderAndText[i],
                    () -> new QueuedMessage(text, channelType, peer, agent));
            if (i == 0) {
                ConversationQueue.tryAcquireOwnership(convo.id, queued, new AtomicBoolean());
            } else {
                assertFalse(ConversationQueue.tryAcquire(convo.id, queued));
            }
        }
        return convo;
    }

    private static AtomicBoolean flagOf(Conversation convo) {
        return ConversationQueue.ownerCancel(convo.id, ConversationQueue.currentGeneration(convo.id));
    }

    private static List<String> waitingTexts(Conversation convo) {
        return ConversationQueue.waiting(convo.id, null, null).stream().map(QueuedMessage::text).toList();
    }
}
