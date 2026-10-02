import jobs.MemoryAuthorTypeColumnJob;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * JCLAW-1353: an existing H2 database created {@code memory.author_type} as a native ENUM that
 * {@code ddl=update} never widens, so {@code GUEST_TURN} could not be inserted until the boot job
 * converted it. On a throwaway database, because the shared one is built from the current enum.
 */
class MemoryAuthorTypeColumnJobTest extends UnitTest {

    private static void insert(Statement s, long id, String authorType) throws SQLException {
        s.execute("INSERT INTO memory (id, author_type) VALUES (" + id + ", '" + authorType + "')");
    }

    @Test
    void anEnumColumnWidensKeepingItsRowsAndAdmitsGuestTurn() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:memory-author-type-" + System.nanoTime() + ";MODE=MYSQL")) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE memory (id BIGINT PRIMARY KEY, "
                        + "author_type ENUM('HUMAN_TURN','AGENT_SYNTHESIZED','CONSOLIDATION_DERIVED'))");
                insert(s, 1, "HUMAN_TURN");
                try {
                    insert(s, 2, "GUEST_TURN");
                    fail("the ENUM fixture must reject GUEST_TURN, or this test proves nothing");
                } catch (SQLException expected) {
                    // the production failure, reproduced
                }
            }

            assertTrue(MemoryAuthorTypeColumnJob.widen(conn));

            try (Statement s = conn.createStatement()) {
                insert(s, 2, "GUEST_TURN");
                try (ResultSet rs = s.executeQuery("SELECT author_type FROM memory ORDER BY id")) {
                    assertTrue(rs.next());
                    assertEquals("HUMAN_TURN", rs.getString(1));
                    assertTrue(rs.next());
                    assertEquals("GUEST_TURN", rs.getString(1));
                }
            }

            assertFalse(MemoryAuthorTypeColumnJob.widen(conn), "a second run is a no-op");
        }
    }

    @Test
    void aDatabaseWithoutTheTableIsANoOp() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:memory-author-type-absent-" + System.nanoTime() + ";MODE=MYSQL")) {
            assertFalse(MemoryAuthorTypeColumnJob.widen(conn));
        }
    }
}
