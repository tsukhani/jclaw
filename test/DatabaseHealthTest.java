import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.database.DatabaseHealth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The database verdict (JCLAW-1165) against a trace snippet shaped like the incident's and a
 * fixed clock. The thresholds are the whole point: Attention for read failures in the window,
 * Critical only when the probe fails.
 */
class DatabaseHealthTest extends UnitTest {

    @TempDir
    Path tmp;

    private static final Instant NOW = Instant.parse("2026-09-09T14:00:00Z");

    private static DatabaseHealth.CorruptionEvent at(Duration ago) {
        return new DatabaseHealth.CorruptionEvent(NOW.minus(ago), "Unable to read the page");
    }

    @Test
    void theTraceFileDatesEachMessageByThePrecedingStamp() throws Exception {
        var trace = tmp.resolve("jclaw.trace.db");
        Files.writeString(trace, """
                preamble without a stamp mentioning File corrupted must be ignored
                2026-09-08 10:30:49.557839+08:00 jdbc[3]: exception
                org.h2.mvstore.MVStoreException: Unable to read the page at position 12 [2.5.250/6]
                \tat org.h2.mvstore.MVStore.readPage(MVStore.java:1)
                2026-09-09 21:15:00.000000+08:00 jdbc[3]: exception
                org.h2.jdbc.JdbcSQLNonTransientConnectionException: The database has been closed [90098-250]
                2026-09-09 21:16:00.000000+08:00 jdbc[4]: exception
                org.h2.jdbc.JdbcSQLDataException: Value not permitted for column
                """);
        var events = DatabaseHealth.corruptionEvents(trace);
        assertEquals(2, events.size(), () -> "events: " + events);
        assertEquals(Instant.parse("2026-09-08T02:30:49.557839Z"), events.get(0).at());
        assertEquals("Unable to read the page", events.get(0).phrase());
        assertEquals(Instant.parse("2026-09-09T13:15:00Z"), events.get(1).at());
        assertEquals("has been closed", events.get(1).phrase());
    }

    @Test
    void aMissingTraceFileMeansNoEvents() throws Exception {
        assertTrue(DatabaseHealth.corruptionEvents(tmp.resolve("absent")).isEmpty());
    }

    @Test
    void theWindowsCountWhatFallsInsideThemAndNameTheEarliest() {
        var events = List.of(at(Duration.ofHours(1)), at(Duration.ofHours(30)), at(Duration.ofDays(10)));
        var summary = DatabaseHealth.summarize(events, NOW);
        assertEquals(1, summary.last24h());
        assertEquals(2, summary.last7d());
        assertEquals(NOW.minus(Duration.ofHours(30)), summary.firstAt());
        assertTrue(DatabaseHealth.anyEventSince(events, NOW.minus(Duration.ofHours(2))));
        assertFalse(DatabaseHealth.anyEventSince(events, NOW.minus(Duration.ofMinutes(30))));
    }

    @Test
    void aFailedProbeIsCriticalWhateverTheTraceSays() {
        var a = DatabaseHealth.assess(new DatabaseHealth.TraceSummary(0, 0, null), null, "The database has been closed");
        assertEquals(DatabaseHealth.Verdict.CRITICAL, a.verdict());
        assertTrue(a.reason().contains("Repair"), a.reason());
        assertTrue(a.reason().contains("Restore"), a.reason());
    }

    @Test
    void readFailuresInTheLastDayAreAttentionWithTheCount() {
        var a = DatabaseHealth.assess(new DatabaseHealth.TraceSummary(3, 9, NOW.minus(Duration.ofDays(3))), 1L, null);
        assertEquals(DatabaseHealth.Verdict.ATTENTION, a.verdict());
        assertTrue(a.reason().startsWith("3 read failures logged in the last 24 hours (9 in 7 days)"), a.reason());
        assertTrue(a.reason().contains("Repair"), a.reason());
    }

    @Test
    void readFailuresOnlyEarlierInTheWeekAreStillAttention() {
        var a = DatabaseHealth.assess(new DatabaseHealth.TraceSummary(0, 1, NOW.minus(Duration.ofDays(3))), 1L, null);
        assertEquals(DatabaseHealth.Verdict.ATTENTION, a.verdict());
        assertTrue(a.reason().startsWith("1 read failure logged in the last 7 days"), a.reason());
    }

    @Test
    void aCleanTraceAndAnAnsweringProbeIsHealthy() {
        var a = DatabaseHealth.assess(new DatabaseHealth.TraceSummary(0, 0, null), 2L, null);
        assertEquals(DatabaseHealth.Verdict.HEALTHY, a.verdict());
        assertEquals(Long.valueOf(2), a.probeMs());
    }
}
