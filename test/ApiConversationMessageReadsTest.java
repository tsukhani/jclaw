import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ApiConversationsController;
import controllers.RequestPrincipal;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;
import tools.JClawApiTool;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * A conversation's messages as an agent reads them through {@code jclaw_api}, and as the operator's chat does (JF-143):
 * an agent's page cuts each long message and fits under {@link JClawApiTool#MAX_RESPONSE_CHARS}, the operator's page
 * carries every row whole, and {@code GET /api/conversations/{id}/messages/{messageId}} returns one message whole.
 *
 * <p>No {@code Fixtures.deleteDatabase()}: every row hangs off an agent this class names with a fresh token, and every
 * read addresses one of its own conversations, so a sibling's rows never reach an assertion.
 */
class ApiConversationMessageReadsTest extends FunctionalTest {

    private static final int BOUND = ApiConversationsController.MESSAGE_EXCERPT_CHARS;
    private static final Instant BASE = Instant.parse("2026-11-11T00:00:00Z");

    @BeforeEach
    void seedPassword() {
        AuthFixture.seedAdminPassword("changeme");
        clearCookies();
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    // --- an agent's page: cut at the bound -------------------------------------------------------

    @Test
    void contentAtTheBoundComesWholeOnAnAgentsPage() {
        var s = seed(1, m -> m.content = "a".repeat(BOUND));

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages");

        assertStatus(200, resp);
        var row = onlyRow(resp);
        assertEquals("a".repeat(BOUND), row.get("content").getAsString());
        assertFalse(row.has("cut"), row.toString());
        assertNull(resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void contentOneCharacterOverTheBoundIsCutMarkedAndCarriesItsId() {
        var s = seed(1, m -> m.content = "a".repeat(BOUND) + "b");

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages");

        assertStatus(200, resp);
        var row = onlyRow(resp);
        assertEquals(s.messageIds().getFirst(), row.get("id").getAsLong());
        assertEquals("a".repeat(BOUND) + "…", row.get("content").getAsString());
        assertEquals(List.of("content"), strings(row.getAsJsonArray("cut")));
        assertEquals("Message " + s.messageIds().getFirst() + " is cut at " + BOUND + " characters; GET /api/conversations/"
                        + s.conversationId() + "/messages/{messageId} returns one whole.",
                resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void everyOtherLongFieldAtTheBoundComesWhole() {
        var s = seedRows(
                m -> {
                    m.role = MessageRole.ASSISTANT.value;
                    m.reasoning = "r".repeat(BOUND);
                    m.toolCalls = toolCall("t".repeat(BOUND));
                },
                m -> {
                    m.role = MessageRole.TOOL.value;
                    m.toolResults = "call_1";
                    m.content = "done";
                    m.toolResultStructured = jsonOfLength(BOUND);
                },
                m -> {
                    m.role = MessageRole.USER.value;
                    m.content = "announce";
                    m.messageKind = "subagent_announce";
                    m.metadata = jsonOfLength(BOUND);
                });

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages");

        assertStatus(200, resp);
        var rows = rows(resp);
        assertEquals(3, rows.size());
        var assistant = rows.get(0).getAsJsonObject();
        assertEquals("r".repeat(BOUND), assistant.get("reasoning").getAsString());
        assertEquals("t".repeat(BOUND), arguments(assistant));
        assertEquals(jsonOfLength(BOUND), rows.get(1).getAsJsonObject().get("toolResultStructured").toString());
        assertEquals(jsonOfLength(BOUND), rows.get(2).getAsJsonObject().get("metadata").toString());
        for (var row : rows) assertFalse(row.getAsJsonObject().has("cut"), row.toString());
        assertNull(resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void everyOtherLongFieldOverTheBoundIsCutOrLeftOut() {
        var s = seedRows(
                m -> {
                    m.role = MessageRole.ASSISTANT.value;
                    m.reasoning = "r".repeat(BOUND) + "s";
                    m.toolCalls = toolCall("t".repeat(BOUND) + "u");
                },
                m -> {
                    m.role = MessageRole.TOOL.value;
                    m.toolResults = "call_1";
                    m.content = "done";
                    m.toolResultStructured = jsonOfLength(BOUND + 1);
                },
                m -> {
                    m.role = MessageRole.USER.value;
                    m.content = "announce";
                    m.messageKind = "subagent_announce";
                    m.metadata = jsonOfLength(BOUND + 1);
                });

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages");

        assertStatus(200, resp);
        var rows = rows(resp);
        assertEquals(3, rows.size());
        var assistant = rows.get(0).getAsJsonObject();
        assertEquals("r".repeat(BOUND) + "…", assistant.get("reasoning").getAsString());
        assertEquals("t".repeat(BOUND) + "…", arguments(assistant));
        assertEquals("web_search", assistant.getAsJsonArray("toolCalls").get(0).getAsJsonObject()
                .getAsJsonObject("function").get("name").getAsString());
        assertEquals(List.of("reasoning", "toolCalls"), strings(assistant.getAsJsonArray("cut")));
        var tool = rows.get(1).getAsJsonObject();
        assertFalse(tool.has("toolResultStructured"), tool.toString());
        assertEquals("done", tool.get("content").getAsString());
        assertEquals("call_1", tool.get("toolResults").getAsString());
        assertEquals(List.of("toolResultStructured"), strings(tool.getAsJsonArray("cut")));
        var announce = rows.get(2).getAsJsonObject();
        assertFalse(announce.has("metadata"), announce.toString());
        assertEquals("subagent_announce", announce.get("messageKind").getAsString());
        assertEquals(List.of("metadata"), strings(announce.getAsJsonArray("cut")));
        var ids = s.messageIds();
        assertEquals("Messages " + ids.get(0) + ", " + ids.get(1) + ", " + ids.get(2) + " are cut at " + BOUND
                        + " characters; GET /api/conversations/" + s.conversationId()
                        + "/messages/{messageId} returns one whole.",
                resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    // --- an agent's page: sized to fit the cap ---------------------------------------------------

    @Test
    void anAgentsDefaultPageHoldsTheOldestTwentyCutMessagesUnderTheCap() {
        int limit = ApiConversationsController.AGENT_PAGE_DEFAULT_LIMIT;
        var s = seed(limit + 5, m -> m.content = "c".repeat(10_000));

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages");

        assertStatus(200, resp);
        var body = getContent(resp);
        var rows = rows(resp);
        assertEquals(s.messageIds().subList(0, limit), ids(rows));
        assertEquals(String.valueOf(limit + 5), resp.getHeader("X-Total-Count"));
        for (var row : rows) {
            assertEquals("c".repeat(BOUND) + "…", row.getAsJsonObject().get("content").getAsString());
        }
        assertTrue(body.length() < JClawApiTool.MAX_RESPONSE_CHARS, "a default page ran to " + body.length());
        assertEquals(cutNote(s.conversationId(), s.messageIds().subList(0, limit)),
                resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void anAgentNamingALimitGetsThatManyMessages() {
        var s = seed(5, m -> m.content = "short");

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages?limit=3");

        assertStatus(200, resp);
        assertEquals(s.messageIds().subList(0, 3), ids(rows(resp)));
        assertNull(resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void anAgentsPageOfMessagesAtTheirLongestStopsAtTheBudgetAndNamesTheNextOffset() {
        int limit = ApiConversationsController.AGENT_PAGE_DEFAULT_LIMIT;
        var s = seed(limit, ApiConversationMessageReadsTest::everyFieldAtItsLongest);
        var url = "/api/conversations/" + s.conversationId() + "/messages";

        var resp = asAgent(s.agentId(), url);

        assertStatus(200, resp);
        var body = getContent(resp);
        var kept = ids(rows(resp));
        int k = kept.size();
        assertTrue(k > 0 && k < limit, "kept " + k);
        assertEquals(s.messageIds().subList(0, k), kept);
        assertTrue(body.length() <= ApiConversationsController.AGENT_PAGE_MAX_CHARS, "the page ran to " + body.length());
        // The row held back would have carried the page past the budget.
        var next = getContent(asAgent(s.agentId(), url + "?after=" + kept.getLast() + "&limit=1"));
        assertEquals(List.of(s.messageIds().get(k)), ids(JsonParser.parseString(next).getAsJsonArray()));
        int nextRow = next.length() - 2;
        assertTrue(body.length() + 1 + nextRow > ApiConversationsController.AGENT_PAGE_MAX_CHARS,
                "the page ran to " + body.length() + " and the next row to " + nextRow);
        assertEquals(cutNote(s.conversationId(), kept) + " " + heldBackNote(k, limit, "offset=" + k),
                resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void aNewestPageThatDoesNotFitKeepsTheNewestMessagesAndNamesTheOlderCursor() {
        int limit = ApiConversationsController.AGENT_PAGE_DEFAULT_LIMIT;
        var s = seed(limit, ApiConversationMessageReadsTest::everyFieldAtItsLongest);

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages?latest=true");

        assertStatus(200, resp);
        var kept = ids(rows(resp));
        int k = kept.size();
        assertTrue(k > 0 && k < limit, "kept " + k);
        assertEquals(s.messageIds().subList(limit - k, limit), kept);
        assertTrue(getContent(resp).length() <= ApiConversationsController.AGENT_PAGE_MAX_CHARS);
        assertEquals(cutNote(s.conversationId(), kept) + " " + heldBackNote(k, limit, "before=" + kept.getFirst()),
                resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void anAfterPageThatDoesNotFitNamesTheNextAfterCursor() {
        int limit = ApiConversationsController.AGENT_PAGE_DEFAULT_LIMIT;
        var s = seed(limit + 1, ApiConversationMessageReadsTest::everyFieldAtItsLongest);
        var first = s.messageIds().getFirst();

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages?after=" + first);

        assertStatus(200, resp);
        var kept = ids(rows(resp));
        int k = kept.size();
        assertTrue(k > 0 && k < limit, "kept " + k);
        assertEquals(s.messageIds().subList(1, 1 + k), kept);
        assertEquals(cutNote(s.conversationId(), kept) + " " + heldBackNote(k, limit, "after=" + kept.getLast()),
                resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    // --- the operator's page: unchanged ----------------------------------------------------------

    @Test
    void theOperatorsPageCarriesEveryMessageWholeTwoHundredToAPage() {
        var s = seed(201, m -> m.content = "m");
        var longId = s.messageIds().getFirst();
        commit(() -> {
            Message m = Message.findById(longId);
            everyFieldAtItsLongest(m);
            m.toolResultStructured = jsonOfLength(BOUND * 3);
            m.save();
            return null;
        });
        login();

        var resp = GET("/api/conversations/" + s.conversationId() + "/messages");

        assertStatus(200, resp);
        var rows = rows(resp);
        assertEquals(s.messageIds().subList(0, 200), ids(rows));
        assertEquals("201", resp.getHeader("X-Total-Count"));
        assertNull(resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
        var whole = rows.get(0).getAsJsonObject();
        assertEquals(Set.of("id", "role", "content", "toolCalls", "toolResults", "reasoning", "createdAt", "usage",
                "toolResultStructured"), whole.keySet());
        assertEquals("c".repeat(10_000), whole.get("content").getAsString());
        assertEquals("r".repeat(10_000), whole.get("reasoning").getAsString());
        assertEquals("t".repeat(10_000), arguments(whole));
        assertEquals(jsonOfLength(BOUND * 3), whole.get("toolResultStructured").toString());
        assertEquals(usageJson(), whole.get("usage").toString());
        for (var row : rows) assertFalse(row.getAsJsonObject().has("cut"), row.toString());
    }

    // --- one message whole -----------------------------------------------------------------------

    @Test
    void theOwningAgentReadsOneMessageWhole() {
        var s = seed(2, ApiConversationMessageReadsTest::everyFieldAtItsLongest);
        var messageId = s.messageIds().get(1);

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages/" + messageId);

        assertStatus(200, resp);
        var message = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals(messageId, message.get("id").getAsLong());
        assertEquals("c".repeat(10_000), message.get("content").getAsString());
        assertEquals("r".repeat(10_000), message.get("reasoning").getAsString());
        assertEquals("t".repeat(10_000), arguments(message));
        assertEquals(usageJson(), message.get("usage").toString());
        assertFalse(message.has("cut"), message.toString());
        assertNull(resp.getHeader(RequestPrincipal.AGENT_NOTE_HEADER));
    }

    @Test
    void theOperatorReadsOneMessageWhole() {
        var s = seed(1, m -> m.content = "o".repeat(BOUND * 60));
        login();

        var resp = GET("/api/conversations/" + s.conversationId() + "/messages/" + s.messageIds().getFirst());

        assertStatus(200, resp);
        var message = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals("o".repeat(BOUND * 60), message.get("content").getAsString());
    }

    @Test
    void anotherAgentIsRefusedTheMessage() {
        var s = seed(1, m -> m.content = "private");
        var alien = seed(0, m -> {});

        var resp = asAgent(alien.agentId(),
                "/api/conversations/" + s.conversationId() + "/messages/" + s.messageIds().getFirst());

        assertStatus(403, resp);
        var body = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals("agent_scope", body.get("code").getAsString());
        assertEquals("Conversation " + s.conversationId() + " belongs to another agent.", body.get("message").getAsString());
    }

    @Test
    void anUnstampedBearerIsRefusedTheMessage() {
        var s = seed(1, m -> m.content = "private");

        var resp = GET(bearer(), "/api/conversations/" + s.conversationId() + "/messages/" + s.messageIds().getFirst());
        clearCookies();

        assertStatus(403, resp);
        assertEquals("agent_scope", JsonParser.parseString(getContent(resp)).getAsJsonObject().get("code").getAsString());
    }

    @Test
    void anUnknownMessageIsNotFound() {
        var s = seed(1, m -> m.content = "here");

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages/" + Long.MAX_VALUE);

        assertStatus(404, resp);
        var body = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals("not_found", body.get("code").getAsString());
        assertEquals("Message " + Long.MAX_VALUE + " not found in conversation " + s.conversationId() + ".",
                body.get("message").getAsString());
    }

    @Test
    void aMessageOfAnotherConversationIsNotFoundThroughThisOne() {
        var s = seed(1, m -> m.content = "here");
        var other = seedConversationFor(s.agentId(), "elsewhere");

        var resp = asAgent(s.agentId(), "/api/conversations/" + s.conversationId() + "/messages/" + other);

        assertStatus(404, resp);
        assertEquals("Message " + other + " not found in conversation " + s.conversationId() + ".",
                JsonParser.parseString(getContent(resp)).getAsJsonObject().get("message").getAsString());
    }

    @Test
    void aMessageOfAnUnknownConversationIsNotFound() {
        var s = seed(1, m -> m.content = "here");

        var resp = asAgent(s.agentId(), "/api/conversations/" + Long.MAX_VALUE + "/messages/" + s.messageIds().getFirst());

        assertStatus(404, resp);
    }

    // --- seam ------------------------------------------------------------------------------------

    private record Seeded(long agentId, long conversationId, List<Long> messageIds) {}

    /** {@code count} assistant messages, a millisecond apart so the offset page's createdAt order is the id order. */
    private static Seeded seed(int count, Consumer<Message> shape) {
        var shapes = new ArrayList<Consumer<Message>>();
        for (int i = 0; i < count; i++) shapes.add(shape);
        return seedAll(shapes);
    }

    @SafeVarargs
    private static Seeded seedRows(Consumer<Message>... shapes) {
        return seedAll(List.of(shapes));
    }

    private static Seeded seedAll(List<Consumer<Message>> shapes) {
        return commit(() -> {
            var agent = new Agent();
            agent.name = "jf143-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var conversation = conversationFor(agent);
            var ids = new ArrayList<Long>();
            for (int i = 0; i < shapes.size(); i++) {
                var m = new Message();
                m.conversation = conversation;
                m.role = MessageRole.ASSISTANT.value;
                shapes.get(i).accept(m);
                AppClock.runWith(Clock.fixed(BASE.plusMillis(i), ZoneOffset.UTC), m::save);
                ids.add(m.id);
            }
            return new Seeded(agent.id, conversation.id, ids);
        });
    }

    /** One message in a second conversation of the same agent; returns its id. */
    private static long seedConversationFor(long agentId, String content) {
        return commit(() -> {
            Agent agent = Agent.findById(agentId);
            var m = new Message();
            m.conversation = conversationFor(agent);
            m.role = MessageRole.ASSISTANT.value;
            m.content = content;
            m.save();
            return m.id;
        });
    }

    private static Conversation conversationFor(Agent agent) {
        var conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "jf143";
        conversation.save();
        return conversation;
    }

    /** An assistant row with its content, reasoning and tool-call arguments far past the bound, and a usage record. */
    private static void everyFieldAtItsLongest(Message m) {
        m.role = MessageRole.ASSISTANT.value;
        m.content = "c".repeat(10_000);
        m.reasoning = "r".repeat(10_000);
        m.toolCalls = toolCall("t".repeat(10_000));
        m.usageJson = usageJson();
    }

    private static String toolCall(String arguments) {
        var fn = new JsonObject();
        fn.addProperty("name", "web_search");
        fn.addProperty("arguments", arguments);
        var call = new JsonObject();
        call.addProperty("id", "call_1");
        call.addProperty("type", "function");
        call.add("function", fn);
        return call.toString();
    }

    /** A usage record about as long as the longest seen on the live instance, 1,402 characters. */
    private static String usageJson() {
        var usage = new JsonObject();
        usage.addProperty("prompt", 123_456);
        usage.addProperty("completion", 7_890);
        usage.addProperty("usageSource", "provider");
        usage.addProperty("modelId", "m".repeat(1_330));
        return usage.toString();
    }

    /** A JSON object whose text is exactly {@code length} characters. */
    private static String jsonOfLength(int length) {
        // {"t":""} is 8 characters around the value.
        return "{\"t\":\"" + "j".repeat(length - 8) + "\"}";
    }

    private static String cutNote(long conversationId, List<Long> ids) {
        return "Messages " + ids.stream().map(String::valueOf).collect(Collectors.joining(", ")) + " are cut at " + BOUND
                + " characters; GET /api/conversations/" + conversationId + "/messages/{messageId} returns one whole.";
    }

    private static String heldBackNote(int kept, int fetched, String rest) {
        return "This page holds " + kept + " of " + fetched + " messages, as many as fit in "
                + ApiConversationsController.AGENT_PAGE_MAX_CHARS + " characters; the rest come with " + rest + ".";
    }

    private static String arguments(JsonObject message) {
        return message.getAsJsonArray("toolCalls").get(0).getAsJsonObject()
                .getAsJsonObject("function").get("arguments").getAsString();
    }

    private static JsonArray rows(Http.Response resp) {
        return JsonParser.parseString(getContent(resp)).getAsJsonArray();
    }

    private static JsonObject onlyRow(Http.Response resp) {
        var rows = rows(resp);
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0).getAsJsonObject();
    }

    private static List<Long> ids(JsonArray rows) {
        var ids = new ArrayList<Long>();
        for (var row : rows) ids.add(row.getAsJsonObject().get("id").getAsLong());
        return ids;
    }

    private static List<String> strings(JsonArray array) {
        var out = new ArrayList<String>();
        for (var el : array) out.add(el.getAsString());
        return out;
    }

    private static void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    /**
     * A GET as {@code agentId}, stamped as {@code JClawApiTool} stamps it. The cookie jar is a JVM-global folded into
     * every later request: an operator cookie left in it would make this the operator's read, and the agent cookie
     * this call earns would make a later one the agent's.
     */
    private Http.Response asAgent(long agentId, String url) {
        clearCookies();
        try {
            var request = bearer();
            request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                    new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
            return GET(request, url);
        } finally {
            clearCookies();
        }
    }

    private static Http.Request bearer() {
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        return request;
    }

    /** Commit off-thread: the test body is already in a transaction this request would not see. */
    private static <T> T commit(Supplier<T> block) {
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
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
