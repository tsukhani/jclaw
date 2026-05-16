package jobs;

import play.Play;
import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.Statement;
import java.util.stream.Collectors;

/**
 * JCLAW-21: create db-scheduler's {@code scheduled_tasks} table (and its
 * three indexes) so the Scheduler has its storage in place at startup.
 *
 * <p>Reads dialect-appropriate DDL from {@code conf/db/db_scheduler_h2.sql}
 * or {@code conf/db/db_scheduler_postgres.sql} and executes each statement.
 * The DDL is idempotent ({@code CREATE TABLE IF NOT EXISTS},
 * {@code CREATE INDEX IF NOT EXISTS}) so re-running on every boot is
 * microseconds when the schema already exists.
 *
 * <p>{@code @NoTransaction} because DDL should not run inside a JPA
 * transaction. We acquire a short-lived raw connection from
 * {@link DB#datasource} for the DDL run, separate from anything Hibernate
 * is managing.
 *
 * <p>Future commits in this story add a {@code DbSchedulerBootstrapJob}
 * that builds the Scheduler. Since Play 1.x doesn't strictly order
 * {@code @OnApplicationStart} jobs, the bootstrap will explicitly call
 * {@link #ensureSchema()} as its first step rather than rely on this job
 * having already run.
 */
@OnApplicationStart
@NoTransaction
public class DbSchedulerSchemaInitJob extends Job<Void> {

    @Override
    public void doJob() {
        try {
            ensureSchema();
        } catch (Exception e) {
            EventLogger.error("system",
                    "db-scheduler schema init failed: " + e.getMessage());
            throw new RuntimeException("db-scheduler schema init failed", e);
        }
    }

    /**
     * Apply the dialect-appropriate {@code scheduled_tasks} DDL. Idempotent;
     * exposed publicly so the bootstrap job (subsequent JCLAW-21 commit) can
     * call it directly without depending on Play's job ordering.
     */
    public static void ensureSchema() throws Exception {
        try (Connection conn = DB.datasource.getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            String dialect = productName.contains("postgresql") ? "postgres" : "h2";
            String ddl = readDdl(dialect);
            executeStatements(conn, ddl);
            EventLogger.info("system",
                    "db-scheduler schema applied (" + dialect + " dialect)");
        }
    }

    private static String readDdl(String dialect) throws Exception {
        File path = Play.getFile("conf/db/db_scheduler_" + dialect + ".sql");
        if (!path.isFile()) {
            throw new IllegalStateException("Missing DDL file: " + path);
        }
        return Files.readString(path.toPath(), StandardCharsets.UTF_8);
    }

    /**
     * Strip {@code --} line comments before splitting on {@code ;}. We can't
     * pass the raw file to {@code split(";")} because our comments contain
     * semicolons (e.g., {@code "Postgres schema; indexes are taken..."}),
     * which would cut a statement boundary inside a comment and leave the
     * downstream SQL parser staring at comment text where it expects a
     * keyword. Stripping whole lines that start with {@code --} after trim
     * is safe — our DDL has no inline trailing comments to preserve.
     */
    private static void executeStatements(Connection conn, String ddl) throws Exception {
        String stripped = ddl.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String stmt : stripped.split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            try (Statement s = conn.createStatement()) {
                s.execute(trimmed);
            }
        }
    }
}
