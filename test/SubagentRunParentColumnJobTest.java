import jobs.SubagentRunParentColumnJob;
import org.junit.jupiter.api.Test;
import play.db.DB;
import play.test.UnitTest;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * The upgrade a run with no parent conversation needs. The test database is built from the current
 * entity, where the column is already optional, so only a schema this test builds can show the
 * migration working. It runs on a throwaway database: a NOT NULL on the shared {@code subagent_run}
 * would break every class running beside this one.
 */
class SubagentRunParentColumnJobTest extends UnitTest {

    private static final String TASK_STARTED_INSERT =
            "INSERT INTO subagent_run (id, parent_agent_id, parent_conversation_id) VALUES (1, 1, NULL)";

    @Test
    void theMigrationLetsAnOlderSchemaStoreARunWithNoParentConversation() throws Exception {
        try (Connection conn = DriverManager.getConnection(
                "jdbc:h2:mem:legacy-subagent-run-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1")) {
            try (Statement s = conn.createStatement()) {
                s.execute("CREATE TABLE subagent_run (id BIGINT PRIMARY KEY, parent_agent_id BIGINT NOT NULL, "
                        + "parent_conversation_id BIGINT NOT NULL)");
            }
            try (Statement s = conn.createStatement()) {
                s.execute(TASK_STARTED_INSERT);
                fail("the older schema must refuse a run with no parent conversation, or this fixture proves nothing");
            } catch (SQLException expected) {
                assertTrue(expected.getMessage().toUpperCase().contains("PARENT_CONVERSATION_ID"), expected.getMessage());
            }

            assertTrue(SubagentRunParentColumnJob.relax(conn), "the column should have been relaxed");

            try (Statement s = conn.createStatement()) {
                s.execute(TASK_STARTED_INSERT);
            }
            assertFalse(SubagentRunParentColumnJob.relax(conn), "a second boot has nothing left to do");
        }
    }

    // relaxNotNull answers false for a column it cannot find, so a renamed join column would turn
    // the migration into a silent no-op: the name it targets must be one the entity still maps.
    @Test
    void theMigrationTargetsAColumnTheEntityMaps() throws Exception {
        try (Connection conn = DB.getDataSource().getConnection();
             var lookup = conn.prepareStatement("SELECT is_nullable FROM information_schema.columns "
                     + "WHERE UPPER(table_name) = 'SUBAGENT_RUN' AND UPPER(column_name) = 'PARENT_CONVERSATION_ID'");
             var rs = lookup.executeQuery()) {
            assertTrue(rs.next(), "subagent_run.parent_conversation_id must exist in the schema built from the entity");
            assertEquals("YES", rs.getString(1), "and a fresh install creates it optional");
        }
    }
}
