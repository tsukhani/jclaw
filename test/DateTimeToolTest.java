import org.junit.jupiter.api.*;
import play.test.*;
import models.Agent;
import services.AgentService;
import tools.DateTimeTool;

class DateTimeToolTest extends UnitTest {

    private DateTimeTool tool;
    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        tool = new DateTimeTool();
        agent = AgentService.create("datetime-test-agent", "openrouter", "gpt-4.1");
    }

    @AfterAll
    static void cleanup() {
        deleteDir(AgentService.workspacePath("datetime-test-agent"));
    }

    // ==================== Tool Metadata ====================

    @Test
    void nameIsDatetime() {
        assertEquals("datetime", tool.name());
    }

    @Test
    void descriptionIsNotBlank() {
        assertNotNull(tool.description());
        assertFalse(tool.description().isBlank());
    }

    @Test
    void parametersContainAction() {
        var params = tool.parameters();
        assertNotNull(params);
        assertTrue(params.containsKey("properties"));
    }

    // ==================== "now" Action ====================

    @Test
    void nowReturnsCurrentDateTimeInfo() {
        var result = tool.execute("""
                {"action": "now"}
                """, agent);
        // Should contain timezone, iso timestamp, and formatted time
        assertTrue(result.contains("timezone:"), "Result should contain timezone field");
        assertTrue(result.contains("iso:"), "Result should contain ISO timestamp");
    }

    @Test
    void nowWithExplicitTimezone() {
        var result = tool.execute("""
                {"action": "now", "timezone": "America/New_York"}
                """, agent);
        assertTrue(result.contains("America/New_York"));
    }

    @Test
    void nowWithInvalidTimezoneFallsBackToDefault() {
        var result = tool.execute("""
                {"action": "now", "timezone": "Invalid/Zone"}
                """, agent);
        // Should not error — falls through to system default
        assertTrue(result.contains("timezone:"));
        assertTrue(result.contains("iso:"));
    }

    @Test
    void nowWithBlankTimezoneFallsBackToDefault() {
        var result = tool.execute("""
                {"action": "now", "timezone": "  "}
                """, agent);
        assertTrue(result.contains("timezone:"));
    }

    // ==================== "convert" Action ====================

    @Test
    void convertBetweenTimezones() {
        var result = tool.execute("""
                {"action": "convert", "timestamp": "2026-04-13T09:00:00",
                 "fromTimezone": "America/New_York", "toTimezone": "Asia/Tokyo"}
                """, agent);
        assertTrue(result.contains("Converted:"));
        assertTrue(result.contains("America/New_York"));
        assertTrue(result.contains("Asia/Tokyo"));
    }

    @Test
    void convertMissingTimestampReturnsError() {
        var result = tool.execute("""
                {"action": "convert", "fromTimezone": "UTC", "toTimezone": "UTC"}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("timestamp"));
    }

    @Test
    void convertBadTimestampFormatReturnsError() {
        var result = tool.execute("""
                {"action": "convert", "timestamp": "not-a-date",
                 "fromTimezone": "UTC", "toTimezone": "UTC"}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("ISO-8601"));
    }

    // ==================== "calculate" Action ====================

    @Test
    void calculateAddDays() {
        var result = tool.execute("""
                {"action": "calculate", "timestamp": "2026-01-01T12:00:00",
                 "amount": 30, "unit": "days", "timezone": "UTC"}
                """, agent);
        assertTrue(result.contains("Result:"));
        assertTrue(result.contains("+30 days"));
    }

    @Test
    void calculateSubtractHours() {
        var result = tool.execute("""
                {"action": "calculate", "timestamp": "2026-04-13T18:00:00",
                 "amount": -6, "unit": "hours", "timezone": "UTC"}
                """, agent);
        assertTrue(result.contains("Result:"));
        assertTrue(result.contains("-6 hours"));
    }

    @Test
    void calculateMissingAmountReturnsError() {
        var result = tool.execute("""
                {"action": "calculate", "unit": "days"}
                """, agent);
        assertTrue(result.contains("Error"));
    }

    @Test
    void calculateDifferenceBetweenTimestamps() {
        var result = tool.execute("""
                {"action": "calculate",
                 "timestamp": "2026-04-01T00:00:00",
                 "endTimestamp": "2026-04-13T12:30:00",
                 "timezone": "UTC"}
                """, agent);
        assertTrue(result.contains("Difference:"));
        assertTrue(result.contains("12 days"));
        assertTrue(result.contains("12 hours"));
        assertTrue(result.contains("30 minutes"));
    }

    @Test
    void calculateWithoutTimestampUsesNow() {
        var result = tool.execute("""
                {"action": "calculate", "amount": 1, "unit": "hours", "timezone": "UTC"}
                """, agent);
        assertTrue(result.contains("Result:"));
    }

    // ==================== Unknown Action ====================

    @Test
    void unknownActionReturnsError() {
        var result = tool.execute("""
                {"action": "bogus"}
                """, agent);
        assertTrue(result.contains("Error"));
        assertTrue(result.contains("Unknown action"));
    }

    // ==================== Helpers ====================

    private static void deleteDir(java.nio.file.Path dir) {
        if (!java.nio.file.Files.exists(dir)) return;
        try (var walk = java.nio.file.Files.walk(dir)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try { java.nio.file.Files.delete(p); } catch (java.io.IOException _) {}
            });
        } catch (java.io.IOException _) {}
    }
}
