import jobs.CredentialEncryptionJob;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.UnitTest;
import services.database.CredentialCopies;
import services.database.H2Maintenance;
import utils.CredentialCipher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipFile;

/**
 * JCLAW-1455 AC7: after the migration and a compaction, the raw bytes of the data file hold no seeded credential, while
 * a seeded value that is no credential is still found. The control is what makes the scan mean anything: a compressed
 * or unreadable file would hide both, and H2's own {@code SHUTDOWN COMPACT} writes a compressed one.
 */
class CredentialAtRestScanTest extends UnitTest {

    private static final CredentialCipher CIPHER = CredentialCipher.of("fake-secret-for-the-ac7-scan-000001", null);
    // Repetitive on purpose: compression stores a repeat as a back-reference, so a compressed file cannot hold it whole.
    private static final String CONTROL = "ac7-control-" + "no-credential-".repeat(4);

    /** Where each fake credential sits, and the value: long and distinct, so no other byte run can match it. */
    private static final Map<String, String> SEEDED = new LinkedHashMap<>();

    static {
        SEEDED.put("config provider.fake1455.apiKey", "sk-ac7-fake-provider-key-1f6a9d");
        SEEDED.put("slack_binding.bot_token", "xoxb-ac7-fake-slack-bot-2b7e0c");
        SEEDED.put("slack_binding.signing_secret", "ac7-fake-slack-signing-3c8f1d");
        SEEDED.put("slack_binding.app_token", "xapp-ac7-fake-slack-app-4d902e");
        SEEDED.put("slack_binding.user_token", "xoxp-ac7-fake-slack-user-5ea13f");
        SEEDED.put("telegram_binding.bot_token", "1455:ac7-fake-telegram-bot-6fb240");
        SEEDED.put("telegram_binding.webhook_secret", "ac7-fake-telegram-webhook-70c351");
        SEEDED.put("telegram_binding.webhook_url", "https://ac7.invalid/hook/ac7-fake-url-secret-81d462");
        SEEDED.put("whatsapp_binding.access_token", "EAA-ac7-fake-whatsapp-access-92e573");
        SEEDED.put("whatsapp_binding.app_secret", "ac7-fake-whatsapp-app-secret-a3f684");
        SEEDED.put("whatsapp_binding.verify_token", "ac7-fake-whatsapp-verify-b40795");
        SEEDED.put("mcp_server headers.Authorization", "Bearer ac7-fake-mcp-header-c518a6");
        SEEDED.put("mcp_server env.GITHUB_TOKEN", "ghp-ac7-fake-mcp-env-d629b7");
        SEEDED.put("channel_config accessToken", "EAA-ac7-fake-app-wide-e73ac8");
    }

    @TempDir
    Path dir;

    private String url() {
        return "jdbc:h2:file:" + dir.toAbsolutePath().resolve(H2Maintenance.DB_NAME) + ";MODE=MYSQL";
    }

    private void seed() throws SQLException {
        try (var conn = CredentialEncryptionJobTest.throwaway(url()); var s = conn.createStatement()) {
            s.execute("ALTER TABLE slack_binding ADD COLUMN user_token VARCHAR(255)");
            s.execute("ALTER TABLE telegram_binding ADD COLUMN webhook_url VARCHAR(255)");
            s.execute("CREATE TABLE note (id BIGINT PRIMARY KEY, body TEXT)");
            s.execute("INSERT INTO config VALUES (1, 'provider.fake1455.apiKey', '" + v("config provider.fake1455.apiKey")
                    + "'), (2, 'app.ac7.note', '" + CONTROL + "')");
            s.execute("INSERT INTO slack_binding (id, app_token, bot_token, signing_secret, user_token) VALUES (1, '"
                    + v("slack_binding.app_token") + "', '" + v("slack_binding.bot_token") + "', '"
                    + v("slack_binding.signing_secret") + "', '" + v("slack_binding.user_token") + "')");
            s.execute("INSERT INTO telegram_binding (id, bot_token, webhook_secret, webhook_url) VALUES (1, '"
                    + v("telegram_binding.bot_token") + "', '" + v("telegram_binding.webhook_secret") + "', '"
                    + v("telegram_binding.webhook_url") + "')");
            s.execute("INSERT INTO whatsapp_binding (id, access_token, app_secret, verify_token) VALUES (1, '"
                    + v("whatsapp_binding.access_token") + "', '" + v("whatsapp_binding.app_secret") + "', '"
                    + v("whatsapp_binding.verify_token") + "')");
            s.execute("INSERT INTO mcp_server VALUES (1, '{\"url\":\"http://127.0.0.1:1/mcp\",\"headers\":{\"Authorization\":\""
                    + v("mcp_server headers.Authorization") + "\"}}'), (2, '{\"command\":\"npx\",\"env\":{\"GITHUB_TOKEN\":\""
                    + v("mcp_server env.GITHUB_TOKEN") + "\"}}')");
            s.execute("INSERT INTO channel_config VALUES (1, '{\"phoneNumberId\":\"1455\",\"accessToken\":\""
                    + v("channel_config accessToken") + "\"}')");
            // Enough other rows that the file runs to many chunks, as a live one does.
            try (var insert = conn.prepareStatement("INSERT INTO note VALUES (?, ?)")) {
                for (int i = 0; i < 2000; i++) {
                    insert.setLong(1, i);
                    insert.setString(2, ("note " + i + " ").repeat(30));
                    insert.executeUpdate();
                }
            }
            conn.commit();
        }
    }

