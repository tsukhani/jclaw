import org.junit.jupiter.api.*;
import play.test.*;
import play.db.jpa.JPA;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import services.ConversationService;
import services.Tx;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * Functional HTTP tests for ApiConversationsController CRUD and query endpoints.
 * Uses real H2 DB, no mocks.
 */
class ApiConversationsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    // --- Auth helper ---

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        var response = POST("/api/auth/login", "application/json", body);
        assertIsOk(response);
    }

    // --- Helpers ---

    private String createAgent(String name) {
        var body = """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name);
        var resp = POST("/api/agents", "application/json", body);
        assertIsOk(resp);
        return extractId(getContent(resp));
    }

    private String extractId(String json) {
        var matcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(json);
        return matcher.find() ? matcher.group(1) : null;
    }

    // --- Tests ---

    @Test
    void unauthenticatedRequestReturns401() {
        var response = GET("/api/conversations");
        assertEquals(401, response.status.intValue());
    }

    @Test
    void listConversationsReturnsJsonArray() {
        login();
        var response = GET("/api/conversations");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    void listConversationsReturnsEmptyAfterDbWipe() {
        login();
        var response = GET("/api/conversations");
        assertIsOk(response);
        assertEquals("[]", getContent(response));
    }

    @Test
    void deleteConversationSucceeds() {
        login();
        var agentId = createAgent("delete-test-agent");

        // Create a conversation by sending a chat message via the correct endpoint.
        // The send may fail (no LLM provider configured) but should still create a conversation.
        var chatBody = """
                {"agentId": %s, "message": "Hello"}
                """.formatted(agentId);
        POST("/api/chat/send", "application/json", chatBody);

        // List conversations and delete the first one found
        var listResp = GET("/api/conversations");
        var content = getContent(listResp);
        var idMatcher = java.util.regex.Pattern.compile("\"id\":(\\d+)").matcher(content);
        if (idMatcher.find()) {
            var convoId = idMatcher.group(1);
            var deleteResp = DELETE("/api/conversations/" + convoId);
            assertIsOk(deleteResp);
            assertTrue(getContent(deleteResp).contains("\"status\":\"deleted\""));
        }
    }

    @Test
    void deleteByIdsPurgesSessionCompactionRows() {
        // Regression for a FK violation seen in prod: session_compaction.conversation_id
        // references conversation(id) without ON DELETE CASCADE, so deleteByIds must
        // hand-delete compaction rows before the parent Conversation delete.
        Long convoId = services.Tx.run(() -> {
            var agent = new Agent();
            agent.name = "compaction-delete-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var convo = new Conversation();
            convo.agent = agent;
            convo.channelType = "web";
            convo.peerId = "unit-test";
            convo.save();

            var sc = new SessionCompaction();
            sc.conversation = convo;
            sc.turnCount = 3;
            sc.summaryTokens = 100;
            sc.model = "openrouter/gpt-4.1";
            sc.summary = "test summary";
            sc.compactedAt = Instant.now();
            sc.save();

            return convo.id;
        });

        int deleted = services.Tx.run(() ->
                ConversationService.deleteByIds(java.util.List.of(convoId)));
        assertEquals(1, deleted,
                "deleteByIds must return 1 when the conversation is purged");

        // Confirm both parent and child are gone — no FK violation, no orphans.
        // Count queries bypass the persistence context L1 cache, which JPQL bulk
        // DELETEs don't sync; findById would otherwise return the stale managed
        // entity left over from the pre-delete insert.
        services.Tx.run(() -> {
            long convoCount = Conversation.count("id = ?1", convoId);
            assertEquals(0L, convoCount, "conversation row must be deleted");
            long compactionCount = SessionCompaction.count(
                    "conversation.id = ?1", convoId);
            assertEquals(0L, compactionCount,
                    "session_compaction rows must be purged alongside the conversation");
        });
    }

    @Test
    void deleteNonExistentConversationReturns404() {
        login();
        var response = DELETE("/api/conversations/999999");
        assertEquals(404, response.status.intValue());
    }

    @Test
    void getMessagesForNonExistentConversationReturns404() {
        login();
        var response = GET("/api/conversations/999999/messages");
        assertEquals(404, response.status.intValue());
    }

    @Test
    void paginationHeadersAreSet() {
        login();
        var response = GET("/api/conversations");
        assertIsOk(response);
        assertNotNull(response.getHeader("X-Total-Count"),
                "X-Total-Count header should be present");
        assertEquals("0", response.getHeader("X-Total-Count"));
    }

    @Test
    void listConversationsWithLimitAndOffset() {
        login();
        var response = GET("/api/conversations?limit=5&offset=0");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    void listConversationChannels() {
        login();
        var response = GET("/api/conversations/channels");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    /**
     * JCLAW-170: tool-call rows persist as one assistant row per call (the
     * {@code toolCalls} column holds a single ToolCall JSON object, not an
     * array) followed by a TOOL-role row keyed by the same id. The
     * /messages endpoint must normalize the single-object shape to an array
     * and stamp the current registry's {@link agents.ToolRegistry#iconFor}
     * hint onto each entry — otherwise the chat UI's hydration fold treats
     * the row as empty and the tool-calls block vanishes on reload.
     */
    @Test
    void getMessagesNormalizesPersistedToolCallsToArrayWithIcons() {
        login();
        var cid = commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "tool-call-test";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var conv = ConversationService.create(agent, "web", "tester");
            // User turn.
            ConversationService.appendUserMessage(conv, "do a search please");
            // Intermediate assistant row — the production code persists ONE
            // ToolCall per row via gson.toJson(tc), not an array.
            var asst = new Message();
            asst.conversation = conv;
            asst.role = MessageRole.ASSISTANT.value;
            asst.content = null;
            asst.toolCalls = "{\"id\":\"call_a\",\"type\":\"function\","
                    + "\"function\":{\"name\":\"web_search\","
                    + "\"arguments\":\"{\\\"query\\\":\\\"jclaw\\\"}\"}}";
            asst.save();
            // Matching tool-row result with a structured payload.
            ConversationService.appendToolResult(conv, "call_a", "Found 1 result...",
                    "{\"provider\":\"Exa\",\"results\":[{\"title\":\"JClaw\","
                    + "\"url\":\"https://example.com\",\"snippet\":\"hi\","
                    + "\"faviconUrl\":\"https://icons.duckduckgo.com/ip3/example.com.ico\"}]}");
            // Final assistant-with-content row.
            ConversationService.appendAssistantMessage(conv, "Here is what I found.", null);
            return conv.id;
        });

        var response = GET("/api/conversations/" + cid + "/messages");
        assertIsOk(response);
        var body = getContent(response);

        // The persisted single-object shape must be promoted to an array.
        // Every entry carries an icon sibling — value depends on whether
        // WebSearchTool is published to the registry in the test harness
        // ("search" if yes, "wrench" fallback if no). The critical
        // invariant for the UI hydrator is that the field exists on every
        // call so historical rows never surface without an icon.
        assertTrue(body.contains("\"toolCalls\":[{"),
                "toolCalls must serialize as an array, got: " + body);
        assertTrue(body.contains("\"name\":\"web_search\"") && body.contains("\"icon\":\""),
                "every tool call must carry an icon field, got: " + body);
        // Structured payload rides as a real JSON object (not a nested string).
        assertTrue(body.contains("\"toolResultStructured\":{\"provider\":\"Exa\""),
                "structured payload must land as object, got: " + body);
        assertTrue(body.contains("\"faviconUrl\":\"https://icons.duckduckgo.com"),
                "favicon URL must roundtrip, got: " + body);
    }

    /**
     * JCLAW-171: GET /api/conversations/{id} must return the conversation
     * with the requested id, not whichever conversation is at the top of
     * the most-recently-updated list. Seeds two conversations (A older,
     * B newer) and asserts the response for A's id contains A's id —
     * the bug returned B every time because the previous detail-page
     * code used the list endpoint with an ignored ?id= filter.
     */
    @Test
    void getConversationReturnsRequestedIdEvenWhenAnotherIsNewer() {
        login();
        var ids = commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "convo-detail-test";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            // Older conversation (A).
            var convA = ConversationService.create(agent, "web", "alice");
            ConversationService.appendUserMessage(convA, "first thread");
            // Newer conversation (B) — created after A, so B sits at the top
            // of the most-recently-updated list and would have been returned
            // by the broken `?id=` list-endpoint pattern.
            var convB = ConversationService.create(agent, "web", "bob");
            ConversationService.appendUserMessage(convB, "second thread");
            return new long[]{convA.id, convB.id};
        });

        var response = GET("/api/conversations/" + ids[0]);
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"id\":" + ids[0]),
                "response must carry conversation A's id, got: " + body);
        assertTrue(body.contains("\"peerId\":\"alice\""),
                "response must carry conversation A's peer, got: " + body);
        assertFalse(body.contains("\"peerId\":\"bob\""),
                "response must not carry conversation B's peer, got: " + body);
    }

    @Test
    void getConversationReturns404ForUnknownId() {
        login();
        var response = GET("/api/conversations/999999");
        assertEquals(404, response.status.intValue());
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        // FunctionalTest's carrier thread runs inside an ambient JPA tx that
        // doesn't commit until the test returns, so inline Tx.run writes are
        // invisible to the in-process HTTP request handler. Spawn a virtual
        // thread to open a fresh tx that commits before the GET fires.
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
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

    /**
     * JCLAW-198: DELETE /api/conversations with a {@code filter} body deletes
     * every row matching the filter. Filter shape mirrors the listing endpoint
     * (channel, agentId, name, peer); each field optional. Seeds two agents'
     * conversations and verifies the agentId filter scopes the wipe.
     */
    @Test
    void deleteByFilterScopedToAgentDeletesOnlyMatchingRows() {
        login();
        long[] ids = commitInFreshTx(() -> {
            var keep = new Agent();
            keep.name = "keep-agent";
            keep.modelProvider = "openrouter";
            keep.modelId = "gpt-4.1";
            keep.save();
            var purge = new Agent();
            purge.name = "purge-agent";
            purge.modelProvider = "openrouter";
            purge.modelId = "gpt-4.1";
            purge.save();
            // Two purge-agent conversations, one keep-agent conversation.
            var p1 = ConversationService.create(purge, "web", "user1");
            ConversationService.appendUserMessage(p1, "purge me 1");
            var p2 = ConversationService.create(purge, "web", "user2");
            ConversationService.appendUserMessage(p2, "purge me 2");
            var k1 = ConversationService.create(keep, "web", "user3");
            ConversationService.appendUserMessage(k1, "keep me");
            return new long[]{purge.id, keep.id, p1.id, p2.id, k1.id};
        });
        long purgeAgentId = ids[0];
        long keepAgentId = ids[1];

        var resp = deleteWithJsonBody("/api/conversations",
                "{\"filter\":{\"agentId\":" + purgeAgentId + "}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":2"),
                "filter delete must report two purge-agent rows removed, got: " + getContent(resp));

        // Keep-agent's conversation must still be in the table; purge-agent's must be gone.
        commitInFreshTx(() -> {
            assertEquals(0L, Conversation.count("agent.id = ?1", purgeAgentId),
                    "purge-agent rows must be deleted");
            assertEquals(1L, Conversation.count("agent.id = ?1", keepAgentId),
                    "keep-agent row must survive");
            return null;
        });
    }

    /**
     * Empty filter object means "match everything", consistent with the
     * no-filter semantic of GET /api/conversations.
     */
    @Test
    void deleteByEmptyFilterDeletesAllConversations() {
        login();
        commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "wipe-test";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            ConversationService.create(agent, "web", "u1");
            ConversationService.create(agent, "telegram", "u2");
            ConversationService.create(agent, "slack", "u3");
            return null;
        });

        var resp = deleteWithJsonBody("/api/conversations", "{\"filter\":{}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":3"),
                "empty-filter delete must report all three rows removed, got: " + getContent(resp));

        commitInFreshTx(() -> {
            assertEquals(0L, Conversation.count(), "table must be empty after wipe");
            return null;
        });
    }

    /**
     * Guard against an accidental DELETE wiping the table when neither
     * shape is present — the controller must reject with 400 rather than
     * fall through to either delete branch.
     */
    @Test
    void deleteWithNeitherIdsNorFilterReturns400() {
        login();
        var resp = deleteWithJsonBody("/api/conversations", "{}");
        assertEquals(400, resp.status.intValue());
    }

    /**
     * Play 1.x's {@link play.test.FunctionalTest#DELETE(play.mvc.Http.Request, Object) DELETE}
     * helper unconditionally overwrites {@code request.body} with an empty stream,
     * so DELETE-with-body has to drive the request through {@code makeRequest}
     * directly. JCLAW-198's filter-body shape is the only DELETE that needs a
     * payload, so this helper lives here rather than in a shared base class.
     */
    private static play.mvc.Http.Response deleteWithJsonBody(String url, String json) {
        var req = newRequest();
        req.method = "DELETE";
        req.contentType = "application/json";
        var qIdx = url.indexOf('?');
        req.url = url;
        req.path = qIdx >= 0 ? url.substring(0, qIdx) : url;
        req.querystring = qIdx >= 0 ? url.substring(qIdx + 1) : "";
        req.body = new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        // The framework helpers attach savedCookies after a login() call;
        // newRequest() doesn't, so we reach for them via reflection — the
        // field is package-private static on FunctionalTest and there's no
        // public accessor. Without this, every test login is silently
        // discarded and the controller returns 401.
        try {
            var f = play.test.FunctionalTest.class.getDeclaredField("savedCookies");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cookies = (java.util.Map<String, play.mvc.Http.Cookie>) f.get(null);
            if (cookies != null) req.cookies = cookies;
        } catch (Exception _) {
            // savedCookies field may shift across play versions; an
            // unauthenticated DELETE will surface as 401, which is what
            // the affected tests already assert against.
        }
        return makeRequest(req);
    }

    @Test
    void getQueueStatusForNonExistentConversation() {
        login();
        // Queue status doesn't require the conversation to exist in DB —
        // it checks the in-memory queue. Should return a valid JSON response.
        var response = GET("/api/conversations/999999/queue");
        assertIsOk(response);
        var content = getContent(response);
        assertTrue(content.contains("\"busy\""));
        assertTrue(content.contains("\"queueSize\""));
    }
}
