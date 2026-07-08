import jobs.CascadeFkMigrator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

/**
 * JCLAW-135 AC2: the ownership-FK cascade migration converts a non-cascade FK
 * to {@code ON DELETE CASCADE} and leaves an already-cascading FK untouched.
 *
 * <p>Runs against a dedicated, throwaway in-memory H2 (its own db name per
 * test) in {@code MODE=MYSQL} — the same H2 build and mode the app's test DB
 * uses — rather than the shared play test database. That keeps the DDL fully
 * self-contained: it can't be seen by, or interfere with, the concurrent
 * unit + functional test lanes (which run agent-delete cascade tests against
 * the real schema).
 */
class CascadeFkMigratorTest extends UnitTest {

    private static Connection freshH2() throws Exception {
        // Unique db name so no two test methods (or lanes) share an in-memory DB.
        var url = "jdbc:h2:mem:migtest_" + System.nanoTime() + ";MODE=MYSQL";
        var conn = DriverManager.getConnection(url);
        conn.setAutoCommit(true);
        return conn;
    }

    private static String deleteRule(Connection conn, String childTable, String childColumn)
            throws Exception {
        try (var ps = conn.prepareStatement(
                "SELECT rc.delete_rule "
                        + "FROM information_schema.referential_constraints rc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON kcu.constraint_name = rc.constraint_name "
                        + " AND kcu.constraint_schema = rc.constraint_schema "
                        + "WHERE UPPER(kcu.table_name) = ? AND UPPER(kcu.column_name) = ?")) {
            ps.setString(1, childTable);
            ps.setString(2, childColumn);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    @Test
    void convertsNonCascadeFkToCascade() throws Exception {
        try (Connection conn = freshH2()) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE mig_parent (id BIGINT PRIMARY KEY)");
                s.execute("CREATE TABLE mig_child (id BIGINT PRIMARY KEY, parent_id BIGINT, "
                        + "CONSTRAINT fk_mig_child FOREIGN KEY (parent_id) REFERENCES mig_parent(id))");
            }
            // Precondition: Hibernate/H2 create the FK without a cascade rule.
            assertFalse("CASCADE".equalsIgnoreCase(deleteRule(conn, "MIG_CHILD", "PARENT_ID")),
                    "precondition: the FK must not already cascade");

            var fks = List.of(new CascadeFkMigrator.ForeignKey(
                    "MIG_CHILD", "PARENT_ID", "MIG_PARENT", "ID"));
            int converted = CascadeFkMigrator.convert(conn, fks);

            assertEquals(1, converted, "exactly one FK should have been converted");
            assertEquals("CASCADE", deleteRule(conn, "MIG_CHILD", "PARENT_ID"),
                    "the FK's delete rule must now be CASCADE");

            // And the cascade must actually delete children when the parent goes.
            try (Statement s = conn.createStatement()) {
                s.execute("INSERT INTO mig_parent VALUES (1)");
                s.execute("INSERT INTO mig_child VALUES (10, 1)");
                s.execute("DELETE FROM mig_parent WHERE id = 1");
                try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM mig_child")) {
                    rs.next();
                    assertEquals(0, rs.getInt(1),
                            "deleting the parent must cascade-delete the child row");
                }
            }
        }
    }

    @Test
    void isANoOpWhenTheFkAlreadyCascades() throws Exception {
        try (Connection conn = freshH2()) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE mig_parent (id BIGINT PRIMARY KEY)");
                s.execute("CREATE TABLE mig_child (id BIGINT PRIMARY KEY, parent_id BIGINT, "
                        + "CONSTRAINT fk_mig_child FOREIGN KEY (parent_id) "
                        + "REFERENCES mig_parent(id) ON DELETE CASCADE)");
            }
            assertEquals("CASCADE", deleteRule(conn, "MIG_CHILD", "PARENT_ID"),
                    "precondition: the FK already cascades");

            var fks = List.of(new CascadeFkMigrator.ForeignKey(
                    "MIG_CHILD", "PARENT_ID", "MIG_PARENT", "ID"));
            int converted = CascadeFkMigrator.convert(conn, fks);

            assertEquals(0, converted, "an already-cascading FK must not be touched");
            assertEquals("CASCADE", deleteRule(conn, "MIG_CHILD", "PARENT_ID"));
        }
    }

    @Test
    void skipsAbsentForeignKeysSilently() throws Exception {
        try (Connection conn = freshH2()) {
            // No tables at all — the migrator must not throw, just convert nothing.
            var fks = List.of(new CascadeFkMigrator.ForeignKey(
                    "NO_SUCH_CHILD", "PARENT_ID", "NO_SUCH_PARENT", "ID"));
            assertEquals(0, CascadeFkMigrator.convert(conn, fks),
                    "a catalog with no matching FK must convert nothing and not throw");
        }
    }
}
