import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Conversation;
import models.Message;
import services.AgentService;
import services.ConversationService;

import java.io.IOException;
import java.nio.file.Files;

public class WebChatTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgent();
        agent = AgentService.create("chat-agent", "openrouter", "gpt-4.1", true);
    }

    @AfterAll
    static void cleanupTestAgent() {
        deleteDir(AgentService.workspacePath("chat-agent"));
    }

    @Test
    public void createConversationAndAppendMessages() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        assertNotNull(convo);
        assertEquals("web", convo.channelType);
        assertEquals("admin", convo.peerId);

        ConversationService.appendUserMessage(convo, "Hello");
        ConversationService.appendAssistantMessage(convo, "Hi there!", null);

        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(2, messages.size());
        assertEquals("user", messages.get(0).role);
        assertEquals("assistant", messages.get(1).role);
    }

    @Test
    public void conversationLinkedToAgent() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        assertNotNull(convo.agent);
        assertEquals(agent.id, convo.agent.id);
    }

    @Test
    public void multipleConversationsPerChannel() {
        var convo1 = ConversationService.findOrCreate(agent, "web", "user1");
        var convo2 = ConversationService.findOrCreate(agent, "web", "user2");
        assertNotEquals(convo1.id, convo2.id);
    }

    @Test
    public void loadRecentMessagesRespectLimit() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        for (int i = 0; i < 60; i++) {
            ConversationService.appendUserMessage(convo, "Message %d".formatted(i));
        }
        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(50, messages.size()); // Default max is 50
        // Should be in chronological order
        assertTrue(messages.getFirst().content.contains("Message 1")); // oldest of last 50
    }

    @Test
    public void toolResultMessagesStored() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "Create a task");
        ConversationService.appendAssistantMessage(convo, null,
                """
                {"id":"call-1","type":"function","function":{"name":"task_manager","arguments":"{\\"action\\":\\"createTask\\"}"}}
                """);
        ConversationService.appendToolResult(convo, "call-1", "Task created successfully.");

        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(3, messages.size());
        assertEquals("tool", messages.get(2).role);
        assertEquals("call-1", messages.get(2).toolResults);
    }

    @Test
    public void messageCountQueryWorks() {
        var convo = ConversationService.findOrCreate(agent, "web", "admin");
        ConversationService.appendUserMessage(convo, "Hello");
        ConversationService.appendAssistantMessage(convo, "Hi", null);
        ConversationService.appendUserMessage(convo, "How are you?");

        var count = Message.count("conversation = ?1", convo);
        assertEquals(3, count);
    }

    private static void deleteDir(java.nio.file.Path dir) {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { Files.delete(p); } catch (IOException _) {}
            });
        } catch (IOException _) {}
    }
}
