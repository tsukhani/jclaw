import agents.AgentRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.Tx;
import tools.CheckListTool;
import utils.CircuitBreakers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JF-565: a turn whose latest checklist call left an item open gets a note asking for an update with the model
 * call that follows the 8th tool round since it, on the sync loop and the streamed one alike, and the note is
 * never stored.
 */
class ToolCallLoopRunnerChecklistNoteTest extends UnitTest {

    private static final String MODEL = "test-model";
    private static final String QUESTION = "Publish the post on three sites.";
    private static final String ANSWER = "All three drafts are up.";
    private static final String FILE = "progress.md";
    private static final String NOTE = "user:" + CheckListTool.STALE_NOTE.formatted(CheckListTool.STALE_AFTER_ROUNDS);

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private String providerName;
    private String agentName;

    @BeforeEach
    void setup() {
        ToolRegistrySync.canonicalForTest();
    }

    @AfterEach
    void teardown() {
        try {
            if (server != null) server.stop(0);
            if (providerName != null) {
                ConfigService.delete("provider." + providerName + ".baseUrl");
                ConfigService.delete("provider." + providerName + ".apiKey");
                ConfigService.delete("provider." + providerName + ".models");
                ProviderRegistry.refresh();
                CircuitBreakers.remove(LlmResilience.breakerName(providerName));
            }
            if (agentName != null) deleteDir(AgentService.workspacePath(agentName));
        } finally {
            ToolRegistrySync.release();
        }
    }

    @Test
    void aSyncTurnAsksForAnUpdateWithTheCallAfterTheEighthRoundSinceAnOpenList() throws Exception {
        var convo = turnScriptedAs(eightRoundsAfter(OPEN_LIST));

        var result = runSync(convo);

        assertEquals(ANSWER, result.response());
        assertNoteOnlyAfterTheEighthRound();
        assertEquals(storedTurn(), stored(convo), "the note is never stored");
    }

    @Test
    void aStreamedTurnAsksForAnUpdateWithTheCallAfterTheEighthRoundSinceAnOpenList() throws Exception {
        var convo = turnScriptedAs(eightRoundsAfter(OPEN_LIST));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(ANSWER, turn.completed.get());
        assertNoteOnlyAfterTheEighthRound();
        assertEquals(storedTurn(), stored(convo), "the note is never stored");
    }

    @Test
    void aTurnWhoseListIsAllCompletedGetsNoNote() throws Exception {
        var convo = turnScriptedAs(eightRoundsAfter(DONE_LIST));

        var turn = runStreamed(convo);

        assertEquals(ANSWER, turn.completed.get());
        assertEquals(10, bodies.size());
        assertEquals("tool<-w8", last(bodies.get(9)));
    }

    private void assertNoteOnlyAfterTheEighthRound() {
        assertEquals(10, bodies.size(), "the list's round, eight more, then the answer");
        for (int i = 1; i <= 8; i++) {
            assertEquals(i == 1 ? "tool<-c1" : "tool<-w" + (i - 1), last(bodies.get(i)), "call " + i + " carries no note");
        }
        assertEquals(NOTE, last(bodies.get(9)));
        assertEquals("tool<-w8", sent(bodies.get(9)).get(sent(bodies.get(9)).size() - 2));
    }

    private static List<String> storedTurn() {
        var out = new ArrayList<>(List.of("user:" + QUESTION, "assistant->c1", "tool<-c1"));
        for (int i = 1; i <= 8; i++) {
            out.add("assistant->w" + i);
            out.add("tool<-w" + i);
        }
        out.add("assistant:" + ANSWER);
        return out;
    }

    // ─── replies ─────────────────────────────────────────────────────────────

    private static final String OPEN_LIST = """
            {"items":[
              {"content":"Draft on LinkedIn","status":"in_progress","activeForm":"Drafting on LinkedIn"},
              {"content":"Draft on Medium","status":"pending","activeForm":"Drafting on Medium"}
            ]}""";

    private static final String DONE_LIST = """
            {"items":[
              {"content":"Draft on LinkedIn","status":"completed","activeForm":"Drafting on LinkedIn"},
              {"content":"Draft on Medium","status":"completed","activeForm":"Drafting on Medium"}
            ]}""";

    /** The list set in the first round, eight filesystem rounds, then the answer. */
    private static String[] eightRoundsAfter(String list) {
        var replies = new ArrayList<String>();
        replies.add(toolCall("c1", "checklist", list));
        for (int i = 1; i <= 8; i++) {
            var args = new JsonObject();
            args.addProperty("action", "writeFile");
            args.addProperty("path", FILE);
            args.addProperty("content", "Step " + i + "\n");
            replies.add(toolCall("w" + i, "filesystem", args.toString()));
        }
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", ANSWER);
        replies.add(completion(message, "stop"));
        return replies.toArray(String[]::new);
    }

