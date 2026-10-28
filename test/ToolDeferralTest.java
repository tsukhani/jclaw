import agents.ConversationSink;
import agents.DangerousActionGate;
import agents.ParallelToolExecutor;
import agents.ToolRegistry;
import channels.TelegramApprovalCallback;
import channels.TelegramApprovalService;
import channels.TelegramChannel;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import models.Agent;
import models.AgentToolConfig;
import models.TelegramBinding;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import services.TaskToolPolicy;
import services.Tx;
import tools.JClawApiTool;
import tools.ToolInvokeTool;
import utils.GsonHolder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * JF-153: an agent whose native schemas exceed the threshold is sent the core ones and {@code tool_invoke}, and a
 * call through the handle meets every check a direct call meets. Drives {@link ParallelToolExecutor} with the names
 * the request offered, over stub tools whose sizes and results the assertions can state exactly.
 *
 * <p>Holds {@link ToolRegistrySync}: the native registry is replaced for the length of each test.
 */
class ToolDeferralTest extends UnitTest {

    private static final String BOT_TOKEN = "deferral-bot-" + UUID.randomUUID();
    private static final String TG_USER = "7531";
    private static final String NOT_ENABLED = "Error: Tool '%s' is not enabled for this agent. Do not retry it — "
            + "use only the tools listed in your request.";
    private static final Pattern CALLBACK_ID = Pattern.compile("a:[osad]:([0-9a-f]+)");

    private final List<String> ran = new CopyOnWriteArrayList<>();
    private Agent agent;
    private Long conversationId;

    @BeforeEach
    void setup() {
        ToolRegistrySync.canonicalForTest();
        ToolRegistry.publish(List.of(
                stub("exec", "Runs a command.", "Runs a command.", false),
                // Alone past the threshold, so the agent defers whatever else it holds.
                stub("cold_a", "Plans the work.", "Plans the work.\n" + "p".repeat(41_000), false),
                stub("cold_b", "Counts things.", "Counts things.\nSecond line.", false),
                stub("cold_danger", "Deletes things.", "Deletes things.", true),
                new JClawApiTool()));
        agent = commitInFreshTx(() -> AgentService.create(uniqueName(), "openrouter", "gpt-4.1"));
        conversationId = commitInFreshTx(() -> ConversationService.create(Agent.findById(agent.id), "web", null).id);
    }

    @AfterEach
    void teardown() {
        ToolRegistrySync.release();
    }

    @Test
    void anAgentOverTheThresholdIsSentTheCoreSchemasAndOneHandleBeforeItsMcpServers() {
        var defs = new ArrayList<>(ToolRegistry.getToolDefsForAgent(agent));
        // Registered as no native tool, so it stands where an MCP server's handle stands.
        defs.add(ToolDef.of("mcp_zz", "An MCP server.", Map.of("type", "object")));

        var sent = ToolRegistry.deferCold(agent, defs);

        assertEquals(List.of("exec", ToolInvokeTool.NAME, "mcp_zz"), names(sent));
        assertEquals(new ToolInvokeTool().description(), sent.get(1).function().description());
        assertEquals(List.of("cold_a", "cold_b", "cold_danger"), ToolInvokeTool.namesIn(sent.get(1)));
        assertEquals(Set.of("exec", ToolInvokeTool.NAME, "mcp_zz", "cold_a", "cold_b", "cold_danger"),
                ToolRegistry.offeredNames(sent));
    }

    @Test
    void anAgentUnderTheThresholdIsSentItsSchemasUnchanged() {
        grant("cold_a", false);
        var defs = ToolRegistry.getToolDefsForAgent(agent);

        assertEquals(List.of("exec", "cold_b", "cold_danger"), names(defs));
        assertEquals(defs, ToolRegistry.deferCold(agent, defs));
        assertFalse(ToolRegistry.defersTools(agent));
    }

    @Test
    void aTaskFencedToToolsUnderTheThresholdIsSentThemWholeOnAnAgentThatDefers() {
        var defs = ToolRegistry.deferCold(agent, TaskToolPolicy.restrict(ToolRegistry.getToolDefsForAgent(agent),
                Set.of("exec", "cold_b", "cold_danger"), "fenced", agent.name));

        assertTrue(ToolRegistry.defersTools(agent), "control: the agent's unfenced turns defer");
        assertEquals(List.of("exec", "cold_b", "cold_danger"), names(defs));
        assertEquals("ran cold_b with {\"n\":3}", dispatch(defs, "cold_b", "{\"n\":3}"));
    }

    @Test
    void aTurnSentNoHandleRefusesACallToIt() {
        grant("cold_a", false);
        var defs = sentDefs();

        assertEquals(NOT_ENABLED.formatted(ToolInvokeTool.NAME),
                dispatch(defs, ToolInvokeTool.NAME, "{\"tool\":\"cold_b\",\"args\":{\"n\":3}}"));
        assertEquals(List.of(), ran);
    }

