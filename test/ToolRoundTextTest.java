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
 * JF-568: the text the model writes in a tool round is stored on the row of that round's first call, on the sync
 * loop and the streamed one alike, so the chat can show it once the turn has ended; it never reaches the model's
 * history in a later turn.
 */
class ToolRoundTextTest extends UnitTest {

    private static final String MODEL = "test-model";
    private static final String QUESTION = "Publish the post on two sites.";
    private static final String NOTE = "Checking both sites first.";
    private static final String ANSWER = "Both drafts are up.";
    private static final String LATER_ANSWER = "Yes, both are drafts.";
    private static final String FILE = "progress.md";
    /** The rows a turn of {@link #script} stores: the note on its first call only, nothing on the text-less round. */
    private static final List<String> STORED = List.of("user:" + QUESTION, "assistant->w1 [" + NOTE + "]",
            "tool<-w1", "assistant->w2", "tool<-w2", "assistant->w3", "tool<-w3", "assistant:" + ANSWER);

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
    void aSyncRoundsTextIsStoredOnItsFirstCallOnly() throws Exception {
        var convo = turnScriptedAs(script());

        assertEquals(ANSWER, runSync(convo, QUESTION).response());
        assertEquals(STORED, stored(convo));
    }

    @Test
    void aStreamedRoundsTextIsStoredOnItsFirstCallOnly() throws Exception {
        var convo = turnScriptedAs(script());

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(ANSWER, turn.completed.get());
        assertEquals(STORED, stored(convo));
    }

    @Test
    void aLaterTurnsHistoryCarriesTheCallsButNotTheRoundsText() throws Exception {
        var convo = turnScriptedAs(script());
        runSync(convo, QUESTION);

        assertEquals(LATER_ANSWER, runSync(convo, "Are they drafts?").response());

        var history = sent(bodies.getLast());
        assertTrue(history.contains("tool<-w1") && history.contains("tool<-w3"), String.valueOf(history));
        assertFalse(bodies.getLast().contains(NOTE), "the round's text stays out of the model's history");
    }

    // ─── replies ─────────────────────────────────────────────────────────────

    /** A round with text and two calls, a round with one call and no text, the answer, then a later turn's answer. */
    private static String[] script() {
        return new String[] {
                toolCalls(NOTE, "w1", "w2"), toolCalls("", "w3"), text(ANSWER), text(LATER_ANSWER)};
    }

    private static String toolCalls(String content, String... ids) {
        var calls = new JsonArray();
        for (var id : ids) {
            var args = new JsonObject();
            args.addProperty("action", "writeFile");
            args.addProperty("path", FILE);
            args.addProperty("content", id + "\n");
            var function = new JsonObject();
            function.addProperty("name", "filesystem");
            function.addProperty("arguments", args.toString());
            var call = new JsonObject();
            call.addProperty("id", id);
            call.addProperty("type", "function");
            call.add("function", function);
            calls.add(call);
        }
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        message.add("tool_calls", calls);
        return completion(message, "tool_calls");
    }

    private static String text(String content) {
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

    private static List<String> stored(Conversation convo) {
        return Tx.run(() -> {
            var out = new ArrayList<String>();
            for (var m : ConversationService.loadRecentMessages(ConversationService.findById(convo.id))) {
                if (m.toolCalls != null) {
                    var id = JsonParser.parseString(m.toolCalls).getAsJsonObject().get("id").getAsString();
                    out.add(m.role + "->" + id + (m.roundText == null ? "" : " [" + m.roundText + "]"));
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
        providerName = "jf568-" + System.nanoTime();
        ConfigService.set("provider." + providerName + ".baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ConfigService.set("provider." + providerName + ".apiKey", "sk-test");
        ConfigService.set("provider." + providerName + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        agentName = "jf568-agent-" + System.nanoTime();
        var agent = AgentService.create(agentName, providerName, MODEL);
        var convo = ConversationService.create(agent, "web", "jf568-peer-" + System.nanoTime());
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
        return convo;
    }

    private static AgentRunner.RunResult runSync(Conversation convo, String question) throws Exception {
        var agentId = convo.agent.id;
        var convoId = convo.id;
        var result = new AtomicReference<AgentRunner.RunResult>();
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var agent = Tx.run(() -> (Agent) Agent.findById(agentId));
                var conversation = Tx.run(() -> (Conversation) Conversation.findById(convoId));
                result.set(AgentRunner.run(agent, conversation, question));
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
