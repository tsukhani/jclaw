import jobs.BindingCredentialColumnsJob;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.CredentialCipher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * JCLAW-1455: the binding tables as a database created before the credential cipher holds them, on a throwaway
 * database because the shared one is built from the current entities. The lookup columns are added the way
 * Hibernate's schema update adds them ahead of the job.
 */
class BindingCredentialColumnsJobTest extends UnitTest {

    private static final CredentialCipher CIPHER = CredentialCipher.of("fake-secret-for-the-column-job-0001", null);

    private static Connection throwaway() throws SQLException {
        var conn = DriverManager.getConnection("jdbc:h2:mem:binding-credential-" + System.nanoTime() + ";MODE=MYSQL");
        try (Statement s = conn.createStatement()) {
            // The live schema's shapes and constraints (2026-10-15).
            s.execute("CREATE TABLE slack_binding (id BIGINT PRIMARY KEY, app_token VARCHAR(255), "
                    + "bot_token VARCHAR(255) NOT NULL, signing_secret VARCHAR(255) NOT NULL)");
            s.execute("ALTER TABLE slack_binding ADD CONSTRAINT UK22IUGW2IFB73J1O3P9VOFIDLK UNIQUE NULLS DISTINCT (bot_token)");
            s.execute("CREATE TABLE telegram_binding (id BIGINT PRIMARY KEY, bot_token VARCHAR(255) NOT NULL, "
                    + "webhook_secret VARCHAR(255))");
            s.execute("ALTER TABLE telegram_binding ADD CONSTRAINT UKNTN79XAXIJ42T5WDOL1Q9DUQ1 UNIQUE NULLS DISTINCT (bot_token)");
            s.execute("CREATE TABLE whatsapp_binding (id BIGINT PRIMARY KEY, access_token VARCHAR(255), "
                    + "app_secret VARCHAR(255), verify_token VARCHAR(255))");
            s.execute("INSERT INTO slack_binding VALUES (1, 'xapp-fake', 'xoxb-fake-1', 'fake-signing')");
            s.execute("INSERT INTO telegram_binding VALUES (1, '1:fake-a', 'fake-webhook'), (2, '2:fake-b', NULL)");
            s.execute("INSERT INTO whatsapp_binding VALUES (1, 'EAA-fake', 'fake-app-secret', 'fake-verify'), "
                    + "(2, NULL, NULL, NULL)");
            s.execute("ALTER TABLE slack_binding ADD COLUMN bot_token_lookup VARCHAR(64) UNIQUE");
            s.execute("ALTER TABLE telegram_binding ADD COLUMN bot_token_lookup VARCHAR(64) UNIQUE");
            s.execute("ALTER TABLE whatsapp_binding ADD COLUMN verify_token_lookup VARCHAR(64)");
        }
        return conn;
    }

    @Test
    void theColumnsWidenKeepingTheirRowsAndNotNullAndTheBotTokenConstraintGoes() throws Exception {
        try (var conn = throwaway(); Statement s = conn.createStatement()) {
            var tooLong = "t".repeat(313);
            assertThrows(SQLException.class,
                    () -> s.execute("UPDATE whatsapp_binding SET access_token = '" + tooLong + "' WHERE id = 1"),
                    "the fixture must refuse what the live column refused, or this test proves nothing");

            assertEquals(10, BindingCredentialColumnsJob.migrate(conn), "two constraints dropped, eight columns widened");

            var sealed = CIPHER.encrypt("whatsapp_binding.access_token", "T".repeat(512));
            s.execute("UPDATE whatsapp_binding SET access_token = '" + sealed + "' WHERE id = 1");
            s.execute("INSERT INTO telegram_binding (id, bot_token) VALUES (3, '1:fake-a')");
            assertThrows(SQLException.class, () -> s.execute("INSERT INTO slack_binding (id, bot_token) VALUES (2, 'x')"),
                    "signing_secret keeps its NOT NULL");
            try (var rows = s.executeQuery("SELECT bot_token, webhook_secret FROM telegram_binding WHERE id = 1")) {
                assertTrue(rows.next());
                assertEquals("1:fake-a", rows.getString(1));
                assertEquals("fake-webhook", rows.getString(2));
            }

            assertEquals(0, BindingCredentialColumnsJob.migrate(conn), "a second start changes nothing");
        }
    }

    @Test
    void theUnmappedCredentialColumnsAnOlderSchemaLeftAreWidenedWhereTheyExist() throws Exception {
        try (var conn = throwaway(); Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE slack_binding ADD COLUMN user_token VARCHAR(255)");
            s.execute("ALTER TABLE telegram_binding ADD COLUMN webhook_url VARCHAR(255)");
            assertEquals(12, BindingCredentialColumnsJob.migrate(conn), "the ten changes, and both unmapped columns widened");
            var sealed = CIPHER.encrypt("telegram_binding.webhook_url", "https://fake.invalid/" + "w".repeat(200));
            s.execute("UPDATE telegram_binding SET webhook_url = '" + sealed + "' WHERE id = 1");
            assertEquals(0, BindingCredentialColumnsJob.migrate(conn));
        }
    }

