import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import models.Config;
import models.ScrapeJob;
import models.ScrapeJobPage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.Tx;
import services.scrape.ScrapeJobFiles;
import services.scrape.ScrapeRung;
import tools.scrape.ScrapeJobRequest;
import tools.scrape.ScrapeOutput;
import tools.scrape.WebScrapeSettings;
import utils.AppClock;
import utils.GsonHolder;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@code /api/scrape-jobs} (JCLAW-1272): the operator's form route, and each agent reaching only its
 * own jobs.
 *
 * <p>The jobs the access cases read are seeded already finished, so no scheduler starts them. No
 * {@code Fixtures.deleteDatabase}: play1 runs test classes concurrently, and every row here belongs
 * to an agent this class names.
 */
class ApiScrapeJobsControllerTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";

    private final List<String> agentNames = new ArrayList<>();
    private Long callerId;
    private Long alienId;
    private Long ownJobId;
    private Long alienJobId;

    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        var suffix = String.valueOf(System.nanoTime() % 1_000_000_000L);
        var callerName = "sjapi-caller-" + suffix;
        var alienName = "sjapi-alien-" + suffix;
        agentNames.add(callerName);
        agentNames.add(alienName);
        var ids = commit(() -> {
            var caller = agentNamed(callerName);
            var alien = agentNamed(alienName);
            return new Long[]{caller.id, alien.id, finishedJob(caller, 3).id, finishedJob(alien, 1).id};
        });
        callerId = ids[0];
        alienId = ids[1];
        ownJobId = ids[2];
        alienJobId = ids[3];
    }

    @AfterEach
    void cleanUp() throws IOException {
        clearCookies();
        for (var name : agentNames) {
            commit(() -> {
                var agent = Agent.findByName(name);
                if (agent != null) AgentService.delete(agent);
                return null;
            });
            deleteTree(AgentService.workspacePath(name));
        }
    }

    // --- the operator's form ----------------------------------------------------------------

    @Test
    void theOperatorStartsAJobPastTheAgentCeilingsWithoutChangingSettings() throws Exception {
        login();
        var before = webScrapeSettings();
        int pages = commit(WebScrapeSettings::jobMaxPages) + 50;

        // .invalid never resolves, so the job fails at once without reaching the network.
        var response = POST("/api/scrape-jobs", "application/json", """
                {"agentId": %d, "url": "https://scrape-api-test.invalid/", "maxPages": %d, "maxDepth": 0,
                 "maxMinutes": 1}""".formatted(callerId, pages));

        assertIsOk(response);
        var job = json(response);
        assertEquals(pages, job.getAsJsonObject("options").get("maxPages").getAsInt(),
                "the operator's own limit is not clamped to the agent ceiling");
        assertTrue(job.get("conversationId").isJsonNull());
        assertEquals(before, webScrapeSettings(), "starting a job writes no setting");

        var id = job.get("id").getAsLong();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!commit(() -> ((ScrapeJob) ScrapeJob.findById(id)).state).terminal()) {
            assertTrue(System.nanoTime() < deadline, "the job ends");
            Thread.sleep(25);
        }
    }

    @Test
    void theFormRouteRefusesAnAgent() {
        var response = asAgent(() -> POST(agentRequest(callerId), "/api/scrape-jobs", "application/json",
                "{\"agentId\": %d, \"url\": \"https://site.test/\"}".formatted(callerId)));

        assertStatus(403, response);
        assertTrue(getContent(response).contains("operator_only"), getContent(response));
    }

    @Test
    void aBadFieldIsNamedAndStartsNothing() {
        login();

        var badLimit = POST("/api/scrape-jobs", "application/json",
                "{\"agentId\": %d, \"url\": \"https://site.test/\", \"maxPages\": 0}".formatted(callerId));
        assertStatus(400, badLimit);
        assertEquals("maxPages", json(badLimit).get("field").getAsString());

        var badSelector = POST("/api/scrape-jobs", "application/json", """
                {"agentId": %d, "url": "https://site.test/", "extract": {"x": "div[["}}""".formatted(callerId));
        assertStatus(400, badSelector);
        assertEquals("extract", json(badSelector).get("field").getAsString());

        var noAgent = POST("/api/scrape-jobs", "application/json", "{\"url\": \"https://site.test/\"}");
        assertStatus(400, noAgent);
        assertEquals("agentId", json(noAgent).get("field").getAsString());

        assertEquals(1L, (long) commit(() -> ScrapeJob.count("agent.id = ?1", callerId)),
                "only the seeded job exists");
    }

    // --- one agent, its own jobs ------------------------------------------------------------

    @Test
    void anAgentListsOnlyItsOwnJobs() {
        var response = asAgent(() -> GET(agentRequest(callerId), "/api/scrape-jobs?agentId=" + alienId));

        assertIsOk(response);
        var ids = new ArrayList<Long>();
        json(response, JsonArray.class).forEach(e -> ids.add(e.getAsJsonObject().get("id").getAsLong()));
        assertTrue(ids.contains(ownJobId), ids.toString());
        assertFalse(ids.contains(alienJobId), "the agent filter is pinned to the caller: " + ids);
    }

    @Test
    void anAgentReachesItsOwnJobAndNotAnotherAgents() {
        var ownPage = commit(() -> ((ScrapeJobPage) ScrapeJobPage.find("job.id = ?1 ORDER BY pageIndex", ownJobId)
                .first()).id);
        var alienPage = commit(() -> ((ScrapeJobPage) ScrapeJobPage.find("job.id = ?1", alienJobId).first()).id);

        for (var path : List.of("", "/pages", "/pages/" + ownPage + "/content", "/download")) {
            var own = asAgent(() -> GET(agentRequest(callerId), "/api/scrape-jobs/" + ownJobId + path));
            assertIsOk(own);
        }
        for (var path : List.of("", "/pages", "/pages/" + alienPage + "/content", "/download")) {
            var alien = asAgent(() -> GET(agentRequest(callerId), "/api/scrape-jobs/" + alienJobId + path));
            assertStatus(403, alien);
            assertTrue(getContent(alien).contains(AGENT_SCOPE), path + ": " + getContent(alien));
        }
        for (var action : List.of("/cancel", "/pause", "/resume")) {
            var refused = asAgent(() -> POST(agentRequest(callerId), "/api/scrape-jobs/" + alienJobId + action,
                    "application/json", "{}"));
            assertStatus(403, refused);
        }
        var delete = asAgent(() -> DELETE(agentRequest(callerId), "/api/scrape-jobs/" + alienJobId));
        assertStatus(403, delete);
        assertNotNull(commit(() -> ScrapeJob.findById(alienJobId)), "a refused delete deletes nothing");
    }

    @Test
    void anUnstampedBearerIsNotTreatedAsAnyAgent() {
        var response = asAgent(() -> GET(bareBearerRequest(), "/api/scrape-jobs"));
        assertStatus(403, response);
    }

    // --- reading a job ----------------------------------------------------------------------

    @Test
    void pagesComeInOrderAfterTheCursor() {
        login();
        var response = GET("/api/scrape-jobs/" + ownJobId + "/pages?after=1");

        assertIsOk(response);
        var indexes = new ArrayList<Integer>();
        json(response, JsonArray.class).forEach(e -> indexes.add(e.getAsJsonObject().get("index").getAsInt()));
        assertEquals(List.of(2, 3), indexes);
    }

    @Test
    void aPageAndTheCombinedFileCanBeRead() throws IOException {
        login();
        var page = commit(() -> ((ScrapeJobPage) ScrapeJobPage.find("job.id = ?1 AND pageIndex = 2", ownJobId)
                .first()).id);

        var content = GET("/api/scrape-jobs/" + ownJobId + "/pages/" + page + "/content");
        assertIsOk(content);
        assertEquals("# Page 2", json(content).get("content").getAsString());
        assertEquals("markdown", json(content).get("format").getAsString());

        var download = GET("/api/scrape-jobs/" + ownJobId + "/download");
        assertIsOk(download);
        assertEquals("attachment; filename=\"scrape-%d.md\"".formatted(ownJobId),
                download.getHeader("Content-Disposition"));
        // Served from disk, so the body is the file rather than bytes the test harness captured.
        assertTrue(download.direct instanceof File, "the file is served as it is on disk");
        assertTrue(Files.readString(((File) download.direct).toPath()).contains("# Page 3"));
    }

    @Test
    void aFinishedJobCannotBeStoppedPausedOrResumedButCanBeDeleted() {
        for (var action : List.of("/cancel", "/pause", "/resume")) {
            var refused = asAgent(() -> POST(agentRequest(callerId), "/api/scrape-jobs/" + ownJobId + action,
                    "application/json", "{}"));
            assertStatus(409, refused);
        }

        var delete = asAgent(() -> DELETE(agentRequest(callerId), "/api/scrape-jobs/" + ownJobId));
        assertIsOk(delete);
        assertNull(commit(() -> ScrapeJob.findById(ownJobId)));
        assertEquals(0L, (long) commit(() -> ScrapeJobPage.count("job.id = ?1", ownJobId)));
    }

    // --- helpers ----------------------------------------------------------------------------

    private static Agent agentNamed(String name) {
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    /** A job that has already ended, with {@code pages} pages written and its combined file in place. */
    private static ScrapeJob finishedJob(Agent agent, int pages) {
        var job = new ScrapeJob();
        job.agent = agent;
        job.url = "https://site.test/";
        job.options = GsonHolder.GSON.toJson(ScrapeJobRequest.forOperator(
                JsonParser.parseString("{\"url\":\"https://site.test/\"}").getAsJsonObject()).toJson());
        job.state = ScrapeJob.State.SUCCEEDED;
        job.pagesRead = pages;
        job.pagesFetched = pages;
        job.pagesDiscovered = pages;
        job.startedAt = AppClock.now();
        job.completedAt = AppClock.now();
        job.save();
        var combined = new StringBuilder("Scraped %d pages\n".formatted(pages));
        for (int i = 1; i <= pages; i++) {
            var file = ScrapeJobFiles.pageFile(job.id, i, ScrapeOutput.Format.MARKDOWN);
            AgentService.writeWorkspaceFile(agent.name, file, "# Page " + i);
            var page = new ScrapeJobPage();
            page.job = job;
            page.pageIndex = i;
            page.url = "https://site.test/" + i;
            page.servedBy = ScrapeRung.PLAIN;
            page.outcome = ScrapeJobPage.Outcome.FETCHED;
            page.chars = 8;
            page.file = file;
            page.fetchedAt = AppClock.now();
            page.save();
            combined.append("\n\n## ").append(page.url).append("\n\n# Page ").append(i);
        }
        AgentService.writeWorkspaceFile(agent.name,
                ScrapeJobFiles.combinedFile(job.id, ScrapeOutput.Format.MARKDOWN), combined.toString());
        return job;
    }

    private static Map<String, String> webScrapeSettings() {
        return commit(() -> {
            var settings = new TreeMap<String, String>();
            List<Config> rows = Config.find("from Config c where c.key LIKE ?1", "web_scrape.%").fetch();
            rows.forEach(row -> settings.put(row.key, row.value));
            return settings;
        });
    }

    private void login() {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    private static JsonObject json(Http.Response response) {
        return json(response, JsonObject.class);
    }

    private static <T> T json(Http.Response response, Class<T> type) {
        return GsonHolder.GSON.fromJson(getContent(response), type);
    }

    private static Http.Request agentRequest(Long agentId) {
        var request = bareBearerRequest();
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    private static Http.Request bareBearerRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
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

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.exists(dir)) return;
        try (var walk = Files.walk(dir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
