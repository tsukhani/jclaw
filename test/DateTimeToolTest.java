import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
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
    void missingActionDefaultsToNowInsteadOfThrowing() {
        // Regression: the LLM sometimes calls datetime with no "action" key.
        // Previously args.get("action").getAsString() NPE'd; it must now fall
        // back to "now" and return a valid time rather than crash the tool.
        var result = tool.execute("{}", agent);
        assertTrue(result.contains("timezone:"), "Empty-args call should return current time");
        assertTrue(result.contains("iso:"), "Empty-args call should return an ISO timestamp");
    }

    @Test
    void timezoneOnlyWithoutActionReturnsCurrentTimeInThatZone() {
        // The exact payload observed failing in production: a timezone but no action.
        var result = tool.execute("""
                {"timezone": "Asia/Kuala_Lumpur"}
                """, agent);
        assertTrue(result.contains("Asia/Kuala_Lumpur"), "Should honor the timezone and default to now");
        assertTrue(result.contains("iso:"), "Should return an ISO timestamp");
    }

    @Test
    void nullActionDefaultsToNow() {
        var result = tool.execute("""
                {"action": null}
                """, agent);
        assertTrue(result.contains("timezone:"), "Explicit null action should default to now");
    }

    @Test
    void nowWithExplicitTimezone() {
        var result = tool.execute("""
                {"action": "now", "timezone": "America/New_York"}
                """, agent);
        assertTrue(result.contains("America/New_York"));
    }

    @Test
    void nowWithInvalidTimezoneReturnsError() {
        // JCLAW-823: a present-but-invalid timezone is a caller mistake, not a
        // cue to silently substitute the server default (which returned a
        // plausible-looking WRONG time). It must surface as an error string.
        var result = tool.execute("""
                {"action": "now", "timezone": "Invalid/Zone"}
                """, agent);
        assertTrue(result.startsWith("Error: Unknown timezone"),
                "invalid tz must return an Unknown timezone error, got: " + result);
        assertTrue(result.contains("Invalid/Zone"), "error should name the bad timezone");
    }

    @Test
    void convertWithInvalidTimezoneReturnsError() {
        // Covers the convert path's resolveZone (fromTimezone/toTimezone).
        var result = tool.execute("""
                {"action": "convert", "timestamp": "2026-04-13T09:00:00",
                 "fromTimezone": "Not/AZone", "toTimezone": "UTC"}
                """, agent);
        assertTrue(result.startsWith("Error: Unknown timezone"),
                "invalid convert tz must return an Unknown timezone error, got: " + result);
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
