import agents.ConversationSink;
import agents.DangerousActionGate;
import agents.ParallelToolExecutor;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import llm.ProviderRegistry;
import models.Agent;
import models.AgentToolConfig;
import models.Conversation;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.LoadTestRunner;
import services.Tx;
import tools.AgentSpawnTool;
import tools.SubagentSpawnTool;
import utils.CircuitBreakers;
import utils.GsonHolder;
import utils.LatencyTrace;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JF-164: {@code agent_spawn} hands a task to another of the operator's agents, which runs as itself in a conversation
 * of its own and answers through {@code subagent_spawn}'s pipeline. The model is a stub routed by the task each
 * conversation was given; each test reads what that stub was offered and what the caller got back.
 */
class AgentSpawnToolTest extends UnitTest {

    private static final String MODEL = "agent-spawn-model";
    private static final String STATIC_DESCRIPTION = """
            Hand a task to another agent on this instance and wait for its reply. The agent works in its own \
            conversation and sees only the task you write, so include everything it needs. Its reply is that \
            agent's own output: check it before relying on it. A file it saves stays in its own workspace: link it \
            as files_url followed by the file's path, using the files_url in the result. To run a helper copy of \
            yourself, use subagent_spawn instead.

            Agents you can hand work to:
            """;

    private HttpServer llmServer;
    private ExecutorService llmThreads;
    private String provider;
    /** What the stub model does in a conversation whose task contains a script's marker. */
    private final List<Script> scripts = new CopyOnWriteArrayList<>();
    /** Each model call: the marker of the conversation it served, and the tools it was offered. */
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private record Script(String marker, String toolName, Map<String, Object> toolArgs, String reply) {
        static Script replies(String marker, String reply) {
            return new Script(marker, null, Map.of(), reply);
        }
    }

