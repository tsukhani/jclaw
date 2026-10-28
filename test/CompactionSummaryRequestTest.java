import agents.AgentRunner;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmTypes.ModelInfo;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.SessionCompactor;
import services.Tx;
import slash.Commands;
import utils.AppClock;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * JF-154 end to end: a chat compacts once its prompt reaches the token trigger, however large the window, and the
 * summary call reuses the agent's own request — its system prompt, history and tools — with the instruction last,
 * and never runs a tool; {@code /compact} sends the same request. On the 1,000,000-token window here the trigger's
 * default (JF-159) is its 150,000-token cap, which the long seeded histories pass; the keys are held absent under
 * {@link CompactionTriggerSync}.
 */
class CompactionSummaryRequestTest extends UnitTest {

    private static final String PROVIDER = "jf154-compaction";
    private static final String MODEL = "jf154-model";
    private static final String TOOL = "counter_bump";
    private static final List<String> SECTIONS = List.of("Goals and constraints", "Decisions",
            "Identifiers and facts", "Open questions and errors", "Next steps");

    private HttpServer llmServer;
    private final List<JsonObject> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger toolRuns = new AtomicInteger();

    @BeforeEach
    void setup() {
        CompactionTriggerSync.acquire();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    @AfterEach
    void teardown() {
        try {
            if (llmServer != null) llmServer.stop(0);
            for (var key : List.of("baseUrl", "apiKey", "models")) ConfigService.delete("provider." + PROVIDER + "." + key);
            llm.ProviderRegistry.refresh();
            CircuitBreakers.remove(PROVIDER);
        } finally {
            CompactionTriggerSync.release();
        }
    }

    @Test
    void theTokenTriggerIsOnByDefaultAtItsCapOnThisWindow() {
        var window = new ModelInfo(MODEL, MODEL, 1_000_000, 4096, false);
        assertTrue(SessionCompactor.shouldCompact(150_000, window));
        assertFalse(SessionCompactor.shouldCompact(149_999, window));
    }

    @Test
    void theSummaryCallSendsTheAgentsOwnPrefixAndToolsWithTheInstructionLast() throws Exception {
        startProvider(n -> text(n == 2 ? "SUMMARY-JF154" : "Reply " + n));
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf154-prefix");
        seed(convo, 4, 40);
        commit();

        ToolRegistrySync.withTools(List.of(countingTool()), () -> {
            run(agent, convo, "First question");
            seed(convo, 24, 32_000);
            commit();
            run(agent, convo, "Second question");
        });

        assertEquals(3, requests.size(), "turn one, the summary, then turn two");
        var turnOne = requests.get(0).getAsJsonArray("messages");
        var summary = requests.get(1).getAsJsonArray("messages");
        var turnTwo = requests.get(2).getAsJsonArray("messages");

        // Everything turn one sent but its trailing clock message is the summary call's prefix.
        int shared = turnOne.size() - 1;
        assertEquals(slice(turnOne, 0, shared), slice(summary, 0, shared));
        assertEquals(List.of(TOOL), toolNames(requests.get(0)));
        assertEquals(requests.get(0).get("tools"), requests.get(1).get("tools"));
        assertEquals(requests.get(2).get("tools"), requests.get(1).get("tools"), "the turn whose prefix is summarized");
        var instruction = summary.get(summary.size() - 1).getAsJsonObject();
        assertEquals("user", instruction.get("role").getAsString());
        assertEquals(SECTIONS, headings(instruction.get("content").getAsString()));

        // The summarized turns and the turns kept verbatim are the stored rows, split on a user row.
        var summarized = slice(summary, 1, summary.size() - 1);
        var kept = slice(turnTwo, 1, turnTwo.size() - 1);
        assertEquals("user", kept.getFirst().getAsJsonObject().get("role").getAsString());
        var sent = new ArrayList<String>();
        for (var m : summarized) sent.add(roleAndContent(m));
        for (var m : kept) sent.add(roleAndContent(m));
        assertEquals(storedRows(convo.id), sent);

        var stored = latestCompaction(convo.id);
        assertEquals(content(summary.get(0)) + "\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\n"
                + SessionCompactor.segmentLabel(stored) + "\n\nSUMMARY-JF154", content(turnTwo.get(0)));
        assertEquals("SUMMARY-JF154", stored.summary);
        assertEquals(summarized.size(), stored.turnCount);
    }

    @Test
    void aToolCallReplyIsRetriedOnceWithoutToolsAndItsToolNeverRuns() throws Exception {
        startProvider(n -> switch (n) {
            case 1 -> toolCall();
            case 2 -> text("SUMMARY-AFTER-RETRY");
            default -> text("Reply after compaction.");
        });
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf154-retry");
        seed(convo, 24, 32_000);
        commit();

        var result = new AtomicReference<AgentRunner.RunResult>();
        ToolRegistrySync.withTools(List.of(countingTool()), () -> result.set(run(agent, convo, "Next?")));

        assertEquals(3, requests.size(), "the summary, its retry, then the turn");
        assertEquals(List.of(TOOL), toolNames(requests.get(0)));
        var retry = requests.get(1);
        assertFalse(retry.has("tools"), "the retry carries no tools");
        var retryMessages = retry.getAsJsonArray("messages");
        assertEquals(List.of("system", "user"), roles(retryMessages));
        assertEquals(SECTIONS, headings(content(retryMessages.get(0))));
        assertEquals(0, toolRuns.get());
        assertEquals("SUMMARY-AFTER-RETRY", latestCompaction(convo.id).summary);
        assertEquals("Reply after compaction.", result.get().response());
        assertEquals(0L, (long) Tx.run(() -> Message.count("conversation.id = ?1 AND role = ?2",
                convo.id, MessageRole.TOOL.value)));
    }

    @Test
    void bothHalvesOfTheTriggerAtZeroOrBelowLeaveALongChatUncompactedOnALargeWindow() throws Exception {
        startProvider(n -> text("Reply " + n));
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf154-off");
        seed(convo, 24, 32_000);
        ConfigService.set(SessionCompactor.TRIGGER_PERCENT_KEY, "0");
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, "0");
        commit();

        run(agent, convo, "Next?");
        run(agent, convo, "And then?");
        ConfigService.set(SessionCompactor.TRIGGER_PERCENT_KEY, "-1");
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, "-1");
        commit();
        run(agent, convo, "And after that?");

