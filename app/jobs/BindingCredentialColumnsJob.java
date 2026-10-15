package jobs;

import models.BindingCredential;
import play.Play;
import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import utils.CredentialCipher;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Bring an existing database's channel-binding credential columns to what {@link BindingCredential} needs
 * (JCLAW-1455). Hibernate's schema update adds the lookup columns and their unique constraint, but it neither widens
 * a column nor drops a constraint, so this widens the credential columns to unbounded text (a ciphertext is about a
 * third longer than its token, and Meta sets its access tokens no maximum), and the {@link BindingCredential#UNMAPPED}
 * ones where they exist, and drops the unique constraint on each bot token, whose ciphertext differs on every write. It then sets every lookup under the current secret, on each boot,
 * so a rotation re-keys them.
 */
// Before anything reads a binding by its token: the channel runners start at the default priority.
@OnApplicationStart(priority = -150)
@NoTransaction
public class BindingCredentialColumnsJob extends Job<Void> {

    /**
     * @param updated rows whose lookup was set; {@code unreadable} rows left as they were, their token not opening
     * @param shared  rows, as {@code <table> <id>}, left as they were because another row holds the same token's lookup
     */
    public record Lookups(int updated, int unreadable, List<String> shared) {}

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try (Connection conn = DB.getDataSource().getConnection()) {
            int altered = migrate(conn);
            conn.commit();
            var lookups = refreshLookups(conn, CredentialCipher.current());
            conn.commit();
            if (altered > 0) {
                EventLogger.info("system", "Widened the binding credential columns and moved the bot token "
                        + "uniqueness to its lookup column: %d change(s) (JCLAW-1455)".formatted(altered));
            }
            if (lookups.updated() > 0) {
                EventLogger.info("system", "Set the token lookup on %d binding row(s) (JCLAW-1455)"
                        .formatted(lookups.updated()));
            }
            if (lookups.unreadable() > 0) {
                EventLogger.warn("system", ("%d binding token(s) did not decrypt under the current secret; "
                        + "their lookups are left as they were (JCLAW-1455)").formatted(lookups.unreadable()));
            }
            if (!lookups.shared().isEmpty()) {
                EventLogger.warn("system", ("Binding(s) %s hold the same bot token as another binding, saved while "
                        + "it could not be decrypted; their lookups are left as they were. Delete the duplicate (JCLAW-1455)")
                        .formatted(String.join(", ", lookups.shared())));
            }
        } catch (SQLException e) {
            EventLogger.error("system", "Binding credential column migration failed: " + e.getMessage());
            throw new IllegalStateException("binding credential column migration failed", e);
        }
    }

    /**
     * Drop each single-column unique constraint on a credential column and widen each bounded one. A no-op on a
     * database other than H2, and on one already migrated or created from the current entities. Does not commit.
     *
     * @return how many constraints were dropped and columns widened
     */
    // Public because Play's tests live in the default package: a test drives it on a throwaway schema.
    public static int migrate(Connection conn) throws SQLException {
        if (!conn.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("h2")) {
            return 0;
        }
        int altered = 0;
        for (var credential : BindingCredential.values()) {
            var table = credential.table.toUpperCase(Locale.ROOT);
            var column = credential.column.toUpperCase(Locale.ROOT);
            try (Statement s = conn.createStatement()) {
                for (var constraint : uniqueConstraintsOnlyOn(conn, table, column)) {
                    s.execute("ALTER TABLE " + table + " DROP CONSTRAINT \"" + constraint + '"');
                    altered++;
                }
                if (bounded(conn, table, column)) {
                    // SET DATA TYPE keeps the column's NOT NULL; the unbounded form is what TEXT creates in MODE=MYSQL.
                    s.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE CHARACTER VARYING");
                    altered++;
                }
            }
        }
        for (var unmapped : BindingCredential.UNMAPPED) {
            var table = unmapped.table().toUpperCase(Locale.ROOT);
            var column = unmapped.column().toUpperCase(Locale.ROOT);
            if (bounded(conn, table, column)) {
                try (Statement s = conn.createStatement()) {
                    s.execute("ALTER TABLE " + table + " ALTER COLUMN " + column + " SET DATA TYPE CHARACTER VARYING");
                }
                altered++;
            }
        }
        return altered;
    }

    /**
     * Set each lookup column to the HMAC of its row's token under {@code cipher}'s current secret, and to null where
     * there is no token. A token that does not open is skipped: its lookup stays as it was, ready for the right secret.
     * Does not commit.
     */
    // Public because Play's tests live in the default package.
    public static Lookups refreshLookups(Connection conn, CredentialCipher cipher) throws SQLException {
        int updated = 0;
        int unreadable = 0;
        var shared = new ArrayList<String>();
        for (var credential : BindingCredential.values()) {
            var lookupColumn = credential.lookupColumn;
            if (lookupColumn == null) continue;
            try (PreparedStatement select = conn.prepareStatement("SELECT id, " + credential.column + ", "
                    + lookupColumn + " FROM " + credential.table + " ORDER BY id");
                 PreparedStatement update = conn.prepareStatement("UPDATE " + credential.table + " SET "
                         + lookupColumn + " = ? WHERE id = ?");
                 var rows = select.executeQuery()) {
                while (rows.next()) {
                    var stored = rows.getString(2);
                    String lookup = null;
                    if (stored != null) {
                        var opened = cipher.open(credential.field(), stored);
                        if (!opened.readable()) {
                            unreadable++;
                            continue;
                        }
                        lookup = cipher.lookup(opened.resolvedPlaintext());
                    }
                    if (Objects.equals(lookup, rows.getString(3))) continue;
                    update.setString(1, lookup);
                    update.setLong(2, rows.getLong(1));
                    try {
                        update.executeUpdate();
                        updated++;
                    } catch (SQLException e) {
                        // A token saved again on a second binding while it could not be decrypted, once the old secret
                        // opens it: the unique lookup refuses one, and throwing here would abort the boot.
                        if (!UNIQUE_VIOLATION.equals(e.getSQLState())) throw e;
                        shared.add(credential.table + " " + rows.getLong(1));
                    }
                }
            }
        }
        return new Lookups(updated, unreadable, List.copyOf(shared));
    }

    private static List<String> uniqueConstraintsOnlyOn(Connection conn, String table, String column)
            throws SQLException {
        var names = new ArrayList<String>();
        try (PreparedStatement lookup = conn.prepareStatement(
                "SELECT tc.constraint_name FROM information_schema.table_constraints tc "
                        + "JOIN information_schema.key_column_usage kcu "
                        + "  ON kcu.constraint_name = tc.constraint_name "
                        + " AND kcu.constraint_schema = tc.constraint_schema "
                        + " AND kcu.table_name = tc.table_name "
                        + "WHERE UPPER(tc.table_name) = ? AND tc.constraint_type = 'UNIQUE' "
                        + "GROUP BY tc.constraint_name "
                        + "HAVING COUNT(*) = 1 AND MAX(UPPER(kcu.column_name)) = ?")) {
            lookup.setString(1, table);
            lookup.setString(2, column);
            try (var rows = lookup.executeQuery()) {
                while (rows.next()) names.add(rows.getString(1));
            }
        }
        return names;
    }

    private static boolean bounded(Connection conn, String table, String column) throws SQLException {
        try (PreparedStatement lookup = conn.prepareStatement(
                "SELECT data_type, character_maximum_length FROM information_schema.columns "
                        + "WHERE UPPER(table_name) = ? AND UPPER(column_name) = ?")) {
            lookup.setString(1, table);
            lookup.setString(2, column);
            try (var rows = lookup.executeQuery()) {
                if (!rows.next() || !"CHARACTER VARYING".equalsIgnoreCase(rows.getString(1))) return false;
                return rows.getLong(2) < UNBOUNDED;
            }
        }
    }

    /** H2's length for a CHARACTER VARYING declared without one, which is what TEXT creates in MODE=MYSQL. */
    private static final long UNBOUNDED = 1_000_000_000L;

    private static final String UNIQUE_VIOLATION = "23505";
}