    private static String toolCall(String id, String name, String arguments) {
        var function = new JsonObject();
        function.addProperty("name", name);
        function.addProperty("arguments", arguments);
        var call = new JsonObject();
        call.addProperty("id", id);
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
        var reply = new JsonObject();
        reply.add("choices", choices);
        reply.add("usage", usage);
        return reply.toString();
    }

    // ─── what was sent and stored ────────────────────────────────────────────

    /** Each message after the system prompt: {@code assistant->ids}, {@code tool<-id} or {@code role:content}. */
    private static List<String> sent(String body) {
        var out = new ArrayList<String>();
        for (var element : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages")) {
            var m = element.getAsJsonObject();
            var role = m.get("role").getAsString();
            var calls = m.get("tool_calls");
            if (calls != null && calls.isJsonArray() && !calls.getAsJsonArray().isEmpty()) {
                var ids = new ArrayList<String>();
                calls.getAsJsonArray().forEach(c -> ids.add(c.getAsJsonObject().get("id").getAsString()));
                out.add(role + "->" + String.join(",", ids));
            } else if (MessageRole.TOOL.value.equals(role)) {
                out.add("tool<-" + m.get("tool_call_id").getAsString());
            } else if (!MessageRole.SYSTEM.value.equals(role)) {
                var content = m.get("content");
                out.add(role + ":" + (content != null && content.isJsonPrimitive() ? content.getAsString() : ""));
            }
        }
        return out;
    }

    private static String last(String body) {
        return sent(body).getLast();
    }

    private static List<String> stored(Conversation convo) {
        return Tx.run(() -> {
            var out = new ArrayList<String>();
            for (var m : ConversationService.loadRecentMessages(ConversationService.findById(convo.id))) {
                if (m.toolCalls != null) {
                    out.add(m.role + "->" + JsonParser.parseString(m.toolCalls).getAsJsonObject().get("id").getAsString());
                } else if (MessageRole.TOOL.value.equals(m.role)) {
                    out.add("tool<-" + m.toolResults);
                } else {
                    out.add(m.role + ":" + m.content);
                }
            }
            return out;
        });
    }

    // ─── drivers ─────────────────────────────────────────────────────────────

    /** A provider answering each request with the next reply, and an agent on it with a fresh conversation. */
    private Conversation turnScriptedAs(String... replies) throws IOException {
        var next = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            LlmReplyFixture.send(exchange, replies[Math.min(next.getAndIncrement(), replies.length - 1)]);
        });
        server.start();
        providerName = "jf565-" + System.nanoTime();
        ConfigService.set("provider." + providerName + ".baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ConfigService.set("provider." + providerName + ".apiKey", "sk-test");
        ConfigService.set("provider." + providerName + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        agentName = "jf565-agent-" + System.nanoTime();
        var agent = AgentService.create(agentName, providerName, MODEL);
        var convo = ConversationService.create(agent, "web", "jf565-peer-" + System.nanoTime());
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    private static AgentRunner.RunResult runSync(Conversation convo) throws Exception {
        var agentId = convo.agent.id;
        var convoId = convo.id;
        var result = new AtomicReference<AgentRunner.RunResult>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
                var conversation = Tx.run(() -> (Conversation) Conversation.findById(convoId));
                result.set(AgentRunner.run(agent, conversation, QUESTION));
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(60_000);
        assertFalse(thread.isAlive(), "the turn must end within 60 s");
        if (error.get() != null) throw error.get();
        return result.get();
    }

    private static final class StreamedTurn {
        final AtomicReference<String> completed = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch ended = new CountDownLatch(1);
    }

    private static StreamedTurn runStreamed(Conversation convo) throws InterruptedException {
        var turn = new StreamedTurn();
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {},
                content -> { turn.completed.set(content); turn.ended.countDown(); },
                e -> { turn.error.set(e); turn.ended.countDown(); },
                turn.ended::countDown);
        AgentRunner.runStreaming(convo.agent, convo.id, convo.channelType, convo.peerId, QUESTION,
                new AtomicBoolean(false), cb, null);
        assertTrue(turn.ended.await(60, TimeUnit.SECONDS), "the turn must end within 60 s");
        return turn;
    }

    private static void deleteDir(Path dir) {
        if (!Files.exists(dir)) return;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException _) {
            // a leftover workspace only affects a later run of this class, under a new agent name
        }
    }
}
