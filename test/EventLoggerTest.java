import org.junit.jupiter.api.*;
import play.test.*;
import models.EventLog;
import services.EventLogger;

import java.util.List;

/**
 * EventLogger persists to the shared EventLog table via a process-global
 * buffer. play1 runs the unit + functional lanes concurrently, so any other
 * test that logs an event lands rows here too — a bare
 * {@code findRecent(10).size() == 1} assertion flakes ("expected 1 but was
 * 10", JCLAW-428). Each test therefore writes a UNIQUE category and asserts on
 * only its own rows via {@link #mine(String)}, so concurrent cross-talk (which
 * uses other categories) can't perturb the count.
 */
class EventLoggerTest extends UnitTest {

    @BeforeEach
    void setup() {
        EventLogger.clear();
        Fixtures.deleteDatabase();
    }

    /** Events with {@code category}, newest first — isolates this test's own
     *  rows from concurrent cross-talk in the shared EventLog table. */
    private static List<EventLog> mine(String category) {
        return EventLog.findRecent(200).stream()
                .filter(e -> category.equals(e.category))
                .toList();
    }

    @Test
    void recordCreatesEventLog() {
        EventLogger.record("INFO", "eltest.record", "Test message", "{\"key\":\"value\"}");
        EventLogger.flush();

        var events = mine("eltest.record");
        assertEquals(1, events.size());
        assertEquals("INFO", events.getFirst().level);
        assertEquals("eltest.record", events.getFirst().category);
        assertEquals("Test message", events.getFirst().message);
        assertEquals("{\"key\":\"value\"}", events.getFirst().details);
    }

    @Test
    void recordWithAgentAndChannel() {
        EventLogger.record("WARN", "eltest.agentchannel", "agent-1", "telegram", "Delivery failed", null);
        EventLogger.flush();

        var events = mine("eltest.agentchannel");
        assertEquals(1, events.size());
        assertEquals("agent-1", events.getFirst().agentId);
        assertEquals("telegram", events.getFirst().channel);
    }

    @Test
    void infoConvenienceMethod() {
        EventLogger.info("eltest.info", "Request sent");
        EventLogger.flush();

        var events = mine("eltest.info");
        assertEquals(1, events.size());
        assertEquals("INFO", events.getFirst().level);
        assertEquals("eltest.info", events.getFirst().category);
    }

    @Test
    void warnConvenienceMethod() {
        EventLogger.warn("eltest.warn", "Retry triggered", "attempt 2");
        EventLogger.flush();

        var events = mine("eltest.warn");
        assertEquals(1, events.size());
        assertEquals("WARN", events.getFirst().level);
        assertEquals("Retry triggered", events.getFirst().message);
    }

    @Test
    void errorConvenienceMethod() {
        EventLogger.error("eltest.error", "Tool execution failed");
        EventLogger.flush();

        var events = mine("eltest.error");
        assertEquals(1, events.size());
        assertEquals("ERROR", events.getFirst().level);
    }

    @Test
    void errorWithThrowable() {
        EventLogger.error("eltest.errthrow", "Unhandled exception", new RuntimeException("test error"));
        EventLogger.flush();

        var events = mine("eltest.errthrow");
        assertEquals(1, events.size());
        assertTrue(events.getFirst().details.contains("test error"));
    }

    // ----- JCLAW-272: subagent lifecycle taxonomy ----------------------------

    @Test
    void recordSubagentSpawnPersistsCategoryAndMetadata() {
        EventLogger.recordSubagentSpawn("eltest-spawn-parent", "child-9", "run-abc", "session", "fresh");
        EventLogger.flush();

        // SUBAGENT_SPAWN is a shared constant category; scope to this test's
        // unique parent id so a concurrent subagent test can't add a row here.
        var events = mine(EventLogger.SUBAGENT_SPAWN).stream()
                .filter(e -> "eltest-spawn-parent".equals(e.agentId))
                .toList();
        assertEquals(1, events.size());
        var e = events.getFirst();
        assertEquals("INFO", e.level);
        assertEquals(EventLogger.SUBAGENT_SPAWN, e.category);
        assertEquals("eltest-spawn-parent", e.agentId);
        assertTrue(e.details.contains("\"parent_agent_id\":\"eltest-spawn-parent\""));
        assertTrue(e.details.contains("\"child_agent_id\":\"child-9\""));
        assertTrue(e.details.contains("\"run_id\":\"run-abc\""));
        assertTrue(e.details.contains("\"mode\":\"session\""));
        assertTrue(e.details.contains("\"context\":\"fresh\""));
    }

    @Test
    void recordSubagentLimitExceededOmitsChild() {
        EventLogger.recordSubagentLimitExceeded("eltest-limit-parent", "max depth 3 reached");
        EventLogger.flush();

        var events = mine(EventLogger.SUBAGENT_LIMIT_EXCEEDED).stream()
                .filter(e -> "eltest-limit-parent".equals(e.agentId))
                .toList();
        assertEquals(1, events.size());
        var e = events.getFirst();
        assertEquals("WARN", e.level);
        assertEquals(EventLogger.SUBAGENT_LIMIT_EXCEEDED, e.category);
        assertEquals("eltest-limit-parent", e.agentId);
        assertTrue(e.details.contains("\"parent_agent_id\":\"eltest-limit-parent\""));
        assertTrue(e.details.contains("\"child_agent_id\":null"));
        assertTrue(e.details.contains("\"reason\":\"max depth 3 reached\""));
    }

    @Test
    void errorWithThrowableStringifiesIntoDetails() {
        // The (category, message, Throwable) overload — previously 0%-covered —
        // routes Throwable.toString() into the details field.
        var t = new RuntimeException("synthetic boom");
        EventLogger.error("eltest.cat.throwable", "ouch", t);
        EventLogger.flush();
        var found = mine("eltest.cat.throwable").stream().anyMatch(e ->
                "ouch".equals(e.message)
                && e.details != null
                && e.details.contains("RuntimeException")
                && e.details.contains("synthetic boom"));
        assertTrue(found, "Throwable.toString() must land in details");
    }

    // JCLAW-334 note: the shutdown-only "file-only / skip flush" behavior of
    // markShuttingDown() is deliberately NOT unit-tested here. TestEngine runs
    // unit tests (launcher thread) and functional tests (functionalTestsExecutor,
    // a separate single-thread VT) concurrently, so flipping EventLogger's
    // process-global shuttingDown flag in a test races any functional test that
    // persists via EventLogger (e.g. ApiLogsControllerTest), making it no-op and
    // flaky. The guard is verified by the live restart -> stop check instead.

    // JCLAW-402: the shutdown path must flush the pending tail before going
    // file-only, instead of discarding it. flushPendingForShutdown() (the path
    // markShuttingDown() runs before flipping the flag) is exercised directly so
    // we never touch the process-global shuttingDown flag — keeping the test
    // race-free per the JCLAW-334 note above.
    @Test
    void flushPendingForShutdownPersistsTail() {
        EventLogger.record("INFO", "eltest.shutdown-tail", "queued but not yet batched", null);

        // Tail is still in memory — below BATCH_SIZE, no boundary flush yet.
        assertEquals(0, mine("eltest.shutdown-tail").size());

        EventLogger.flushPendingForShutdown();

        var events = mine("eltest.shutdown-tail");
        assertEquals(1, events.size());
        assertEquals("eltest.shutdown-tail", events.getFirst().category);
        assertEquals("queued but not yet batched", events.getFirst().message);
    }
}
