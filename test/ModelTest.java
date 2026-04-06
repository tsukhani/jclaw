import org.junit.jupiter.api.*;
import play.test.*;
import models.*;

import java.time.Instant;

public class ModelTest extends UnitTest {

    @Test
    public void canCreateAndFindAgent() {
        Fixtures.deleteDatabase();
        var agent = new Agent();
        agent.name = "test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "openai/gpt-4.1";
        agent.isDefault = true;
        agent.save();

        assertNotNull(agent.id);
        assertNotNull(agent.createdAt);
        assertNotNull(agent.updatedAt);

        var found = Agent.findByName("test-agent");
        assertNotNull(found);
        assertEquals("openrouter", found.modelProvider);

        var defaultAgent = Agent.findDefault();
        assertNotNull(defaultAgent);
        assertEquals("test-agent", defaultAgent.name);
    }

    @Test
    public void canCreateAgentBinding() {
        Fixtures.deleteDatabase();
        var agent = new Agent();
        agent.name = "bound-agent";
        agent.modelProvider = "ollama-cloud";
        agent.modelId = "qwen3.5";
        agent.save();

        var binding = new AgentBinding();
        binding.agent = agent;
        binding.channelType = "telegram";
        binding.peerId = "12345";
        binding.save();

        var found = AgentBinding.findByChannelAndPeer("telegram", "12345");
        assertNotNull(found);
        assertEquals(agent.id, found.agent.id);

        var channelWide = AgentBinding.findByChannel("telegram");
        assertNull(channelWide);
    }

    @Test
    public void canCreateConversationWithMessages() {
        Fixtures.deleteDatabase();
        var agent = new Agent();
        agent.name = "chat-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "openai/gpt-4.1";
        agent.save();

        var convo = new Conversation();
        convo.agent = agent;
        convo.channelType = "web";
        convo.peerId = "admin";
        convo.save();

        var msg1 = new Message();
        msg1.conversation = convo;
        msg1.role = "user";
        msg1.content = "Hello";
        msg1.save();

        var msg2 = new Message();
        msg2.conversation = convo;
        msg2.role = "assistant";
        msg2.content = "Hi there!";
        msg2.save();

        var found = Conversation.findByAgentChannelPeer(agent, "web", "admin");
        assertNotNull(found);

        var messages = Message.findRecent(found, 10);
        assertEquals(2, messages.size());
    }

    @Test
    public void canCreateChannelConfig() {
        Fixtures.deleteDatabase();
        var config = new ChannelConfig();
        config.channelType = "telegram";
        config.configJson = """
                {"botToken": "123:ABC", "webhookSecret": "secret123"}
                """;
        config.enabled = true;
        config.save();

        var found = ChannelConfig.findByType("telegram");
        assertNotNull(found);
        assertTrue(found.enabled);
        assertTrue(found.configJson.contains("botToken"));
    }

    @Test
    public void canCreateTask() {
        Fixtures.deleteDatabase();
        var task = new Task();
        task.name = "test-task";
        task.description = "A test task";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        var pending = Task.findPendingDue();
        assertEquals(1, pending.size());
        assertEquals("test-task", pending.getFirst().name);
    }

    @Test
    public void canUpsertConfig() {
        Fixtures.deleteDatabase();
        Config.upsert("provider.openrouter.apiKey", "sk-test-123");
        var found = Config.findByKey("provider.openrouter.apiKey");
        assertNotNull(found);
        assertEquals("sk-test-123", found.value);

        Config.upsert("provider.openrouter.apiKey", "sk-test-456");
        var updated = Config.findByKey("provider.openrouter.apiKey");
        assertEquals("sk-test-456", updated.value);
    }

    @Test
    public void canCreateEventLog() {
        Fixtures.deleteDatabase();
        var log = new EventLog();
        log.level = "INFO";
        log.category = "system";
        log.message = "Application started";
        log.save();

        var recent = EventLog.findRecent(10);
        assertEquals(1, recent.size());
        assertEquals("system", recent.getFirst().category);
    }

    @Test
    public void canCreateMemory() {
        Fixtures.deleteDatabase();
        var mem = new Memory();
        mem.agentId = "main";
        mem.text = "The user prefers concise responses";
        mem.category = "preference";
        mem.save();

        var found = Memory.findByAgent("main");
        assertEquals(1, found.size());

        var searched = Memory.searchByText("main", "concise", 10);
        assertEquals(1, searched.size());

        var noResults = Memory.searchByText("main", "verbose", 10);
        assertEquals(0, noResults.size());
    }

    @Test
    public void canDeleteOldEventLogs() {
        Fixtures.deleteDatabase();
        var old = new EventLog();
        old.level = "INFO";
        old.category = "system";
        old.message = "Old event";
        old.timestamp = Instant.parse("2020-01-01T00:00:00Z");
        old.save();

        var recent = new EventLog();
        recent.level = "INFO";
        recent.category = "system";
        recent.message = "Recent event";
        recent.save();

        var deleted = EventLog.deleteOlderThan(Instant.parse("2025-01-01T00:00:00Z"));
        assertEquals(1, deleted);

        var remaining = EventLog.findRecent(10);
        assertEquals(1, remaining.size());
        assertEquals("Recent event", remaining.getFirst().message);
    }
}
