package jobs;

import mcp.McpGrantKeyMigration;
import play.Play;
import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Let an existing database record a subagent run that has no parent conversation.
 *
 * <p>A run a task fire starts names its task run instead ({@code SubagentRun.parentTaskRun}), but
 * a database created before that carries {@code subagent_run.parent_conversation_id NOT NULL}, and
 * Hibernate's schema update adds columns without ever relaxing one. A fresh install is unaffected.
 */
// Ahead of the default-priority jobs: two of them work on subagent_run from their own threads,
// and this ALTER needs the table to itself.
@OnApplicationStart(priority = -50)
@NoTransaction
public class SubagentRunParentColumnJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try (Connection conn = DB.getDataSource().getConnection()) {
            if (relax(conn)) {
                conn.commit();
                EventLogger.info("system",
                        "Relaxed NOT NULL on subagent_run.parent_conversation_id: a run a task starts has no parent conversation");
            }
        } catch (SQLException e) {
            EventLogger.error("system", "subagent_run parent-column migration failed: " + e.getMessage());
            throw new IllegalStateException("subagent_run parent-column migration failed", e);
        }
    }

    // Public because Play's tests live in the default package: a test drives it on a throwaway schema.
    public static boolean relax(Connection conn) throws SQLException {
        return McpGrantKeyMigration.relaxNotNull(conn, "SUBAGENT_RUN", "PARENT_CONVERSATION_ID");
    }
}
