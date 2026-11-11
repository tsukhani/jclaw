import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.ApiTaskSearchController;
import controllers.RequestPrincipal;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.h2.fulltext.FullTextLucene;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.mvc.Http;
import play.test.Fixtures;
import play.test.FunctionalTest;
import tools.JClawApiTool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Functional HTTP tests for {@code GET /api/task-runs/search} (JCLAW-294
 * commit 8). Routes through services.search.MessageSearch which dispatches
 * to H2 FullTextLucene under the test DB; tests both the empty/error paths
 * and the live-trigger indexing of newly inserted messages.
 */
class ApiTasksControllerSearchTest extends FunctionalTest {

    @BeforeEach
    void setup() throws Exception {
        // JCLAW-428: serialize against other Lucene tests and open a clean
        // index at the %test path (data/jclaw-lucene-test). openForTest()
        // replaces the old close/open/wipe dance.
        LuceneTestSync.openForTest();
        // Drop any FT state left from a prior test so Play's
        // Fixtures.deleteDatabase doesn't trip on the FT.INDEXES
        // housekeeping table — see H2LuceneMessageSearchRepositoryTest
        // for the same dance.
        dropFtSafely();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
        // FullTextSearchInitJob skips init in test mode; this test
        // wants the live triggers + Lucene index, so init explicitly.
        services.search.MessageSearch.init();
    }

    @AfterEach
    void cleanup() {
        // Tear down so the NEXT test class's Fixtures.deleteDatabase
        // doesn't blow up on FT.INDEXES.
        dropFtSafely();
        LuceneTestSync.release();
    }

