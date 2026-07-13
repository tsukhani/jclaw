import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.catalog.CatalogRegistry;
import services.search.MessageSearch;

/**
 * Functional HTTP tests for the importable-skills catalog surface of
 * {@code ApiSkillsController}: the catalog selector list ({@code catalogs}),
 * per-catalog browse/search ({@code catalogSearch}), the refresh endpoint
 * ({@code catalogRefresh}), and the validation/failure paths of the import
 * endpoint ({@code catalogImport}).
 *
 * <p>No network: the static (mastra) catalog is seeded through
 * {@code MASTRA.loadForTest} from an inline fixture, so {@code catalogSearch}
 * never triggers the lazy dump download. Refresh is exercised only against the
 * dynamic clawhub catalog (a no-op that reports {@code refreshed=false}) so the
 * real mastra disk cache is never touched. Import is exercised only up to the
 * fetch stage's fail-fast validations ({@code invalid source} / {@code missing
 * skill id}) — a successful import requires GitHub and is covered elsewhere.
 *
 * <p>{@code loadForTest} indexes into the JVM-global Lucene index, so the whole
 * class is serialized behind {@link LuceneTestSync} (JCLAW-428), mirroring
 * {@code StaticDumpCatalogTest}.
 */
class ApiSkillsControllerCatalogTest extends FunctionalTest {

    // 3 skills, installs descending so browse order is deterministic.
    private static final String FIXTURE = """
            {
              "scrapedAt": "2026-01-30T04:51:07.907Z",
              "skills": [
                {"skillId":"git-commit-helper","displayName":"Git Commit Helper","source":"a/tools","installs":100,"owner":"a","repo":"tools","githubUrl":"https://github.com/a/tools"},
                {"skillId":"react-dashboard","displayName":"React Dashboard","source":"b/web","installs":90,"owner":"b","repo":"web","githubUrl":"https://github.com/b/web"},
                {"skillId":"docker-deploy","displayName":"Docker Deploy","source":"c/ops","installs":80,"owner":"c","repo":"ops","githubUrl":"https://github.com/c/ops"}
              ]
            }
            """;

    @BeforeEach
    void setup() throws Exception {
        play.test.Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        LuceneTestSync.openForTest();
        MessageSearch.init();
        CatalogRegistry.MASTRA.loadForTest(FIXTURE);
    }

    @AfterEach
    void teardown() {
        CatalogRegistry.MASTRA.resetForTest();
        LuceneTestSync.release();
    }

    private void login() {
        var response = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(response);
    }

    // ==================== Auth gate ====================

    @Test
    void catalogsRequiresAuth() {
        assertEquals(401, GET("/api/skills/catalogs").status.intValue());
    }

    @Test
    void catalogSearchRequiresAuth() {
        assertEquals(401, GET("/api/skills/catalog/search?catalog=mastra").status.intValue());
    }

