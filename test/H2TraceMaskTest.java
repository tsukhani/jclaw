import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.database.DatabaseHealth;
import services.database.H2Maintenance;
import services.database.H2TraceMask;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * JCLAW-1455: the values H2 quotes into its trace file are masked in place. The trace here is H2's own, written by
 * refused write against a file database, so the test pins the form H2 2.5 writes; the rest are written by hand in the
 * forms H2 uses for them.
 */
class H2TraceMaskTest extends UnitTest {

    private static final String LONG_TOKEN = "EAAG-fake-trace-" + "q".repeat(300);

    @TempDir
    Path dir;

    private Path trace() {
        return dir.resolve(H2Maintenance.TRACE_FILE);
    }

    /** A value too long for its column: H2 copies it into the trace, cut short. */
    private void refusedWrite() throws SQLException {
        var url = "jdbc:h2:file:" + dir.toAbsolutePath().resolve(H2Maintenance.DB_NAME) + ";MODE=MYSQL";
        try (var conn = DriverManager.getConnection(url, "", ""); var s = conn.createStatement()) {
            s.execute("CREATE TABLE binding (id BIGINT PRIMARY KEY, access_token VARCHAR(255))");
            try (var insert = conn.prepareStatement("INSERT INTO binding VALUES (?, ?)")) {
                insert.setLong(1, 1);
                insert.setString(2, LONG_TOKEN);
                assertThrows(SQLException.class, insert::executeUpdate);
            }
        }
    }

    @Test
    void theValueARefusedWriteCopiedIntoTheTraceIsMaskedInPlace() throws Exception {
        refusedWrite();
        var before = Files.readString(trace(), StandardCharsets.UTF_8);
        assertTrue(before.contains(LONG_TOKEN.substring(0, 60)), "H2 copied the value into its trace: " + before);
        assertTrue(before.contains("... (" + LONG_TOKEN.length() + ")"), "cut short: " + before);

        assertEquals(1, H2TraceMask.maskTraces(dir));

        var after = Files.readString(trace(), StandardCharsets.UTF_8);
        assertEquals(before.length(), after.length(), "masked byte for byte");
        assertFalse(after.contains("fake-trace"), after);
        assertTrue(after.contains("... (" + LONG_TOKEN.length() + ")"), "H2's note of the full length stays");
        assertTrue(after.contains("Value too long for column \"ACCESS_TOKEN"), "and so does the rest of the message");

        assertEquals(0, H2TraceMask.maskTraces(dir), "a second pass has nothing left to mask");
        assertEquals(after, Files.readString(trace(), StandardCharsets.UTF_8));
    }

    @Test
    void theCorruptionLinesTheHealthCheckReadsSurviveTheMask() throws Exception {
        var text = """
                2026-09-09 10:30:49.557839+08:00 jdbc[3]: exception
                org.h2.jdbc.JdbcSQLNonTransientException: General error: "org.h2.mvstore.MVStoreException: File corrupted in chunk 10461, expected page length 4..1024, got 1919251574 [2.2.224/6]"
                2026-09-09 10:31:02.000001+08:00 jdbc[3]: exception
                org.h2.jdbc.JdbcSQLIntegrityConstraintViolationException: Unique index or primary key violation: "PUBLIC.UK ON PUBLIC.T(C NULLS FIRST) VALUES ( /* 2 */ 'it''s-a-fake-secret' )"; SQL statement:
                2026-09-09 10:32:10.000001+08:00 jdbc[3]: exception
                org.h2.mvstore.MVStoreException: Unable to read the page at position 2199023255552, chunk 9233, offset 0 [2.2.224/6]
                2026-09-09 10:33:10.000001+08:00 database: unterminated 'fake-secret-to-the-end-of-the-line
                2026-09-09 10:34:10.000001+08:00 database: org.h2.mvstore.MVStoreException: This store has been closed
                """;
        Files.writeString(trace(), text, StandardCharsets.UTF_8);
        var events = DatabaseHealth.corruptionEvents(trace());
        assertEquals(3, events.size());

        assertEquals(2, H2TraceMask.mask(trace()));

        var after = Files.readString(trace(), StandardCharsets.UTF_8);
        assertFalse(after.contains("fake-secret"), after);
        assertTrue(after.contains("'" + "*".repeat("it''s-a-fake-secret".length()) + "'"),
                "an escaped quote inside a value is part of it: " + after);
        assertEquals(events, DatabaseHealth.corruptionEvents(trace()), "every corruption event reads as it did");
    }

    @Test
    void whatH2AppendsAfterTheMaskReadTheFileIsKept() throws Exception {
        Files.writeString(trace(), "2026-09-09 10:31:02.000001+08:00 jdbc[3]: VALUES ( 'fake-secret-one' )\n");
        H2TraceMask.mask(trace());
        Files.writeString(trace(), "2026-09-09 10:31:03.000001+08:00 jdbc[3]: next entry\n", StandardOpenOption.APPEND);
        assertEquals("2026-09-09 10:31:02.000001+08:00 jdbc[3]: VALUES ( '" + "*".repeat("fake-secret-one".length()) + "' )\n"
                + "2026-09-09 10:31:03.000001+08:00 jdbc[3]: next entry\n", Files.readString(trace()));
        assertEquals(0, H2TraceMask.maskTraces(dir.resolve("no-such-dir")), "no trace file is nothing to mask");
    }
}
