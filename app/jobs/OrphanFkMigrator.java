package jobs;

import play.db.DB;
import services.EventLogger;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Locale;

/**
 * JCLAW-984: constrain a parent reference that shipped as a plain id column, clearing the
 * rows whose parent is already gone so the foreign key can be created at all.
 *
 * <p>Distinct from {@link CascadeFkMigrator}, which converts an <em>existing</em> foreign key
 * to {@code ON DELETE CASCADE}. This one handles the case where no foreign key was ever
 * declared, so nothing stopped the child rows outliving their parent — measured on the
 * reporting instance as 15 of 15 {@code video_generation_job} rows naming a conversation and
 * an attachment that no longer existed.
 *
 * <p>Order is the whole point: adding a foreign key to a populated table fails while an
 * orphan is present, and Hibernate's own {@code hbm2ddl=update} pass runs before any
 * {@code @OnApplicationStart} job can clean up. So its {@code ALTER} loses the race, logs,
 * and leaves the constraint absent — which is exactly the state this class then finds and
 * repairs, within the same boot.
 *
 * <p>Orphans are resolved the way the new {@code ON DELETE CASCADE} constraint would have:
 * the row is deleted.
 * That keeps the repair and the rule it installs from ever disagreeing.
 */
public final class OrphanFkMigrator {

    private OrphanFkMigrator() {}

    /** One reference being constrained: {@code childTable.childColumn} to {@code parentTable.ID}. */
    public record Ref(String childTable, String childColumn, String parentTable) {}

    /** @param constrained references given a foreign key, {@code orphansResolved} rows deleted */
    public record Result(int constrained, int orphansResolved) {}

    private static final String VIDEO_JOB = "VIDEO_GENERATION_JOB";

    /**
     * The three ownership references JCLAW-984 constrains.
     *
     * <p>{@code VIDEO_GENERATION_JOB.RESULT_ATTACHMENT_ID} is deliberately not among them:
     * it is the job's result rather than its owner, and {@code Message.attachments} is mapped
     * {@code orphanRemoval = true}, so an attachment is removed through Hibernate and a
     * managed association there fails the flush before any {@code ON DELETE} rule applies.
     * See {@code VideoGenerationJob#resultAttachmentId}.
     */
    // Public because Play's tests live in the default package.
    public static final List<Ref> REFS = List.of(
            new Ref(VIDEO_JOB, "AGENT_ID", "AGENT"),
            new Ref(VIDEO_JOB, "CONVERSATION_ID", "CONVERSATION"),
            new Ref("WHATSAPP_CONVERSATION_WINDOW", "BINDING_ID", "WHATSAPP_BINDING"));

    /**
     * Startup entry point: constrain every reference in {@link #REFS}, committing once.
     * Idempotent and near-instant once each foreign key exists — a fresh install, where
     * Hibernate created them from the entity, does nothing at all.
     *
     * @throws SQLException on any catalog read, purge, or ALTER failure
     */
    public static void run() throws SQLException {
        try (Connection conn = DB.getDataSource().getConnection()) {
            var result = constrain(conn, REFS);
            conn.commit();
            if (result.constrained() > 0) {
                EventLogger.info("system",
                        "Constrained %d previously-unreferenced parent link(s); resolved %d orphaned row(s) (JCLAW-984)"
                                .formatted(result.constrained(), result.orphansResolved()));
            }
        }
    }

    /**
     * Give each reference in {@code refs} a foreign key, resolving its orphans first. A
     * reference that already has one is skipped whole — a constraint's existence is proof
     * no orphan can be there. Does not commit; the caller owns the transaction.
     *
     * @throws SQLException on any catalog read, purge, or ALTER failure
     */
    // Public because Play's tests live in the default package: a test drives the exact logic
    // against a self-contained temp schema rather than the live one.
    public static Result constrain(Connection conn, List<Ref> refs) throws SQLException {
        int constrained = 0;
        int resolved = 0;
        for (Ref ref : refs) {
            if (hasForeignKey(conn, ref)) continue;
            resolved += resolveOrphans(conn, ref);
            addForeignKey(conn, ref);
            constrained++;
        }
        return new Result(constrained, resolved);
    }

    private static final String LOOKUP_SQL =
            "SELECT rc.constraint_name "
                    + "FROM information_schema.referential_constraints rc "
                    + "JOIN information_schema.key_column_usage kcu "
                    + "  ON kcu.constraint_name = rc.constraint_name "
                    + " AND kcu.constraint_schema = rc.constraint_schema "
                    + "WHERE UPPER(kcu.table_name) = ? AND UPPER(kcu.column_name) = ?";

    private static boolean hasForeignKey(Connection conn, Ref ref) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(LOOKUP_SQL)) {
            ps.setString(1, ref.childTable().toUpperCase(Locale.ROOT));
            ps.setString(2, ref.childColumn().toUpperCase(Locale.ROOT));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Delete the rows whose parent is gone, exactly as the cascade about to be created would
     * have. The child table is named rather than aliased inside the correlated subquery: H2
     * and PostgreSQL disagree about aliasing a DELETE target, and they do not disagree about
     * this.
     *
     * @return rows deleted
     */
    private static int resolveOrphans(Connection conn, Ref ref) throws SQLException {
        try (Statement s = conn.createStatement()) {
            return s.executeUpdate("DELETE FROM " + ref.childTable()
                    + " WHERE " + ref.childColumn() + " IS NOT NULL AND NOT EXISTS ("
                    + "SELECT 1 FROM " + ref.parentTable() + " p "
                    + "WHERE p.ID = " + ref.childTable() + "." + ref.childColumn() + ")");
        }
    }

    private static void addForeignKey(Connection conn, Ref ref) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE " + ref.childTable()
                    + " ADD CONSTRAINT FK_" + ref.childTable() + "_" + ref.childColumn()
                    + " FOREIGN KEY (" + ref.childColumn() + ") "
                    + "REFERENCES " + ref.parentTable() + "(ID) "
                    + "ON DELETE CASCADE");
        }
    }
}
