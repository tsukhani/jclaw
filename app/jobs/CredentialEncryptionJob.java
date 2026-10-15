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
import utils.CredentialCipher;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.function.Function;

/**
 * The boot pass over every stored credential (JCLAW-1455): plaintext is sealed in place, a value only
 * {@value CredentialCipher#PREVIOUS_SECRET_VAR} opens is sealed again under the current secret, and a value neither
 * opens is counted and left as it is, for the right secret to recover. It commits once, so a start that dies partway
 * writes nothing, and a sealed value is never sealed again, so the next start finishes the job. The binding lookups
 * are {@link BindingCredentialColumnsJob}'s, which sets them under the current secret before this runs.
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
            pass = reseal(conn, cipher);
            conn.commit();
        } catch (SQLException e) {
            // Every credential still reads as it did: plaintext and the previous secret's ciphertext both open.
            EventLogger.error("system", "Encrypting the stored credentials failed; the next start tries again: "
                    + e.getMessage());
            return;
        }
        report(pass, cipher.hasPrevious());
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