    private static void dropFtSafely() {
        try (var conn = DB.getDataSource().getConnection()) {
            FullTextLucene.dropAll(conn);
        } catch (Exception _) {
            // No FT state to drop — fine, defensive cleanup only.
        }
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    private Long seedAgent() {
        return seedAgent("task-search-agent");
    }

    private Long seedAgent(String name) {
        var resp = POST("/api/agents", "application/json", """
                {"name": "%s", "modelProvider": "openrouter", "modelId": "gpt-4.1"}
                """.formatted(name));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    private Long seedTask(Long agentId, String name) {
        var resp = POST("/api/tasks", "application/json", """
                {"agentId": %d, "name": "%s", "schedule": "1d"}
                """.formatted(agentId, name));
        assertIsOk(resp);
        return Long.parseLong(extractId(getContent(resp)));
    }

    /**
     * Seed a TaskRun + N TaskRunMessage rows in a fresh tx so H2's
     * FullTextLucene triggers fire on commit (the carrier-thread tx
     * never commits, so messages seeded inline aren't indexed yet
     * when the HTTP search hits the index).
     */
    private static void seedTranscript(Long taskId, String... contents) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var task = (Task) Task.findById(taskId);
                    var run = new TaskRun();
                    run.task = task;
                    run.startedAt = Instant.now();
                    run.completedAt = Instant.now();
                    run.durationMs = 100L;
                    run.status = TaskRun.Status.COMPLETED;
                    run.save();
                    for (String c : contents) {
                        var m = new TaskRunMessage();
                        m.taskRun = run;
                        m.role = MessageRole.ASSISTANT;
                        m.content = c;
                        m.save();
                    }
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try { t.join(); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    private JsonArray hits(String query) {
        var resp = GET("/api/task-runs/search?q=" + query);
        assertIsOk(resp);
        return JsonParser.parseString(getContent(resp)).getAsJsonArray();
    }

    private List<String> snippets(String query) {
        var out = new ArrayList<String>();
        for (var hit : hits(query)) out.add(((JsonObject) hit).get("snippet").getAsString());
        return out;
    }

    private static String extractId(String json) {
        var m = Pattern.compile("\"id\":(\\d+)").matcher(json);
        if (!m.find()) throw new AssertionError("no id in: " + json);
        return m.group(1);
    }

    @Test
    void emptyQueryReturnsEmptyArray() {
        var resp = GET("/api/task-runs/search?q=");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void missingQueryReturnsEmptyArray() {
        var resp = GET("/api/task-runs/search");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void findsMatchingMessages() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "search-task");
        seedTranscript(taskId,
                "the quick brown fox jumps over the lazy dog",
                "completely unrelated content about cats");

        assertEquals(List.of("the quick brown fox jumps over the lazy dog"), snippets("fox"));
    }

    @Test
    void aWordMatchesTheBeginningOfALongerOneAndAStarAddsNothing() {
        var taskId = seedTask(seedAgent(), "prefix-task");
        seedTranscript(taskId, "the quarterly invoice went out", "a reminder about lunch");

        assertEquals(List.of("the quarterly invoice went out"), snippets("invoi"));
        assertEquals(List.of("the quarterly invoice went out"), snippets(encoded("invoi*")));
        assertEquals(List.of(), snippets("nvoice"));
    }

    @Test
    void aHitHoldsEveryWordInAnyOrderAndQuotesMakeNoPhrase() {
        var taskId = seedTask(seedAgent(), "every-word-task");
        seedTranscript(taskId, "invoice drafted for review", "the client got it: send the invoice");

        assertEquals(List.of("the client got it: send the invoice"), snippets(encoded("invoice client")));
        assertEquals(List.of("the client got it: send the invoice"), snippets(encoded("\"invoice send client\"")));
    }

    @Test
    void orAndNotAreWordsAHitMustHoldNotOperators() {
        var taskId = seedTask(seedAgent(), "operator-task");
        seedTranscript(taskId, "pay the invoice", "pay the bill", "invoice or bill, whichever is due");

        assertEquals(List.of("invoice or bill, whichever is due"), snippets(encoded("invoice OR bill")));
        assertEquals(List.of(), snippets(encoded("invoice NOT bill")));
    }

    private static String encoded(String query) {
        return URLEncoder.encode(query, StandardCharsets.UTF_8);
    }

    @Test
    void anAgentFindsOnlyItsOwnTasksTranscriptsAndAnotherAgentsHitsTakeNoPlaceOnThePage() {
        var mine = seedAgent("search-scope-own");
        var theirs = seedAgent("search-scope-theirs");
        seedTranscript(seedTask(mine, "own-search-task"), "the quarterly zephyr report for its owner");
        // Shorter, so they rank above the owner's hit: a filter applied after the limit would
        // leave the owner's one-hit page empty.
        seedTranscript(seedTask(theirs, "their-search-task"), "zephyr one", "zephyr two", "zephyr three");

        assertEquals(List.of("the quarterly zephyr report for its owner"), agentSnippets(mine, "zephyr&limit=1"));
        assertEquals(List.of("zephyr one", "zephyr three", "zephyr two"),
                agentSnippets(theirs, "zephyr").stream().sorted().toList());
        assertEquals(4, snippets("zephyr").size(), "the operator searches every agent's transcripts");
    }

    /** The snippets {@code agentId} gets for {@code query}, sent as jclaw_api sends it. */
    private List<String> agentSnippets(Long agentId, String query) {
        clearCookies();
        try {
            var request = newRequest();
            request.headers.put("authorization",
                    new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
            request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                    new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
            var resp = GET(request, "/api/task-runs/search?q=" + query);
            assertIsOk(resp);
            var out = new ArrayList<String>();
            for (var hit : JsonParser.parseString(getContent(resp)).getAsJsonArray()) {
                out.add(((JsonObject) hit).get("snippet").getAsString());
            }
            return out;
        } finally {
            // A bearer call answers with a cookie carrying the agent principal; the next request is the operator's.
            clearCookies();
            login();
        }
    }

    @Test
    void hitCarriesTheSnippetInPlaceOfTheFullContent() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "shape-task");
        seedTranscript(taskId, "a shapekeyword message");

        var hits = hits("shapekeyword");
        assertEquals(1, hits.size(), hits.toString());
        assertEquals(List.of("messageId", "role", "snippet", "createdAt", "taskRunId", "taskId", "taskName",
                        "agentId", "agentName"),
                new ArrayList<>(hits.get(0).getAsJsonObject().keySet()));
    }

    @Test
    void aMessageShorterThanTheBoundIsItsOwnSnippetUnmarked() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "short-task");
        seedTranscript(taskId, "a short zebra message");

        assertEquals(List.of("a short zebra message"), snippets("zebra"));
    }

