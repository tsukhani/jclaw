import org.junit.jupiter.api.*;
import play.test.*;
import models.EventLog;
import services.EventLogger;

class EventLoggerTest extends UnitTest {

    @BeforeEach
    void setup() {
        EventLogger.clear();
        Fixtures.deleteDatabase();
    }

    @Test
    void recordCreatesEventLog() {
        EventLogger.record("INFO", "system", "Test message", "{\"key\":\"value\"}");
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        assertEquals("INFO", events.getFirst().level);
        assertEquals("system", events.getFirst().category);
        assertEquals("Test message", events.getFirst().message);
        assertEquals("{\"key\":\"value\"}", events.getFirst().details);
    }

    @Test
    void recordWithAgentAndChannel() {
        EventLogger.record("WARN", "channel", "agent-1", "telegram", "Delivery failed", null);
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        assertEquals("agent-1", events.getFirst().agentId);
        assertEquals("telegram", events.getFirst().channel);
    }

    @Test
    void infoConvenienceMethod() {
        EventLogger.info("llm", "Request sent");
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        assertEquals("INFO", events.getFirst().level);
        assertEquals("llm", events.getFirst().category);
    }

    @Test
    void warnConvenienceMethod() {
        EventLogger.warn("channel", "Retry triggered", "attempt 2");
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        assertEquals("WARN", events.getFirst().level);
        assertEquals("Retry triggered", events.getFirst().message);
    }

    @Test
    void errorConvenienceMethod() {
        EventLogger.error("tool", "Tool execution failed");
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        assertEquals("ERROR", events.getFirst().level);
    }

    @Test
    void errorWithThrowable() {
        EventLogger.error("system", "Unhandled exception", new RuntimeException("test error"));
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        assertTrue(events.getFirst().details.contains("test error"));
    }

    // ----- JCLAW-272: subagent lifecycle taxonomy ----------------------------

    @Test
    void recordSubagentSpawnPersistsCategoryAndMetadata() {
        EventLogger.recordSubagentSpawn("parent-1", "child-9", "run-abc", "session", "fresh");
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        var e = events.getFirst();
        assertEquals("INFO", e.level);
        assertEquals(EventLogger.SUBAGENT_SPAWN, e.category);
        assertEquals("parent-1", e.agentId);
        assertTrue(e.details.contains("\"parent_agent_id\":\"parent-1\""));
        assertTrue(e.details.contains("\"child_agent_id\":\"child-9\""));
        assertTrue(e.details.contains("\"run_id\":\"run-abc\""));
        assertTrue(e.details.contains("\"mode\":\"session\""));
        assertTrue(e.details.contains("\"context\":\"fresh\""));
    }

    @Test
    void recordSubagentLimitExceededOmitsChild() {
        EventLogger.recordSubagentLimitExceeded("parent-2", "max depth 3 reached");
        EventLogger.flush();

        var events = EventLog.findRecent(10);
        assertEquals(1, events.size());
        var e = events.getFirst();
        assertEquals("WARN", e.level);
        assertEquals(EventLogger.SUBAGENT_LIMIT_EXCEEDED, e.category);
        assertEquals("parent-2", e.agentId);
        assertTrue(e.details.contains("\"parent_agent_id\":\"parent-2\""));
        assertTrue(e.details.contains("\"child_agent_id\":null"));
        assertTrue(e.details.contains("\"reason\":\"max depth 3 reached\""));
    }

    @Test
    void errorWithThrowableStringifiesIntoDetails() {
        // The (category, message, Throwable) overload — previously 0%-covered —
        // routes Throwable.toString() into the details field.
        var t = new RuntimeException("synthetic boom");
        EventLogger.error("test-cat-throwable", "ouch", t);
        EventLogger.flush();
        var events = EventLog.findRecent(40);
        var found = events.stream().anyMatch(e ->
                "test-cat-throwable".equals(e.category)
                && "ouch".equals(e.message)
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
}
