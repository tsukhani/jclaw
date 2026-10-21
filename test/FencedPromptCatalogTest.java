import agents.AgentRunner;
import agents.SkillLoader;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.ProviderRegistry;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * A turn that carries a tool allow-list is offered only those tools. Its system prompt has to say
 * the same: the Tool Catalog section once listed the agent's whole set, so a fenced task or its
 * subagent named, and tried, tools it could not call. Read off the request the model is sent.
 */
class FencedPromptCatalogTest extends UnitTest {

    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
    private static final Set<String> ALLOWED = Set.of("web_search", "datetime");
    private static final Pattern CATALOG_ROW = Pattern.compile("(?m)^\\| `([A-Za-z0-9_.-]+)` \\|");

    // Own provider name: breakers are keyed by provider in a process-global registry.
    private final String provider = "fenced-prompt-" + UUID.randomUUID().toString().substring(0, 8);
    private final AtomicReference<String> systemPrompt = new AtomicReference<>();
    private HttpServer llmServer;
    private Long agentId;

    @BeforeEach
    void setup() throws Exception {
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            systemPrompt.set(body.getAsJsonObject().getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString());
            LlmReplyFixture.send(exchange, REPLY);
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"agent-model\",\"name\":\"Agent\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        new jobs.ToolRegistrationJob().doJob();

        var agent = AgentService.create(provider + "-agent", provider, "agent-model");
        agent.enabled = true;
        agent.save();
        agentId = agent.id;
        // The catalog section is written only for an agent that has a skill to check against it.
        var skill = AgentService.workspacePath(agent.name).resolve("skills").resolve("notes");
        Files.createDirectories(skill);
        Files.writeString(skill.resolve("SKILL.md"), """
                ---
                name: notes
                description: Keep short notes
                ---
                # Notes
                Write the note down.
                """);
        SkillLoader.clearCache();
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
    void aFencedFireIsToldOnlyAboutTheToolsItsTaskAllows() throws Exception {
        fire(null);
        var open = catalogTools();
        fire(ALLOWED);
        var fenced = catalogTools();

        assertTrue(open.stream().anyMatch(name -> !ALLOWED.contains(name)),
                "control: without a list the catalog names tools outside it, so the check below can fail: " + open);
        assertEquals(new TreeSet<>(ALLOWED), fenced, "the prompt's catalog must match what the fire can call");
    }

    @Test
    void aTurnInAConversationThatCarriesAListIsToldOnlyAboutThoseTools() throws Exception {
        var conversationId = Tx.run(() -> {
            Agent agent = Agent.findById(agentId);
            var conversation = ConversationService.create(agent, "web", null);
            conversation.enabledToolNames = "[\"web_search\",\"datetime\"]";
            conversation.save();
            return conversation.id;
        });
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();

        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            var conversation = Tx.run(() -> ConversationService.findById(conversationId));
            AgentRunner.run(agent, conversation, "hello");
        });

        assertEquals(new TreeSet<>(ALLOWED), catalogTools(),
                "a fenced task's subagent runs in such a conversation, and its prompt must match its tools");
    }

    private void fire(Set<String> allowedTools) throws Exception {
        onVirtualThread(() -> {
            var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
            AgentRunner.runForTask(agent, "Summarize the day.", new AgentExecutionSinkTest.Recorder(),
                    allowedTools, "fenced-task", null, null);
        });
    }

    /** The tool names in the last request's Tool Catalog section. */
    private Set<String> catalogTools() {
        var prompt = systemPrompt.get();
        int start = prompt.indexOf("## Tool Catalog");
        assertTrue(start >= 0, "the prompt must carry a Tool Catalog section for this check to mean anything");
        int end = prompt.indexOf("\n## ", start + 1);
        var names = new TreeSet<String>();
        var rows = CATALOG_ROW.matcher(prompt.substring(start, end < 0 ? prompt.length() : end));
        while (rows.find()) names.add(rows.group(1));
        return names;
    }

    // A fire and a subagent turn run on their own threads with no ambient transaction; the test thread holds one.
    private static void onVirtualThread(ThrowingRunnable body) throws Exception {
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the turn should complete within 30s");
        if (error.get() != null) throw error.get();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
