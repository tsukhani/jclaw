import models.Agent;
import models.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Functional HTTP tests for {@code GET /api/tasks?status=PAUSED}.
 *
 * <p>PAUSED is a display status: pause sets {@link Task#paused} and leaves the
 * row PENDING/ACTIVE, so there is no {@code Task.Status.PAUSED} for the filter
 * to resolve against. The Tasks page renders the pill and offers a {@code
 * status:} filter token in the bar directly above it, so the value the pill
 * shows has to be one the endpoint accepts — untranslated it reaches
 * {@code Status.valueOf} and 500s the list.
 */
class ApiTasksPausedFilterTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        assertIsOk(POST("/api/auth/login", "application/json", """
                {"username": "admin", "password": "changeme"}
                """));
    }

    @Test
    void statusPausedReturnsOnlySuspendedLiveSchedules() {
        seed();

        var resp = GET("/api/tasks?status=PAUSED");
        assertIsOk(resp);
        var body = getContent(resp);

        assertTrue(body.contains("paused-cron"), body);
        assertTrue(body.contains("paused-oneshot"), body);
        assertFalse(body.contains("live-cron"), body);
        // A cancelled task keeps its paused flag (only re-enable clears it) but
        // has no schedule left to suspend, so it is not a paused schedule.
        assertFalse(body.contains("cancelled-stale-flag"), body);
        assertEquals("2", resp.headers.get("X-Total-Count").value());
    }

    /** The unfiltered list is unaffected — the translation is scoped to the token. */
    @Test
    void unfilteredListStillReturnsEveryTask() {
        seed();

        var resp = GET("/api/tasks");
        assertIsOk(resp);
        assertEquals("4", resp.headers.get("X-Total-Count").value());
    }

    /** An enum status still resolves normally, paused rows included. */
    @Test
    void statusActiveStillMatchesAPausedRecurringTask() {
        seed();

        var body = getContent(GET("/api/tasks?status=ACTIVE"));
        assertTrue(body.contains("paused-cron"), body);
        assertTrue(body.contains("live-cron"), body);
    }

    private static void seed() {
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var agent = new Agent();
                    agent.name = "paused-filter-agent";
                    agent.modelProvider = "openrouter";
                    agent.modelId = "gpt-4.1";
                    agent.enabled = true;
                    agent.save();

                    mkTask(agent, "paused-cron", Task.Type.CRON, Task.Status.ACTIVE, true);
                    mkTask(agent, "paused-oneshot", Task.Type.SCHEDULED, Task.Status.PENDING, true);
                    mkTask(agent, "live-cron", Task.Type.CRON, Task.Status.ACTIVE, false);
                    mkTask(agent, "cancelled-stale-flag", Task.Type.CRON, Task.Status.CANCELLED, true);
                });
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
    }

    private static void mkTask(Agent agent, String name, Task.Type type,
                               Task.Status status, boolean paused) {
        var t = new Task();
        t.agent = agent;
        t.name = name;
        t.type = type;
        t.status = status;
        t.paused = paused;
        t.nextRunAt = Instant.now();
        t.save();
    }
}
