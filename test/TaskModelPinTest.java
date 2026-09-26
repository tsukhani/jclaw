import agents.AgentRunner;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.ProviderRegistry;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.ConfigService;
import services.Tx;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** A task's own model pin decides which model its fire calls; without one the fire follows the agent. */
class TaskModelPinTest extends UnitTest {

    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";

    // Own provider name: breakers are keyed by provider in a process-global registry.
    private final String provider = "task-pin-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicReference<String> calledModel = new AtomicReference<>();
    private HttpServer llmServer;
    private Long agentId;

    @BeforeEach
    void setup() throws Exception {
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calledModel.set(JsonParser.parseString(body).getAsJsonObject().get("model").getAsString());
            var reply = REPLY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply);
            exchange.close();
        });
        llmServer.start();

        ConfigService.set("provider." + provider + ".baseUrl",
                "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models", """
                [{"id":"agent-model","name":"Agent","contextWindow":100000,"maxTokens":4096},
                 {"id":"pinned-model","name":"Pinned","contextWindow":100000,"maxTokens":4096}]""");
        ProviderRegistry.refresh();

        var agent = new Agent();
        agent.name = provider + "-agent";
        agent.modelProvider = provider;
        agent.modelId = "agent-model";
        agent.enabled = true;
        agent.save();
        agentId = agent.id;
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    @AfterEach
    void teardown() {
        llmServer.stop(0);
        CircuitBreakers.remove(provider);
        for (var suffix : new String[] {"baseUrl", "apiKey", "models"}) {
            ConfigService.delete("provider." + provider + "." + suffix);
        }
        ProviderRegistry.refresh();
    }

    @Test
    void aPinnedTaskFiresOnItsOwnModel() throws Exception {
        fire(provider, "pinned-model");

        assertEquals("pinned-model", calledModel.get(), "the task's pin must win over the agent's model");
    }

    @Test
    void anUnpinnedTaskFiresOnTheAgentsModel() throws Exception {
        fire(null, null);

        assertEquals("agent-model", calledModel.get());
    }

    @Test
    void aHalfSetPinIsIgnored() throws Exception {
        fire(provider, null);

        assertEquals("agent-model", calledModel.get());
    }

    @Test
    void aPinToAnUnconfiguredProviderFiresOnTheAgentsModel() throws Exception {
        fire("no-such-provider-" + UUID.randomUUID(), "pinned-model");

        assertEquals("agent-model", calledModel.get(),
                "an unconfigured pinned provider must not reach the primary provider with a foreign model id");
    }

    // A task fires on a scheduler thread with no ambient transaction; the test thread holds one.
    private void fire(String pinnedProvider, String pinnedModelId) throws Exception {
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
                AgentRunner.runForTask(agent, "Summarize the day.", new AgentExecutionSinkTest.Recorder(),
                        null, "pinned-task", pinnedProvider, pinnedModelId);
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the task fire should complete within 30s");
        if (error.get() != null) throw error.get();
    }
}
