import agents.AgentRunner;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmProvider;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
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
 * A response that stops at the output limit while it is writing a tool call: the call is not run and
 * stays out of the history, and the turn sends once more with a note asking the model to split the
 * content. A second cut-off in the same turn ends it with the cut-off reply, as before. A streamed reply cut
 * off at the limit is stored flagged truncated, as a sync one is.
 */
class ToolCallLoopRunnerCutOffTest extends UnitTest {

    private static final String MODEL = "test-model";
    private static final String QUESTION = "Write the quarterly report to report.md.";
    private static final String FILE = "report.md";
    private static final String ANSWER = "The report is in report.md.";
    /** The current-time block every turn sends after the question, whose text changes each minute. */
    private static final String CLOCK = "user:<current time>";
    private static final String NUDGE = "Your last response stopped at the output limit while it was writing a tool call, "
            + "so the call was not run. Split its content across smaller calls: for example, writeFile with the "
            + "first part, then appendFile for each further part.";
    /** Today's reply when the first round is cut off again, on the sync loop and the streamed first round. */
    private static final String CUT_OFF_REPLY =
            "I tried to use a tool but my response was too long and got cut off, so the tool call was not run.";
    /** Today's reply when a streamed round after tool results is cut off again. */
    private static final String STREAMED_CONTINUATION_CUT_OFF_REPLY = "I tried to use a tool but the response "
            + "exceeded the token limit before the tool arguments finished. Try breaking the task into smaller steps "
            + "— for example, write large files in multiple append operations instead of one big write.";

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

    // ─── sync loop ───────────────────────────────────────────────────────────