    @Test
    void catalogRefreshRequiresAuth() {
        var resp = POST("/api/skills/catalog/refresh", "application/json",
                "{\"catalog\": \"clawhub\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void catalogImportRequiresAuth() {
        var resp = POST("/api/skills/catalog/import", "application/json",
                "{\"source\": \"a/b\", \"skillId\": \"x\"}");
        assertEquals(401, resp.status.intValue());
    }

    // ==================== GET /api/skills/catalogs ====================

    @Test
    void catalogsListsStaticAndDynamicSources() {
        login();
        var resp = GET("/api/skills/catalogs");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":\"mastra\""), "mastra catalog listed: " + body);
        assertTrue(body.contains("\"type\":\"static\""), "mastra is static: " + body);
        assertTrue(body.contains("\"id\":\"clawhub\""), "clawhub catalog listed: " + body);
        assertTrue(body.contains("\"type\":\"dynamic\""), "clawhub is dynamic: " + body);
        assertTrue(body.contains("\"displayName\""), "selector needs display names: " + body);
    }

    // ==================== GET /api/skills/catalog/search ====================

    @Test
    void catalogSearchBrowsesStaticDumpByInstalls() {
        login();
        var resp = GET("/api/skills/catalog/search?catalog=mastra&pageSize=50");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"total\":3"), "all fixture skills counted: " + body);
        assertTrue(body.contains("git-commit-helper"), "most-installed present: " + body);
        assertTrue(body.contains("docker-deploy"), "least-installed present: " + body);
        // Blank-query browse orders by installs descending.
        assertTrue(body.indexOf("git-commit-helper") < body.indexOf("react-dashboard"),
                "browse must be installs-descending: " + body);
    }

    @Test
    void catalogSearchHonorsPageSizeAndPage() {
        login();
        var page0 = getContent(GET("/api/skills/catalog/search?catalog=mastra&page=0&pageSize=1"));
        assertTrue(page0.contains("git-commit-helper"), "page 0 carries the top skill: " + page0);
        assertFalse(page0.contains("react-dashboard"), "pageSize=1 must slice: " + page0);

        var page1 = getContent(GET("/api/skills/catalog/search?catalog=mastra&page=1&pageSize=1"));
        assertTrue(page1.contains("react-dashboard"), "page 1 carries the second skill: " + page1);
        assertFalse(page1.contains("git-commit-helper"), "page 1 must not repeat page 0: " + page1);
    }

    @Test
    void catalogSearchTermQueryFiltersViaLucene() {
        login();
        var resp = GET("/api/skills/catalog/search?catalog=mastra&q=react&pageSize=50");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("react-dashboard"), "term match returned: " + body);
        assertFalse(body.contains("docker-deploy"), "non-matching skill filtered out: " + body);
    }

    // ==================== POST /api/skills/catalog/refresh ====================

    @Test
    void catalogRefreshOnDynamicCatalogReportsNotApplicable() {
        login();
        var resp = POST("/api/skills/catalog/refresh", "application/json",
                "{\"catalog\": \"clawhub\"}");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"catalog\":\"clawhub\""), "echoes the catalog id: " + body);
        assertTrue(body.contains("\"type\":\"dynamic\""), "reports the catalog type: " + body);
        assertTrue(body.contains("\"refreshed\":false"),
                "a live registry has nothing to snapshot-refresh: " + body);
    }

    // ==================== POST /api/skills/catalog/import — validation/failure ====================

    @Test
    void catalogImportRejectsMissingFields() {
        login();
        assertEquals(400,
                POST("/api/skills/catalog/import", "application/json", "{}").status.intValue());
    }

    @Test
    void catalogImportRejectsBodyMissingSkillId() {
        login();
        var resp = POST("/api/skills/catalog/import", "application/json",
                "{\"source\": \"a/b\"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void catalogImportReportsFailureForMalformedSource() {
        // "not-owner-repo" fails the owner/repo split inside
        // RegistrySkillImporter before any network fetch — the controller
        // surfaces the failure as {"status":"failed","message":...}.
        login();
        var resp = POST("/api/skills/catalog/import", "application/json",
                "{\"source\": \"not-owner-repo\", \"skillId\": \"foo\"}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"failed\""), "import must report failed: " + body);
        assertTrue(body.contains("invalid source"), "message names the cause: " + body);
    }

    @Test
    void catalogImportReportsFailureForBlankSkillId() {
        login();
        var resp = POST("/api/skills/catalog/import", "application/json",
                "{\"source\": \"a/b\", \"skillId\": \"\"}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"failed\""), "import must report failed: " + body);
        assertTrue(body.contains("missing skill id"), "message names the cause: " + body);
    }

    /**
     * With no page/pageSize the browse defaults to page 0 + pageSize 20 (both
     * ternary-default branches), returning the full fixture in one page.
     */
    @Test
    void catalogSearchUsesDefaultPagingWhenParamsOmitted() {
        login();
        var resp = GET("/api/skills/catalog/search?catalog=mastra");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"total\":3"), "all fixture skills counted: " + body);
        assertTrue(body.contains("git-commit-helper") && body.contains("react-dashboard")
                        && body.contains("docker-deploy"),
                "default page must hold all three fixture skills: " + body);
    }

    /**
     * A refresh with a body that carries no {@code catalog} key falls back to the
     * default (first, static) catalog — {@code CatalogRegistry.byId(null)} — and a
     * static catalog reports {@code refreshed=true}. The existing refresh test only
     * covers the dynamic (refreshed=false) arm, and never the null-id fallback.
     */
    @Test
    void catalogRefreshWithoutCatalogKeyDefaultsToStaticCatalog() {
        login();
        var resp = POST("/api/skills/catalog/refresh", "application/json", "{}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"catalog\":\"mastra\""), "falls back to the default catalog: " + body);
        assertTrue(body.contains("\"type\":\"static\""), "the default catalog is static: " + body);
        assertTrue(body.contains("\"refreshed\":true"),
                "a static catalog reports a real refresh: " + body);
    }

    /**
     * An empty request body ({@code readJsonBody} → null) takes the
     * {@code body == null} short-circuit and likewise defaults to the static
     * catalog. Distinct from the has-no-key branch above.
     */
    @Test
    void catalogRefreshWithEmptyBodyDefaultsToStaticCatalog() {
        login();
        var resp = POST("/api/skills/catalog/refresh", "application/json", "");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"catalog\":\"mastra\""), "empty body falls back to default catalog: " + body);
        assertTrue(body.contains("\"refreshed\":true"), "static default reports refreshed: " + body);
    }

    /**
     * Import reads the optional {@code provider} and {@code owner} fields when
     * present and non-null (both ternary-true branches). A non-clawhub provider
     * with a malformed source fails fast at "invalid source" — no network — so the
     * failure is still surfaced with those fields supplied. The existing malformed
     * -source test omits both fields, covering the null branches.
     */
    @Test
    void catalogImportReadsProviderAndOwnerWhenSupplied() {
        login();
        var resp = POST("/api/skills/catalog/import", "application/json",
                "{\"source\": \"not-owner-repo\", \"skillId\": \"foo\","
                + " \"provider\": \"github\", \"owner\": \"someone\"}");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"status\":\"failed\""), "import must report failed: " + body);
        assertTrue(body.contains("invalid source"), "message names the cause: " + body);
    }
}
