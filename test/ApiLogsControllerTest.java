import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import models.EventLog;

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
        var t = Thread.ofPlatform().start(() -> {
            try { services.Tx.run(block); } catch (Throwable ex) { err.set(ex); }
        });
        try { t.join(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new RuntimeException(e); }
        if (err.get() != null) throw new RuntimeException(err.get());
    }

    /** Insert an EventLog row directly via JPA (must run inside a committed Tx,
     *  e.g. {@link #commitInFreshTx}). Deliberately bypasses EventLogger, whose
     *  process-global `pending` queue a concurrently-running test's clear()/flush()
     *  can drain mid-test — emptying our seeded rows and flaking this suite on CI.
     *  @PrePersist fills timestamp + createdAt. */
    private static void seedEvent(String category, String message) {
        var e = new EventLog();
        e.level = "INFO";
        e.category = category;
        e.message = message;
        e.save();
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
            seedEvent("test-cat-A", "first entry");
            seedEvent("test-cat-B", "second entry");
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
            seedEvent("search-cat", "Unique-Sentence-Marker-Alpha");
            seedEvent("search-cat", "different message");
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

    @Test
    void listRejectsUnparseableSinceWith400() {
        login();
        // JCLAW-808: an unparseable ISO-8601 bound is a 400, not a 500 from a
        // bubbled DateTimeParseException.
        var resp = GET("/api/logs?since=not-a-date");
        assertEquals(400, resp.status.intValue());
        assertTrue(getContent(resp).contains("since"),
                "error should name the offending param: " + getContent(resp));
    }
}