    private record Request(String marker, Set<String> tools) {}

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        Fixtures.deleteDatabase();
        EventLogger.clear();
        provider = "agent-spawn-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A callee's model call is answered while its caller's call is still open.
        llmThreads = Executors.newVirtualThreadPerTaskExecutor();
        llmServer.setExecutor(llmThreads);
        llmServer.createContext("/chat/completions", this::answer);
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        inFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Spawn\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) {
            ProviderRegistry.refresh();
            if (ProviderRegistry.get(provider) == null) Thread.sleep(20);
        }
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
    }

    @AfterEach
    void tearDown() {
        llmServer.stop(0);
        llmThreads.shutdownNow();
        inFreshTx(() -> {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            return null;
        });
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
        EventLogger.clear();
        ToolRegistrySync.release();
    }

    // ── main hands work to a specialist ──

    @Test
    void mainHandsATaskToASpecialistThatHoldsAToolMainHasDisabled() throws Exception {
        var main = createAgent("main");
        setTool(main, "generate_video", false);
        var videoMaker = createAgent("video-maker");
        setTool(videoMaker, "generate_video", true);
        var web = ConversationService.create(main, "web", "u-main");
        commitAndReopen();
        scripts.add(Script.replies("Make the launch clip.", "CLIP_READY"));

        var result = handOff(main.id, web.id, false, "{\"agent\":\"video-maker\",\"task\":\"Make the launch clip.\"}");

        JPA.em().clear();
        SubagentRun run = SubagentRun.find("childAgent.id = ?1", videoMaker.id).first();
        assertNotNull(run, result);
        assertEquals(expectedResult(run, "CLIP_READY", videoMaker), result);
        assertEquals(1, requests.size(), requests.toString());
        assertTrue(requests.getFirst().tools().contains("generate_video"),
                "the callee is offered its own tools: " + requests.getFirst().tools());
        assertEquals(main.id, run.parentAgent.id);
        assertEquals(web.id, run.parentConversation.id);
        assertNotEquals(web.id, run.childConversation.id, "the callee works in a conversation of its own");
        Conversation calleeConv = Conversation.findById(run.childConversation.id);
        assertEquals("web", calleeConv.channelType, "the callee's conversation takes the caller's channel");
        assertEquals(SubagentRun.Status.COMPLETED, run.status);
        Agent callee = Agent.findById(videoMaker.id);
        assertNull(callee.parentAgent, "the callee stays a top-level agent");
        ToolRegistry.invalidateDisabledToolsCache(callee);
        assertFalse(ToolRegistry.loadDisabledTools(callee).contains("generate_video"),
                "the caller's deny rows are never copied onto the callee");
    }

    @Test
    void aCalleeRunsOnItsOwnModelNotTheCallersOverride() throws Exception {
        var main = createAgent("main");
        var writer = createAgent("writer");
        var web = ConversationService.create(main, "web", "u-model");
        web.modelProviderOverride = provider;
        web.modelIdOverride = "some-other-model";
        web.save();
        commitAndReopen();
        scripts.add(Script.replies("Write the blurb.", "BLURB"));

        handOff(main.id, web.id, false, "{\"agent\":\"writer\",\"task\":\"Write the blurb.\"}");

        JPA.em().clear();
        SubagentRun run = SubagentRun.find("childAgent.id = ?1", writer.id).first();
        Conversation calleeConv = Conversation.findById(run.childConversation.id);
        assertNull(calleeConv.modelProviderOverride);
        assertNull(calleeConv.modelIdOverride);
    }

    // ── depth and the hand-off budget ──

    @Test
    void aChainOfSpecialistsPassesEachReplyUpAtTheDefaultDepthLimit() throws Exception {
        var main = createAgent("main");
        var a = createAgent("researcher");
        var b = createAgent("analyst");
        var c = createAgent("auditor");
        var web = ConversationService.create(main, "web", "u-chain");
        commitAndReopen();
        scripts.add(new Script("Research the market.", "agent_spawn",
                Map.of("agent", "analyst", "task", "Analyse the figures."), "researcher saw: "));
        scripts.add(new Script("Analyse the figures.", "agent_spawn",
                Map.of("agent", "auditor", "task", "Audit the numbers."), "analyst saw: "));
        scripts.add(Script.replies("Audit the numbers.", "AUDITED"));

        var result = withConfig(SubagentSpawnTool.DEPTH_LIMIT_KEY, "1",
                () -> handOff(main.id, web.id, false, "{\"agent\":\"researcher\",\"task\":\"Research the market.\"}"));

        JPA.em().clear();
        SubagentRun toA = SubagentRun.find("childAgent.id = ?1", a.id).first();
        SubagentRun toB = SubagentRun.find("childAgent.id = ?1", b.id).first();
        SubagentRun toC = SubagentRun.find("childAgent.id = ?1", c.id).first();
        assertNotNull(toC, "the analyst handed off to the auditor: " + result);
        assertEquals(a.id, toB.parentAgent.id);
        assertEquals(toA.childConversation.id, toB.parentConversation.id);
        assertEquals(toB.childConversation.id, toC.parentConversation.id);
        var cResult = expectedResult(toC, "AUDITED", c);
        var bResult = expectedResult(toB, "analyst saw: " + cResult, b);
        assertEquals(expectedResult(toA, "researcher saw: " + bResult, a), result);
    }

    @Test
    void aChainMayHandWorkBackToAnAgentAlreadyInIt() throws Exception {
        var main = createAgent("main");
        var legal = createAgent("legal");
        var finance = createAgent("finance");
        var web = ConversationService.create(main, "web", "u-revisit");
        commitAndReopen();
        scripts.add(new Script("Review the contract.", "agent_spawn",
                Map.of("agent", "finance", "task", "Price the deal."), "legal saw: "));
        scripts.add(new Script("Price the deal.", "agent_spawn",
                Map.of("agent", "legal", "task", "Confirm clause 4."), "finance saw: "));
        scripts.add(Script.replies("Confirm clause 4.", "CLAUSE_4_CONFIRMED"));

        var result = handOff(main.id, web.id, false, "{\"agent\":\"legal\",\"task\":\"Review the contract.\"}");

        JPA.em().clear();
        List<SubagentRun> toLegal = SubagentRun.find("childAgent.id = ?1 ORDER BY id", legal.id).fetch();
        SubagentRun toFinance = SubagentRun.find("childAgent.id = ?1", finance.id).first();
        assertEquals(2, toLegal.size(), "legal was handed work twice: " + result);
        var confirm = toLegal.get(1);
        assertEquals(finance.id, confirm.parentAgent.id);
        assertNotEquals(toLegal.getFirst().childConversation.id, confirm.childConversation.id,
                "the second visit runs in a conversation of its own");
        var confirmResult = expectedResult(confirm, "CLAUSE_4_CONFIRMED", legal);
        var financeResult = expectedResult(toFinance, "finance saw: " + confirmResult, finance);
        assertEquals(expectedResult(toLegal.getFirst(), "legal saw: " + financeResult, legal), result);
    }

    @Test
    void agentsHandingWorkBackAndForthStopAtTheHandOffBudget() throws Exception {
        var main = createAgent("main");
        var a = createAgent("ping");
        var b = createAgent("pong");
        var web = ConversationService.create(main, "web", "u-ping-pong");
        commitAndReopen();
        scripts.add(new Script("Ping.", "agent_spawn", Map.of("agent", "pong", "task", "Pong."), "ping saw: "));
        scripts.add(new Script("Pong.", "agent_spawn", Map.of("agent", "ping", "task", "Ping."), "pong saw: "));

        var result = withConfig(AgentSpawnTool.MAX_HAND_OFFS_KEY, "3",
                () -> handOff(main.id, web.id, false, "{\"agent\":\"ping\",\"task\":\"Ping.\"}"));

        JPA.em().clear();
        List<SubagentRun> toA = SubagentRun.find("childAgent.id = ?1 ORDER BY id", a.id).fetch();
        SubagentRun toB = SubagentRun.find("childAgent.id = ?1", b.id).first();
        assertEquals(2, toA.size(), result);
        assertEquals(3, SubagentRun.count(), "three hand-offs, and the fourth refused: " + result);
        var innermost = expectedResult(toA.get(1),
                "ping saw: Agent hand-off refused: hand-off limit 3 reached (hand-offs so far: 3). Reply with what you have.",
                a);
        var bResult = expectedResult(toB, "pong saw: " + innermost, b);
        assertEquals(expectedResult(toA.getFirst(), "ping saw: " + bResult, a), result);
    }

    @Test
    void everyHandOffUnderOneTurnDrawsFromTheSameBudget() throws Exception {
        var main = createAgent("main");
        var a = createAgent("researcher");
        var b = createAgent("analyst");
        var c = createAgent("writer");
        var d = createAgent("editor");
        var web = ConversationService.create(main, "web", "u-turn-budget");
        commitAndReopen();
        scripts.add(new Script("Research the market.", "agent_spawn",
                Map.of("agent", "analyst", "task", "Analyse the figures."), "researcher saw: "));
        scripts.add(Script.replies("Analyse the figures.", "FIGURES_ANALYSED"));
        scripts.add(new Script("Write the summary.", "agent_spawn",
                Map.of("agent", "editor", "task", "Edit the summary."), "writer saw: "));
        scripts.add(Script.replies("Edit the summary.", "EDITED"));

        var results = withConfig(AgentSpawnTool.MAX_HAND_OFFS_KEY, "3", () -> inOneTurn(main.id, web.id,
                "{\"agent\":\"researcher\",\"task\":\"Research the market.\"}",
                "{\"agent\":\"writer\",\"task\":\"Write the summary.\"}"));

        JPA.em().clear();
        SubagentRun toA = SubagentRun.find("childAgent.id = ?1", a.id).first();
        SubagentRun toB = SubagentRun.find("childAgent.id = ?1", b.id).first();
        SubagentRun toC = SubagentRun.find("childAgent.id = ?1", c.id).first();
        assertEquals(List.of(
                expectedResult(toA, "researcher saw: " + expectedResult(toB, "FIGURES_ANALYSED", b), a),
                expectedResult(toC, "writer saw: Agent hand-off refused: hand-off limit 3 reached (hand-offs so far: 3)."
                        + " Reply with what you have.", c)),
                results, "main's second chain started with the first chain's two hand-offs already counted");
        assertEquals(0, SubagentRun.count("childAgent.id = ?1", d.id), "no run for the refused hand-off");
    }

    @Test
    void theLastHandOffTheBudgetAllowsRunsAndTheNextIsRefusedAndLogged() throws Exception {
        var main = createAgent("main");
        var a = createAgent("researcher");
        var web = ConversationService.create(main, "web", "u-budget-edge");
        commitAndReopen();
        scripts.add(Script.replies("Look it up.", "LOOKED_UP"));
        var caller = (Agent) Agent.findById(main.id);
        var call = "{\"agent\":\"researcher\",\"task\":\"Look it up.\"}";
        var trace = LatencyTrace.forTurn("web", null);

        trace.handOffs().set(AgentSpawnTool.DEFAULT_MAX_HAND_OFFS - 1);
        var last = onVirtualThread(() -> {
            try (var _ = LatencyTrace.bind(trace)) {
                return ToolContext.withConversation(web.id, () -> new AgentSpawnTool().execute(call, caller));
            }
        });
        var refused = new AtomicReference<String>();
        var events = EventLogger.captureForTest(() -> {
            try (var _ = LatencyTrace.bind(trace)) {
                refused.set(ToolContext.withConversation(web.id, () -> new AgentSpawnTool().execute(call, caller)));
            }
        });

        JPA.em().clear();
        SubagentRun run = SubagentRun.find("childAgent.id = ?1", a.id).first();
        assertEquals(expectedResult(run, "LOOKED_UP", a), last);
        assertEquals("Agent hand-off refused: hand-off limit 10 reached (hand-offs so far: 10). Reply with what you have.",
                refused.get());
        assertEquals(1, SubagentRun.count(), "the refused hand-off wrote no run");
        var limits = events.stream().filter(e -> EventLogger.SUBAGENT_LIMIT_EXCEEDED.equals(e.category())).toList();
        assertEquals(1, limits.size(), events.toString());
        assertEquals("hand-off limit 10 reached (hand-offs so far: 10).",
                JsonParser.parseString(limits.getFirst().details()).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void aCalleeSpawnsItsOwnSubagentAtTheDefaultDepthLimit() throws Exception {
        var main = createAgent("main");
        var a = createAgent("researcher");
        var web = ConversationService.create(main, "web", "u-callee-spawn");
        commitAndReopen();
        scripts.add(new Script("Research the market.", "subagent_spawn",
                Map.of("task", "Collect the raw figures."), "researcher saw: "));
        scripts.add(Script.replies("Collect the raw figures.", "RAW"));

        var result = withConfig(SubagentSpawnTool.DEPTH_LIMIT_KEY, "1",
                () -> handOff(main.id, web.id, false, "{\"agent\":\"researcher\",\"task\":\"Research the market.\"}"));

        JPA.em().clear();
        SubagentRun toA = SubagentRun.find("childAgent.id = ?1", a.id).first();
        SubagentRun toClone = SubagentRun.find("parentAgent.id = ?1", a.id).first();
        assertNotNull(toClone, "the callee spawned a subagent: " + result);
        assertEquals(a.id, toClone.childAgent.parentAgent.id, "the subagent is a helper copy of the callee");
        assertEquals(expectedResult(toA, "researcher saw: " + expectedSpawnResult(toClone, "RAW"), a), result);
        var calleeTools = requestFor("Research the market.").tools();
        var subagentTools = requestFor("Collect the raw figures.").tools();
        assertTrue(calleeTools.contains("agent_spawn"), "the callee may hand work on: " + calleeTools);
        assertFalse(subagentTools.contains("agent_spawn"), "its subagent may not: " + subagentTools);
    }

    @Test
    void aCalleesBatchSubagentsSitOneLevelBelowIt() throws Exception {
        var main = createAgent("main");
        var a = createAgent("researcher");
        var web = ConversationService.create(main, "web", "u-batch-depth");
        commitAndReopen();
        scripts.add(new Script("Research the market.", "subagent_spawn",
                Map.of("tasks", List.of("Clone the work.")), "researcher saw: "));
        scripts.add(new Script("Clone the work.", "subagent_spawn", Map.of("task", "Go deeper."), "clone saw: "));
        scripts.add(Script.replies("Go deeper.", "DEEPER"));

        var outcome = withConfig(SubagentSpawnTool.DEPTH_LIMIT_KEY, "1", () -> {
            handOff(main.id, web.id, false, "{\"agent\":\"researcher\",\"task\":\"Research the market.\"}");
            return awaitCloneOutcome(a.id);
        });

        assertEquals("clone saw: Subagent spawn refused: depth limit 1 exceeded (current depth: 1).", outcome);
        JPA.em().clear();
        SubagentRun clone = SubagentRun.find("parentAgent.id = ?1", a.id).first();
        assertEquals(0, SubagentRun.count("parentAgent.id = ?1", clone.childAgent.id), "the clone spawned nothing");
    }

    @Test
    void aSubagentIsNeverOfferedAgentSpawnAndItsDirectCallIsRefused() throws Exception {
        var main = createAgent("main");
        createAgent("researcher");
        // createAgent grants agent_spawn, so this subagent holds a grant and must still never get the tool.
        var clone = createAgent("main-sub-1");
        clone.parentAgent = main;
        clone.save();
        var conv = ConversationService.create(clone, "web", "u-subagent");
        commitAndReopen();
        var cloneAgent = (Agent) Agent.findById(clone.id);
        ToolRegistry.invalidateDisabledToolsCache(cloneAgent);

        var offered = ToolRegistry.getToolDefsForAgent(cloneAgent).stream().map(d -> d.function().name()).toList();

        assertFalse(offered.contains(AgentSpawnTool.TOOL_NAME), "not even a grant offers it: " + offered);
        assertTrue(offered.contains("subagent_spawn"), offered.toString());
        assertEquals("Error: a subagent cannot hand work to another agent. Return what you have to the agent that "
                + "spawned you.", handOff(clone.id, conv.id, false, "{\"agent\":\"researcher\",\"task\":\"x\"}"));
        assertNoRuns();
    }

    // ── who may be reached ──

    @Test
    void namingTheMainAgentIsRefusedWhateverItsCase() throws Exception {
        var main = createAgent("main");
        var a = createAgent("researcher");
        var conv = ConversationService.create(a, "web", "u-names-main");
        var mainConv = ConversationService.create(main, "web", "u-main-self");
        commitAndReopen();

        assertEquals("Error: agent_spawn cannot hand work to the main agent.",
                handOff(a.id, conv.id, false, "{\"agent\":\"main\",\"task\":\"x\"}"));
        assertEquals("Error: agent_spawn cannot hand work to the main agent.",
                handOff(a.id, conv.id, false, "{\"agent\":\"Main\",\"task\":\"x\"}"));
        assertEquals("Error: agent_spawn cannot hand work to the main agent.",
                handOff(main.id, mainConv.id, false,
                        "{\"agent\":\"main\",\"task\":\"x\"}"));
        assertNoRuns();
    }

    @Test
    void namingAnAgentJclawRunsForItselfIsRefused() throws Exception {
        var main = createAgent("main");
        for (var name : List.of(Agent.EVALTEST_AGENT_NAME, LoadTestRunner.LOADTEST_AGENT_NAME,
                LoadTestRunner.LOADTEST_TOOLS_AGENT_NAME)) {
            createAgent(name);
        }
        var conv = ConversationService.create(main, "web", "u-internal");
        commitAndReopen();

        for (var name : List.of(Agent.EVALTEST_AGENT_NAME, LoadTestRunner.LOADTEST_AGENT_NAME,
                LoadTestRunner.LOADTEST_TOOLS_AGENT_NAME)) {
            assertEquals("Error: agent_spawn cannot hand work to '" + name + "', an agent JClaw runs for itself.",
                    handOff(main.id, conv.id, false, "{\"agent\":\"" + name + "\",\"task\":\"x\"}"));
        }
        assertNoRuns();
    }

    @Test
    void namingItselfIsRefusedPointingAtSubagentSpawn() throws Exception {
        var a = createAgent("researcher");
        var conv = ConversationService.create(a, "web", "u-self");
        commitAndReopen();

        assertEquals("Error: 'researcher' is the calling agent. To run a helper copy of yourself, use subagent_spawn.",
                handOff(a.id, conv.id, false, "{\"agent\":\"researcher\",\"task\":\"x\"}"));
        assertNoRuns();
    }

    @Test
    void namingAnUnknownAgentOrASpawnedCloneIsRefused() throws Exception {
        var main = createAgent("main");
        var clone = createAgent("main-sub-1");
        clone.parentAgent = main;
        clone.save();
        var conv = ConversationService.create(main, "web", "u-unknown");
        commitAndReopen();

        assertEquals("Error: no agent named 'ghost' can take this task. Choose one of the agents listed in this "
                + "tool's description.", handOff(main.id, conv.id, false, "{\"agent\":\"ghost\",\"task\":\"x\"}"));
        assertEquals("Error: no agent named 'main-sub-1' can take this task. Choose one of the agents listed in this "
                + "tool's description.", handOff(main.id, conv.id, false, "{\"agent\":\"main-sub-1\",\"task\":\"x\"}"));
        assertNoRuns();
    }

    @Test
    void namingADisabledAgentIsRefused() throws Exception {
        var main = createAgent("main");
        var off = createAgent("archivist");
        off.enabled = false;
        off.save();
        var conv = ConversationService.create(main, "web", "u-disabled");
        commitAndReopen();

        assertEquals("Error: agent 'archivist' is disabled.",
                handOff(main.id, conv.id, false, "{\"agent\":\"archivist\",\"task\":\"x\"}"));
        assertNoRuns();
    }

    @Test
    void aMissingOrBlankAgentOrTaskIsRefused() throws Exception {
        var main = createAgent("main");
        createAgent("researcher");
        var conv = ConversationService.create(main, "web", "u-args");
        commitAndReopen();

        assertEquals("Error: 'agent' is required.", handOff(main.id, conv.id, false, "{\"task\":\"x\"}"));
        assertEquals("Error: 'agent' is required.", handOff(main.id, conv.id, false, "{\"agent\":\" \",\"task\":\"x\"}"));
        assertEquals("Error: 'task' is required.", handOff(main.id, conv.id, false, "{\"agent\":\"researcher\"}"));
        assertEquals("Error: 'task' is required.",
                handOff(main.id, conv.id, false, "{\"agent\":\"researcher\",\"task\":\"\"}"));
        assertNoRuns();
    }

    // ── the description lists who may be reached ──

    @Test
    void theDescriptionListsEveryAgentTheCallerMayReachAndNoOther() {
        var main = createAgent("main");
        var a = createAgent("A");
        a.description = "Makes videos";
        a.save();
        var b = createAgent("b");
        var clone = createAgent("A-sub-1");
        clone.parentAgent = a;
        clone.save();
        var off = createAgent("disabled-one");
        off.enabled = false;
        off.save();
        for (var name : List.of(Agent.EVALTEST_AGENT_NAME, LoadTestRunner.LOADTEST_AGENT_NAME,
                LoadTestRunner.LOADTEST_TOOLS_AGENT_NAME)) {
            createAgent(name);
        }

        assertEquals(STATIC_DESCRIPTION + "- A: Makes videos\n- b: (no purpose given)", agentSpawnDescription(main));
        assertEquals(STATIC_DESCRIPTION + "- b: (no purpose given)", agentSpawnDescription(a));
        assertEquals(STATIC_DESCRIPTION + "- A: Makes videos", agentSpawnDescription(b));
        assertEquals(STATIC_DESCRIPTION + "(listed per agent)", new AgentSpawnTool().description());
        assertEquals(STATIC_DESCRIPTION + "- A: Makes videos\n- b: (no purpose given)", new AgentSpawnTool().description(null),
                "defs built for no agent exclude no caller");
    }

    @Test
    void theDescriptionSaysNoneWhenNoAgentCanBeReached() {
        var main = createAgent("main");

        assertEquals(STATIC_DESCRIPTION + "none", agentSpawnDescription(main));
    }

    // ── guest turns ──

    @Test
    void aDirectCallOnAGuestsTurnAsksNoAgent() throws Exception {
        var main = createAgent("main");
        createAgent("researcher");
        var guestDm = ConversationService.create(main, "telegram", "5151");
        var group = ConversationService.create(main, "telegram", "-100200");
        commitAndReopen();
        var call = "{\"agent\":\"researcher\",\"task\":\"x\"}";

        assertEquals("Error: agent_spawn is available only on a turn the owner reads alone; no agent was asked.",
                handOff(main.id, guestDm.id, false, call));
        assertEquals("Error: agent_spawn is available only on a turn the owner reads alone; no agent was asked.",
                handOff(main.id, group.id, true, call));
        assertNoRuns();
        assertTrue(requests.isEmpty(), requests.toString());
    }

    @Test
    void aGuestsCallThroughTheToolLoopIsRefusedAsNotOffered() throws Exception {
        var main = createAgent("main");
        createAgent("researcher");
        var guestDm = ConversationService.create(main, "telegram", "5151");
        commitAndReopen();
        var offered = new TreeSet<String>();
        for (var def : ToolRegistry.forTurn(ToolRegistry.getToolDefsForAgent(main), false)) {
            offered.add(def.function().name());
        }
        assertFalse(offered.contains(AgentSpawnTool.TOOL_NAME), offered.toString());

        var reply = onVirtualThread(() -> {
            var conversation = Tx.run(() -> (Conversation) Conversation.findById(guestDm.id));
            var caller = Tx.run(() -> (Agent) Agent.findById(main.id));
            var messages = new ArrayList<ChatMessage>();
            DangerousActionGate.withOwnerInitiated(false, () -> {
                ParallelToolExecutor.executeToolsParallel(
                        List.of(new ToolCall("c1", "function", new FunctionCall(AgentSpawnTool.TOOL_NAME,
                                "{\"agent\":\"researcher\",\"task\":\"x\"}"))),
                        caller, guestDm.id, messages, null, null, null, new AtomicBoolean(false),
                        new ConversationSink(conversation), offered);
                return null;
            });
            return (String) messages.getFirst().content();
        });

        assertEquals("Error: Tool 'agent_spawn' is not enabled for this agent. Do not retry it — "
                + "use only the tools listed in your request.", reply);
        assertNoRuns();
    }

    @Test
    void aCalleeIsOfferedJclawApiAndAgentSpawnOnlyWhenTheOwnerReadsTheHandingTurnAlone() throws Exception {
        var main = createAgent("main");
        var callee = createAgent("researcher");
        setTool(callee, "jclaw_api", true);
        var web = ConversationService.create(main, "web", "u-trust");
        var ownerDm = ConversationService.create(main, "telegram", "4242");
        var group = ConversationService.create(main, "telegram", "-100200");
        var guestDm = ConversationService.create(main, "telegram", "5151");
        commitAndReopen();
        scripts.add(Script.replies("Look it up.", "LOOKED_UP"));
        var call = "{\"agent\":\"researcher\",\"task\":\"Look it up.\"}";

        handOff(main.id, web.id, false, call);
        handOff(main.id, ownerDm.id, true, call);
        // A turn offering agent_spawn is never a guest's, so the guest side is driven one level below the tool's gate.
        pipelineHandOff(main.id, group.id, true, callee.id, "Look it up.");
        pipelineHandOff(main.id, guestDm.id, false, callee.id, "Look it up.");

        assertEquals(4, requests.size(), "one model call per hand-off: " + requests);
        var fromWeb = requests.get(0).tools();
        // The callee is one of the operator's own agents, so it works with the jclaw_api it was granted (JF-293).
        assertTrue(fromWeb.contains("jclaw_api") && fromWeb.contains("agent_spawn"), "from web chat: " + fromWeb);
        assertEquals(fromWeb, requests.get(1).tools(), "from the owner's own Telegram DM");
        var expected = new TreeSet<>(fromWeb);
        expected.remove("jclaw_api");
        expected.remove("agent_spawn");
        assertEquals(expected, requests.get(2).tools(), "from the owner's message in a Telegram group");
        assertEquals(expected, requests.get(3).tools(), "from a guest's Telegram turn");
    }

    // ── task fires ──

    @Test
    void aCalleeInAFireIsBoundByTheTasksToolList() throws Exception {
        var main = createAgent("main");
        var callee = createAgent("researcher");
        var taskRunId = seedTaskRun(main, "[\"agent_spawn\",\"datetime\"]");
        commitAndReopen();
        scripts.add(Script.replies("Check the clock.", "CHECKED"));

        var result = onVirtualThread(() -> {
            var caller = Tx.run(() -> (Agent) Agent.findById(main.id));
            return DangerousActionGate.withFireOrigin("web", () -> ToolContext.withScope(null, taskRunId,
                    () -> new AgentSpawnTool().execute("{\"agent\":\"researcher\",\"task\":\"Check the clock.\"}",
                            caller)));
        });

        JPA.em().clear();
        SubagentRun run = SubagentRun.find("childAgent.id = ?1", callee.id).first();
        assertEquals(expectedResult(run, "CHECKED", callee), result);
        assertEquals(taskRunId, run.parentTaskRun.id);
        Conversation calleeConv = Conversation.findById(run.childConversation.id);
        assertEquals("[\"agent_spawn\",\"datetime\"]", calleeConv.enabledToolNames);
        assertEquals(1, requests.size(), requests.toString());
        assertEquals(new TreeSet<>(Set.of("agent_spawn", "datetime")), requests.getFirst().tools());
    }

    /** The outcome of the batch child {@code parentId} started, once its detached run has ended. */
    private static String awaitCloneOutcome(Long parentId) throws InterruptedException {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            String outcome = inFreshTx(() -> {
                SubagentRun run = SubagentRun.find("parentAgent.id = ?1 AND status = ?2",
                        parentId, SubagentRun.Status.COMPLETED).first();
                return run == null ? null : run.outcome;
            });
            if (outcome != null) return outcome;
            Thread.sleep(50);
        }
        fail("the batch child did not complete within 30s");
        return null;
    }

    @Test
    void theBreadthCapIsSharedWithSubagentSpawn() throws Exception {
        var main = createAgent("main");
        createAgent("researcher");
        var web = ConversationService.create(main, "web", "u-breadth");
        for (int i = 0; i < SubagentSpawnTool.DEFAULT_BREADTH_LIMIT; i++) {
            var child = createAgent("main-sub-" + i);
            child.parentAgent = main;
            child.save();
            var run = new SubagentRun();
            run.parentAgent = main;
            run.childAgent = child;
            run.parentConversation = web;
            run.childConversation = ConversationService.create(child, SubagentSpawnTool.SUBAGENT_CHANNEL, null);
            run.status = SubagentRun.Status.RUNNING;
            run.save();
        }
        commitAndReopen();
        var caller = (Agent) Agent.findById(main.id);
        var result = new AtomicReference<String>();

        // On the test thread, so captureForTest sees the event; the cap refuses before the transaction check.
        var events = EventLogger.captureForTest(() -> result.set(ToolContext.withConversation(web.id,
                () -> new AgentSpawnTool().execute("{\"agent\":\"researcher\",\"task\":\"x\"}", caller))));

        assertEquals("Agent hand-off refused: breadth limit 5 exceeded (running children: 5).", result.get());
        JPA.em().clear();
        assertEquals(SubagentSpawnTool.DEFAULT_BREADTH_LIMIT, SubagentRun.count(), "no new run row");
        var limits = events.stream().filter(e -> EventLogger.SUBAGENT_LIMIT_EXCEEDED.equals(e.category())).toList();
        assertEquals(1, limits.size(), events.toString());
        assertEquals("main", limits.getFirst().agentId());
        assertEquals("breadth limit 5 exceeded (running children: 5).",
                JsonParser.parseString(limits.getFirst().details()).getAsJsonObject().get("reason").getAsString());
    }

    @Test
    void aFireOfATaskAGuestSetUpAsksNoAgent() throws Exception {
        var main = createAgent("main");
        createAgent("researcher");
        var taskRunId = seedTaskRun(main, null, "telegram");
        commitAndReopen();

        var result = onVirtualThread(() -> {
            var caller = Tx.run(() -> (Agent) Agent.findById(main.id));
            return DangerousActionGate.withFireOrigin("telegram", () -> ToolContext.withScope(null, taskRunId,
                    () -> new AgentSpawnTool().execute("{\"agent\":\"researcher\",\"task\":\"x\"}", caller)));
        });

        assertEquals("Error: agent_spawn is available only on a turn the owner reads alone; no agent was asked.", result);
        assertNoRuns();
        assertTrue(requests.isEmpty(), requests.toString());
    }

    // ---- helpers ----

    private String agentSpawnDescription(Agent caller) {
        return ToolRegistry.getToolDefsForAgent(caller).stream()
                .filter(d -> AgentSpawnTool.TOOL_NAME.equals(d.function().name()))
                .findFirst().orElseThrow().function().description();
    }

    private static String expectedResult(SubagentRun run, String reply, Agent callee) {
        var expected = new LinkedHashMap<String, Object>();
        expected.put("run_id", String.valueOf(run.id));
        expected.put("conversation_id", String.valueOf(run.childConversation.id));
        expected.put("reply", reply);
        expected.put("status", "COMPLETED");
        expected.put("agent", callee.name);
        expected.put("agent_id", callee.id);
        expected.put("files_url", "/api/agents/" + callee.id + "/files/");
        return GsonHolder.GSON.toJson(expected, Map.class);
    }

    private static void assertNoRuns() {
        JPA.em().clear();
        assertEquals(0, SubagentRun.count(), "a refused hand-off writes no run");
    }

    /** {@code body} with config {@code key} at {@code value}, restored afterwards. */
    private static <T> T withConfig(String key, String value, ThrowingSupplier<T> body) throws Exception {
        var prior = inFreshTx(() -> ConfigService.get(key));
        inFreshTx(() -> {
            ConfigService.set(key, value);
            return null;
        });
        try {
            return body.get();
        } finally {
            inFreshTx(() -> {
                if (prior == null) ConfigService.delete(key);
                else ConfigService.set(key, prior);
                return null;
            });
        }
    }

    /** Several {@code agent_spawn} calls made one after another by a single chat turn, which a trace binds. */
    private static List<String> inOneTurn(Long callerId, Long conversationId, String... calls) throws Exception {
        return onVirtualThread(() -> {
            var caller = Tx.run(() -> (Agent) Agent.findById(callerId));
            var out = new ArrayList<String>();
            try (var _ = LatencyTrace.bind(LatencyTrace.forTurn("web", null))) {
                for (var call : calls) {
                    out.add(DangerousActionGate.withOwnerInitiated(false, () -> ToolContext.withConversation(
                            conversationId, () -> new AgentSpawnTool().execute(call, caller))));
                }
            }
            return out;
        });
    }

    private Request requestFor(String marker) {
        return requests.stream().filter(r -> marker.equals(r.marker())).findFirst()
                .orElseThrow(() -> new AssertionError("no model call for " + marker + ": " + requests));
    }

    /** What {@code subagent_spawn} returns for a synchronous child that completed with {@code reply}. */
    private static String expectedSpawnResult(SubagentRun run, String reply) {
        var expected = new LinkedHashMap<String, Object>();
        expected.put("run_id", String.valueOf(run.id));
        expected.put("conversation_id", String.valueOf(run.childConversation.id));
        expected.put("reply", reply);
        expected.put("status", "COMPLETED");
        return GsonHolder.GSON.toJson(expected, Map.class);
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    /** {@code agent_spawn} as a chat turn's tool call runs it: on its own thread, in the conversation, with its sender. */
    private static String handOff(Long callerId, Long conversationId, boolean ownerInitiated, String argsJson)
            throws Exception {
        return onVirtualThread(() -> {
            var caller = Tx.run(() -> (Agent) Agent.findById(callerId));
            return DangerousActionGate.withOwnerInitiated(ownerInitiated,
                    () -> ToolContext.withConversation(conversationId,
                            () -> new AgentSpawnTool().execute(argsJson, caller)));
        });
    }

    /** The shared pipeline {@code agent_spawn} runs once its gate has passed, on a turn the gate would refuse. */
    private static String pipelineHandOff(Long callerId, Long conversationId, boolean ownerInitiated, Long calleeId,
                                          String task)
            throws Exception {
        var argsClass = Class.forName("tools.SubagentSpawnArgs");
        var handOff = argsClass.getDeclaredMethod("handOff", String.class, String.class, Long.class, int.class);
        handOff.setAccessible(true);
        var spawnOne = SubagentSpawnTool.class.getDeclaredMethod("spawnOne", JsonObject.class, Agent.class,
                argsClass, String.class, Map.class, AtomicInteger.class);
        spawnOne.setAccessible(true);
        var parsed = handOff.invoke(null, task, null, calleeId, 300);
        return onVirtualThread(() -> {
            var caller = Tx.run(() -> (Agent) Agent.findById(callerId));
            return DangerousActionGate.withOwnerInitiated(ownerInitiated, () -> ToolContext.withConversation(conversationId,
                    () -> {
                        try {
                            return (String) spawnOne.invoke(null, new JsonObject(), caller, parsed,
                                    "Agent hand-off refused: ", Map.of(), null);
                        } catch (ReflectiveOperationException e) {
                            throw new AssertionError(e);
                        }
                    }));
        });
    }

    /** An agent the operator has switched {@code agent_spawn} on for, as every case here hands work on (JF-288). */
    private Agent createAgent(String name) {
        var agent = AgentService.create(name, provider, MODEL);
        agent.enabled = true;
        agent.save();
        if (!agent.isMain()) setTool(agent, "agent_spawn", true);
        return agent;
    }

    private static void setTool(Agent agent, String tool, boolean enabled) {
        var row = new AgentToolConfig();
        row.agent = agent;
        row.toolName = tool;
        row.enabled = enabled;
        row.save();
    }

    private static Long seedTaskRun(Agent agent, String enabledToolNames) {
        return seedTaskRun(agent, enabledToolNames, "web");
    }

    private static Long seedTaskRun(Agent agent, String enabledToolNames, String originChannel) {
        var task = new models.Task();
        task.enabledToolNames = enabledToolNames;
        task.agent = agent;
        task.name = "fenced-" + agent.name;
        task.originChannel = originChannel;
        task.type = models.Task.Type.IMMEDIATE;
        task.status = models.Task.Status.RUNNING;
        task.nextRunAt = java.time.Instant.now();
        task.save();
        var run = new models.TaskRun();
        run.task = task;
        run.startedAt = java.time.Instant.now();
        run.save();
        return run.id;
    }

    /** Routes each model call by the task its conversation was given and the tool result that ends it. */
    private void answer(HttpExchange exchange) throws IOException {
        var body = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
        var messages = body.getAsJsonArray("messages");
        var userText = new StringBuilder();
        for (var m : messages) {
            if ("user".equals(role(m.getAsJsonObject()))) userText.append(textOf(m.getAsJsonObject()));
        }
        var last = messages.get(messages.size() - 1).getAsJsonObject();
        var script = scripts.stream().filter(s -> userText.toString().contains(s.marker())).findFirst().orElse(null);
        var tools = new TreeSet<String>();
        if (body.has("tools")) {
            for (var tool : body.getAsJsonArray("tools")) {
                tools.add(tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
            }
        }
        requests.add(new Request(script == null ? "?" : script.marker(), tools));
        if (script == null) {
            LlmReplyFixture.send(exchange, reply("UNEXPECTED: " + userText));
        } else if ("tool".equals(role(last))) {
            LlmReplyFixture.send(exchange, reply(script.reply() + textOf(last)));
        } else if (script.toolName() != null) {
            LlmReplyFixture.send(exchange, toolCall(script.toolName(), script.toolArgs()));
        } else {
            LlmReplyFixture.send(exchange, reply(script.reply()));
        }
    }

    private static String toolCall(String name, Map<String, Object> arguments) {
        var function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", GsonHolder.GSON.toJson(arguments));
        var call = new JsonObject();
        call.addProperty("id", "call_" + name);
        call.addProperty("type", "function");
        call.add("function", function);
        var calls = new JsonArray();
        calls.add(call);
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", "");
        message.add("tool_calls", calls);
        return completion(message, "tool_calls");
    }

    private static String role(JsonObject message) {
        return message.get("role").getAsString();
    }

    private static String textOf(JsonObject message) {
        var content = message.get("content");
        if (content == null || content.isJsonNull()) return "";
        if (content.isJsonPrimitive()) return content.getAsString();
        var text = new StringBuilder();
        for (var part : content.getAsJsonArray()) {
            var p = part.getAsJsonObject();
            if (p.has("text")) text.append(p.get("text").getAsString());
        }
        return text.toString();
    }

    private static String reply(String content) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        return completion(message, "stop");
    }

    private static String completion(JsonObject message, String finishReason) {
        var choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", message);
        choice.addProperty("finish_reason", finishReason);
        var choices = new JsonArray();
        choices.add(choice);
        var usage = new JsonObject();
        usage.addProperty("prompt_tokens", 10);
        usage.addProperty("completion_tokens", 5);
        var completion = new JsonObject();
        completion.add("choices", choices);
        completion.add("usage", usage);
        return completion.toString();
    }

    @FunctionalInterface
    private interface ThrowingBody<T> {
        T get() throws Exception;
    }

    /** Runs {@code body} on its own thread, outside the test's transaction, as a turn's tool call runs. */
    private static <T> T onVirtualThread(ThrowingBody<T> body) throws Exception {
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                result.set(body.get());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the call did not return within 60s");
        if (error.get() instanceof Exception e) throw e;
        if (error.get() != null) throw new AssertionError(error.get());
        return result.get();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static <T> T inFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
