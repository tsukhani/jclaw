import memory.MemoryStoreFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-707 branch-coverage companion for {@code ApiMemoryController}. The
 * primary suite ({@code ApiMemoryControllerTest}) covers list happy paths, the
 * agent/category filters, {@code >0.8} importance, sort by importance/agent,
 * update-importance, delete, and bulk delete. This suite fills the remaining
 * decision branches:
 * <ul>
 *   <li>{@code applyImportance} — every comparator arm ({@code <}, {@code <=},
 *       {@code >=}, a bare number, and a non-numeric value that's ignored), not
 *       just the {@code >} arm the primary suite exercises.</li>
 *   <li>{@code orderByClause} — the {@code text}, {@code category}, and
 *       {@code created} sort columns (only importance/agent were covered).</li>
 *   <li>{@code update} — the {@code category} normalize arm, the below-range
 *       ({@code < 0.0}) importance rejection, and the malformed-body null
 *       guard.</li>
 *   <li>{@code bulkDelete} — the malformed-body null guard.</li>
 * </ul>
 * Uses the closed-index (LIKE-fallback) idiom of the primary suite: none of
 * these tests exercise free-text {@code q}, only JPQL filters/sort.
 */
class ApiMemoryControllerCoverageTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        LuceneTestSync.closedForTest();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        MemoryStoreFactory.reset();
    }

    @AfterEach
    void release() {
        LuceneTestSync.release();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(resp);
    }

    private static <T> T fetchInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
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

    private String seedMemory(String agentName, String text, String category, double importance) {
        return fetchInFreshTx(() -> {
            models.Agent agent = models.Agent.find("name = ?1", agentName).first();
            if (agent == null) {
                agent = services.AgentService.create(agentName, "openrouter", "gpt-4.1");
            }
            return MemoryStoreFactory.get().store(String.valueOf(agent.id), text, category, importance);
        });
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }

    // ─── importance comparator arms (applyImportance) ─────────────────────────

    @Test
    void importanceThresholdComparatorArms() {
        // Three rows under one agent so every GET is scoped away from any
        // concurrently-running suite's rows via the agent filter.
        var agent = "impfilter707";
        seedMemory(agent, "imp-high", "fact", 0.9);
        seedMemory(agent, "imp-mid", "fact", 0.5);
        seedMemory(agent, "imp-low", "fact", 0.2);
        login();

        // "<0.5" strict-less → only the 0.2 row.
        var lt = getContent(GET("/api/memories?agent=" + agent + "&importance=" + enc("<0.5")));
        assertTrue(lt.contains("imp-low"), "strict < keeps 0.2: " + lt);
        assertFalse(lt.contains("imp-mid"), "strict < excludes the 0.5 boundary: " + lt);
        assertFalse(lt.contains("imp-high"), "strict < excludes 0.9: " + lt);

        // "<=0.5" inclusive-less → 0.2 and the 0.5 boundary.
        var lte = getContent(GET("/api/memories?agent=" + agent + "&importance=" + enc("<=0.5")));
        assertTrue(lte.contains("imp-low") && lte.contains("imp-mid"),
                "inclusive <= keeps 0.2 and the 0.5 boundary: " + lte);
        assertFalse(lte.contains("imp-high"), "inclusive <= excludes 0.9: " + lte);

        // ">=0.9" inclusive-greater → only the 0.9 boundary.
        var gte = getContent(GET("/api/memories?agent=" + agent + "&importance=" + enc(">=0.9")));
        assertTrue(gte.contains("imp-high"), "inclusive >= keeps the 0.9 boundary: " + gte);
        assertFalse(gte.contains("imp-mid"), "inclusive >= excludes 0.5: " + gte);

        // Bare "0.5" is treated as >= → 0.9 and the 0.5 boundary.
        var bare = getContent(GET("/api/memories?agent=" + agent + "&importance=0.5"));
        assertTrue(bare.contains("imp-high") && bare.contains("imp-mid"),
                "bare number is inclusive >=: " + bare);
        assertFalse(bare.contains("imp-low"), "bare >= excludes 0.2: " + bare);

        // A non-numeric threshold is ignored (NumberFormatException swallowed) →
        // the filter contributes nothing, so all three rows return.
        var bad = getContent(GET("/api/memories?agent=" + agent + "&importance=notanumber"));
        assertTrue(bad.contains("imp-high") && bad.contains("imp-mid") && bad.contains("imp-low"),
                "an unparseable importance filter is ignored, not applied: " + bad);
    }

    // ─── sort columns (orderByClause) ─────────────────────────────────────────

    @Test
    void sortsByTextCategoryAndCreatedServerSide() {
        var agent = "sortcols707";
        // Distinguishable text + category values so the ordering is unambiguous.
        seedMemory(agent, "zzz-text", "lesson", 0.5);
        seedMemory(agent, "aaa-text", "core", 0.5);
        seedMemory(agent, "mmm-text", "fact", 0.5);
        login();

        var byText = getContent(GET("/api/memories?agent=" + agent + "&sort=text&dir=asc"));
        assertTrue(byText.indexOf("aaa-text") < byText.indexOf("mmm-text")
                        && byText.indexOf("mmm-text") < byText.indexOf("zzz-text"),
                "ascending by text is aaa<mmm<zzz: " + byText);

        // category asc: core < fact < lesson (their canonical labels).
        var byCat = getContent(GET("/api/memories?agent=" + agent + "&sort=category&dir=asc"));
        assertTrue(byCat.indexOf("aaa-text") < byCat.indexOf("mmm-text")
                        && byCat.indexOf("mmm-text") < byCat.indexOf("zzz-text"),
                "ascending by category orders core(aaa)<fact(mmm)<lesson(zzz): " + byCat);

        // created asc: insertion order, with the id tiebreak keeping it stable.
        var byCreated = getContent(GET("/api/memories?agent=" + agent + "&sort=created&dir=asc"));
        assertTrue(byCreated.indexOf("zzz-text") < byCreated.indexOf("aaa-text")
                        && byCreated.indexOf("aaa-text") < byCreated.indexOf("mmm-text"),
                "ascending by created follows seed order zzz<aaa<mmm: " + byCreated);
    }

    // ─── update: category normalize + below-range + malformed body ────────────

    @Test
    void updateNormalizesCategory() {
        var memId = seedMemory("catupd707", "recategorize me", "fact", 0.4);
        login();

        var resp = PUT("/api/memories/" + memId, "application/json",
                "{\"importance\":0.6,\"category\":\"CORE\"}");
        assertIsOk(resp);
        // MemoryCategory.normalize lowercases → the DTO surfaces "core".
        assertTrue(getContent(resp).contains("\"category\":\"core\""),
                "category must normalize to lowercase: " + getContent(resp));

        var stored = fetchInFreshTx(() -> {
            models.Memory m = models.Memory.findById(Long.parseLong(memId));
            return m.category + "|" + m.importance;
        });
        assertEquals("core|0.6", stored, "both category and importance must persist");
    }

    @Test
    void rejectsBelowRangeImportance() {
        var memId = seedMemory("belowrange707", "x", "fact", 0.4);
        login();
        // The lower operand of the range check (imp < 0.0) — the primary suite
        // only exercises the upper operand (1.5).
        var resp = PUT("/api/memories/" + memId, "application/json", "{\"importance\":-0.5}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void updateRejectsMalformedBodyWith400() {
        var memId = seedMemory("malformedupd707", "x", "fact", 0.4);
        login();
        // A JSON array parses but isn't an object → readJsonBody() returns null →
        // the update()'s `body == null` badRequest branch (found only after the
        // 404 findById gate, so a real memory must exist).
        var resp = PUT("/api/memories/" + memId, "application/json", "[]");
        assertEquals(400, resp.status.intValue());
    }

    // ─── bulkDelete: malformed body ───────────────────────────────────────────

    @Test
    void bulkDeleteRejectsMalformedBodyWith400() {
        login();
        var resp = deleteWithJsonBody("/api/memories", "[]");
        assertEquals(400, resp.status.intValue());
    }

    /** DELETE-with-body helper — Play's DELETE helper drops the body, so mirror
     *  the makeRequest workaround the primary memory suite uses. */
    private static play.mvc.Http.Response deleteWithJsonBody(String url, String json) {
        var req = newRequest();
        req.method = "DELETE";
        req.contentType = "application/json";
        req.url = url;
        req.path = url;
        req.querystring = "";
        req.body = new java.io.ByteArrayInputStream(
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        try {
            var f = play.test.FunctionalTest.class.getDeclaredField("savedCookies");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cookies = (java.util.Map<String, play.mvc.Http.Cookie>) f.get(null);
            if (cookies != null) req.cookies = cookies;
        } catch (Exception _) {
            // fall through — an unauthenticated DELETE surfaces as 401
        }
        return makeRequest(req);
    }
}
