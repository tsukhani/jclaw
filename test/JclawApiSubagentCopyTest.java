import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import tools.SubagentSpawnTool;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A helper copy never has {@code jclaw_api}, whatever its parent was granted (JF-293): the operator's grant is to
 * one agent, and a copy is made by that agent's own {@code subagent_spawn}. Read off the copy {@code subagent_spawn}
 * creates and the request its turn sends the model, for a fresh copy and for one that inherits its parent's tools.
 *
 * <p>Holds {@link ToolRegistrySync}: what is checked is what the native registry offers.
 */
class JclawApiSubagentCopyTest extends UnitTest {

    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
    private static final String JCLAW_API = "jclaw_api";

    // Own provider name: breakers are keyed by provider in a process-global registry.
    private final String provider = "jf293-copy-" + UUID.randomUUID().toString().substring(0, 8);
    /** The tool names of each request the model was sent, in order. */
    private final List<Set<String>> offered = new CopyOnWriteArrayList<>();
    private HttpServer llmServer;

    @BeforeEach
    void setup() throws Exception {
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            var tools = new TreeSet<String>();
            if (body.has("tools")) {
                for (var tool : body.getAsJsonArray("tools")) {
                    tools.add(tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
                }
            }
            offered.add(tools);
            LlmReplyFixture.send(exchange, REPLY);
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"agent-model\",\"name\":\"Agent\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        ToolRegistrySync.canonicalForTest();
    }

    @AfterEach
    void teardown() {
        ToolRegistrySync.release();
        llmServer.stop(0);
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
        for (var suffix : new String[] {"baseUrl", "apiKey", "models"}) {
            ConfigService.delete("provider." + provider + "." + suffix);
        }
        ProviderRegistry.refresh();
    }

    @Test
    void aCopyOfAnAgentGrantedJclawApiIsNeitherGivenItNorOfferedIt() throws Exception {
        var parent = AgentService.create(provider + "-agent", provider, "agent-model");
        parent.enabled = true;
        parent.save();
        grant(parent, JCLAW_API);
        // The shell is the opt-in tool a copy does carry (JF-288), which shows the grants were read.
        grant(parent, ToolRegistry.EXEC_TOOL);
        var chat = ConversationService.create(parent, "web", "u-jf293");
        ConversationService.appendUserMessage(chat, "an earlier question");
        ConversationService.appendAssistantMessage(chat, "its answer", null);
        commitAndReopen();
        ToolRegistry.invalidateDisabledToolsCache(parent);
        assertFalse(ToolRegistry.loadDisabledTools(parent).contains(JCLAW_API), "premise: the parent holds the operator's grant");

        for (var args : List.of("{\"task\":\"do work\"}", "{\"task\":\"do work\",\"context\":\"inherit\"}")) {
            offered.clear();

            var copy = copyMadeBy(parent.id, chat.id, args);

            ToolRegistry.invalidateDisabledToolsCache(copy);
            var disabled = ToolRegistry.loadDisabledTools(copy);
            assertTrue(disabled.contains(JCLAW_API), args);
            assertFalse(disabled.contains(ToolRegistry.EXEC_TOOL), "control: the copy carries its parent's shell: " + args);
            var copyTurn = offered.getLast();
            assertFalse(copyTurn.contains(JCLAW_API), args + " offered " + copyTurn);
            assertTrue(copyTurn.contains(ToolRegistry.EXEC_TOOL), "control: " + args + " offered " + copyTurn);
        }
    }

    private static void grant(Agent agent, String tool) {
        var row = new AgentToolConfig();
        row.agent = agent;
        row.toolName = tool;
        row.enabled = true;
        row.save();
    }

    /** The fresh copy one {@code subagent_spawn} from the parent's web chat creates, once its turn has ended. */
    private static Agent copyMadeBy(Long parentId, Long chatId, String argsJson) throws Exception {
        var reply = new AtomicReference<String>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var parent = Tx.run(() -> (Agent) Agent.findById(parentId));
                reply.set(ToolContext.withConversation(chatId, () -> new SubagentSpawnTool().execute(argsJson, parent)));
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "subagent_spawn must complete within 30s");
        if (error.get() != null) throw error.get();
        var json = JsonParser.parseString(reply.get()).getAsJsonObject();
        assertEquals("COMPLETED", json.get("status").getAsString(), reply.get());
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(Long.parseLong(json.get("run_id").getAsString()));
        Agent copy = Agent.findById(run.childAgent.id);
        assertEquals(parentId, copy.parentAgent.id, "premise: the run's child is a copy of the parent");
        return copy;
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
