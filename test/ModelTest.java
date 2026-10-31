import models.Agent;
import models.AgentBinding;
import models.ChannelConfig;
import models.Config;
import models.Conversation;
import models.EventLog;
import models.Memory;
import models.Message;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

class ModelTest extends UnitTest {

    @BeforeEach
    void luceneClosed() {
        // JCLAW-428: Memory.searchByText exercises the DB-LIKE fallback here,
        // so force the Lucene index closed (and serialize against the
        // search-mode tests via the shared global lock).
        LuceneTestSync.closedForTest();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    @Test
    void canCreateAndFindAgent() {
        Fixtures.deleteDatabase();
        var agent = new Agent();
        agent.name = "test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "openai/gpt-4.1";
        agent.save();

        assertNotNull(agent.id);
        assertNotNull(agent.createdAt);
        assertNotNull(agent.updatedAt);

        var found = Agent.findByName("test-agent");
        assertNotNull(found);
        assertEquals("openrouter", found.modelProvider);
        assertFalse(found.isMain());
    }

    @Test
    void mainAgentIdentityByName() {
        Fixtures.deleteDatabase();
        var agent = new Agent();
        agent.name = "main";
        agent.modelProvider = "openrouter";
        agent.modelId = "openai/gpt-4.1";
        agent.save();

        var found = Agent.findByName(Agent.MAIN_AGENT_NAME);
        assertNotNull(found);
        assertTrue(found.isMain());
    }

    @Test
    void canCreateAgentBinding() {
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
    void canCreateConversationWithMessages() {
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
    void canCreateChannelConfig() {
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
    void canCreateTask() {
        Fixtures.deleteDatabase();
        var task = new Task();
        task.name = "test-task";
        task.description = "A test task";
        task.type = Task.Type.IMMEDIATE;
        task.status = Task.Status.PENDING;
        task.nextRunAt = Instant.now();
        task.save();

        var pending = Task.findByStatus(Task.Status.PENDING);
        assertEquals(1, pending.size());
        assertEquals("test-task", pending.getFirst().name);
    }

    @Test
    void canUpsertConfig() {
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
    void canCreateEventLog() {
        var category = "modeltest." + UUID.randomUUID();
        var log = new EventLog();
        log.level = "INFO";
        log.category = category;
        log.message = "Application started";
        log.save();

        assertEquals(List.of("Application started"), messagesIn(category));
    }

    @Test
    void findRecentReturnsTheNewestRowsNewestFirst() {
        var category = "modeltest." + UUID.randomUUID();
        // Stamped past every other class's rows, so theirs cannot take a place in the limit.
        for (var year : List.of(2102, 2100, 2103, 2101)) {
            var row = new EventLog();
            row.level = "INFO";
            row.category = category;
            row.message = String.valueOf(year);
            row.timestamp = Instant.parse(year + "-01-01T00:00:00Z");
            row.save();
        }

        assertEquals(List.of("2103", "2102", "2101"), EventLog.findRecent(3).stream().map(e -> e.message).toList());
        EventLog.delete("category = ?1", category);
    }

    /** The messages of the rows in {@code category}: each test saves its own rows, so the table is the subject. */
    private static List<String> messagesIn(String category) {
        List<EventLog> rows = EventLog.find("category = ?1", category).fetch();
        return rows.stream().map(e -> e.message).toList();
    }

    private static Agent mkAgent(String name) {
        var a = new Agent();
        a.name = name;
        a.modelProvider = "openrouter";
        a.modelId = "gpt-4.1";
        a.save();
        return a;
    }

    @Test
    void canCreateMemory() {
        Fixtures.deleteDatabase();
        var agent = mkAgent("main");
        var key = String.valueOf(agent.id);
        var mem = new Memory();
        mem.agent = agent;
        mem.text = "The user prefers concise responses";
        mem.category = "preference";
        mem.save();

        var found = Memory.findByAgent(key);
        assertEquals(1, found.size());

        var searched = Memory.searchByText(key, "concise", 10);
        assertEquals(1, searched.size());

        var noResults = Memory.searchByText(key, "verbose", 10);
        assertEquals(0, noResults.size());
    }

    @Test
    void memorySearchIsAgentScoped() {
        Fixtures.deleteDatabase();
        var agentA = mkAgent("agentA");
        var agentB = mkAgent("agentB");
        var a = new Memory();
        a.agent = agentA;
        a.text = "shared widget knowledge";
        a.save();
        var b = new Memory();
        b.agent = agentB;
        b.text = "shared widget knowledge";
        b.save();

        // The shared "widget" term is in both agents' memories, but search is
        // agent-scoped (privacy invariant, JCLAW-415) — each agent sees only
        // its own, never the other's. Holds across both the Lucene and the
        // LIKE-fallback backends.
        var aResults = Memory.searchByText(String.valueOf(agentA.id), "widget", 10);
        assertEquals(1, aResults.size(), "search must be scoped to the calling agent");
        assertEquals(agentA.id, aResults.get(0).agent.id);

        var bResults = Memory.searchByText(String.valueOf(agentB.id), "widget", 10);
        assertEquals(1, bResults.size());
        assertEquals(agentB.id, bResults.get(0).agent.id);

        assertEquals(0, Memory.searchByText(String.valueOf(agentA.id), "nonexistent", 10).size());
    }

    @Test
    void canDeleteOldEventLogs() {
        var category = "modeltest." + UUID.randomUUID();
        var old = new EventLog();
        old.level = "INFO";
        old.category = category;
        old.message = "Old event";
        old.timestamp = Instant.parse("1999-01-01T00:00:00Z");
        old.save();

        var recent = new EventLog();
        recent.level = "INFO";
        recent.category = category;
        recent.message = "Recent event";
        recent.save();

        // A cutoff older than any other class's rows: the delete takes only this test's, and leaves theirs.
        var deleted = EventLog.deleteOlderThan(Instant.parse("2000-01-01T00:00:00Z"));
        assertEquals(1, deleted);

        assertEquals(List.of("Recent event"), messagesIn(category));
    }
}