        assertEquals(3, requests.size(), "one call a turn, no summary call");
        assertNull(Tx.run(() -> SessionCompaction.findLatest(Conversation.findById(convo.id))));
    }

    @Test
    void slashCompactSendsTheRequestATurnSendsWithTheInstructionLast() throws Exception {
        startProvider(n -> text(n == 2 ? "SUMMARY-SLASH" : "Reply " + n));
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf154-slash");
        seed(convo, 12, 400);
        commit();

        var result = new AtomicReference<Commands.Result>();
        ToolRegistrySync.withTools(List.of(countingTool()), () -> {
            run(agent, convo, "First question");
            result.set(slashCompact(agent, convo, null));
        });

        assertEquals(2, requests.size(), "the turn, then the /compact summary");
        var turn = requests.get(0).getAsJsonArray("messages");
        var summary = requests.get(1).getAsJsonArray("messages");
        assertEquals(List.of(TOOL), toolNames(requests.get(0)));
        assertEquals(requests.get(0).get("tools"), requests.get(1).get("tools"));
        // The summarized rows follow the turn's own system message, as the turn sent them.
        int shared = summary.size() - 1;
        assertEquals(slice(turn, 0, shared), slice(summary, 0, shared));
        var instruction = summary.get(shared).getAsJsonObject();
        assertEquals("user", instruction.get("role").getAsString());
        assertEquals(SECTIONS, headings(instruction.get("content").getAsString()));
        assertEquals("SUMMARY-SLASH", latestCompaction(convo.id).summary);
        assertTrue(result.get().responseText().startsWith("> Conversation Compacted"), result.get().responseText());
    }

    @Test
    void aSlashCompactHintFollowsTheInstruction() throws Exception {
        startProvider(n -> text("SUMMARY-HINTED"));
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf154-hint");
        seed(convo, 12, 400);
        commit();

        ToolRegistrySync.withTools(List.of(countingTool()), () -> slashCompact(agent, convo, "focus on the ledger"));

        assertEquals(1, requests.size());
        assertEquals(List.of(TOOL), toolNames(requests.getFirst()));
        var messages = requests.getFirst().getAsJsonArray("messages");
        var instruction = content(messages.get(messages.size() - 1));
        assertEquals(SECTIONS, headings(instruction));
        assertTrue(instruction.endsWith("\n\nAdditional user guidance for this summary: focus on the ledger"), instruction);
    }

    @Test
    void aToolCallReplyToSlashCompactIsRetriedWithoutToolsAndItsToolNeverRuns() throws Exception {
        startProvider(n -> n == 1 ? toolCall() : text("SUMMARY-SLASH-RETRY"));
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf154-slash-retry");
        seed(convo, 12, 400);
        commit();

        var result = new AtomicReference<Commands.Result>();
        ToolRegistrySync.withTools(List.of(countingTool()), () -> result.set(slashCompact(agent, convo, null)));

        assertEquals(2, requests.size(), "the summary, then its retry");
        assertEquals(List.of(TOOL), toolNames(requests.get(0)));
        var retry = requests.get(1);
        assertFalse(retry.has("tools"), "the retry carries no tools");
        var retryMessages = retry.getAsJsonArray("messages");
        assertEquals(List.of("system", "user"), roles(retryMessages));
        assertEquals(SECTIONS, headings(content(retryMessages.get(0))));
        assertEquals(0, toolRuns.get());
        assertEquals("SUMMARY-SLASH-RETRY", latestCompaction(convo.id).summary);
        assertTrue(result.get().responseText().startsWith("> Conversation Compacted"), result.get().responseText());
    }

    @Test
    void slashCompactOnAChatPastTheHistoryLimitSummarizesItsOldestRowsFirstInChunks() throws Exception {
        startProvider(n -> text("SUMMARY " + n));
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf159-slash");
        // A millisecond apart, so no chunk's floor can share a timestamp with the row before it.
        var tick = new AtomicLong(Instant.now().minusSeconds(60).toEpochMilli());
        AppClock.runWith(new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(tick.incrementAndGet()); }
        }, () -> seed(convo, 30, 40));
        ConfigService.set("chat.maxContextMessages", "12");
        commit();

        var result = new AtomicReference<Commands.Result>();
        ToolRegistrySync.withTools(List.of(countingTool()), () -> result.set(slashCompact(agent, convo, null)));

        // /compact keeps 4: chunks of rows 0-11 and 12-23 past the limit, then 24-25 ahead of the kept 26-29.
        assertEquals(3, requests.size());
        var rows = storedContents(convo.id);
        var inputs = new ArrayList<String>();
        for (var request : requests) {
            assertFalse(request.has("tools"), "rows the turn never loaded go as the transcript");
            inputs.add(content(request.getAsJsonArray("messages").get(1)));
        }
        // Each chunk goes alone, with no earlier summary folded in (JF-161).
        assertTrue(inputs.get(0).startsWith("[USER] " + rows.get(0)), inputs.get(0));
        assertTrue(inputs.get(1).startsWith("[USER] " + rows.get(12)), inputs.get(1));
        assertTrue(inputs.get(2).startsWith("[USER] " + rows.get(24)), inputs.get(2));
        assertEquals(3L, (long) Tx.run(() -> SessionCompaction.count("conversation.id = ?1", convo.id)));
        assertEquals("""
                > Conversation Compacted

                Summarized 26 earlier messages in 3 summary calls; the summaries the agent reads in their place \
                come to ~6 tokens. Older messages remain in history and will stop being shipped to the model \
                starting next turn.""", result.get().responseText());
    }

    @Test
    void slashCompactOnAChatNeedingMoreThanEightCallsFinishesAndSaysHowManyItMade() throws Exception {
        startProvider(n -> text("SUMMARY " + n));
        var agent = createAgent();
        var convo = ConversationService.create(agent, "web", "u-jf161-slash");
        var tick = new AtomicLong(Instant.now().minusSeconds(60).toEpochMilli());
        AppClock.runWith(new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(tick.incrementAndGet()); }
        }, () -> seed(convo, 120, 40));
        ConfigService.set("chat.maxContextMessages", "12");
        commit();

        var result = new AtomicReference<Commands.Result>();
        ToolRegistrySync.withTools(List.of(countingTool()), () -> result.set(slashCompact(agent, convo, null)));

        // /compact keeps 4: nine chunks of 12 rows past the limit, then rows 108-115 ahead of the kept 116-119.
        assertEquals(10, requests.size());
        var rows = storedContents(convo.id);
        var last = content(requests.getLast().getAsJsonArray("messages").get(1));
        assertTrue(last.startsWith("[USER] " + rows.get(108)), last);
        assertEquals(10L, (long) Tx.run(() -> SessionCompaction.count("conversation.id = ?1", convo.id)));
        // "SUMMARY 1" to "SUMMARY 9" and "SUMMARY 10": 91 characters, 22 tokens at four a token.
        assertEquals("""
                > Conversation Compacted

                Summarized 116 earlier messages in 10 summary calls; the summaries the agent reads in their place \
                come to ~22 tokens. Older messages remain in history and will stop being shipped to the model \
                starting next turn.""", result.get().responseText());
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    /** {@code /compact} as web chat runs it, on a thread with no transaction of its own. */
    private static Commands.Result slashCompact(Agent agent, Conversation convo, String hint) throws Exception {
        var result = new AtomicReference<Commands.Result>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convo.id));
                result.set(Commands.execute(Commands.Command.COMPACT, a, "web", c.peerId, c, hint, true));
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "/compact must finish within 60 s");
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private Agent createAgent() {
        var agent = new Agent();
        agent.name = "jf154-agent";
        agent.modelProvider = PROVIDER;
        agent.modelId = MODEL;
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private void startProvider(IntFunction<String> replyForCall) throws Exception {
        var calls = new AtomicInteger();
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(JsonParser.parseString(body).getAsJsonObject());
            LlmReplyFixture.send(exchange, replyForCall.apply(calls.incrementAndGet()));
        });
        llmServer.start();
        ConfigService.set("provider." + PROVIDER + ".baseUrl", "http://127.0.0.1:" + llmServer.getAddress().getPort());
        ConfigService.set("provider." + PROVIDER + ".apiKey", "sk-test");
        ConfigService.set("provider." + PROVIDER + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"JF-154\",\"contextWindow\":1000000,\"maxTokens\":4096}]");
        llm.ProviderRegistry.refresh();
    }

    private static String text(String content) {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(content);
    }

    private static String toolCall() {
        return """
                {"choices":[{"index":0,"message":{"role":"assistant","content":null,"tool_calls":[
                  {"id":"call_1","type":"function","function":{"name":"%s","arguments":"{}"}}]},
                  "finish_reason":"tool_calls"}],"usage":{"prompt_tokens":10,"completion_tokens":5}}""".formatted(TOOL);
    }

    private ToolRegistry.Tool countingTool() {
        return new ToolRegistry.Tool() {
            @Override public String name() { return TOOL; }
            @Override public String description() { return "Counts its runs"; }
            @Override public Map<String, Object> parameters() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public String execute(String argsJson, Agent agent) {
                toolRuns.incrementAndGet();
                return "counted";
            }
        };
    }

    /** Alternating user and assistant rows of {@code chars} characters of ledger prose, which tokenizes near 4 characters a token. */
    private static void seed(Conversation convo, int rows, int chars) {
        Tx.run(() -> {
            var managed = (Conversation) Conversation.findById(convo.id);
            for (int r = 0; r < rows; r++) {
                var sb = new StringBuilder(chars + 120);
                sb.append("Row ").append(r).append(' ').append(convo.peerId).append('.');
                for (int i = 0; sb.length() < chars; i++) {
                    sb.append(" Note ").append(i).append(": the ledger for account ").append(r * 10_000 + i)
                            .append(" lists invoices, refunds and adjustments in order.");
                }
                if (r % 2 == 0) ConversationService.appendUserMessage(managed, sb.toString());
                else ConversationService.appendAssistantMessage(managed, sb.toString(), null);
            }
        });
    }

    private static void commit() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private static AgentRunner.RunResult run(Agent agent, Conversation convo, String message) throws Exception {
        var result = new AtomicReference<AgentRunner.RunResult>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var a = Tx.run(() -> (Agent) Agent.findById(agent.id));
                var c = Tx.run(() -> (Conversation) Conversation.findById(convo.id));
                result.set(AgentRunner.run(a, c, message));
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the turn must finish within 60 s");
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private static SessionCompaction latestCompaction(Long conversationId) {
        JPA.em().clear();
        var latest = Tx.run(() -> SessionCompaction.findLatest(Conversation.findById(conversationId)));
        assertNotNull(latest, "a summary was stored");
        return latest;
    }

    /** Every stored row's content, oldest first. */
    private static List<String> storedContents(Long conversationId) {
        JPA.em().clear();
        return Tx.run(() -> {
            var out = new ArrayList<String>();
            for (Object o : Message.find("conversation.id = ?1 ORDER BY createdAt, id", conversationId).fetch()) {
                out.add(((Message) o).content);
            }
            return out;
        });
    }

    private static List<String> storedRows(Long conversationId) {
        JPA.em().clear();
        return Tx.run(() -> {
            var out = new ArrayList<String>();
            for (Object o : Message.find("conversation.id = ?1 ORDER BY createdAt, id", conversationId).fetch()) {
                var m = (Message) o;
                out.add(m.role + ": " + m.content);
            }
            // The turn's own reply is stored after the call that the comparison reads.
            out.removeLast();
            return out;
        });
    }

    private static List<JsonElement> slice(JsonArray array, int from, int to) {
        var out = new ArrayList<JsonElement>();
        for (int i = from; i < to; i++) out.add(array.get(i));
        return out;
    }

    private static String content(JsonElement message) {
        return message.getAsJsonObject().get("content").getAsString();
    }

    private static String roleAndContent(JsonElement message) {
        return message.getAsJsonObject().get("role").getAsString() + ": " + content(message);
    }

    private static List<String> roles(JsonArray messages) {
        var out = new ArrayList<String>();
        for (var m : messages) out.add(m.getAsJsonObject().get("role").getAsString());
        return out;
    }

    private static List<String> toolNames(JsonObject request) {
        var out = new ArrayList<String>();
        if (!request.has("tools")) return out;
        for (var t : request.getAsJsonArray("tools")) {
            out.add(t.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
        }
        return out;
    }

    private static List<String> headings(String instruction) {
        return instruction.lines().filter(l -> l.startsWith("### ")).map(l -> l.substring(4)).toList();
    }
}