    @Test
    void theThresholdIsCrossedPastTenThousandEstimatedTokens() {
        // Unsaved, so nothing is disabled and the one stub is the agent's whole schema.
        var boundary = new Agent();
        boundary.name = uniqueName();

        ToolRegistry.publish(List.of(paddedTo(40_001)));
        assertEquals(40_001, GsonHolder.GSON.toJson(ToolRegistry.getToolDefsForAgent(boundary).getFirst()).length());
        assertFalse(ToolRegistry.defersTools(boundary), "40,001 characters estimate at 10,000 tokens");

        ToolRegistry.publish(List.of(paddedTo(40_002)));
        assertEquals(40_002, GsonHolder.GSON.toJson(ToolRegistry.getToolDefsForAgent(boundary).getFirst()).length());
        assertTrue(ToolRegistry.defersTools(boundary), "40,002 characters estimate at 10,001 tokens");
    }

    @Test
    void callingTheHandleWithNoArgumentsListsEachDeferredToolInOneLine() {
        var result = dispatch(sentDefs(), ToolInvokeTool.NAME, "{}");

        assertEquals("{\"inputSchemas\":\"Not listed. Call tool_invoke with {\\\"describe\\\": [\\\"<tool>\\\", ...]} "
                + "for the input schemas of the tools you will use, before calling them.\",\"tools\":["
                + "{\"name\":\"cold_a\",\"description\":\"Plans the work.\"},"
                + "{\"name\":\"cold_b\",\"description\":\"Counts things.\"},"
                + "{\"name\":\"cold_danger\",\"description\":\"Deletes things.\"}]}", result);
        assertEquals(List.of(), ran);
    }

    @Test
    void describingReturnsEachNamedToolsWholeSchemaAndNamesTheUnknownOnes() {
        var result = dispatch(sentDefs(), ToolInvokeTool.NAME, "{\"describe\":[\"cold_b\",\"cold_z\"]}");

        assertEquals("{\"tools\":[{\"name\":\"cold_b\",\"description\":\"Counts things.\\nSecond line.\","
                + "\"inputSchema\":{\"properties\":{\"n\":{\"type\":\"integer\"}},\"required\":[\"n\"],"
                + "\"type\":\"object\"}}],\"unknown\":[\"cold_z\"]}", result);
    }

    @Test
    void theListAndTheSchemasReachTheModelWholeWhileARunIsTheNamedToolsOwn() {
        var handle = ToolRegistry.lookupTool(ToolInvokeTool.NAME);

        ToolRegistry.publish(List.of(stub("cold_b", "Counts things.", "Counts things.", false), keeper("cold_keep")));

        assertTrue(handle.verbatimResult("{}"));
        assertTrue(handle.verbatimResult("{\"describe\":[\"cold_b\"]}"));
        assertTrue(handle.verbatimResult("{\"tool\":\"cold_keep\",\"args\":{}}"));
        assertTrue(handle.keepsResultInContext("{\"tool\":\"cold_keep\",\"args\":{}}"));
        assertFalse(handle.verbatimResult("{\"tool\":\"cold_b\",\"args\":{\"n\":3}}"));
        assertFalse(handle.keepsResultInContext("{\"tool\":\"cold_b\",\"args\":{\"n\":3}}"));
        assertFalse(handle.keepsResultInContext("{}"));
    }

    @Test
    void theHandleRunsNoToolItselfSoOnlyTheTurnsGatesDispatchOne() {
        var refusal = "Error: tool_invoke runs no tool itself; the turn's tool loop dispatches 'cold_danger' as its own call.";

        assertEquals(refusal, ToolRegistry.execute(ToolInvokeTool.NAME, "{\"tool\":\"cold_danger\",\"args\":{}}", agent));
        var offered = ToolRegistry.executeRich(ToolInvokeTool.NAME, "{\"tool\":\"cold_danger\",\"args\":{}}", agent,
                ToolRegistry.offeredNames(sentDefs()));
        assertEquals(refusal, offered.text());
        assertEquals(ToolRegistry.ToolResult.Outcome.NOT_ENABLED, offered.outcome());
        assertEquals(List.of(), ran);
    }

    @Test
    void aCallThroughTheHandleRunsTheNamedToolAndIsLoggedUnderItsName() {
        var result = new AtomicReference<String>();
        var events = EventLogger.captureForTest(() ->
                result.set(dispatch(sentDefs(), ToolInvokeTool.NAME, "{\"tool\":\"cold_b\",\"args\":{\"n\":3}}")));

        assertEquals("ran cold_b with {\"n\":3}", result.get());
        assertEquals(List.of("cold_b"), ran);
        assertEquals(List.of("Executing tool 'cold_b' (id: c1, args: {\"n\":3})",
                        "Tool 'cold_b' returned: ran cold_b with {\"n\":3}"),
                events.stream().filter(e -> "tool".equals(e.category())).map(EventLogger.Captured::message).toList());
    }