    private static String v(String where) {
        return SEEDED.get(where);
    }

    private static boolean holds(byte[] bytes, String value) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains(value);
    }

    private static Map<String, Boolean> found(byte[] bytes) {
        var out = new LinkedHashMap<String, Boolean>();
        SEEDED.forEach((where, value) -> out.put(where, holds(bytes, value)));
        return out;
    }

    private byte[] dataFile() throws Exception {
        return Files.readAllBytes(dir.resolve(H2Maintenance.DATA_FILE));
    }

    private static byte[] zippedDataFile(Path zip) throws Exception {
        try (var archive = new ZipFile(zip.toFile())) {
            return archive.getInputStream(archive.getEntry(H2Maintenance.DATA_FILE)).readAllBytes();
        }
    }

    @Test
    void afterTheMigrationAndACompactionTheFileHoldsNoSeededCredentialButStillHoldsTheControl() throws Exception {
        seed();
        var seeded = dataFile();
        assertTrue(found(seeded).values().stream().allMatch(b -> b), "the scan reads this file: " + found(seeded));
        assertTrue(holds(seeded, CONTROL));

        var backup = dir.resolve("after-the-migration.zip");
        try (var conn = DriverManager.getConnection(url())) {
            conn.setAutoCommit(false);
            var pass = CredentialEncryptionJob.reseal(conn, CIPHER);
            var unmapped = CredentialEncryptionJob.resealUnmapped(conn, CIPHER);
            conn.commit();
            assertEquals(SEEDED.size(), pass.sealed() + unmapped.sealed(), "every seeded credential is sealed");
            CredentialCopies.read(dir).afterPass(pass.sealed() + unmapped.sealed(), 0, Instant.now()).write(dir);
            H2Maintenance.backupOnline(conn, backup);
        }

        var migrated = dataFile();
        assertTrue(found(migrated).containsValue(true),
                "encrypting a row leaves its plaintext in the file's free pages, which is why compaction exists: "
                        + found(migrated));
        assertTrue(found(zippedDataFile(backup)).containsValue(true),
                "and a backup taken before the compaction copies those pages");
        assertEquals(CredentialCopies.OldCopies.PLAINTEXT, CredentialCopies.read(dir).due());

        var stamp = Instant.now().plus(1, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
        H2Maintenance.compact(dir, stamp.toString().replace("-", "").replace(":", ""));

        var compacted = dataFile();
        assertFalse(found(compacted).containsValue(true), "no seeded credential survives the compaction: " + found(compacted));
        assertTrue(holds(compacted, CONTROL), "while the value that is no credential is still found, so the scan read the file");

        var copies = CredentialCopies.read(dir);
        assertNull(copies.due(), "nothing is left for a compaction to remove");
        assertTrue(copies.holdsPlaintext(CredentialCopies.takenAt(backup)), "the backup taken before it stays marked");
        assertFalse(copies.holdsPlaintext(stamp.plusSeconds(1)), "one taken after it holds none");

        try (var conn = DriverManager.getConnection(url()); var s = conn.createStatement();
             var rows = s.executeQuery("SELECT config_value FROM config WHERE id = 1")) {
            assertTrue(rows.next());
            assertEquals(v("config provider.fake1455.apiKey"),
                    CIPHER.open("config.provider.fake1455.apiKey", rows.getString(1)).resolvedPlaintext(),
                    "the compacted file still opens every credential");
        }
    }
}
