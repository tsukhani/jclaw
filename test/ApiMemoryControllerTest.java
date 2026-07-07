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

        assertIsOk(DELETE("/api/memories/" + memId));
        var remaining = fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(memId)));
        assertNull(remaining);
    }
}
