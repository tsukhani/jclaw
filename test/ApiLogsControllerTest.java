import org.junit.jupiter.api.*;
import play.test.*;
import services.EventLogger;

class ApiLogsControllerTest extends FunctionalTest {

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
        assertEquals(401, GET("/api/logs").status.intValue());
    }

    @Test
    void listReturnsEnvelopeWithLimitAndOffset() {
        login();
        var resp = GET("/api/logs");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"events\""));
        assertTrue(body.contains("\"limit\":100"), "default limit is 100: " + body);
        assertTrue(body.contains("\"offset\":0"), "default offset is 0: " + body);
    }

    @Test
    void listAppliesCategoryFilter() {
        login();
        commitInFreshTx(() -> {
            EventLogger.info("test-cat-A", "first entry");
            EventLogger.info("test-cat-B", "second entry");
            EventLogger.flush();
        });
        var resp = GET("/api/logs?category=test-cat-A");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("first entry"), "filtered result missing: " + body);
        assertFalse(body.contains("second entry"), "filter must exclude other category: " + body);
    }

    @Test
    void listAppliesSearchFilterCaseInsensitively() {
        login();
        commitInFreshTx(() -> {
            EventLogger.info("search-cat", "Unique-Sentence-Marker-Alpha");
            EventLogger.info("search-cat", "different message");
            EventLogger.flush();
        });
        var resp = GET("/api/logs?search=unique-sentence-marker");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("Unique-Sentence-Marker-Alpha"),
                "case-insensitive search must match: " + body);
    }

    @Test
    void listClampsExcessiveLimit() {
        login();
        // 9999 → controller clamps to 500.
        var resp = GET("/api/logs?limit=9999");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"limit\":500"),
                "limit must be clamped at 500: " + getContent(resp));
    }

    @Test
    void listIgnoresNegativeOffset() {
        login();
        var resp = GET("/api/logs?offset=-5");
        assertIsOk(resp);
        assertTrue(getContent(resp).contains("\"offset\":0"),
                "negative offset must default to 0: " + getContent(resp));
    }
}
