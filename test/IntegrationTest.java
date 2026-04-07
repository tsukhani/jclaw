import org.junit.jupiter.api.*;
import play.test.*;
import agents.*;
import channels.*;
import com.google.gson.JsonParser;
import models.*;
import services.*;

import java.io.IOException;
import java.nio.file.Files;

/**
 * Integration tests verifying the full pipeline from inbound message to outbound response.
 */
public class IntegrationTest extends UnitTest {

    private static final String[] TEST_AGENTS = {
            "telegram-bot", "slack-bot", "wa-bot", "web-agent", "tool-agent",
            "memory-agent", "skill-agent", "test-main", "test-support"
    };

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        cleanupTestAgents();
        ConfigService.clearCache();
        ToolRegistry.clear();
        new jobs.ToolRegistrationJob().doJob();
    }

    @AfterAll
    static void cleanup() {
        cleanupTestAgents();
    }

    // --- Full pipeline: webhook → routing → agent → conversation ---

    @Test
    public void telegramWebhookParsesAndRoutes() {
        // Setup: agent + binding
        var agent = AgentService.create("telegram-bot", "openrouter", "gpt-4.1", true);
        agent.enabled = true; agent.save();
        var binding = new AgentBinding();
        binding.agent = agent;
        binding.channelType = "telegram";
        binding.peerId = null;
        binding.save();

        // Simulate Telegram webhook payload
        var update = JsonParser.parseString("""
                {
                    "update_id": 1,
                    "message": {
                        "message_id": 1,
                        "from": {"id": 42, "is_bot": false, "first_name": "Test"},
                        "chat": {"id": 42, "type": "private"},
                        "text": "Hello bot"
                    }
                }
                """).getAsJsonObject();

        var msg = TelegramChannel.parseUpdate(update);
        assertNotNull(msg);
        assertEquals("42", msg.chatId());
        assertEquals("Hello bot", msg.text());

        // Route the message
        var route = AgentRouter.resolve("telegram", msg.chatId());
        assertNotNull(route);
        assertEquals("telegram-bot", route.agent().name);
        assertEquals("channel", route.matchedBy());

        // Create conversation
        var convo = ConversationService.findOrCreate(route.agent(), "telegram", msg.chatId());
        assertNotNull(convo);
        assertEquals("telegram", convo.channelType);
        assertEquals("42", convo.peerId);

        // Append user message
        ConversationService.appendUserMessage(convo, msg.text());
        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(1, messages.size());
        assertEquals("Hello bot", messages.getFirst().content);
    }

    @Test
    public void slackWebhookVerifiesAndRoutes() {
        var agent = AgentService.create("slack-bot", "openrouter", "gpt-4.1", true);
        agent.enabled = true; agent.save();

        // Simulate Slack event payload
        var payload = JsonParser.parseString("""
                {
                    "type": "event_callback",
                    "event": {
                        "type": "message",
                        "channel": "C01234567",
                        "user": "U42",
                        "text": "Hey from Slack"
                    }
                }
                """).getAsJsonObject();

        var msg = SlackChannel.parseEvent(payload);
        assertNotNull(msg);
        assertEquals("C01234567", msg.channelId());
        assertEquals("Hey from Slack", msg.text());

        // Route falls through to default agent
        var route = AgentRouter.resolve("slack", msg.channelId());
        assertNotNull(route);
        assertEquals("slack-bot", route.agent().name);
        assertEquals("default", route.matchedBy());
    }

    @Test
    public void whatsappWebhookParsesAndRoutes() {
        var agent = AgentService.create("wa-bot", "ollama-cloud", "qwen3.5", true);
        agent.enabled = true; agent.save();

        var payload = JsonParser.parseString("""
                {
                    "object": "whatsapp_business_account",
                    "entry": [{
                        "changes": [{
                            "value": {
                                "metadata": {"phone_number_id": "123"},
                                "messages": [{
                                    "from": "15551234567",
                                    "id": "wamid.test",
                                    "type": "text",
                                    "text": {"body": "Hello from WhatsApp"}
                                }]
                            }
                        }]
                    }]
                }
                """).getAsJsonObject();

        var msg = WhatsAppChannel.parseWebhook(payload);
        assertNotNull(msg);
        assertEquals("15551234567", msg.from());
        assertEquals("Hello from WhatsApp", msg.text());

        var route = AgentRouter.resolve("whatsapp", msg.from());
        assertNotNull(route);
        assertEquals("wa-bot", route.agent().name);
    }

    // --- Web chat pipeline ---

    @Test
    public void webChatCreatesConversationAndStoresMessages() {
        var agent = AgentService.create("web-agent", "openrouter", "gpt-4.1", true);
        var convo = ConversationService.findOrCreate(agent, "web", "admin");

        ConversationService.appendUserMessage(convo, "What is JClaw?");

        // Verify system prompt assembly works
        var assembled = SystemPromptAssembler.assemble(agent, "What is JClaw?");
        assertNotNull(assembled.systemPrompt());
        assertTrue(assembled.systemPrompt().contains("Agent Instructions"));
        assertTrue(assembled.systemPrompt().contains("Current time:"));

        // Verify conversation persistence
        var messages = ConversationService.loadRecentMessages(convo);
        assertEquals(1, messages.size());
        assertEquals("What is JClaw?", messages.getFirst().content);

        // Simulate assistant response
        ConversationService.appendAssistantMessage(convo,
                "JClaw is an AI automation platform.", null);
        messages = ConversationService.loadRecentMessages(convo);
        assertEquals(2, messages.size());
    }

    // --- Tool execution in pipeline ---

    @Test
    public void toolExecutionInAgentPipeline() {
        var agent = AgentService.create("tool-agent", "openrouter", "gpt-4.1", true);

        // Test TaskTool creates a task
        var result = ToolRegistry.execute("task_manager",
                """
                {"action": "createTask", "name": "integration-task", "description": "Test task from integration test"}
                """, agent);
        assertTrue(result.contains("created"));

        var tasks = Task.findPendingDue();
        assertEquals(1, tasks.size());
        assertEquals("integration-task", tasks.getFirst().name);
        assertEquals(agent.id, tasks.getFirst().agent.id);

        // Test FileSystemTools reads workspace file
        var fsResult = ToolRegistry.execute("filesystem",
                """
                {"action": "readFile", "path": "AGENT.md"}
                """, agent);
        assertTrue(fsResult.contains("Agent Instructions"));
    }

    // --- Memory in prompt assembly ---

    @Test
    public void memoryRecalledDuringPromptAssembly() {
        var agent = AgentService.create("memory-agent", "openrouter", "gpt-4.1", true);

        // Store a memory
        var store = memory.MemoryStoreFactory.get();
        store.store(agent.name, "User prefers dark mode interfaces", "preference");

        // Assemble prompt — should include the memory
        var assembled = SystemPromptAssembler.assemble(agent, "dark mode");
        assertTrue(assembled.systemPrompt().contains("dark mode"));
        assertTrue(assembled.systemPrompt().contains("Relevant Memories"));
    }

    // --- Skill loading in prompt assembly ---

    @Test
    public void skillsLoadedDuringPromptAssembly() throws IOException {
        var agent = AgentService.create("skill-agent", "openrouter", "gpt-4.1", true);
        var skillDir = AgentService.workspacePath("skill-agent").resolve("skills/coding");
        Files.createDirectories(skillDir);
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                name: coding
                description: Help with code review and writing
                ---
                # Coding Skill
                Review code carefully.
                """);

        var assembled = SystemPromptAssembler.assemble(agent, "review my code");
        assertTrue(assembled.systemPrompt().contains("<available_skills>"));
        assertTrue(assembled.systemPrompt().contains("coding"));
        assertEquals(1, assembled.skills().size());
    }

    // --- Multi-agent routing ---

    @Test
    public void multiAgentRoutingFullPipeline() {
        var mainAgent = AgentService.create("test-main", "openrouter", "gpt-4.1", true);
        mainAgent.enabled = true; mainAgent.save();
        var supportAgent = AgentService.create("test-support", "ollama-cloud", "qwen3.5", false);
        supportAgent.enabled = true; supportAgent.save();

        // Bind support agent to specific Telegram user
        var binding = new AgentBinding();
        binding.agent = supportAgent;
        binding.channelType = "telegram";
        binding.peerId = "VIP_USER";
        binding.save();

        // VIP user routes to support
        var vipRoute = AgentRouter.resolve("telegram", "VIP_USER");
        assertNotNull(vipRoute);
        assertEquals("test-support", vipRoute.agent().name);
        assertEquals("peer", vipRoute.matchedBy());

        // Regular user routes to default
        var regularRoute = AgentRouter.resolve("telegram", "REGULAR_USER");
        assertNotNull(regularRoute);
        assertEquals("test-main", regularRoute.agent().name);
        assertEquals("default", regularRoute.matchedBy());

        // Each gets their own conversation
        var vipConvo = ConversationService.findOrCreate(vipRoute.agent(), "telegram", "VIP_USER");
        var regularConvo = ConversationService.findOrCreate(regularRoute.agent(), "telegram", "REGULAR_USER");
        assertNotEquals(vipConvo.id, regularConvo.id);
        assertEquals("test-support", vipConvo.agent.name);
        assertEquals("test-main", regularConvo.agent.name);
    }

    // --- Event logging across pipeline ---

    @Test
    public void eventLogCapturesActivity() {
        Fixtures.deleteDatabase();
        EventLogger.info("system", "Application started");
        EventLogger.info("agent", "main", null, "Agent created");
        EventLogger.warn("channel", null, "telegram", "Delivery retry");
        EventLogger.error("llm", "Provider timeout");

        var events = EventLog.findRecent(10);
        assertEquals(4, events.size());
        assertEquals("ERROR", events.get(0).level);
        assertEquals("llm", events.get(0).category);
    }

    // --- Helpers ---

    private static void cleanupTestAgents() {
        for (var name : TEST_AGENTS) {
            deleteDir(AgentService.workspacePath(name));
        }
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
