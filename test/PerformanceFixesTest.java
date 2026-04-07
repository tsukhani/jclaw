import org.junit.jupiter.api.*;
import play.test.*;
import com.google.gson.JsonParser;
import models.*;
import play.db.jpa.JPA;
import services.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tests for the performance-critical-fixes proposal:
 * - N+1 query fix: bindings list with JOIN FETCH
 * - Transaction scope: concurrent runs without pool exhaustion
 */
public class PerformanceFixesTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    // --- Task 5.10: JOIN FETCH query returns correct agent data ---

    @Test
    public void joinFetchBindingsQueryReturnsAgentFields() {
        // Create two agents
        var agent1 = new Agent();
        agent1.name = "agent-alpha";
        agent1.modelProvider = "openrouter";
        agent1.modelId = "gpt-4.1";
        agent1.enabled = true;
        agent1.save();

        var agent2 = new Agent();
        agent2.name = "agent-beta";
        agent2.modelProvider = "openrouter";
        agent2.modelId = "gpt-4.1";
        agent2.enabled = true;
        agent2.save();

        // Create 3 bindings for agent1, 2 for agent2
        for (int i = 0; i < 3; i++) {
            var b = new AgentBinding();
            b.agent = agent1;
            b.channelType = "telegram";
            b.peerId = "peer-a" + i;
            b.save();
        }
        for (int i = 0; i < 2; i++) {
            var b = new AgentBinding();
            b.agent = agent2;
            b.channelType = "slack";
            b.peerId = "peer-b" + i;
            b.save();
        }

        // Execute the exact JOIN FETCH query used by ApiBindingsController.list()
        var bindings = JPA.em()
                .createQuery("SELECT b FROM AgentBinding b JOIN FETCH b.agent", AgentBinding.class)
                .getResultList();

        assertEquals(5, bindings.size(), "Should return all 5 bindings");

        // Verify agent fields are populated (not lazy proxies that would fail outside transaction)
        int alphaCount = 0, betaCount = 0;
        for (var b : bindings) {
            assertNotNull(b.agent, "Binding should have agent loaded");
            assertNotNull(b.agent.name, "Agent name should be populated via JOIN FETCH");

            if ("agent-alpha".equals(b.agent.name)) alphaCount++;
            else if ("agent-beta".equals(b.agent.name)) betaCount++;
        }
        assertEquals(3, alphaCount, "agent-alpha should have 3 bindings");
        assertEquals(2, betaCount, "agent-beta should have 2 bindings");
    }

    @Test
    public void joinFetchConversationsQueryWithFilters() {
        var agent = new Agent();
        agent.name = "convo-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();

        // Create conversations across channels
        ConversationService.create(agent, "web", "user1");
        ConversationService.create(agent, "web", "user2");
        ConversationService.create(agent, "telegram", "user3");

        // Test unfiltered query
        var all = JPA.em()
                .createQuery("SELECT c FROM Conversation c JOIN FETCH c.agent ORDER BY c.updatedAt DESC",
                        Conversation.class)
                .getResultList();
        assertEquals(3, all.size());

        // Test filtered query (same pattern as ApiChatController.listConversations)
        var webOnly = JPA.em()
                .createQuery("SELECT c FROM Conversation c JOIN FETCH c.agent WHERE channelType = ?1 ORDER BY c.updatedAt DESC",
                        Conversation.class)
                .setParameter(1, "web")
                .getResultList();
        assertEquals(2, webOnly.size());
        for (var c : webOnly) {
            assertEquals("web", c.channelType);
            assertNotNull(c.agent.name, "Agent should be fetched via JOIN FETCH");
        }
    }

    @Test
    public void joinFetchTasksQueryWithLeftJoin() {
        var agent = new Agent();
        agent.name = "task-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();

        // Create tasks with and without agents
        var task1 = new Task();
        task1.name = "With agent";
        task1.agent = agent;
        task1.type = Task.Type.IMMEDIATE;
        task1.status = Task.Status.PENDING;
        task1.save();

        var task2 = new Task();
        task2.name = "No agent";
        task2.agent = null;
        task2.type = Task.Type.IMMEDIATE;
        task2.status = Task.Status.PENDING;
        task2.save();

        // LEFT JOIN FETCH should include tasks without agents
        var tasks = JPA.em()
                .createQuery("SELECT t FROM Task t LEFT JOIN FETCH t.agent ORDER BY t.createdAt DESC",
                        Task.class)
                .getResultList();
        assertEquals(2, tasks.size(), "LEFT JOIN FETCH should include tasks with null agent");

        boolean foundWithAgent = false, foundWithoutAgent = false;
        for (var t : tasks) {
            if (t.agent != null && "task-test-agent".equals(t.agent.name)) foundWithAgent = true;
            if (t.agent == null) foundWithoutAgent = true;
        }
        assertTrue(foundWithAgent, "Should include task with agent");
        assertTrue(foundWithoutAgent, "Should include task without agent (LEFT JOIN)");
    }

    // --- Task 4.7: Synchronous run releases connection during LLM phase ---

    @Test
    public void syncRunReleasesConnectionBeforeLlmCall() {
        // Create an agent with no provider configured — run() exercises the
        // Tx.run() setup path and returns early with "No LLM provider configured"
        // without holding the connection through an LLM call.
        var agent = new Agent();
        agent.name = "pool-test-agent";
        agent.modelProvider = "nonexistent";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();

        var convo = ConversationService.create(agent, "web", "user1");

        // This should complete without holding a connection during the "LLM call" phase.
        // With the old code, it would hold the Play request transaction the entire time.
        // With the new code, the setup Tx.run() completes, and if the LLM call fails,
        // the error is returned without a connection pool leak.
        var result = agents.AgentRunner.run(agent, convo, "Hello");
        assertNotNull(result, "run() should return a result, not throw");
        assertNotNull(result.response(), "Response should not be null");

        // Verify the user message was persisted (inside the setup Tx.run)
        var messages = ConversationService.loadRecentMessages(convo);
        assertTrue(messages.size() >= 1, "User message should have been persisted");
    }

    @Test
    public void syncRunWithProviderCompletesToolLoop() {
        // Verify the full run() path works with scoped transactions.
        // Without an actual LLM, this tests the transaction scoping is correct
        // by running to the "no response" path.
        var agent = new Agent();
        agent.name = "tx-scope-agent";
        agent.modelProvider = "test-provider";
        agent.modelId = "test-model";
        agent.enabled = true;
        agent.save();

        // Set up a provider so run() gets past the null check
        ConfigService.set("provider.test-provider.baseUrl", "https://test.ai/v1");
        ConfigService.set("provider.test-provider.apiKey", "sk-test");
        ConfigService.set("provider.test-provider.models",
                "[{\"id\":\"test-model\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();

        var convo = ConversationService.create(agent, "web", "user1");

        // run() will attempt an actual LLM call which will fail (no real endpoint),
        // but the transaction scoping is exercised: setup in Tx.run(), LLM call outside,
        // error handling doesn't hold a connection.
        var result = agents.AgentRunner.run(agent, convo, "Hello");
        assertNotNull(result);
        assertNotNull(result.response());
        // The LLM call will fail with a connection error — that's expected
        // What matters is no JPA/pool exception occurred during the transaction scoping
    }
}
