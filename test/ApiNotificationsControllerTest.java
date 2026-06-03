import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import models.Notification;
import services.AgentService;

import java.time.Instant;

/**
 * Covers the GET /api/notifications list endpoint, which drives the typed
 * finders Notification.findUnread / findAllNewestFirst (JCLAW-408): the
 * "unread" mode must elide acknowledged rows, "all" must return both, and the
 * limit must cap the result count.
 */
class ApiNotificationsControllerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(resp);
    }

    private static <T> T fetchInFreshTx(java.util.function.Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
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

    /** Seed one notification, optionally already acknowledged. */
    private Long seedNotification(Long agentId, String content, boolean acknowledged) {
        return fetchInFreshTx(() -> {
            var n = new Notification();
            n.agent = Agent.findById(agentId);
            n.content = content;
            if (acknowledged) n.acknowledgedAt = Instant.now();
            n.save();
            return n.id;
        });
    }

    @Test
    void listRequiresAuth() {
        assertEquals(401, GET("/api/notifications").status.intValue());
    }

    @Test
    void unreadModeElidesAcknowledgedRows() {
        login();
        var agentId = fetchInFreshTx(() -> AgentService.create("notif-unread", "openrouter", "gpt-4.1").id);
        seedNotification(agentId, "still-unread", false);
        seedNotification(agentId, "already-acked", true);

        var resp = GET("/api/notifications?status=unread");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("still-unread"), "unread row must appear: " + body);
        assertFalse(body.contains("already-acked"), "acknowledged row must be elided: " + body);
    }

    @Test
    void allModeReturnsAcknowledgedAndUnread() {
        login();
        var agentId = fetchInFreshTx(() -> AgentService.create("notif-all", "openrouter", "gpt-4.1").id);
        seedNotification(agentId, "row-unread", false);
        seedNotification(agentId, "row-acked", true);

        var resp = GET("/api/notifications?status=all");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("row-unread") && body.contains("row-acked"),
                "all mode must return both rows: " + body);
    }

    @Test
    void limitCapsResultCount() {
        login();
        var agentId = fetchInFreshTx(() -> AgentService.create("notif-limit", "openrouter", "gpt-4.1").id);
        for (int i = 0; i < 3; i++) seedNotification(agentId, "n-" + i, false);

        var resp = GET("/api/notifications?status=all&limit=2");
        assertIsOk(resp);
        var body = getContent(resp);
        // Each view object carries exactly one "id" field; count them to assert the cap.
        int idCount = body.split("\"id\":", -1).length - 1;
        assertEquals(2, idCount, "limit=2 must cap the result at two rows: " + body);
    }

    @Test
    void invalidStatusReturns400() {
        login();
        var resp = GET("/api/notifications?status=bogus");
        assertEquals(400, resp.status.intValue());
    }
}
