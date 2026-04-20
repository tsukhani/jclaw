import org.junit.jupiter.api.*;
import agents.ToolRegistry;
import models.EventLog;
import play.test.*;
import services.ConfigService;
import services.EventLogger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Phase 5 of the backend test audit: cover Play Job {@code doJob()} invocations
 * that had no direct coverage. Each test drives a real job through its lifecycle
 * and asserts the observable effect, rather than mocking anything.
 */
public class JobLifecycleTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        EventLogger.clear();
    }

    // === EventLogCleanupJob ===

    @Test
    public void eventLogCleanupDeletesRowsOlderThanCutoff() {
        // Seed two rows: one well past retention, one recent. Run the job
        // and assert only the old one is deleted.
        services.Tx.run(() -> {
            var oldLog = new EventLog();
            oldLog.level = "INFO";
            oldLog.category = "cleanup-test";
            oldLog.message = "old";
            oldLog.timestamp = Instant.now().minus(60, ChronoUnit.DAYS);
            oldLog.save();

            var recent = new EventLog();
            recent.level = "INFO";
            recent.category = "cleanup-test";
            recent.message = "recent";
            recent.timestamp = Instant.now();
            recent.save();
        });

        new jobs.EventLogCleanupJob().doJob();

        // After cleanup (default retention 30 days), only the recent row remains.
        List<EventLog> remaining = services.Tx.run(() -> EventLog.<EventLog>find(
                "category = ?1", "cleanup-test").fetch());
        assertEquals(1, remaining.size(),
                "exactly one cleanup-test row must remain (the recent one)");
        assertEquals("recent", remaining.getFirst().message,
                "the recent row must be the survivor, not the old one");
    }

    @Test
    public void eventLogCleanupIsNoOpWhenAllRowsWithinRetention() {
        services.Tx.run(() -> {
            var log = new EventLog();
            log.level = "INFO";
            log.category = "fresh-only";
            log.message = "fresh";
            log.timestamp = Instant.now();
            log.save();
        });

        new jobs.EventLogCleanupJob().doJob();

        List<EventLog> remaining = services.Tx.run(() -> EventLog.<EventLog>find(
                "category = ?1", "fresh-only").fetch());
        assertEquals(1, remaining.size(),
                "a fresh row must survive a cleanup pass");
    }

    // === ToolRegistrationJob ===

    @Test
    public void toolRegistrationPublishesBaseTools() {
        // The always-on tools must land in the registry after registerAll.
        // This is the smoke test for the job's primary contract.
        ConfigService.set("playwright.enabled", "false");
        ConfigService.set("shell.enabled", "false");
        new jobs.ToolRegistrationJob().doJob();

        var names = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertTrue(names.contains("filesystem"), "filesystem must always be registered");
        assertTrue(names.contains("datetime"), "datetime must always be registered");
        assertTrue(names.contains("task_manager"), "task_manager tool must always be registered");
        assertTrue(names.contains("web_fetch"), "web_fetch must always be registered");
    }

    @Test
    public void toolRegistrationIncludesShellWhenEnabled() {
        // Flipping shell.enabled from false → true + re-registering must
        // add the exec tool. This is the path exercised by
        // ConfigService.setWithSideEffects for operator toggles.
        ConfigService.set("shell.enabled", "false");
        jobs.ToolRegistrationJob.registerAll();
        var before = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertFalse(before.contains("exec"),
                "exec must not be registered when shell.enabled=false");

        ConfigService.set("shell.enabled", "true");
        jobs.ToolRegistrationJob.registerAll();
        var after = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertTrue(after.contains("exec"),
                "exec must be registered when shell.enabled=true");
    }

    @Test
    public void toolRegistrationIncludesPlaywrightWhenEnabled() {
        ConfigService.set("playwright.enabled", "true");
        jobs.ToolRegistrationJob.registerAll();
        var names = ToolRegistry.listTools().stream()
                .map(ToolRegistry.Tool::name)
                .toList();
        assertTrue(names.contains("browser"),
                "browser tool must be registered when playwright.enabled=true");
    }

    // === BrowserCleanupJob ===

    @Test
    public void browserCleanupJobRunsWithoutError() {
        // With no open sessions, the cleanup pass must be a no-op that
        // doesn't throw — it runs every 60s and any exception would flood
        // the logs. The substantive cleanup logic is covered by
        // PlaywrightToolTest.idleSessionCleanupDoesNotThrow; this test
        // locks in that the job wiring itself is sound.
        new jobs.BrowserCleanupJob().doJob();
    }

    // === ShutdownJob ===

    @Test
    public void shutdownJobRunsWithoutError() {
        // The job chains three shutdown-style calls (task poller, browser
        // sessions, telegram poller). In a unit-test context with none of
        // those running, the job must still complete cleanly.
        new jobs.ShutdownJob().doJob();
    }
}