    @Test
    void theLookupsAreSetUnderTheCurrentSecretAndARotationReKeysThem() throws Exception {
        try (var conn = throwaway(); Statement s = conn.createStatement()) {
            BindingCredentialColumnsJob.migrate(conn);
            var sealedElsewhere = CredentialCipher.of("fake-secret-nobody-holds-any-more-01", null)
                    .encrypt("telegram_binding.bot_token", "3:fake-lost");
            s.execute("INSERT INTO telegram_binding (id, bot_token, bot_token_lookup) VALUES (3, '" + sealedElsewhere
                    + "', 'a-lookup-the-right-secret-would-confirm')");
            s.execute("UPDATE whatsapp_binding SET verify_token_lookup = 'stale' WHERE id = 2");

            var first = BindingCredentialColumnsJob.refreshLookups(conn, CIPHER);
            assertEquals(new BindingCredentialColumnsJob.Lookups(5, 1, List.of()), first,
                    "four tokens get a lookup and a cleared token loses its stale one; the one that does not open is left");
            assertEquals(CIPHER.lookup("xoxb-fake-1"), lookup(s, "slack_binding", "bot_token_lookup", 1));
            assertEquals(CIPHER.lookup("1:fake-a"), lookup(s, "telegram_binding", "bot_token_lookup", 1));
            assertEquals(CIPHER.lookup("2:fake-b"), lookup(s, "telegram_binding", "bot_token_lookup", 2));
            assertEquals(CIPHER.lookup("fake-verify"), lookup(s, "whatsapp_binding", "verify_token_lookup", 1));
            assertNull(lookup(s, "whatsapp_binding", "verify_token_lookup", 2));
            assertEquals("a-lookup-the-right-secret-would-confirm", lookup(s, "telegram_binding", "bot_token_lookup", 3));
            assertEquals(new BindingCredentialColumnsJob.Lookups(0, 1, List.of()),
                    BindingCredentialColumnsJob.refreshLookups(conn, CIPHER), "a second start changes nothing");

            // A rotation: the new secret is current and the old one is PLAY_SECRET_PREVIOUS.
            var sealedBefore = CIPHER.encrypt("telegram_binding.bot_token", "2:fake-b");
            s.execute("UPDATE telegram_binding SET bot_token = '" + sealedBefore + "' WHERE id = 2");
            var rotated = CredentialCipher.of("fake-secret-after-the-rotation-0002", "fake-secret-for-the-column-job-0001");
            assertEquals(new BindingCredentialColumnsJob.Lookups(4, 1, List.of()),
                    BindingCredentialColumnsJob.refreshLookups(conn, rotated));
            assertEquals(rotated.lookup("2:fake-b"), lookup(s, "telegram_binding", "bot_token_lookup", 2),
                    "a token only the previous secret opens is re-keyed under the current one");
            assertEquals(rotated.lookup("fake-verify"), lookup(s, "whatsapp_binding", "verify_token_lookup", 1));
            try (var rows = s.executeQuery("SELECT bot_token FROM telegram_binding WHERE id = 3")) {
                assertTrue(rows.next());
                assertEquals(sealedElsewhere, rows.getString(1), "the job never rewrites a token");
            }
        }
    }

    @Test
    void aTokenSavedAgainOnASecondBindingWhileItCouldNotBeDecryptedLeavesItsLookupRatherThanFailingTheStart()
            throws Exception {
        try (var conn = throwaway(); Statement s = conn.createStatement()) {
            BindingCredentialColumnsJob.migrate(conn);
            // Restored under another secret, the operator bound the same bot again; then put the old secret back as previous.
            var restoredUnder = CredentialCipher.of("fake-secret-the-restore-came-up-with-1", "fake-secret-for-the-column-job-0001");
            s.execute("UPDATE telegram_binding SET bot_token = '" + CIPHER.encrypt("telegram_binding.bot_token", "1:fake-a")
                    + "', bot_token_lookup = '" + CIPHER.lookup("1:fake-a") + "' WHERE id = 1");
            s.execute("INSERT INTO telegram_binding (id, bot_token, bot_token_lookup) VALUES (3, '"
                    + restoredUnder.encrypt("telegram_binding.bot_token", "1:fake-a") + "', '"
                    + restoredUnder.lookup("1:fake-a") + "')");
            conn.setAutoCommit(false);

            assertEquals(new BindingCredentialColumnsJob.Lookups(3, 0, List.of("telegram_binding 1")),
                    BindingCredentialColumnsJob.refreshLookups(conn, restoredUnder));
            conn.commit();
            assertEquals(CIPHER.lookup("1:fake-a"), lookup(s, "telegram_binding", "bot_token_lookup", 1));
            assertEquals(restoredUnder.lookup("2:fake-b"), lookup(s, "telegram_binding", "bot_token_lookup", 2),
                    "the refused row costs only its own update");
        }
    }

    @Test
    void aDatabaseWithoutTheTablesIsANoOp() throws Exception {
        try (var conn = DriverManager.getConnection("jdbc:h2:mem:binding-credential-absent-" + System.nanoTime()
                + ";MODE=MYSQL")) {
            assertEquals(0, BindingCredentialColumnsJob.migrate(conn));
        }
    }

    private static String lookup(Statement s, String table, String column, long id) throws SQLException {
        try (var rows = s.executeQuery("SELECT " + column + " FROM " + table + " WHERE id = " + id)) {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }
}