    @Test
    void anUnknownNameIsRefusedNamingTheListCall() {
        var result = dispatch(sentDefs(), ToolInvokeTool.NAME, "{\"tool\":\"cold_z\",\"args\":{}}");

        assertEquals("Error: tool_invoke reaches no tool named 'cold_z'. It reaches: cold_a, cold_b, cold_danger. "
                + "Call tool_invoke with no arguments to list them with one line each.", result);
        assertEquals(List.of(), ran);
    }

    @Test
    void aToolTheRequestCarriesIsCalledByItsOwnName() {
        var result = dispatch(sentDefs(), ToolInvokeTool.NAME, "{\"tool\":\"exec\",\"args\":{}}");

        assertEquals("Error: 'exec' is in your tool list: call it directly, not through tool_invoke.", result);
        assertEquals(List.of(), ran);
    }

    @Test
    void aDisabledToolIsRefusedThroughTheHandleAsItIsDirectly() {
        grant("cold_b", false);
        var defs = sentDefs();

        var direct = dispatch(defs, "cold_b", "{\"n\":3}");
        var throughHandle = dispatch(defs, ToolInvokeTool.NAME, "{\"tool\":\"cold_b\",\"args\":{\"n\":3}}");

        assertEquals(List.of("cold_a", "cold_danger"), ToolInvokeTool.namesIn(defs.get(1)));
        assertEquals(NOT_ENABLED.formatted("cold_b"), direct);
        assertEquals(direct, throughHandle);
        assertEquals(List.of(), ran);
    }

    @Test
    void aToolATaskFencesOutIsRefusedThroughTheHandleAsItIsDirectly() {
        var defs = ToolRegistry.deferCold(agent, TaskToolPolicy.restrict(ToolRegistry.getToolDefsForAgent(agent),
                Set.of("exec", "cold_a", "cold_danger"), "fenced", agent.name));

        var direct = dispatch(defs, "cold_b", "{\"n\":3}");
        var throughHandle = dispatch(defs, ToolInvokeTool.NAME, "{\"tool\":\"cold_b\",\"args\":{\"n\":3}}");

        assertEquals(List.of("cold_a", "cold_danger"), ToolInvokeTool.namesIn(defs.get(1)));
        assertEquals(NOT_ENABLED.formatted("cold_b"), direct);
        assertEquals(direct, throughHandle);
        assertEquals(List.of(), ran);
    }

    @Test
    void aToolTheTurnWithholdsFromAGuestIsRefusedThroughTheHandleAsItIsDirectly() {
        grant(JClawApiTool.TOOL_NAME, true);
        var args = "{\"method\":\"GET\",\"path\":\"/api/agents\"}";
        var owners = ToolRegistry.forTurn(sentDefs(), true);
        var guests = ToolRegistry.forTurn(sentDefs(), false);

        assertEquals("Error: 'jclaw_api' is in your tool list: call it directly, not through tool_invoke.",
                dispatch(owners, ToolInvokeTool.NAME, "{\"tool\":\"jclaw_api\",\"args\":" + args + "}"),
                "control: the owner's turn carries it");
        var direct = dispatch(guests, JClawApiTool.TOOL_NAME, args);
        assertEquals(NOT_ENABLED.formatted(JClawApiTool.TOOL_NAME), direct);
        assertEquals(direct, dispatch(guests, ToolInvokeTool.NAME, "{\"tool\":\"jclaw_api\",\"args\":" + args + "}"));
    }

    @Test
    void aDangerousToolThroughTheHandleAsksForTheSameApprovalAsADirectCall() throws Exception {
        var bound = commitInFreshTx(() -> {
            var created = AgentService.create(uniqueName(), "openrouter", "gpt-4.1");
            var binding = new TelegramBinding();
            binding.agent = created;
            binding.botToken = BOT_TOKEN;
            binding.telegramUserId = TG_USER;
            binding.enabled = true;
            binding.save();
            return created;
        });
        var telegramConversation = commitInFreshTx(() ->
                ConversationService.create(Agent.findById(bound.id), "telegram", TG_USER).id);
        var defs = ToolRegistry.deferCold(bound, ToolRegistry.getToolDefsForAgent(bound));
        try (var server = new MockTelegramServer()) {
            server.start();
            TelegramChannel.installForTest(BOT_TOKEN, server.telegramUrl());
            try {
                var direct = deniedPrompt(server, bound, telegramConversation, defs, "cold_danger", "{}");
                var throughHandle = deniedPrompt(server, bound, telegramConversation, defs, ToolInvokeTool.NAME,
                        "{\"tool\":\"cold_danger\",\"args\":{}}");

                assertEquals(direct.prompt(), throughHandle.prompt());
                assertEquals(DangerousActionGate.abortResult("cold_danger"), direct.result());
                assertEquals(direct.result(), throughHandle.result());
                assertEquals(List.of(), ran);
            } finally {
                TelegramChannel.clearForTest(BOT_TOKEN);
            }
        }
    }

