package jobs;

import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.sql.Connection;
import java.sql.Statement;

/**
 * JCLAW-21: convert tasks.type / tasks.status / task_run.status from H2
 * MySQL-mode native ENUM columns to VARCHAR so future enum widenings
 * (e.g. adding {@code INTERVAL} to {@link models.Task.Type}) don't
 * silently get rejected by a stale check tuple.
 *
 * <h3>Why the migration is needed</h3>
 * The app's DB connection uses {@code MODE=MYSQL}, which causes
 * Hibernate's MySQL dialect to emit {@code ENUM('A','B','C')} column
 * types for {@code @Enumerated(EnumType.STRING)} fields. H2 stores
 * the allowed-values set inside the column type itself. When the Java
 * enum gains a new value, Hibernate's {@code hbm2ddl=update} does NOT
 * widen the column — INSERTs of the new value fail with
 * {@code Value not permitted for column "('CRON', 'IMMEDIATE',
 * 'SCHEDULED')": "INTERVAL"}.
 *
 * <p>Switching to {@code VARCHAR} removes the column-level allow-list
 * and shifts validation to the JPA layer (where the Java enum is the
 * single source of truth). Future enum additions then "just work."
 *
 * <h3>Idempotency</h3>
 * Each ALTER is wrapped in try/catch and tolerates the "column
 * already VARCHAR" case (H2 throws a specific exception we ignore).
 * Re-running on a converted schema is microseconds.
 *
 * <p>Postgres path (when JClaw migrates) needs its own migration —
 * Postgres uses CHECK constraints, not native ENUMs — but the
 * Hibernate-emitted DDL there is already VARCHAR + check, and
 * Hibernate widens the check on enum changes.
 */
@OnApplicationStart
@NoTransaction
public class TasksEnumColumnMigration extends Job<Void> {

    @Override
    public void doJob() {
        try (Connection conn = DB.datasource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            if (productName.contains("postgresql")) {
                // Postgres path uses VARCHAR + check constraint by default
                // and Hibernate widens the check on enum changes. No migration
                // needed; this column-type rewrite is H2-specific.
                return;
            }
            tryAlterToVarchar(conn, "tasks", "type", 32);
            tryAlterToVarchar(conn, "tasks", "status", 32);
            tryAlterToVarchar(conn, "task_run", "status", 32);
        } catch (Exception e) {
            EventLogger.error("system",
                    "TasksEnumColumnMigration failed: " + e.getMessage());
        }
    }

    private static void tryAlterToVarchar(Connection conn, String table, String column, int len) {
        // H2 idempotency strategy: try the ALTER unconditionally. If the
        // column is already VARCHAR, H2 silently succeeds (it's a
        // type-preserving change at that point). If it's still ENUM, the
        // ALTER converts in place, preserving the string values.
        String sql = "ALTER TABLE " + table + " ALTER COLUMN " + column
                + " VARCHAR(" + len + ")";
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
            EventLogger.info("system",
                    "TasksEnumColumnMigration: %s.%s converted to VARCHAR(%d)"
                            .formatted(table, column, len));
        } catch (Exception e) {
            EventLogger.warn("system",
                    "TasksEnumColumnMigration: %s.%s ALTER skipped: %s"
                            .formatted(table, column, e.getMessage()));
        }
    }
}
