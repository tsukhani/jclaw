package jobs;

import play.Play;
import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import utils.ErrorRendering;
import utils.StartupErrorTemplates;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
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
 * <p>Play 1.x doesn't strictly order {@code @OnApplicationStart} jobs, so
 * {@link DbSchedulerBootstrapJob} calls {@link #ensureSchema()} explicitly
 * rather than rely on this job having already run.
 */
@OnApplicationStart
@NoTransaction
public class DbSchedulerSchemaInitJob extends Job<Void> {

    @Override
    public void doJob() {
        try {
            ensureSchema();
        } catch (SQLException | IOException e) {
            // The three-part message goes to the log line; the exception keeps a one-line
            // message so the stack trace Play prints on top of it does not repeat the paragraph.
            EventLogger.error("system", bootFailureMessage(e));
            throw new IllegalStateException("db-scheduler schema init failed", e);
        }
    }

    /**
     * The actionable console message for a boot failure (JCLAW-1136): the three causes have
     * different remedies — a database that will not take a connection, shipped DDL that cannot
     * be read, and DDL the database refuses on a working connection — so they are not merged
     * into one "schema init failed".
     *
     * <p>Rendered {@link ErrorRendering#PLAIN}: nothing on the boot path has a request, a markup
     * parser or a guarantee of terminal colour.
     *
     * <p>Public because test sources are the default package.
     */
    public static String bootFailureMessage(Exception cause) {
        var template = switch (cause) {
            case IOException _ -> StartupErrorTemplates.schemaDdlUnreadable(cause.getMessage());
            case DdlStatementFailed _ -> StartupErrorTemplates.schemaDdlFailed(cause.getMessage());
            default -> StartupErrorTemplates.databaseUnavailable(cause.getMessage());
        };
        return ErrorRendering.PLAIN.render(template);
    }

    /**
     * Apply the dialect-appropriate {@code scheduled_tasks} DDL. Idempotent;
     * exposed publicly so {@link DbSchedulerBootstrapJob} can call it directly
     * without depending on Play's job ordering.
     */
    public static void ensureSchema() throws SQLException, IOException {
        try (Connection conn = DB.getDataSource().getConnection()) {
            String productName = conn.getMetaData().getDatabaseProductName().toLowerCase();
            String dialect = productName.contains("postgresql") ? "postgres" : "h2";
            String ddl = readDdl(dialect);
            executeStatements(conn, ddl);
            // Hikari hands out connections with autoCommit=false. H2 implicitly
            // commits DDL so this was invisible for years, but PostgreSQL's DDL
            // is transactional — without an explicit commit the CREATE TABLE
            // rolls back when the connection closes, leaving db-scheduler with no
            // scheduled_tasks table. Commit so the schema persists on every engine.
            conn.commit();
            EventLogger.info("system",
                    "db-scheduler schema applied (" + dialect + " dialect)");
        }
    }

    private static String readDdl(String dialect) throws IOException {
        File path = Play.getFile("conf/db/db_scheduler_" + dialect + ".sql");
        if (!path.isFile()) {
            throw new FileNotFoundException("Missing DDL file: " + path);
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
    private static void executeStatements(Connection conn, String ddl) throws SQLException {
        String stripped = ddl.lines()
                .filter(line -> !line.trim().startsWith("--"))
                .collect(Collectors.joining("\n"));
        for (String stmt : stripped.split(";")) {
            String trimmed = stmt.trim();
            if (trimmed.isEmpty()) continue;
            try (Statement s = conn.createStatement()) {
                s.execute(trimmed);
            } catch (SQLException e) {
                throw new DdlStatementFailed(e);
            }
        }
    }

    /**
     * A statement refused on a connection that worked — a permission or an existing table,
     * never the connection settings the plain {@link SQLException} remedy points at.
     */
    public static final class DdlStatementFailed extends SQLException {
        public DdlStatementFailed(SQLException cause) {
            super(cause.getMessage(), cause.getSQLState(), cause.getErrorCode(), cause);
        }
    }
}
