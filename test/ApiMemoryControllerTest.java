import org.junit.jupiter.api.*;
import play.test.*;
import memory.MemoryStoreFactory;
import services.AgentService;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

class ApiMemoryControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        // Seeding memories triggers Memory @PostPersist Lucene indexing; close the
        // index and serialize against the other Lucene tests.
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

    // Seed in a fresh committed Tx on a separate thread so the HTTP handler sees
    // it (the FunctionalTest carrier thread already holds an uncommitted Tx).
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

    private Long createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1").id);
    }

    private String seedMemory(String agentName, String text, String category, double importance) {
        return fetchInFreshTx(() ->
                MemoryStoreFactory.get().store(agentName, text, category, importance, "auto-capture"));
    }

    // ─── Auth gate ───────────────────────────────────────────────────────────

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/agents/1/memories").status.intValue());
    }

    @Test
    void updateRequiresAuth() {
        var resp = PUT("/api/agents/1/memories/1", "application/json", "{\"importance\":0.9}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/agents/1/memories/1").status.intValue());
    }

    // ─── List ────────────────────────────────────────────────────────────────

    @Test
    void listsAgentMemories() {
        var agentId = createAgent("mem-list");
        seedMemory("mem-list", "The user prefers dark mode", "preference", 0.7);
        seedMemory("mem-list", "Operator is the sole admin", "core", 0.9);
        login();

        var resp = GET("/api/agents/" + agentId + "/memories");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("dark mode"), "memory text present");
        assertTrue(body.contains("core"), "category present");
        assertTrue(body.contains("auto-capture"), "source present");
    }

    @Test
    void listUnknownAgentIs404() {
        login();
        assertEquals(404, GET("/api/agents/999999/memories").status.intValue());
    }

    // ─── Update ──────────────────────────────────────────────────────────────

    @Test
    void updatesImportance() {
        var agentId = createAgent("mem-upd");
        var memId = seedMemory("mem-upd", "Tweak me", "fact", 0.4);
        login();

        var resp = PUT("/api/agents/" + agentId + "/memories/" + memId,
                "application/json", "{\"importance\":0.95}");
        assertIsOk(resp);

        var stored = fetchInFreshTx(() ->
                models.Memory.<models.Memory>findById(Long.parseLong(memId)).importance);
        assertEquals(0.95, stored, 1e-9);
    }

    @Test
    void rejectsOutOfRangeImportance() {
        var agentId = createAgent("mem-bad");
        var memId = seedMemory("mem-bad", "x", "fact", 0.4);
        login();
        var resp = PUT("/api/agents/" + agentId + "/memories/" + memId,
                "application/json", "{\"importance\":1.5}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void updateForeignMemoryIs404() {
        var agentA = createAgent("mem-a");
        createAgent("mem-b");
        var memOfB = seedMemory("mem-b", "B's private memory", "fact", 0.5);
        login();
        // agentA's id paired with agentB's memory must 404 (scoped to the agent).
        var resp = PUT("/api/agents/" + agentA + "/memories/" + memOfB,
                "application/json", "{\"importance\":0.9}");
        assertEquals(404, resp.status.intValue());
    }

    // ─── Delete ──────────────────────────────────────────────────────────────

    @Test
    void deletesMemory() {
        var agentId = createAgent("mem-del");
        var memId = seedMemory("mem-del", "Delete me", "fact", 0.5);
        login();

        var resp = DELETE("/api/agents/" + agentId + "/memories/" + memId);
        assertIsOk(resp);

        var remaining = fetchInFreshTx(() -> models.Memory.findById(Long.parseLong(memId)));
        assertNull(remaining);
    }
}
