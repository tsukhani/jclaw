import org.junit.jupiter.api.*;
import play.test.*;
import play.db.jpa.JPA;
import models.Agent;
import models.Conversation;
import models.SessionCompaction;
import services.ConversationService;

import java.time.Instant;

/**
 * Functional HTTP tests for ApiConversationsController CRUD and query endpoints.
 * Uses real H2 DB, no mocks.
 */
public class ApiConversationsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
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
    public void unauthenticatedRequestReturns401() {
        var response = GET("/api/conversations");
        assertEquals(401, response.status.intValue());
    }

    @Test
    public void listConversationsReturnsJsonArray() {
        login();
        var response = GET("/api/conversations");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    public void listConversationsReturnsEmptyAfterDbWipe() {
        login();
        var response = GET("/api/conversations");
        assertIsOk(response);
        assertEquals("[]", getContent(response));
    }

    @Test
    public void deleteConversationSucceeds() {
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
    public void deleteByIdsPurgesSessionCompactionRows() {
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
    public void deleteNonExistentConversationReturns404() {
        login();
        var response = DELETE("/api/conversations/999999");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void getMessagesForNonExistentConversationReturns404() {
        login();
        var response = GET("/api/conversations/999999/messages");
        assertEquals(404, response.status.intValue());
    }

    @Test
    public void paginationHeadersAreSet() {
        login();
        var response = GET("/api/conversations");
        assertIsOk(response);
        assertNotNull(response.getHeader("X-Total-Count"),
                "X-Total-Count header should be present");
        assertEquals("0", response.getHeader("X-Total-Count"));
    }

    @Test
    public void listConversationsWithLimitAndOffset() {
        login();
        var response = GET("/api/conversations?limit=5&offset=0");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    public void listConversationChannels() {
        login();
        var response = GET("/api/conversations/channels");
        assertIsOk(response);
        assertContentType("application/json", response);
        assertTrue(getContent(response).startsWith("["));
    }

    @Test
    public void getQueueStatusForNonExistentConversation() {
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
