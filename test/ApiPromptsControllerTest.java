import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ApiPromptsController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import tools.JClawApiTool;

import java.util.Set;
import java.util.function.Supplier;

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

    // ==================== generate ====================

    @Test
    void generateRequiresAuth() {
        var resp = POST("/api/prompts/generate", "application/json", "{\"description\":\"x\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void generateRejectsBlankDescription() {
        login();
        assertEquals(400, POST("/api/prompts/generate", "application/json", "{}").status.intValue());
    }

    @Test
    void generateReturns503WhenNoLlmConfigured() {
        // setUp() wiped the DB, so there is no 'main' agent / provider to resolve —
        // generation is unavailable and must surface a clean error, not a 500.
        login();
        var resp = POST("/api/prompts/generate", "application/json", "{\"description\":\"a code review checklist\"}");
        assertEquals(503, resp.status.intValue());
    }

    // ==================== list ====================

    @Test
    void listEmptyWhenNoPrompts() {
        login();
        var resp = GET("/api/prompts");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void listFiltersByTitleIgnoringCase() {
        login();
        createPrompt("Article Creator: LinkedIn Posts", "a", "WRITING");
        createPrompt("Code Review Checklist", "b", "CODING");
        var arr = JsonParser.parseString(getContent(GET("/api/prompts?title=article%20creator"))).getAsJsonArray();
        assertEquals(1, arr.size(), arr.toString());
        assertEquals("Article Creator: LinkedIn Posts", arr.get(0).getAsJsonObject().get("title").getAsString());
        assertEquals("a", arr.get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void listTitleFilterWithNoMatchIsEmpty() {
        login();
        createPrompt("Code Review Checklist", "b", "CODING");
        assertEquals("[]", getContent(GET("/api/prompts?title=Article%20Creator")).trim());
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

    // ==================== an agent's list and single read (JF-144) ====================

    private static final String AT_BOUND = "0123456789".repeat(ApiPromptsController.CONTENT_PREVIEW_CHARS / 10);
    private static final String LONG_CONTENT = AT_BOUND.repeat(25);

    @Test
    void anAgentsListRowKeepsContentAtTheBoundWholeAndUnmarked() {
        login();
        var id = createPrompt("Review", AT_BOUND, "CODING", "a, b");

        var row = onlyRow(asAgent(() -> GET(agentRequest(), "/api/prompts")));

        assertEquals(Set.of("id", "title", "tags", "category", "categoryLabel",
                "contentPreview", "contentTruncated", "createdAt", "updatedAt"), row.keySet());
        assertEquals(id, row.get("id").getAsLong());
        assertEquals("Review", row.get("title").getAsString());
        assertEquals("a, b", row.get("tags").getAsString());
        assertEquals("CODING", row.get("category").getAsString());
        assertEquals("Coding", row.get("categoryLabel").getAsString());
        assertEquals(AT_BOUND, row.get("contentPreview").getAsString());
        assertFalse(row.get("contentTruncated").getAsBoolean());
    }

    @Test
    void anAgentsListRowCutsContentOneCharacterOverTheBoundAndMarksTheCut() {
        login();
        createPrompt("Review", AT_BOUND + "X", "CODING", "a, b");

        var row = onlyRow(asAgent(() -> GET(agentRequest(), "/api/prompts")));

        assertEquals(AT_BOUND + "…", row.get("contentPreview").getAsString());
        assertTrue(row.get("contentTruncated").getAsBoolean());
    }

    @Test
    void anAgentsTitleFilterStillApplies() {
        login();
        createPrompt("Article Creator", LONG_CONTENT, "WRITING", "a");
        createPrompt("Code Review Checklist", LONG_CONTENT, "CODING", "b");

        var row = onlyRow(asAgent(() -> GET(agentRequest(), "/api/prompts?title=article")));

        assertEquals("Article Creator", row.get("title").getAsString());
        assertEquals(AT_BOUND + "…", row.get("contentPreview").getAsString());
    }

    @Test
    void theOperatorsListRowStillCarriesTheWholeContent() {
        login();
        var id = createPrompt("Review", LONG_CONTENT, "CODING", "a, b");

        var row = onlyRow(GET("/api/prompts"));

        assertEquals(Set.of("id", "title", "content", "tags", "category", "categoryLabel",
                "createdAt", "updatedAt"), row.keySet());
        assertEquals(id, row.get("id").getAsLong());
        assertEquals(LONG_CONTENT, row.get("content").getAsString());
    }

    @Test
    void anAgentReadsOnePromptWhole() {
        login();
        var id = createPrompt("Review", LONG_CONTENT, "CODING", "a, b");
        var operatorRow = onlyRow(GET("/api/prompts"));

        var resp = asAgent(() -> GET(agentRequest(), "/api/prompts/" + id));

        assertIsOk(resp);
        var prompt = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals(LONG_CONTENT, prompt.get("content").getAsString());
        assertEquals(operatorRow, prompt);
    }

    @Test
    void theOperatorReadsOnePromptWhole() {
        login();
        var id = createPrompt("Review", LONG_CONTENT, "CODING", "a, b");
        createPrompt("Other", "c", "WRITING", "c");

        var resp = GET("/api/prompts/" + id);

        assertIsOk(resp);
        var prompt = JsonParser.parseString(getContent(resp)).getAsJsonObject();
        assertEquals(id, prompt.get("id").getAsLong());
        assertEquals("Review", prompt.get("title").getAsString());
        assertEquals(LONG_CONTENT, prompt.get("content").getAsString());
        assertEquals("a, b", prompt.get("tags").getAsString());
        assertEquals("CODING", prompt.get("category").getAsString());
        assertEquals("Coding", prompt.get("categoryLabel").getAsString());
    }

    @Test
    void anUnknownPromptIdIsNotFoundForTheOperatorAndForAnAgent() {
        login();
        createPrompt("Review", "c", "CODING", "a");

        assertIsNotFound(GET("/api/prompts/999999"));
        assertIsNotFound(asAgent(() -> GET(agentRequest(), "/api/prompts/999999")));
    }

    @Test
    void anAgentsExportStillCarriesTheWholeContent() {
        login();
        createPrompt("Review", LONG_CONTENT, "CODING", "a, b");

        var doc = JsonParser.parseString(getContent(asAgent(() -> GET(agentRequest(), "/api/prompts/export"))))
                .getAsJsonObject();

        var prompts = doc.getAsJsonArray("prompts");
        assertEquals(1, prompts.size());
        assertEquals(LONG_CONTENT, prompts.get(0).getAsJsonObject().get("content").getAsString());
    }

    @Test
    void todaysPromptCountFitsUnderTheJClawApiCapForAnAgent() {
        // 21 prompts on the live instance on 2026-11-11, here at the title and tags column maxima.
        login();
        for (int i = 0; i < 21; i++) {
            createPrompt(("Prompt " + i + " ").repeat(40).substring(0, 200), LONG_CONTENT, "CODING", "t".repeat(500));
        }

        var agentBody = getContent(asAgent(() -> GET(agentRequest(), "/api/prompts")));
        login();
        var operatorBody = getContent(GET("/api/prompts"));

        assertEquals(21, JsonParser.parseString(agentBody).getAsJsonArray().size());
        assertTrue(agentBody.length() < JClawApiTool.MAX_RESPONSE_CHARS, "agent list chars: " + agentBody.length());
        assertTrue(operatorBody.length() > JClawApiTool.MAX_RESPONSE_CHARS,
                "the whole rows must overflow the cap, or this measures nothing: " + operatorBody.length());
    }

    // ==================== helpers ====================

    private long createPrompt(String title, String content, String category) {
        var resp = POST("/api/prompts", "application/json",
                "{\"title\":\"%s\",\"content\":\"%s\",\"category\":\"%s\"}".formatted(title, content, category));
        assertIsOk(resp);
        return JsonParser.parseString(getContent(resp)).getAsJsonObject().get("id").getAsLong();
    }

    private long createPrompt(String title, String content, String category, String tags) {
        var body = new JsonObject();
        body.addProperty("title", title);
        body.addProperty("content", content);
        body.addProperty("category", category);
        body.addProperty("tags", tags);
        var resp = POST("/api/prompts", "application/json", body.toString());
        assertIsOk(resp);
        return JsonParser.parseString(getContent(resp)).getAsJsonObject().get("id").getAsLong();
    }

    private JsonObject onlyRow(Http.Response resp) {
        assertIsOk(resp);
        var rows = JsonParser.parseString(getContent(resp)).getAsJsonArray();
        assertEquals(1, rows.size(), rows.toString());
        return rows.get(0).getAsJsonObject();
    }

    /** The agent principal: the internal bearer, which takes precedence over the operator's session cookie. */
    private static Http.Request agentRequest() {
        var request = newRequest();
        request.headers.put("authorization",
                new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        return request;
    }

    /** A bearer call leaves an agent-principal cookie in the JVM-global jar; clear it so no sibling inherits it. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }
}
