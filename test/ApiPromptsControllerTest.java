import com.google.gson.JsonParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

/**
 * Functional HTTP tests for {@code ApiPromptsController} (JCLAW-813): auth
 * gating, the fixed category list, CRUD round-trips, validation, and the
 * import/export (merge / replace / unknown-category coercion) paths.
 */
class ApiPromptsControllerTest extends FunctionalTest {

    @BeforeEach
    void setUp() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    // ==================== auth ====================

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/prompts").status.intValue());
    }

    @Test
    void createRequiresAuth() {
        var resp = POST("/api/prompts", "application/json",
                "{\"title\":\"x\",\"content\":\"y\",\"category\":\"CODING\"}");
        assertEquals(401, resp.status.intValue());
    }

    // ==================== categories ====================

    @Test
    void categoriesReturnsFixedSix() {
        login();
        var resp = GET("/api/prompts/categories");
        assertIsOk(resp);
        var content = getContent(resp);
        assertEquals(6, JsonParser.parseString(content).getAsJsonArray().size());
        assertTrue(content.contains("\"value\":\"CODING\""), content);
        assertTrue(content.contains("\"label\":\"Coding\""), content);
    }

    // ==================== list ====================

    @Test
    void listEmptyWhenNoPrompts() {
        login();
        var resp = GET("/api/prompts");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    // ==================== create ====================

    @Test
    void createPersistsPrompt() {
        login();
        var resp = POST("/api/prompts", "application/json", """
                {"title":"Review","content":"Do a review","category":"CODING","tags":"a, b"}
                """);
        assertIsOk(resp);
        var content = getContent(resp);
        assertTrue(content.contains("\"title\":\"Review\""), content);
        assertTrue(content.contains("\"category\":\"CODING\""), content);
        assertTrue(content.contains("\"categoryLabel\":\"Coding\""), content);
        assertTrue(content.contains("\"tags\":\"a, b\""), content);
    }

    @Test
    void createRejectsMissingTitle() {
        login();
        var resp = POST("/api/prompts", "application/json",
                "{\"content\":\"c\",\"category\":\"CODING\"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void createRejectsUnknownCategory() {
        login();
        var resp = POST("/api/prompts", "application/json",
                "{\"title\":\"t\",\"content\":\"c\",\"category\":\"NONSENSE\"}");
        assertEquals(400, resp.status.intValue());
    }

    // ==================== update ====================

    @Test
    void updateChangesOnlySuppliedFields() {
        login();
        var id = createPrompt("Old", "c", "CODING");
        var resp = PUT("/api/prompts/" + id, "application/json", "{\"title\":\"New\"}");
        assertIsOk(resp);
        var updated = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals("New", updated.get("title").getAsString());
        assertEquals("CODING", updated.get("category").getAsString());  // untouched
    }

    @Test
    void updateMissingPromptIs404() {
        login();
        assertEquals(404, PUT("/api/prompts/999999", "application/json", "{\"title\":\"x\"}").status.intValue());
    }

    // ==================== delete ====================

    @Test
    void deleteRemovesPrompt() {
        login();
        var id = createPrompt("D", "c", "CUSTOM");
        assertIsOk(DELETE("/api/prompts/" + id));
        assertEquals("[]", getContent(GET("/api/prompts")).trim());
    }

    // ==================== export / import ====================

    @Test
    void exportReturnsAllPrompts() {
        login();
        createPrompt("P1", "c1", "CODING");
        createPrompt("P2", "c2", "WRITING");
        var content = getContent(GET("/api/prompts/export"));
        assertTrue(content.contains("\"version\""), content);
        assertTrue(content.contains("\"P1\""), content);
        assertTrue(content.contains("\"P2\""), content);
    }

    @Test
    void importMergeAppends() {
        login();
        createPrompt("Existing", "c", "CODING");
        var resp = POST("/api/prompts/import", "application/json", """
                {"mode":"merge","prompts":[{"title":"Added","content":"c2","category":"WRITING"}]}
                """);
        assertIsOk(resp);
        assertEquals(2, JsonParser.parseString(getContent(GET("/api/prompts"))).getAsJsonArray().size());
    }

    @Test
    void importReplaceWipesFirst() {
        login();
        createPrompt("Gone1", "c", "CODING");
        createPrompt("Gone2", "c", "CODING");
        var resp = POST("/api/prompts/import", "application/json", """
                {"mode":"replace","prompts":[{"title":"OnlyOne","content":"c","category":"CUSTOM"}]}
                """);
        assertIsOk(resp);
        var arr = JsonParser.parseString(getContent(GET("/api/prompts"))).getAsJsonArray();
        assertEquals(1, arr.size());
        assertEquals("OnlyOne", arr.get(0).getAsJsonObject().get("title").getAsString());
    }

    @Test
    void importCoercesUnknownCategoryToCustom() {
        login();
        assertIsOk(POST("/api/prompts/import", "application/json", """
                {"mode":"merge","prompts":[{"title":"Foreign","content":"c","category":"ZZZ_UNKNOWN"}]}
                """));
        var arr = JsonParser.parseString(getContent(GET("/api/prompts"))).getAsJsonArray();
        assertEquals(1, arr.size());
        assertEquals("CUSTOM", arr.get(0).getAsJsonObject().get("category").getAsString());
    }

    @Test
    void importRejectsBadMode() {
        login();
        assertEquals(400, POST("/api/prompts/import", "application/json",
                "{\"mode\":\"nuke\",\"prompts\":[]}").status.intValue());
    }

    // ==================== helpers ====================

    private long createPrompt(String title, String content, String category) {
        var resp = POST("/api/prompts", "application/json",
                "{\"title\":\"%s\",\"content\":\"%s\",\"category\":\"%s\"}".formatted(title, content, category));
        assertIsOk(resp);
        return JsonParser.parseString(getContent(resp)).getAsJsonObject().get("id").getAsLong();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }
}
