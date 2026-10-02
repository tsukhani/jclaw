package jobs;

import play.Play;
import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/**
 * Convert {@code memory.author_type} from an H2 native ENUM to VARCHAR so it admits
 * {@code GUEST_TURN} (JCLAW-1353).
 *
 * <p>H2 in {@code MODE=MYSQL} creates an {@code @Enumerated(STRING)} column as
 * {@code ENUM(...)}, and {@code ddl=update} never widens it, so an insert of a newly added value
 * fails on every database created before it (the JCLAW-21 shape). Existing rows keep their values.
 */
@OnApplicationStart
@NoTransaction
public class MemoryAuthorTypeColumnJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try (Connection conn = DB.getDataSource().getConnection()) {
            if (widen(conn)) {
                conn.commit();
                EventLogger.info("system", "Converted memory.author_type from ENUM to VARCHAR (JCLAW-1353)");
            }
        } catch (SQLException e) {
            EventLogger.error("system", "memory.author_type migration failed: " + e.getMessage());
            throw new IllegalStateException("memory.author_type migration failed", e);
        }
    }

    /**
     * The migration against a caller-supplied connection, without committing. A no-op on a
     * database other than H2, or whose column is absent or already VARCHAR.
     *
     * @return whether the column was altered
     */
    // Public because Play's tests live in the default package.
    public static boolean widen(Connection conn) throws SQLException {
        if (!conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2")) {
            return false;
        }
        try (PreparedStatement lookup = conn.prepareStatement(
                "SELECT data_type FROM information_schema.columns "
                        + "WHERE UPPER(table_name) = 'MEMORY' AND UPPER(column_name) = 'AUTHOR_TYPE'")) {
            try (ResultSet rs = lookup.executeQuery()) {
                if (!rs.next() || !"ENUM".equalsIgnoreCase(rs.getString(1))) return false;
            }
        }
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE memory ALTER COLUMN author_type VARCHAR(30)");
        }
        return true;
    }
}