    @Test
    void aMatchNearTheStartShowsTheOpeningCutOnlyAtTheEnd() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "start-task");
        seedTranscript(taskId, "A".repeat(49) + " zebra " + "B".repeat(944));

        assertEquals(List.of("A".repeat(49) + " zebra " + "B".repeat(144) + "…"), snippets("zebra"));
    }

    @Test
    void aMatchInTheMiddleIsCentredAndCutAtBothEnds() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "middle-task");
        seedTranscript(taskId, "A".repeat(500) + " zebra " + "B".repeat(500));

        assertEquals(List.of("…" + "A".repeat(96) + " zebra " + "B".repeat(97) + "…"), snippets("zebra"));
    }

    @Test
    void aMatchNearTheEndShowsTheCloseCutOnlyAtTheStart() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "end-task");
        seedTranscript(taskId, "A".repeat(944) + " zebra " + "B".repeat(49));

        assertEquals(List.of("…" + "A".repeat(144) + " zebra " + "B".repeat(49)), snippets("zebra"));
    }

    @Test
    void theSnippetCentresOnTheMatchWhateverItsCase() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "case-task");
        seedTranscript(taskId, "A".repeat(500) + " ZEBRA " + "B".repeat(500));

        assertEquals(List.of("…" + "A".repeat(96) + " ZEBRA " + "B".repeat(97) + "…"), snippets("zebra"));
    }

    @Test
    void aFullPageOfTheLongestHitsFitsUnderTheJclawApiCap() {
        // Every field at its longest: a 255-character task name (the column), a 64-character agent
        // name (the name pattern) and a snippet of quotes, which JSON escapes to twice their length.
        var agent = seedAgent("a".repeat(64));
        var taskId = seedTask(agent, "t".repeat(255));
        var message = "\"".repeat(150) + " zebra " + "\"".repeat(1500);
        seedTranscript(taskId, Collections.nCopies(ApiTaskSearchController.MAX_LIMIT + 1, message)
                .toArray(String[]::new));

        for (var query : List.of("q=zebra", "q=zebra&limit=50", "q=zebra&limit=51")) {
            var resp = GET("/api/task-runs/search?" + query);
            assertIsOk(resp);
            var body = getContent(resp);
            assertEquals(ApiTaskSearchController.MAX_LIMIT, JsonParser.parseString(body).getAsJsonArray().size(),
                    query);
            assertTrue(body.length() <= JClawApiTool.MAX_RESPONSE_CHARS,
                    query + " returned " + body.length() + " characters");
        }
    }

    @Test
    void hitCarriesTheLowercaseRoleWireValue() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "rolecasing-task");
        seedTranscript(taskId, "a distinctive rolecasingtoken phrase");

        var resp = GET("/api/task-runs/search?q=rolecasingtoken");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"role\":\"assistant\""),
                "search hits must use the same wire value as /api/conversations messages; got: " + body);
    }

    @Test
    void noResultsReturnsEmptyArray() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "noresults-task");
        seedTranscript(taskId, "some content here");

        var resp = GET("/api/task-runs/search?q=zzzznomatchzzzz");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp).trim());
    }

    @Test
    void includesParentTaskContext() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "context-task");
        seedTranscript(taskId, "unique-keyword-banana");

        var resp = GET("/api/task-runs/search?q=unique-keyword-banana");
        assertIsOk(resp);
        var body = getContent(resp);
        // Hit carries the parent Task name + Agent name + the run id, so the
        // monitoring UI can render a "found in task X by agent Y" link.
        assertTrue(body.contains("\"taskName\":\"context-task\""), body);
        assertTrue(body.contains("\"agentName\":\"task-search-agent\""), body);
        assertTrue(body.contains("\"taskRunId\":"), body);
    }

    @Test
    void respectsLimitParam() {
        var agent = seedAgent();
        var taskId = seedTask(agent, "limit-task");
        // Seed 5 messages all matching "marker"
        seedTranscript(taskId,
                "marker one", "marker two", "marker three",
                "marker four", "marker five");

        var resp = GET("/api/task-runs/search?q=marker&limit=2");
        assertIsOk(resp);
        var body = getContent(resp);
        int hits = body.split("\"messageId\":", -1).length - 1;
        assertEquals(2, hits, "limit=2 should return exactly 2 results; body=" + body);
    }
}
