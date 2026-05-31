import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.*;
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

    /**
     * The JSON-array list endpoints — the bare conversations list, the
     * limit/offset-paginated list, and the channels list — all return 200
     * with an application/json array body.
     */
    @ParameterizedTest(name = "listReturnsJsonArray[{0}]")
    @ValueSource(strings = {
            "/api/conversations",
            "/api/conversations?limit=5&offset=0",
            "/api/conversations/channels"
    })
    void listEndpointReturnsJsonArray(String url) {
        login();
        var response = GET(url);
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
    void listConversationsExcludesSubagentChildren() {
        // Regression: /api/conversations is the data source for both the
        // /conversations admin page AND the chat sidebar's recent feed.
        // Subagent child conversations (parentConversation != null) belong
        // on the dedicated /subagents page, not in either of those views.
        // Pre-fix the page rendered every Conversation row including
        // children, with a "SUBAGENT" pill, which was redundant with
        // /subagents and made the bulk-delete dangerous.
        var ids = commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "subagent-filter-test";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();

            var parent = new Conversation();
            parent.agent = agent;
            parent.channelType = "web";
            parent.peerId = "list-filter-test";
            parent.preview = "parent visible row";
            parent.save();

            var child = new Conversation();
            child.agent = agent;
            child.channelType = "subagent";
            child.parentConversation = parent;
            child.preview = "subagent invisible child";
            child.save();

            return new long[] { parent.id, child.id };
        });

        login();
        var response = GET("/api/conversations?peer=list-filter-test");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains("\"id\":" + ids[0]),
                "parent conversation must be in the list response: " + body);
        assertFalse(body.contains("\"id\":" + ids[1]),
                "subagent child conversation must NOT appear in /api/conversations: " + body);
    }

    @Test
    void deleteByFilterSkipsSubagentChildren() {
        // Companion to the list test above: bulk-delete-by-filter must
        // mirror the listing exclusion. Otherwise a /conversations "Delete
        // all" would silently nuke subagent transcripts the operator
        // couldn't even see. Tests ConversationService.deleteByFilter
        // directly because Play 1.x's FunctionalTest.DELETE doesn't
        // expose a body-bearing overload, and the controller is a thin
        // pass-through to this service method.
        var ids = commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = "subagent-deletefilter-test";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();

            var parent = new Conversation();
            parent.agent = agent;
            parent.channelType = "web";
            parent.peerId = "delete-filter-test";
            parent.preview = "parent should be deleted";
            parent.save();

            var child = new Conversation();
            child.agent = agent;
            child.channelType = "subagent";
            child.parentConversation = parent;
            child.peerId = "delete-filter-test";
            child.preview = "subagent should survive";
            child.save();

            return new long[] { parent.id, child.id };
        });

        // Filter picks up only the parent (peer matches both, but the
        // listing/filter exclusion drops the child). Returned count is
        // the number of top-level rows the filter resolved to — 1.
        Integer deletedCount = commitInFreshTx(() ->
                ConversationService.deleteByFilter(null, null, null, "delete-filter-test"));
        assertEquals(Integer.valueOf(1), deletedCount,
                "expected deletedCount=1 (parent only — child not directly matched by filter)");

        // The parent IS deleted, and the cascade walks down to the child
        // so no FK-violating orphan remains. Both rows are gone end-state.
        Boolean parentDeleted = commitInFreshTx(() -> Conversation.findById(ids[0]) == null);
        assertTrue(parentDeleted, "parent conversation should have been deleted by the filter");
        Boolean childCascaded = commitInFreshTx(() -> Conversation.findById(ids[1]) == null);
        assertTrue(childCascaded,
                "subagent child must be cascaded when its parent is deleted (FK integrity)");
    }

    // listConversationsWithLimitAndOffset and listConversationChannels merged
    // into listEndpointReturnsJsonArray above.

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

    // --- deleteMessage tests (DELETE /api/conversations/{id}/messages/{mid}) ---

    @Test
    void deleteMessageReturns404ForUnknownConversation() {
        login();
        var resp = DELETE("/api/conversations/999999/messages/1");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void deleteMessageReturns404ForUnknownMessage() {
        login();
        Long convoId = createConversationForOverride("delete-msg-404");
        var resp = DELETE("/api/conversations/" + convoId + "/messages/999999");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void deleteMessageReturns400WhenMessageBelongsToDifferentConversation() {
        login();
        Long[] ids = commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = "delete-msg-mismatch";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var c1 = ConversationService.create(agent, "web", "u1");
            var c2 = ConversationService.create(agent, "web", "u2");
            Message msg = new Message();
            msg.conversation = c1;
            msg.role = "user";
            msg.content = "hi";
            msg.createdAt = Instant.now();
            msg.save();
            return new Long[] { c2.id, msg.id };
        });
        var resp = DELETE("/api/conversations/" + ids[0] + "/messages/" + ids[1]);
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void deleteMessageSucceedsForMatchingConversation() {
        login();
        Long[] ids = commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = "delete-msg-ok";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var c = ConversationService.create(agent, "web", "u1");
            Message msg = new Message();
            msg.conversation = c;
            msg.role = "user";
            msg.content = "delete me";
            msg.createdAt = Instant.now();
            msg.save();
            return new Long[] { c.id, msg.id };
        });
        var resp = DELETE("/api/conversations/" + ids[0] + "/messages/" + ids[1]);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"deleted\""));
        Boolean gone = commitInFreshTx(() -> Message.findById(ids[1]) == null);
        assertTrue(gone, "message must be deleted from DB");
    }

    // --- setModelOverride tests (PUT /api/conversations/{id}/model-override) ---

    @Test
    void setModelOverrideReturns404ForUnknownConversation() {
        login();
        var resp = PUT("/api/conversations/999999/model-override", "application/json",
                "{\"modelProvider\":\"openrouter\",\"modelId\":\"gpt-4.1\"}");
        assertEquals(404, resp.status.intValue());
    }

    /**
     * Three model-override validation rejections that share the seed +
     * PUT + 400 skeleton: missing both fields, blank provider, blank modelId.
     */
    @ParameterizedTest(name = "setModelOverrideReturns400For[{0}]")
    @CsvSource(delimiter = '|', value = {
            "MissingFields  | override-400-missing         | {}",
            "BlankProvider  | override-400-blank-provider  | {\"modelProvider\":\"\",\"modelId\":\"gpt-4.1\"}",
            "BlankModelId   | override-400-blank-model     | {\"modelProvider\":\"openrouter\",\"modelId\":\"\"}"
    })
    void setModelOverrideReturns400ForInvalidBody(String label, String convoTag, String body) {
        login();
        Long convoId = createConversationForOverride(convoTag);
        var resp = PUT("/api/conversations/" + convoId + "/model-override",
                "application/json", body);
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void setModelOverrideReturns400ForUnknownProvider() {
        login();
        Long convoId = createConversationForOverride("override-400-unknown-provider");
        var resp = PUT("/api/conversations/" + convoId + "/model-override",
                "application/json",
                "{\"modelProvider\":\"definitely-not-a-real-provider\",\"modelId\":\"gpt-4.1\"}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("is not configured"));
    }

    @Test
    void setModelOverrideReturns400ForUnknownModel() {
        login();
        seedOpenRouterProvider(
                "[{\"id\":\"some-other-model\",\"name\":\"X\",\"contextWindow\":1000,\"maxTokens\":100}]");
        Long convoId = createConversationForOverride("override-400-unknown-model");
        var resp = PUT("/api/conversations/" + convoId + "/model-override",
                "application/json",
                "{\"modelProvider\":\"openrouter\",\"modelId\":\"definitely-not-a-real-model\"}");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("has no model with id"));
    }

    @Test
    void setModelOverrideSucceedsAndPersistsValues() {
        login();
        seedOpenRouterProvider(
                "[{\"id\":\"gpt-4.1\",\"name\":\"GPT-4.1\",\"contextWindow\":1000,\"maxTokens\":100}]");
        Long convoId = createConversationForOverride("override-ok");
        var resp = PUT("/api/conversations/" + convoId + "/model-override",
                "application/json",
                "{\"modelProvider\":\"openrouter\",\"modelId\":\"gpt-4.1\"}");
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("openrouter"));
        assertTrue(content.contains("gpt-4.1"));
        Boolean persisted = commitInFreshTx(() -> {
            Conversation c = Conversation.findById(convoId);
            return "openrouter".equals(c.modelProviderOverride)
                    && "gpt-4.1".equals(c.modelIdOverride);
        });
        assertTrue(persisted, "override fields must persist on conversation row");
    }

    private void seedOpenRouterProvider(String modelsJson) {
        commitInFreshTx(() -> {
            services.ConfigService.set("provider.openrouter.baseUrl", "https://openrouter.ai/api/v1");
            services.ConfigService.set("provider.openrouter.apiKey", "sk-test");
            services.ConfigService.set("provider.openrouter.models", modelsJson);
            return null;
        });
        llm.ProviderRegistry.refresh();
    }

    // --- clearModelOverride tests (DELETE /api/conversations/{id}/model-override) ---

    @Test
    void clearModelOverrideReturns404ForUnknownConversation() {
        login();
        var resp = DELETE("/api/conversations/999999/model-override");
        assertEquals(404, resp.status.intValue());
    }

    // --- enrichToolCallsWithIcons additional branches ---

    @Test
    void getMessagesAcceptsToolCallsPersistedAsJsonArray() {
        // Existing test covers the single-object → array wrap path; this
        // covers the alternate isJsonArray branch (some legacy rows persist
        // as ["..."]) plus the "function is not an object" continue arm.
        login();
        var cid = commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = "tool-call-array-shape";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var conv = ConversationService.create(agent, "web", "u");
            Message asst = new Message();
            asst.conversation = conv;
            asst.role = MessageRole.ASSISTANT.value;
            // Two entries: one well-formed function object, one shape the
            // enricher must skip (function is a string, not object). Both
            // must round-trip without crashing.
            asst.toolCalls = "[{\"id\":\"a\",\"function\":{\"name\":\"web_search\"}},"
                    + "{\"id\":\"b\",\"function\":\"malformed-string\"}]";
            asst.save();
            return conv.id;
        });
        var resp = GET("/api/conversations/" + cid + "/messages");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"toolCalls\":[{"),
                "array shape should pass through, got: " + body);
        assertTrue(body.contains("\"name\":\"web_search\""),
                "first entry's name resolves, got: " + body);
    }

    @Test
    void getMessagesHandlesMalformedToolCallsJson() {
        // The Exception catch returns null — the row should still serialize
        // without breaking the endpoint.
        login();
        var cid = commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = "tool-call-malformed";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var conv = ConversationService.create(agent, "web", "u");
            Message asst = new Message();
            asst.conversation = conv;
            asst.role = MessageRole.ASSISTANT.value;
            asst.toolCalls = "{not valid json";
            asst.save();
            return conv.id;
        });
        var resp = GET("/api/conversations/" + cid + "/messages");
        assertIsOk(resp);
        // Malformed → null → toolCalls field absent OR explicitly null.
        var body = getContent(resp);
        assertTrue(body.contains("\"toolCalls\":null") || !body.contains("\"toolCalls\":["),
                "malformed JSON must not crash the endpoint: " + body);
    }

    @Test
    void getMessagesHandlesPrimitiveToolCallsValue() {
        // enrichToolCallsWithIcons's "neither array nor object" branch:
        // persisted value is a JSON primitive (number/bool/string).
        login();
        var cid = commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = "tool-call-primitive";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var conv = ConversationService.create(agent, "web", "u");
            Message asst = new Message();
            asst.conversation = conv;
            asst.role = MessageRole.ASSISTANT.value;
            asst.toolCalls = "42";  // JSON primitive — neither array nor object
            asst.save();
            return conv.id;
        });
        var resp = GET("/api/conversations/" + cid + "/messages");
        assertIsOk(resp);
    }

    // --- deleteConversations branch coverage ---

    @Test
    void deleteByEmptyIdsArrayReturnsZero() {
        // Edge case: {"ids":[]} should short-circuit to deleted=0 before any
        // DB call, rather than fall through to badRequest.
        login();
        var resp = deleteWithJsonBody("/api/conversations", "{\"ids\":[]}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":0"),
                "empty ids array must return deleted=0: " + getContent(resp));
    }

    @Test
    void deleteByFilterWithBlankFieldsTreatedAsAbsent() {
        // stringField returns null for blank strings; deleteByFilter then
        // treats those fields as not-specified. With ALL filter fields blank,
        // it's effectively an empty-filter delete (wipe everything).
        login();
        commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = "blank-fields-agent";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            ConversationService.create(agent, "web", "u1");
            ConversationService.create(agent, "telegram", "u2");
            return null;
        });

        var resp = deleteWithJsonBody("/api/conversations",
                "{\"filter\":{\"channel\":\"   \",\"name\":\"\",\"peer\":null}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":2"),
                "blank filter fields must collapse to wipe-all: " + getContent(resp));
    }

    @Test
    void deleteByFilterWithBlankAgentIdHonoredAsNull() {
        // Specifically the longField path: missing/null agentId → null →
        // service-level "any agent". Combined with a channel filter to scope
        // the wipe.
        login();
        Long convoId = commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = "long-field-null";
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            var c1 = ConversationService.create(agent, "slack", "uX");
            ConversationService.create(agent, "web", "uY");
            return c1.id;
        });
        // agentId omitted entirely; only channel set.
        var resp = deleteWithJsonBody("/api/conversations",
                "{\"filter\":{\"channel\":\"slack\"}}");
        assertIsOk(resp);
        Boolean slackGone = commitInFreshTx(() -> Conversation.findById(convoId) == null);
        assertTrue(slackGone, "slack conversation should have been deleted");
    }

    @Test
    void clearModelOverrideRemovesPersistedValues() {
        login();
        Long convoId = createConversationForOverride("clear-override");
        PUT("/api/conversations/" + convoId + "/model-override",
                "application/json",
                "{\"modelProvider\":\"openrouter\",\"modelId\":\"gpt-4.1\"}");
        var resp = DELETE("/api/conversations/" + convoId + "/model-override");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"cleared\""));
        Boolean cleared = commitInFreshTx(() -> {
            Conversation c = Conversation.findById(convoId);
            return c.modelProviderOverride == null && c.modelIdOverride == null;
        });
        assertTrue(cleared, "override fields must be null after clear");
    }

    private Long createConversationForOverride(String agentName) {
        return commitInFreshTx(() -> {
            Agent agent = new Agent();
            agent.name = agentName;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.save();
            return ConversationService.create(agent, "web", "u1").id;
        });
    }
}
