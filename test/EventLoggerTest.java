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
}
