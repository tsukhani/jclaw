import agents.AgentRunner;
import agents.SkillLoader;
import agents.SystemPromptAssembler;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import tools.ToolInvokeTool;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-153, read off the request the model is sent: an agent whose native schemas pass the threshold gets the core ones
 * and {@code tool_invoke}; one under it, or any with {@code chat.toolDeferral=false}, gets every schema as before; and
 * a call the model makes through the handle runs the named tool in the tool loop.
 *
 * <p>Holds {@link ToolRegistrySync}: what is checked is what the canonical native registry offers.
 */
class ToolDeferralTurnTest extends UnitTest {

    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"done"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5}}""";
    /** Every tool the default policy keeps off a custom agent, granted so the agent holds main's set and more. */
    private static final List<String> OPT_IN = List.of("memory", "jclaw_api", "conversation_search",
            "generate_image", "generate_video", "generate_audio", "printer", "skill_read");
    /** Sent whole: main's six most-called tools and each tool JClaw's own text tells the model to call. */
    private static final Set<String> CORE = Set.of("exec", "filesystem", "ccr_retrieve", "web_search", "jclaw_api",
            "web_fetch", "skill_read", "checklist", "subagent_yield", "memory", "datetime", "documents", "printer",
            "diarize_audio");

    // Own provider name: breakers are keyed by provider in a process-global registry.
    private final String provider = "deferral-turn-" + UUID.randomUUID().toString().substring(0, 8);
    private final List<JsonObject> requestBodies = new CopyOnWriteArrayList<>();
    private final ConcurrentLinkedQueue<String> replies = new ConcurrentLinkedQueue<>();
    private HttpServer llmServer;
    private Long agentId;

    @BeforeEach
    void setup() throws Exception {
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            requestBodies.add(JsonParser.parseString(
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            var reply = replies.poll();
            LlmReplyFixture.send(exchange, reply != null ? reply : REPLY);
        });
        llmServer.start();
        ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + provider + ".apiKey", "sk-test");
        ConfigService.set("provider." + provider + ".models",
                "[{\"id\":\"agent-model\",\"name\":\"Agent\",\"contextWindow\":200000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        ToolRegistrySync.canonicalForTest();

        var agent = AgentService.create(provider + "-agent", provider, "agent-model");
        agent.enabled = true;
        agent.save();
        agentId = agent.id;
        for (var tool : OPT_IN) toolRow(agent, tool, true);
        // The Tool Catalog section is written only for an agent with a skill.
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
        commit();
        ToolRegistry.invalidateDisabledToolsCache(agent);
        SkillLoader.clearCache();
    }

    @AfterEach
    void teardown() {
        ConfigService.delete(ToolRegistry.TOOL_DEFERRAL_KEY);
        ToolRegistrySync.release();
        llmServer.stop(0);
        CircuitBreakers.remove(provider);
        for (var suffix : new String[] {"baseUrl", "apiKey", "models"}) {
            ConfigService.delete("provider." + provider + "." + suffix);
        }
        ProviderRegistry.refresh();
    }

    @Test
    void anAgentOverTheThresholdIsSentExactlyTheCoreSchemasAndTheHandle() throws Exception {
        var agent = agent();
        var natives = names(ToolRegistry.getToolDefsForAgent(agent));
        var core = natives.stream().filter(CORE::contains).toList();
        var cold = natives.stream().filter(n -> !CORE.contains(n)).toList();
        var expected = new ArrayList<>(core);
        expected.add(ToolInvokeTool.NAME);

        turn();

        assertEquals(CORE, Set.copyOf(core), "control: the agent holds every core tool");
        assertTrue(cold.contains("task_manager"), "control: the agent holds tools to defer: " + natives);
        assertEquals(expected, requestTools(0));
        assertEquals(cold, handleNames(0));
        assertTrue(systemPrompt(0).contains(SystemPromptAssembler.DEFERRED_TOOLS_NOTE),
                "the Tool Catalog says how the tools it names but the request leaves out are called");
        assertEquals(expected, SystemPromptAssembler.breakdown(agent, null, "web").tools().stream()
                .map(SystemPromptAssembler.PromptBreakdown.Entry::name).toList(),
                "the prompt breakdown reports the schemas the request carries");
    }

    @Test
    void anAgentUnderTheThresholdIsSentItsToolsUnchanged() throws Exception {
        var agent = agent();
        var kept = Set.of("web_search", "web_fetch", "datetime", "message", "task_manager");
        Tx.run(() -> AgentToolConfig.delete("agent.id = ?1", agentId));
        for (var tool : ToolRegistry.listTools()) {
            if (tool.group() == null) toolRow(agent, tool.name(), kept.contains(tool.name()));
        }
        commit();
        ToolRegistry.invalidateDisabledToolsCache(agent);

        turn();

        assertEquals(List.of("task_manager", "datetime", "web_fetch", "web_search", "message"), requestTools(0));
    }

    @Test
    void theKeyOffSendsEverySchemaAsBefore() throws Exception {
        ConfigService.set(ToolRegistry.TOOL_DEFERRAL_KEY, "false");
        commit();

        turn();

        assertEquals(names(ToolRegistry.getToolDefsForAgent(agent())), requestTools(0));
        assertFalse(systemPrompt(0).contains(SystemPromptAssembler.DEFERRED_TOOLS_NOTE));
    }

    @Test
    void aCallTheModelMakesThroughTheHandleRunsTheNamedTool() throws Exception {
        replies.add("""
                {"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"tool_invoke",
                   "arguments":"{\\"tool\\":\\"jclaw_docs\\",\\"args\\":{}}"}}]},"finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""");

        turn();

        assertEquals(2, requestBodies.size(), "the tool round and the answer after it");
        var messages = requestBodies.get(1).getAsJsonArray("messages");
        var toolResult = messages.get(messages.size() - 1).getAsJsonObject();
        assertEquals("tool", toolResult.get("role").getAsString());
        assertEquals("Error: 'query' is required.", toolResult.get("content").getAsString(),
                "jclaw_docs ran and answered its missing argument, rather than the call being refused");
    }

    private Agent agent() {
        return Tx.run(() -> (Agent) Agent.findById(agentId));
    }

    private void turn() throws Exception {
        var conversationId = Tx.run(() -> ConversationService.create(agent(), "web", null).id);
        commit();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                AgentRunner.run(agent(), Tx.run(() -> ConversationService.findById(conversationId)), "hello");
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the turn should complete within 30s");
        if (error.get() != null) throw error.get();
    }

    private List<String> requestTools(int request) {
        var names = new ArrayList<String>();
        for (var tool : requestBodies.get(request).getAsJsonArray("tools")) {
            names.add(tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        }
        return names;
    }

    private List<String> handleNames(int request) {
        for (var tool : requestBodies.get(request).getAsJsonArray("tools")) {
            var function = tool.getAsJsonObject().getAsJsonObject("function");
            if (!ToolInvokeTool.NAME.equals(function.get("name").getAsString())) continue;
            var names = new ArrayList<String>();
            for (var name : function.getAsJsonObject("parameters").getAsJsonObject("properties")
                    .getAsJsonObject("tool").getAsJsonArray("enum")) {
                names.add(name.getAsString());
            }
            return names;
        }
        throw new AssertionError("the request carries no " + ToolInvokeTool.NAME);
    }

    private String systemPrompt(int request) {
        return requestBodies.get(request).getAsJsonArray("messages").get(0).getAsJsonObject().get("content").getAsString();
    }

    private static List<String> names(List<llm.LlmTypes.ToolDef> defs) {
        return defs.stream().map(d -> d.function().name()).toList();
    }

    private static void toolRow(Agent agent, String tool, boolean enabled) {
        Tx.run(() -> {
            var row = new AgentToolConfig();
            row.agent = Agent.findById(agent.id);
            row.toolName = tool;
            row.enabled = enabled;
            row.save();
        });
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }
}