    /** The approval prompt a call raised, its id masked, and the result once the owner denied it. */
    private record Denied(String prompt, String result) {}

    private Denied deniedPrompt(MockTelegramServer server, Agent on, Long conversation, List<ToolDef> defs,
                                String name, String args) throws Exception {
        var seen = new HashSet<String>();
        for (var r : server.requests()) {
            var m = CALLBACK_ID.matcher(r.body());
            if (m.find()) seen.add(m.group(1));
        }
        var result = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try {
                result.complete(dispatch(on, conversation, defs, name, args));
            } catch (RuntimeException e) {
                result.completeExceptionally(e);
            }
        });
        for (int i = 0; i < 500; i++) {
            for (var r : server.requests()) {
                if (!r.method().equalsIgnoreCase("sendMessage")) continue;
                var m = CALLBACK_ID.matcher(r.body());
                if (!m.find() || seen.contains(m.group(1)) || !TelegramApprovalService.isPending(m.group(1))) continue;
                var id = m.group(1);
                TelegramApprovalService.resolve(id, TelegramApprovalCallback.Decision.DENY, TG_USER);
                return new Denied(r.body().replace(id, "<id>"), result.get(5, TimeUnit.SECONDS));
            }
            Thread.sleep(10);
        }
        throw new AssertionError("no approval prompt arrived for " + name);
    }

    private List<ToolDef> sentDefs() {
        return ToolRegistry.deferCold(agent, ToolRegistry.getToolDefsForAgent(agent));
    }

    private String dispatch(List<ToolDef> defs, String name, String args) {
        return dispatch(agent, conversationId, defs, name, args);
    }

    /** One call dispatched as a turn that sent {@code defs} dispatches it; the text the model reads back. */
    private static String dispatch(Agent on, Long conversation, List<ToolDef> defs, String name, String args) {
        var messages = new ArrayList<ChatMessage>();
        var loaded = Tx.run(() -> ConversationService.findById(conversation));
        ParallelToolExecutor.executeToolsParallel(List.of(new ToolCall("c1", "function", new FunctionCall(name, args))),
                on, conversation, messages, null, null, null, new AtomicBoolean(false), new ConversationSink(loaded),
                ToolRegistry.offeredNames(defs));
        return (String) messages.getLast().content();
    }

    private void grant(String tool, boolean enabled) {
        commitInFreshTx(() -> {
            var row = new AgentToolConfig();
            row.agent = Agent.findById(agent.id);
            row.toolName = tool;
            row.enabled = enabled;
            row.save();
            return row.id;
        });
        ToolRegistry.invalidateDisabledToolsCache(agent);
    }

    private ToolRegistry.Tool stub(String name, String summary, String description, boolean dangerous) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String summary() { return summary; }
            @Override public String description() { return description; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of("n", Map.of("type", "integer")),
                        "required", List.of("n"));
            }
            @Override public boolean dangerous() { return dangerous; }
            @Override public String execute(String argsJson, Agent caller) {
                ran.add(name);
                return "ran " + name + " with " + argsJson;
            }
        };
    }

    /** A stub whose result the model reads whole and keeps, as a skill's text is. */
    private ToolRegistry.Tool keeper(String name) {
        var base = stub(name, "Keeps its result.", "Keeps its result.", false);
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return base.description(); }
            @Override public Map<String, Object> parameters() { return base.parameters(); }
            @Override public boolean verbatimResult(String argsJson) { return true; }
            @Override public boolean keepsResultInContext(String argsJson) { return true; }
            @Override public String execute(String argsJson, Agent caller) { return base.execute(argsJson, caller); }
        };
    }

    /** A stub whose definition serializes to exactly {@code chars} characters. */
    private ToolRegistry.Tool paddedTo(int chars) {
        var base = GsonHolder.GSON.toJson(ToolDef.of("pad", "", stub("pad", "", "", false).parameters())).length();
        return stub("pad", "Pads.", "a".repeat(chars - base), false);
    }

    private static List<String> names(List<ToolDef> defs) {
        return defs.stream().map(d -> d.function().name()).toList();
    }

    private static String uniqueName() {
        return "deferral-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var thread = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable e) {
                err.set(e);
            }
        });
        try {
            thread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        if (err.get() != null) throw new AssertionError(err.get());
        return ref.get();
    }
}