    @Test
    void aSyncToolCallCutOffAtTheOutputLimitIsSentAgainWithANoteToSplitIt() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), write("w1", "writeFile", "Part one.\n"),
                write("w2", "appendFile", "Part two.\n"), text(ANSWER));

        var result = runSync(convo);

        assertEquals(ANSWER, result.response());
        assertFalse(result.truncated());
        assertEquals("Part one.\nPart two.\n", workspaceFile());
        assertEquals(4, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "user:" + NUDGE), sentAfterSystem(bodies.get(1)),
                "the cut-off call is left out and the note follows the question");
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2"),
                sentAfterSystem(bodies.get(3)), "the note went with the resend only");
        assertEquals(List.of("user:" + QUESTION, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2",
                "assistant:" + ANSWER), stored(convo));
        assertFalse(lastAssistant(convo).truncated);
    }

    @Test
    void aSecondSyncCutOffEndsTheTurnWithTheCutOffReply() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), cutOff("max_tokens"));

        var result = runSync(convo);

        assertEquals(CUT_OFF_REPLY, result.response());
        assertTrue(result.truncated());
        assertEquals(2, bodies.size(), "one more call, then the turn ends");
        assertEquals(List.of("user:" + QUESTION, "assistant:" + CUT_OFF_REPLY), stored(convo));
        assertTrue(lastAssistant(convo).truncated);
        assertFalse(Files.exists(workspacePath()), "no cut-off call is run");
    }

    @Test
    void aSyncTurnSendsACutOffCallAgainOnlyOnce() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), write("w1", "writeFile", "Part one.\n"), cutOff("length"));

        var result = runSync(convo);

        assertEquals(CUT_OFF_REPLY, result.response());
        assertTrue(result.truncated());
        assertEquals(3, bodies.size());
        assertEquals("Part one.\n", workspaceFile());
    }

    @Test
    void aSyncResendTheProviderRefusesEndsTheTurnWithTheProviderErrorReply() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), REFUSED);

        var result = runSync(convo);

        assertEquals("I'm sorry, I encountered an error communicating with the AI provider. Please try again.",
                result.response());
        assertFalse(result.truncated());
        assertEquals(2, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "user:" + NUDGE), sentAfterSystem(bodies.get(1)));
    }

    @Test
    void aSyncLengthRefusalLaterInTheTurnKeepsTheQuestionAndTheClockInTheTrimmedResend() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", LARGE_PART), cutOff("length"),
                write("w2", "appendFile", "Part two.\n"), REFUSED_FOR_LENGTH, text(ANSWER));

        var result = runSync(convo);

        assertEquals(ANSWER, result.response());
        assertEquals(5, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2"),
                sentAfterSystem(bodies.get(3)));
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w2", "tool<-w2"), sentAfterSystem(bodies.get(4)),
                "the trim drops the oldest round, not the task");
    }

    @Test
    void aSyncResendRefusedForLengthIsTrimmedWithTheQuestionKeptAndTheNoteLast() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", LARGE_PART), write("w2", "appendFile", "Part two.\n"),
                cutOff("length"), REFUSED_FOR_LENGTH, text(ANSWER));

        var result = runSync(convo);

        assertEquals(ANSWER, result.response());
        assertEquals(5, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2",
                "user:" + NUDGE), sentAfterSystem(bodies.get(3)));
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w2", "tool<-w2", "user:" + NUDGE),
                sentAfterSystem(bodies.get(4)));
    }

    @Test
    void aToolResultFromBeforeTheResendIsNotPrunedLaterInTheSameTurn() throws Exception {
        var convo = turnScriptedAs(read("r1", SOURCE), cutOff("length"), write("w1", "writeFile", "Part 1.\n"),
                write("w2", "appendFile", "Part 2.\n"), write("w3", "appendFile", "Part 3.\n"),
                write("w4", "appendFile", "Part 4.\n"), write("w5", "appendFile", "Part 5.\n"),
                write("w6", "appendFile", "Part 6.\n"), text(ANSWER));
        var source = AgentService.workspacePath(agentName).resolve(SOURCE);
        Files.createDirectories(source.getParent());
        Files.writeString(source, SOURCE_TEXT);

        var result = runSync(convo);

        assertEquals(ANSWER, result.response());
        assertEquals(9, bodies.size());
        assertEquals(toolResult(bodies.get(3), "r1"), toolResult(bodies.get(8), "r1"),
                "twelve messages later this turn's result is sent as before, not as a pruned stub");
    }

    // ─── streamed turn ───────────────────────────────────────────────────────

    @Test
    void aStreamedLengthRefusalLaterInTheTurnKeepsTheQuestionAndTheClockInTheTrimmedResend() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", LARGE_PART), cutOff("length"),
                write("w2", "appendFile", "Part two.\n"), REFUSED_FOR_LENGTH, text(ANSWER));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(ANSWER, turn.completed.get());
        assertEquals(5, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2"),
                sentAfterSystem(bodies.get(3)));
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w2", "tool<-w2"), sentAfterSystem(bodies.get(4)),
                "the trim drops the oldest round, not the task");
    }

    @Test
    void aStreamedResendRefusedForLengthIsTrimmedWithTheQuestionKeptAndTheNoteLast() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", LARGE_PART), write("w2", "appendFile", "Part two.\n"),
                cutOff("length"), REFUSED_FOR_LENGTH, text(ANSWER));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(ANSWER, turn.completed.get());
        assertEquals(5, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2",
                "user:" + NUDGE), sentAfterSystem(bodies.get(3)));
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w2", "tool<-w2", "user:" + NUDGE),
                sentAfterSystem(bodies.get(4)));
    }

    @Test
    void aStreamedToolCallCutOffInTheFirstRoundIsSentAgainWithANoteToSplitIt() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), write("w1", "writeFile", "Part one.\n"),
                write("w2", "appendFile", "Part two.\n"), text(ANSWER));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(ANSWER, turn.completed.get());
        assertEquals(ANSWER, String.join("", turn.tokens), "the reader sees the answer and nothing of the cut-off call");
        assertEquals("Part one.\nPart two.\n", workspaceFile());
        assertEquals(4, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "user:" + NUDGE), sentAfterSystem(bodies.get(1)));
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2"),
                sentAfterSystem(bodies.get(3)), "the note went with the resend only");
        assertEquals(List.of("user:" + QUESTION, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2",
                "assistant:" + ANSWER), stored(convo));
        assertFalse(lastAssistant(convo).truncated);
    }

    @Test
    void aStreamedToolCallCutOffAfterToolResultsIsSentAgainWithANoteToSplitIt() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", "Part one.\n"), cutOff("length"),
                write("w2", "appendFile", "Part two.\n"), write("w3", "appendFile", "Part three.\n"), text(ANSWER));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(ANSWER, turn.completed.get());
        assertEquals("Part one.\nPart two.\nPart three.\n", workspaceFile());
        assertEquals(5, bodies.size());
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "user:" + NUDGE),
                sentAfterSystem(bodies.get(2)));
        assertEquals(List.of("user:" + QUESTION, CLOCK, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2",
                "assistant->w3", "tool<-w3"), sentAfterSystem(bodies.get(4)), "the note went with the resend only");
        assertEquals(List.of("user:" + QUESTION, "assistant->w1", "tool<-w1", "assistant->w2", "tool<-w2",
                "assistant->w3", "tool<-w3", "assistant:" + ANSWER), stored(convo));
        assertFalse(lastAssistant(convo).truncated);
    }

    @Test
    void aStreamedFirstRoundResendTheProviderRefusesEndsTheTurnWithTheError() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), REFUSED);

        var turn = runStreamed(convo);

        assertInstanceOf(LlmProvider.LlmException.class, turn.error.get());
        assertNull(turn.completed.get());
        assertEquals(2, bodies.size());
        assertEquals(List.of("user:" + QUESTION), stored(convo), "nothing is stored as the turn's reply");
    }

    @Test
    void aSecondStreamedCutOffInTheFirstRoundEndsTheTurnWithTheCutOffReply() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), cutOff("max_tokens"));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(CUT_OFF_REPLY, turn.completed.get());
        assertEquals(2, bodies.size());
        assertEquals(List.of("user:" + QUESTION, "assistant:" + CUT_OFF_REPLY), stored(convo));
        var reply = lastAssistant(convo);
        assertTrue(reply.truncated, "flagged as the sync path flags it");
        assertNotNull(reply.usageJson, "the turn's usage is saved with the reply");
        var usage = JsonParser.parseString(reply.usageJson).getAsJsonObject();
        assertEquals(20, usage.get("prompt").getAsInt(), "both calls count: " + reply.usageJson);
        assertEquals(10, usage.get("completion").getAsInt(), reply.usageJson);
        assertFalse(Files.exists(workspacePath()));
    }

    @Test
    void aSecondStreamedCutOffAfterToolResultsEndsTheTurnWithTheCutOffReply() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", "Part one.\n"), cutOff("length"), cutOff("length"));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(STREAMED_CONTINUATION_CUT_OFF_REPLY, turn.completed.get());
        assertEquals(3, bodies.size());
        assertEquals(List.of("user:" + QUESTION, "assistant->w1", "tool<-w1",
                "assistant:" + STREAMED_CONTINUATION_CUT_OFF_REPLY), stored(convo));
        assertTrue(lastAssistant(convo).truncated);
        assertEquals("Part one.\n", workspaceFile());
    }

    @Test
    void aStreamedTurnSendsACutOffCallAgainOnlyOnce() throws Exception {
        var convo = turnScriptedAs(cutOff("length"), write("w1", "writeFile", "Part one.\n"), cutOff("length"));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(STREAMED_CONTINUATION_CUT_OFF_REPLY, turn.completed.get());
        assertEquals(3, bodies.size());
        assertTrue(lastAssistant(convo).truncated);
        assertEquals("Part one.\n", workspaceFile());
    }

    @Test
    void aStreamedReplyThatStopsAtTheOutputLimitAfterToolResultsIsFlaggedTruncated() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", "Part one.\n"), text(PARTIAL, "length"));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(PARTIAL, turn.completed.get());
        assertEquals(List.of("user:" + QUESTION, "assistant->w1", "tool<-w1", "assistant:" + PARTIAL), stored(convo));
        assertTrue(lastAssistant(convo).truncated, "flagged as the sync path flags it");
    }

    @Test
    void aStreamedEmptyReplyAfterToolResultsWhoseRetryStopsAtTheOutputLimitIsFlaggedTruncated() throws Exception {
        var convo = turnScriptedAs(write("w1", "writeFile", "Part one.\n"), text(""), text(PARTIAL, "length"));

        var turn = runStreamed(convo);

        assertNull(turn.error.get());
        assertEquals(PARTIAL, turn.completed.get());
        assertEquals(3, bodies.size(), "the empty continuation is retried once");
        assertTrue(lastAssistant(convo).truncated);
    }

    // ─── replies ─────────────────────────────────────────────────────────────

    /** Stands for a 400 the provider answers instead of a completion. */
    private static final String REFUSED = "<refused>";

    private static final String REFUSAL_BODY = """
            {"error":{"message":"Invalid value for 'temperature'.","type":"invalid_request_error",\
            "param":"temperature","code":"invalid_value"}}""";

    /** Stands for a 400 refusing the request as longer than the model's context window. */
    private static final String REFUSED_FOR_LENGTH = "<refused for length>";

    private static final String LENGTH_REFUSAL_BODY = """
            {"error":{"message":"This model's maximum context length is 100000 tokens. However, your messages \
            resulted in 100500 tokens.","type":"invalid_request_error","param":"messages","code":"context_length_exceeded"}}""";

    /** A write body large enough that its round is what a trim drops. */
    private static final String LARGE_PART = "Part one of the quarterly report, with its tables. ".repeat(200);

    private static final String SOURCE = "source.md";
    /** Long enough that a pruner would stub its read result once twelve messages follow it. */
    private static final String SOURCE_TEXT = "The harbour ledger records each ship that docks, its cargo and its berth. "
            .repeat(110);

    private static String read(String id, String path) {
        var args = new JsonObject();
        args.addProperty("action", "readFile");
        args.addProperty("path", path);
        return toolCall(id, args.toString(), "tool_calls");
    }

    /** A reply that ends partway through, as an output limit leaves it. */
    private static final String PARTIAL = "The report covers the first quarter, its revenue and";

    private static String text(String content) {
        return text(content, "stop");
    }

    private static String text(String content, String finishReason) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        return completion(message, finishReason);
    }

    private static String write(String id, String action, String content) {
        var args = new JsonObject();
        args.addProperty("action", action);
        args.addProperty("path", FILE);
        args.addProperty("content", content);
        return toolCall(id, args.toString(), "tool_calls");
    }

    /** A filesystem write whose arguments stop partway through, as an output limit leaves them. */
    private static String cutOff(String finishReason) {
        var args = new JsonObject();
        args.addProperty("action", "writeFile");
        args.addProperty("path", FILE);
        args.addProperty("content", "Part one.\nPart two.\nPart three.\n");
        var whole = args.toString();
        return toolCall("cut", whole.substring(0, whole.length() - 12), finishReason);
    }

    private static String toolCall(String id, String arguments, String finishReason) {
        var function = new JsonObject();
        function.addProperty("name", "filesystem");
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
        return completion(message, finishReason);
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

    /**
     * Each message after the system prompt as {@code role:content}, a tool-call turn as {@code assistant->ids},
     * a result as {@code tool<-id}, and the injected wall-clock block as {@link #CLOCK}.
     */
    private static List<String> sentAfterSystem(String body) {
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
                var text = content != null && content.isJsonPrimitive() ? content.getAsString() : "";
                out.add(text.contains("## Current Date and Time") ? CLOCK : role + ":" + text);
            }
        }
        return out;
    }

    /** The content of the result for {@code callId} in a request body. */
    private static String toolResult(String body, String callId) {
        for (var element : JsonParser.parseString(body).getAsJsonObject().getAsJsonArray("messages")) {
            var m = element.getAsJsonObject();
            var id = m.get("tool_call_id");
            if (id != null && id.isJsonPrimitive() && callId.equals(id.getAsString())) return m.get("content").getAsString();
        }
        throw new AssertionError("no result for " + callId + " in the request");
    }

    /** The conversation's stored rows in the same notation as {@link #sentAfterSystem}. */
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

    private static Message lastAssistant(Conversation convo) {
        return Tx.run(() -> {
            Message last = null;
            for (var m : ConversationService.loadRecentMessages(ConversationService.findById(convo.id))) {
                if (MessageRole.ASSISTANT.value.equals(m.role)) last = m;
            }
            return last;
        });
    }

    private Path workspacePath() {
        return AgentService.workspacePath(agentName).resolve(FILE);
    }

    private String workspaceFile() throws IOException {
        return Files.readString(workspacePath());
    }

    // ─── drivers ─────────────────────────────────────────────────────────────

    /** A provider answering each request with the next reply, and an agent on it with a fresh conversation. */
    private Conversation turnScriptedAs(String... replies) throws IOException {
        var next = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            var reply = replies[Math.min(next.getAndIncrement(), replies.length - 1)];
            if (!REFUSED.equals(reply) && !REFUSED_FOR_LENGTH.equals(reply)) {
                LlmReplyFixture.send(exchange, reply);
                return;
            }
            var bytes = (REFUSED.equals(reply) ? REFUSAL_BODY : LENGTH_REFUSAL_BODY).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        providerName = "jf129-" + System.nanoTime();
        ConfigService.set("provider." + providerName + ".baseUrl",
                "http://127.0.0.1:" + server.getAddress().getPort());
        ConfigService.set("provider." + providerName + ".apiKey", "sk-test");
        ConfigService.set("provider." + providerName + ".models",
                "[{\"id\":\"" + MODEL + "\",\"name\":\"Test\",\"contextWindow\":100000,\"maxTokens\":4096}]");
        ProviderRegistry.refresh();
        agentName = "jf129-agent-" + System.nanoTime();
        var agent = AgentService.create(agentName, providerName, MODEL);
        var convo = ConversationService.create(agent, "web", "jf129-peer-" + System.nanoTime());
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
        final List<String> tokens = new CopyOnWriteArrayList<>();
        final AtomicReference<String> completed = new AtomicReference<>();
        final AtomicReference<Exception> error = new AtomicReference<>();
        final CountDownLatch ended = new CountDownLatch(1);
    }

    private static StreamedTurn runStreamed(Conversation convo) throws InterruptedException {
        var turn = new StreamedTurn();
        var cb = new AgentRunner.StreamingCallbacks(
                _ -> {}, turn.tokens::add, _ -> {}, _ -> {}, _ -> {},
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
