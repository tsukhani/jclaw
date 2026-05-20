import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;

class ApiConfigControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}");
        assertIsOk(resp);
    }

    private static void commitInFreshTx(Runnable block) {
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try { services.Tx.run(block); } catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/config").status.intValue());
    }

    @Test
    void getRequiresAuth() {
        assertEquals(401, GET("/api/config/example.key").status.intValue());
    }

    @Test
    void saveRequiresAuth() {
        var resp = POST("/api/config", "application/json",
                "{\"key\":\"x\",\"value\":\"y\"}");
        assertEquals(401, resp.status.intValue());
    }

    @Test
    void deleteRequiresAuth() {
        assertEquals(401, DELETE("/api/config/example.key").status.intValue());
    }

    @Test
    void listReturnsJsonEntries() {
        login();
        commitInFreshTx(() -> ConfigService.set("ui.theme", "dark"));
        var resp = GET("/api/config");
        assertIsOk(resp);
        assertContentType("application/json", resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"entries\""), "must carry an entries array: " + body);
        assertTrue(body.contains("\"ui.theme\""), "must include the seeded key: " + body);
    }

    @Test
    void getReturns404ForUnknownKey() {
        login();
        var resp = GET("/api/config/definitely-not-a-real-key");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void getReturnsEntryForExistingKey() {
        login();
        commitInFreshTx(() -> ConfigService.set("ui.density", "compact"));
        var resp = GET("/api/config/ui.density");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"key\":\"ui.density\""));
        assertTrue(body.contains("\"value\":\"compact\""));
    }

    @Test
    void saveReturns400OnMissingFields() {
        login();
        var resp = POST("/api/config", "application/json", "{}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void saveReturns400OnBlankKey() {
        login();
        var resp = POST("/api/config", "application/json",
                "{\"key\":\"\",\"value\":\"v\"}");
        assertEquals(400, resp.status.intValue());
    }

    @Test
    void saveSucceedsForRegularKey() {
        login();
        var resp = POST("/api/config", "application/json",
                "{\"key\":\"ui.locale\",\"value\":\"en\"}");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
    }

    @Test
    void saveRejectsReservedKeyPrefix() {
        // Reserved prefix (internal.*) — controller answers 409.
        login();
        var resp = POST("/api/config", "application/json",
                "{\"key\":\"auth.internal.something\",\"value\":\"v\"}");
        assertEquals(409, resp.status.intValue());
    }

    @Test
    void deleteRejectsReservedKeyPrefix() {
        login();
        assertEquals(409, DELETE("/api/config/auth.internal.foo").status.intValue());
    }

    @Test
    void deleteIsIdempotentForUnknownKey() {
        // deleteWithSideEffects is no-op for missing rows; controller still returns ok.
        login();
        var resp = DELETE("/api/config/never.set.this");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"status\":\"ok\""));
    }
}
