import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import memory.MemoryStoreFactory;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class ApiMemoryControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        // Seeding memories triggers Memory @PostPersist Lucene indexing; close the
        // index (and hold the lock) so the q LIKE-fallback path is deterministic
        // and we don't clash with the search-mode tests.
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

    // Run a block in a fresh committed Tx on a separate thread so the HTTP handler
    // (its own Tx) sees the committed rows.
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

    // Memory is partitioned on the immutable agent id (JCLAW-531), and the
    // controller resolves that id back to the agent's name for display + filtering,
    // so a real Agent must exist. Create it once per name (reused across seeds).
    private String seedMemory(String agentName, String text, String category, double importance) {
        return fetchInFreshTx(() -> {
            models.Agent agent = models.Agent.find("name = ?1", agentName).first();
            if (agent == null) {
                agent = services.AgentService.create(agentName, "openrouter", "gpt-4.1");
            }
            return MemoryStoreFactory.get().store(String.valueOf(agent.id), text, category, importance);
        });
    }

    // ─── Auth gate ───────────────────────────────────────────────────────────

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/memories").status.intValue());
    }

    @Test
    void updateRequiresAuth() {
        var resp = PUT("/api/memories/1", "application/json", "{\"importance\":0.9}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/memories/1").status.intValue());
    }

    // ─── List + filters ──────────────────────────────────────────────────────

    @Test
    void listsMemoriesAcrossAgentsWithAgentName() {
        seedMemory("alice", "The user prefers dark mode", "preference", 0.7);
        seedMemory("bob", "Operator is the sole admin", "core", 0.9);
        login();

        var body = getContent(GET("/api/memories"));
        assertTrue(body.contains("dark mode"), "alice's memory text present");
        assertTrue(body.contains("Operator is the sole admin"), "bob's memory text present");
        assertTrue(body.contains("alice"), "agent name alice present");
        assertTrue(body.contains("bob"), "agent name bob present");
    }

    @Test
    void filtersByAgent() {
        seedMemory("alice", "alice only fact", "fact", 0.5);
        seedMemory("bob", "bob only fact", "fact", 0.5);
        login();

        var body = getContent(GET("/api/memories?agent=alice"));
        assertTrue(body.contains("alice only fact"), "alice's memory present");
        assertFalse(body.contains("bob only fact"), "bob's memory excluded");
    }

    @Test
    void filtersByCategory() {
        seedMemory("alice", "a core memory", "core", 0.9);
        seedMemory("alice", "a plain fact memory", "fact", 0.5);
        login();

        var body = getContent(GET("/api/memories?category=core"));
        assertTrue(body.contains("a core memory"), "core memory present");
        assertFalse(body.contains("a plain fact memory"), "fact excluded");
    }

    // NB: free-text `q` search is intentionally not asserted here. It routes
    // through MessageSearch.searchIds(MEMORY, ...) (Lucene), whose behavior under
    // the concurrent test runner depends on JVM-global search-backend state that
    // leaks from the dedicated *SearchTest classes — making a deterministic q
    // assertion in this (closed-index) controller test infeasible. The Lucene
    // scope itself is covered by the search-infra tests; q is verified live in
    // the Chrome UAT. The agent / category / importance filters below use plain
    // JPQL and are fully deterministic.

    @Test
    void filtersByImportanceThreshold() {
        seedMemory("alice", "high importance memory", "core", 0.9);
        seedMemory("alice", "boundary importance memory", "fact", 0.8);
        seedMemory("alice", "low importance memory", "fact", 0.4);
        login();

        // ">0.8" is strict: the 0.9 row stays; the 0.8 boundary and 0.4 row drop.
        var q = URLEncoder.encode(">0.8", StandardCharsets.UTF_8);
        var body = getContent(GET("/api/memories?importance=" + q));
        assertTrue(body.contains("high importance memory"), "above-threshold present");
        assertFalse(body.contains("boundary importance memory"), "strict > excludes the boundary value");
        assertFalse(body.contains("low importance memory"), "below-threshold excluded");
    }

    @Test
    void statusFilterSplitsActiveAndSupersededViews() {
        // JCLAW-557: the default view matches recall (active only); the
        // JCLAW-525 supersession trail is opt-in via status=superseded / all.
        var oldId = seedMemory("alice", "The user lives in Berlin", "fact", 0.7);
        var newId = seedMemory("alice", "The user lives in Porto", "fact", 0.7);
        fetchInFreshTx(() -> {
            models.Memory.<models.Memory>findById(Long.valueOf(oldId)).supersede(Long.valueOf(newId));
            return null;
        });
        login();

        var active = getContent(GET("/api/memories"));
        assertTrue(active.contains("Porto"), "active memory in the default view");
        assertFalse(active.contains("Berlin"), "superseded row hidden by default (matches recall)");

        var superseded = getContent(GET("/api/memories?status=superseded"));
        assertTrue(superseded.contains("Berlin"), "superseded view surfaces the trail");
        assertFalse(superseded.contains("Porto"), "active row excluded from the superseded view");
        assertTrue(superseded.contains("\"supersededAt\""), "DTO carries the supersession timestamp");
        assertTrue(superseded.contains("\"supersededById\":\"" + newId + "\""),
                "DTO carries the superseding memory id");

        var all = getContent(GET("/api/memories?status=all"));
        assertTrue(all.contains("Berlin") && all.contains("Porto"), "status=all shows both");
    }

    // ─── Pagination (X-Total-Count) ──────────────────────────────────────────

    @Test
    void listSetsTotalCountHeaderReflectingFullMatchNotThePage() {
        seedMemory("alice", "mem one", "fact", 0.5);
        seedMemory("alice", "mem two", "fact", 0.5);
        seedMemory("bob", "mem three", "core", 0.9);
        login();

        // limit=2 caps the body to a page, but the header must report all 3.
        var resp = GET("/api/memories?limit=2");
        assertIsOk(resp);
        assertEquals("3", resp.getHeader("X-Total-Count"),
                "X-Total-Count reports the full match count, not the page size");
        assertEquals(2, countOccurrences(getContent(resp), "\"agentName\""),
                "body is capped to the requested page size");
    }

    @Test
    void listTotalCountHonorsTheFilter() {
        seedMemory("alice", "alice one", "fact", 0.5);
        seedMemory("alice", "alice two", "fact", 0.5);
        seedMemory("bob", "bob one", "core", 0.9);
        login();

        var resp = GET("/api/memories?agent=alice");
        assertIsOk(resp);
        assertEquals("2", resp.getHeader("X-Total-Count"),
                "count reflects the agent filter, not the whole table");
    }

    @Test
    void listTotalCountIsZeroForUnknownAgent() {
        seedMemory("alice", "alice one", "fact", 0.5);
        login();

        // Unknown agent → the resolve short-circuits to empty; count must be 0
        // (not a bare COUNT that would return the whole table).
        var resp = GET("/api/memories?agent=ghost");
        assertIsOk(resp);
        assertEquals("0", resp.getHeader("X-Total-Count"), "no rows for an unknown agent");
        assertFalse(getContent(resp).contains("alice one"), "no rows leaked");
    }

    // ─── Server-side sort ────────────────────────────────────────────────────

    @Test
    void sortsByImportanceServerSide() {
        seedMemory("alice", "lowimp", "fact", 0.2);
        seedMemory("alice", "highimp", "fact", 0.9);
        seedMemory("alice", "midimp", "fact", 0.5);
        login();

        var asc = getContent(GET("/api/memories?sort=importance&dir=asc"));
        assertTrue(asc.indexOf("lowimp") < asc.indexOf("midimp") && asc.indexOf("midimp") < asc.indexOf("highimp"),
                "ascending by importance is low<mid<high, got: " + asc);

        var desc = getContent(GET("/api/memories?sort=importance&dir=desc"));
        assertTrue(desc.indexOf("highimp") < desc.indexOf("midimp") && desc.indexOf("midimp") < desc.indexOf("lowimp"),
                "descending by importance is high<mid<low, got: " + desc);
    }

    @Test
    void sortsByAgentNameServerSide() {
        seedMemory("zeta", "z-mem", "fact", 0.5);
        seedMemory("alpha", "a-mem", "fact", 0.5);
        login();

        var asc = getContent(GET("/api/memories?sort=agent&dir=asc"));
        assertTrue(asc.indexOf("alpha") < asc.indexOf("zeta"),
                "ascending by agent name puts alpha before zeta, got: " + asc);
    }

    @Test
    void unknownSortColumnFallsBackToDefaultOrderNot400() {
        seedMemory("alice", "only-one", "fact", 0.5);
        login();
        // A bogus sort column must not error — it falls back to the recency default.
        var resp = GET("/api/memories?sort=bogus&dir=sideways");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("only-one"), "row still returned under fallback order");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int idx = 0;
        while ((idx = haystack.indexOf(needle, idx)) >= 0) {
            count++;
            idx += needle.length();
        }
        return count;
    }

    // ─── Update / Delete (by memory id) ──────────────────────────────────────

    @Test
    void updatesImportance() {
        var memId = seedMemory("alice", "Tweak me", "fact", 0.4);
        login();

        var resp = PUT("/api/memories/" + memId, "application/json", "{\"importance\":0.95}");
        assertIsOk(resp);

        var stored = fetchInFreshTx(() ->
                models.Memory.<models.Memory>findById(Long.parseLong(memId)).importance);
        assertEquals(0.95, stored, 1e-9);
    }

    @Test
    void rejectsOutOfRangeImportance() {
        var memId = seedMemory("alice", "x", "fact", 0.4);
        login();
        var resp = PUT("/api/memories/" + memId, "application/json", "{\"importance\":1.5}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void unknownMemoryUpdateIs404() {
        login();
        var resp = PUT("/api/memories/999999", "application/json", "{\"importance\":0.9}");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void deletesMemory() {
        var memId = seedMemory("alice", "Delete me", "fact", 0.5);
        login();

        var resp = DELETE("/api/memories/" + memId);
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""), "canonical ack: " + getContent(resp));
        var remaining = fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(memId)));
        assertNull(remaining);
    }

    // ─── Bulk delete (Memories-page Delete / Delete all) ─────────────────────

    @Test
    void bulkDeletesByIds() {
        var keep = seedMemory("alice", "keep me", "fact", 0.5);
        var goOne = seedMemory("alice", "bulk one", "fact", 0.5);
        var goTwo = seedMemory("bob", "bulk two", "core", 0.9);
        login();

        var resp = deleteWithJsonBody("/api/memories",
                "{\"ids\": [" + goOne + ", " + goTwo + "]}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":2"));
        assertNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(goOne))));
        assertNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(goTwo))));
        assertNotNull(fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(keep))));
    }

    @Test
    void bulkDeletesByFilterRespectingPredicates() {
        seedMemory("alice", "alice fact", "fact", 0.5);
        seedMemory("bob", "bob core", "core", 0.9);
        login();

        var resp = deleteWithJsonBody("/api/memories",
                "{\"filter\": {\"agent\": \"alice\"}}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"deleted\":1"));
        var left = fetchInFreshTx(() -> models.Memory.count());
        assertEquals(1L, left.longValue());
    }

    @Test
    void bulkDeleteEmptyBodyIs400() {
        login();
        var resp = deleteWithJsonBody("/api/memories", "{}");
        assertEquals(400, resp.status.intValue());
    }

    /** DELETE-with-body helper — same makeRequest workaround as
     *  ApiConversationsControllerTest (Play's DELETE helper drops the body). */
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
