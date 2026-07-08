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
 * JCLAW-135: durable, idempotent migration that converts JClaw's ownership
 * foreign keys to {@code ON DELETE CASCADE} on a database whose FKs were
 * created without it.
 *
 * <p>Background: parent/child deletion moved to DB-level {@code ON DELETE
 * CASCADE} under JCLAW-542, and the {@code @OnDelete(CASCADE)} annotations on
 * every child FK make Hibernate emit the cascade clause when it creates the
 * schema. Fresh installs and the dev DB therefore already cascade — for them
 * this migration is a pure no-op. The gap it closes is a <em>pre-existing</em>
 * database (created before the annotations landed) whose FKs are still
 * {@code NO ACTION}/{@code RESTRICT}: there, a parent delete that now relies on
 * the cascade would fail with a referential-integrity violation.
 *
 * <p>Strategy: for each ownership FK in {@link #OWNERSHIP_FKS}, introspect
 * {@code information_schema} for the FK's actual constraint name and its
 * {@code delete_rule}. Only the constraint name and rule are read from the
 * catalog (they are engine-generated and must be discovered); the child/parent
 * table and column identifiers come from the authoritative inventory below so
 * the parent-side introspection join — the dialect-fragile part — is avoided.
 * When the rule is not already {@code CASCADE}, drop and re-add the constraint
 * with {@code ON DELETE CASCADE}. When it is, nothing is touched.
 *
 * <p>Dialect: the {@code information_schema.referential_constraints} /
 * {@code key_column_usage} views and the {@code ALTER TABLE ... DROP/ADD
 * CONSTRAINT} syntax are shared by H2 (dev file DB + the test lane) and
 * PostgreSQL (prod), so no per-engine branch is needed. Identifier casing is
 * handled by matching case-insensitively ({@code UPPER(...)}) and quoting only
 * the catalog-sourced constraint name verbatim.
 */
public final class CascadeFkMigrator {

    private CascadeFkMigrator() {}

    /**
     * One ownership FK: {@code childTable.childColumn} references
     * {@code parentTable.parentColumn}. All parent columns are {@code ID}.
     */
    public record ForeignKey(String childTable, String childColumn,
                             String parentTable, String parentColumn) {}

    /**
     * The 25 ownership FKs JClaw relies on the cascade for (JCLAW-135). Every
     * parent PK column is {@code ID}. Scoped deliberately: the migration touches
     * only these constraints and never any unrelated FK.
     */
    static final List<ForeignKey> OWNERSHIP_FKS = List.of(
            new ForeignKey("AGENT", "PARENT_AGENT_ID", "AGENT", "ID"),
            new ForeignKey("AGENT_BINDING", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("AGENT_SKILL_ALLOWED_TOOL", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("AGENT_SKILL_CONFIG", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("AGENT_TOOL_CONFIG", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("CHAT_MESSAGE_ATTACHMENT", "MESSAGE_ID", "MESSAGE", "ID"),
            new ForeignKey("CONVERSATION", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("CONVERSATION", "PARENT_CONVERSATION_ID", "CONVERSATION", "ID"),
            new ForeignKey("MEMORY", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("MESSAGE", "CONVERSATION_ID", "CONVERSATION", "ID"),
            new ForeignKey("NOTIFICATION", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("SESSION_COMPACTION", "CONVERSATION_ID", "CONVERSATION", "ID"),
            new ForeignKey("SLACK_BINDING", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("SUBAGENT_RUN", "CHILD_AGENT_ID", "AGENT", "ID"),
            new ForeignKey("SUBAGENT_RUN", "CHILD_CONVERSATION_ID", "CONVERSATION", "ID"),
            new ForeignKey("SUBAGENT_RUN", "PARENT_AGENT_ID", "AGENT", "ID"),
            new ForeignKey("SUBAGENT_RUN", "PARENT_CONVERSATION_ID", "CONVERSATION", "ID"),
            new ForeignKey("TASK", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("TASK_RUN", "TASK_ID", "TASK", "ID"),
            new ForeignKey("TASK_RUN_MESSAGE", "TASK_RUN_ID", "TASK_RUN", "ID"),
            new ForeignKey("TELEGRAM_BINDING", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("TELEGRAM_TOPIC_BINDING", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("TELEGRAM_TOPIC_BINDING", "BINDING_ID", "TELEGRAM_BINDING", "ID"),
            new ForeignKey("TOOL_APPROVAL_GRANT", "AGENT_ID", "AGENT", "ID"),
            new ForeignKey("WHATSAPP_BINDING", "AGENT_ID", "AGENT", "ID"));

    /**
     * Resolve the FK's constraint name and current {@code delete_rule} by its
     * child table + column. Joining {@code key_column_usage} (which lists every
     * constraint's columns) to {@code referential_constraints} (which lists
     * only FKs) restricts the match to the single FK on that column; the row
     * carries the introspected constraint name and delete rule.
     */
    private static final String LOOKUP_SQL =
            "SELECT rc.constraint_name, rc.delete_rule "
                    + "FROM information_schema.referential_constraints rc "
                    + "JOIN information_schema.key_column_usage kcu "
                    + "  ON kcu.constraint_name = rc.constraint_name "
                    + " AND kcu.constraint_schema = rc.constraint_schema "
                    + "WHERE UPPER(kcu.table_name) = ? AND UPPER(kcu.column_name) = ?";

    /**
     * Startup entry point: acquire a raw connection and convert every
     * non-cascade ownership FK, committing once. Idempotent — a database whose
     * FKs already cascade does no DDL and logs nothing. DDL runs outside any JPA
     * transaction (the caller is {@code @NoTransaction}); the raw connection is
     * committed explicitly because Hikari hands out {@code autoCommit=false}
     * connections and PostgreSQL DDL is transactional.
     *
     * @throws SQLException on any catalog read or ALTER failure
     */
    public static void ensureCascades() throws SQLException {
        try (Connection conn = DB.getDataSource().getConnection()) {
            int converted = convert(conn, OWNERSHIP_FKS);
            conn.commit();
            if (converted > 0) {
                EventLogger.info("system",
                        "Ownership FK cascade migration: converted " + converted
                                + " foreign key(s) to ON DELETE CASCADE (JCLAW-135)");
            }
        }
    }

    /**
     * Convert every FK in {@code fks} whose {@code delete_rule} is not already
     * {@code CASCADE} by dropping and re-adding the constraint with
     * {@code ON DELETE CASCADE}. An FK that the catalog doesn't report (table or
     * constraint absent) is skipped. Does <em>not</em> commit — the caller owns
     * the transaction boundary. Returns the number of FKs converted.
     *
     * <p>Package-visible seam so a test can drive the exact conversion logic
     * against a self-contained temp schema without depending on the production
     * inventory or the shared JPA connection.
     *
     * @param conn an open connection (raw or test-owned)
     * @param fks  the FKs to inspect and, where needed, convert
     * @return the count of FKs converted to cascade
     * @throws SQLException on any catalog read or ALTER failure
     */
    public static int convert(Connection conn, List<ForeignKey> fks) throws SQLException {
        int converted = 0;
        try (PreparedStatement lookup = conn.prepareStatement(LOOKUP_SQL)) {
            for (ForeignKey fk : fks) {
                String constraintName = findNonCascadeConstraint(lookup, fk);
                if (constraintName == null) continue; // already cascades, or FK not present
                alterToCascade(conn, fk, constraintName);
                converted++;
            }
        }
        return converted;
    }

    /**
     * Return the constraint name of {@code fk} when its delete rule is not
     * {@code CASCADE}, or {@code null} when it already cascades or the catalog
     * has no such FK.
     */
    private static String findNonCascadeConstraint(PreparedStatement lookup, ForeignKey fk)
            throws SQLException {
        lookup.setString(1, fk.childTable().toUpperCase(Locale.ROOT));
        lookup.setString(2, fk.childColumn().toUpperCase(Locale.ROOT));
        try (ResultSet rs = lookup.executeQuery()) {
            if (!rs.next()) return null;
            String constraintName = rs.getString(1);
            String deleteRule = rs.getString(2);
            if ("CASCADE".equalsIgnoreCase(deleteRule)) return null;
            return constraintName;
        }
    }

    /**
     * Drop and re-add {@code fk}'s constraint with {@code ON DELETE CASCADE}.
     * The catalog-sourced constraint name is quoted verbatim (it may be
     * engine-cased); the table/column identifiers come from the trusted
     * inventory and are emitted unquoted so both engines fold their casing.
     */
    private static void alterToCascade(Connection conn, ForeignKey fk, String constraintName)
            throws SQLException {
        String quotedName = '"' + constraintName + '"';
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE " + fk.childTable() + " DROP CONSTRAINT " + quotedName);
            s.execute("ALTER TABLE " + fk.childTable() + " ADD CONSTRAINT " + quotedName
                    + " FOREIGN KEY (" + fk.childColumn() + ") "
                    + "REFERENCES " + fk.parentTable() + "(" + fk.parentColumn() + ") "
                    + "ON DELETE CASCADE");
        }
    }
}
