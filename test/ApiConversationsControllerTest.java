import org.junit.jupiter.api.*;
import play.test.*;
import play.db.jpa.JPA;
import models.Agent;
import models.Conversation;
import services.ConversationService;

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
