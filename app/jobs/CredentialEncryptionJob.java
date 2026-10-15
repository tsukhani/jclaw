package jobs;

import models.BindingCredential;
import models.JsonCredential;
import org.jspecify.annotations.Nullable;
import play.Play;
import play.db.DB;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.EventLogger;
import services.OperatorAlerts;
import services.database.DatabaseService;
import utils.CredentialCipher;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Locale;
import java.util.function.Function;

/**
 * The boot pass over every stored credential (JCLAW-1455): plaintext is sealed in place, a value only
 * {@value CredentialCipher#PREVIOUS_SECRET_VAR} opens is sealed again under the current secret, and a value neither
 * opens is counted and left as it is, for the right secret to recover. It commits once, so a start that dies partway
 * writes nothing, and a sealed value is never sealed again, so the next start finishes the job. The binding lookups
 * are {@link BindingCredentialColumnsJob}'s, which sets them under the current secret before this runs. Before it
 * commits it records whether the data file now holds old copies a compaction would remove; afterwards it masks the
 * values H2's trace file quotes.
 */
// After BindingCredentialColumnsJob widens the binding columns, which a long token's ciphertext needs; before
// DefaultConfigJob, the channel runners and the MCP connections read a credential.
@OnApplicationStart(priority = -140)
@NoTransaction
public class CredentialEncryptionJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        var cipher = CredentialCipher.current();
        CredentialCipher.Reseal pass;
        try (Connection conn = DB.getDataSource().getConnection()) {
            pass = sealAndCommit(conn, cipher, DatabaseService.dataDir());
        } catch (SQLException | IOException e) {
            // Every credential still reads as it did: plaintext and the previous secret's ciphertext both open.
            EventLogger.error("system", "Encrypting the stored credentials failed; the next start tries again: "
                    + e.getMessage());
            return;
        }
        report(pass, cipher.hasPrevious());
        DatabaseService.maskTraces();
    }

    /**
     * Seal every store, record in {@code dataDir} the old copies that leaves in the data file, and only then commit: a
     * start that dies between the two finds a compaction due, never sealed values whose plaintext no record names. A
     * record that cannot be written rolls the pass back for the next start.
     *
     * @return the pass over the stores, without the {@link #resealUnmapped} columns
     */
    // Public because Play's tests live in the default package.
    public static CredentialCipher.Reseal sealAndCommit(Connection conn, CredentialCipher cipher, Path dataDir)
            throws SQLException, IOException {
        var pass = reseal(conn, cipher);
        var unmapped = resealUnmapped(conn, cipher);
        try {
            DatabaseService.recordCredentialPass(dataDir, pass.sealed() + unmapped.sealed(),
                    pass.rekeyed() + unmapped.rekeyed());
        } catch (IOException e) {
            conn.rollback();
            throw e;
        }
        conn.commit();
        return pass;
    }

    /** Seal every store's credentials under {@code cipher}'s current secret. Does not commit. */
    // Public because Play's tests live in the default package: a test drives it on a throwaway schema.
    public static CredentialCipher.Reseal reseal(Connection conn, CredentialCipher cipher) throws SQLException {
        var pass = new CredentialCipher.Reseal(cipher);
        ConfigService.resealStored(conn, pass);
        for (var credential : BindingCredential.values()) {
            resealColumn(conn, credential.table, credential.column, stored -> pass.reseal(credential.field(), stored));
        }
        for (var json : JsonCredential.values()) {
            resealColumn(conn, json.table, JsonCredential.COLUMN, stored -> json.reseal(stored, pass));
        }
        return pass;
    }

    /**
     * Seal the {@link BindingCredential#UNMAPPED} columns where the schema still has them. Counted apart from
     * {@link #reseal}: nothing reads them, so one that does not open is no credential to enter again.
     */
    // Public because Play's tests live in the default package.
    public static CredentialCipher.Reseal resealUnmapped(Connection conn, CredentialCipher cipher) throws SQLException {
        var pass = new CredentialCipher.Reseal(cipher);
        for (var column : BindingCredential.UNMAPPED) {
            if (!columnExists(conn, column.table(), column.column())) continue;
            resealColumn(conn, column.table(), column.column(), stored -> pass.reseal(column.field(), stored));
        }
        return pass;
    }

    private static boolean columnExists(Connection conn, String table, String column) throws SQLException {
        try (var lookup = conn.prepareStatement("SELECT COUNT(*) FROM information_schema.columns "
                + "WHERE UPPER(table_name) = ? AND UPPER(column_name) = ?")) {
            lookup.setString(1, table.toUpperCase(Locale.ROOT));
            lookup.setString(2, column.toUpperCase(Locale.ROOT));
            try (var rows = lookup.executeQuery()) {
                return rows.next() && rows.getLong(1) > 0;
            }
        }
    }

    private static void resealColumn(Connection conn, String table, String column,
                                     Function<String, @Nullable String> reseal) throws SQLException {
        try (var select = conn.prepareStatement("SELECT id, " + column + " FROM " + table);
             var update = conn.prepareStatement("UPDATE " + table + " SET " + column + " = ? WHERE id = ?");
             var rows = select.executeQuery()) {
            while (rows.next()) {
                var stored = rows.getString(2);
                if (stored == null) continue;
                var resealed = reseal.apply(stored);
                if (resealed == null) continue;
                update.setString(1, resealed);
                update.setLong(2, rows.getLong(1));
                update.executeUpdate();
            }
        }
    }

    private static void report(CredentialCipher.Reseal pass, boolean hasPrevious) {
        if (pass.sealed() > 0) {
            EventLogger.info("system", "Encrypted %d stored credential(s) in place (JCLAW-1455)".formatted(pass.sealed()));
        }
        if (pass.rekeyed() > 0) {
            EventLogger.info("system", "Re-encrypted %d stored credential(s) under PLAY_SECRET that only %s opened"
                    .formatted(pass.rekeyed(), CredentialCipher.PREVIOUS_SECRET_VAR));
        }
        if (hasPrevious) {
            EventLogger.info("system", ("No stored credential needs %s any more: remove it from certs/.env")
                    .formatted(CredentialCipher.PREVIOUS_SECRET_VAR));
        }
        if (pass.unreadable() > 0) {
            EventLogger.warn("system", ("%d stored credential(s) did not decrypt under PLAY_SECRET and read as missing; "
                    + "they are left as they are for the secret they were saved under").formatted(pass.unreadable()));
            OperatorAlerts.onUnreadableCredentials(pass.unreadable());
        }
    }
}
