import com.google.gson.JsonParser;
import jobs.BindingCredentialColumnsJob;
import jobs.CredentialEncryptionJob;
import models.BindingCredential;
import models.JsonCredential;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.OperatorAlerts;
import utils.CredentialCipher;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JCLAW-1455: the boot pass over every credential store, on a throwaway database holding each store's table as the
 * migrated schema has it, since the pass rewrites whole tables and the shared one is other classes' too.
 */
class CredentialEncryptionJobTest extends UnitTest {

    static final String OLD = "fake-secret-before-the-rotation-0001";
    static final String NEW = "fake-secret-after-the-rotation-00002";
    private static final CredentialCipher CIPHER = CredentialCipher.of(OLD, null);

    private static final String MCP_JSON = """
            {"url":"http://127.0.0.1:1/mcp","headers":{"Authorization":"Bearer fake-mcp-header"}}""";
    private static final String MCP_STDIO_JSON = """
            {"command":"npx","args":["fake-server"],"env":{"GITHUB_TOKEN":"ghp-fake-env-token","NODE_ENV":"production"}}""";
    private static final String CHANNEL_JSON = """
            {"phoneNumberId":"1455","accessToken":"EAA-fake-app-wide","appSecret":"fake-app-wide-secret"}""";

    /** The credentials {@link #seed} writes, by where they sit. */
    private static final Map<String, String> SEEDED = new LinkedHashMap<>();

    static {
        SEEDED.put("config:provider.fake1455.apiKey", "sk-fake-provider-key");
        SEEDED.put("slack_binding.bot_token", "xoxb-fake-1455");
        SEEDED.put("slack_binding.signing_secret", "fake-signing-1455");
        SEEDED.put("slack_binding.app_token", "xapp-fake-1455");
        SEEDED.put("telegram_binding.bot_token", "1455:fake-bot");
        SEEDED.put("telegram_binding.webhook_secret", "fake-webhook-1455");
        SEEDED.put("whatsapp_binding.access_token", "EAA-fake-1455");
        SEEDED.put("whatsapp_binding.app_secret", "fake-app-secret-1455");
        SEEDED.put("whatsapp_binding.verify_token", "fake-verify-1455");
        SEEDED.put("mcp:1:headers.Authorization", "Bearer fake-mcp-header");
        SEEDED.put("mcp:2:env.GITHUB_TOKEN", "ghp-fake-env-token");
        SEEDED.put("channel:accessToken", "EAA-fake-app-wide");
        SEEDED.put("channel:appSecret", "fake-app-wide-secret");
    }

    /** Every store's table, empty, with the columns the pass reads: the binding ones as the column job leaves them. */
    static Connection throwaway() throws SQLException {
        var conn = DriverManager.getConnection("jdbc:h2:mem:credential-pass-" + System.nanoTime() + ";MODE=MYSQL");
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE config (id BIGINT PRIMARY KEY, config_key VARCHAR(255) NOT NULL, config_value TEXT)");
            s.execute("CREATE TABLE slack_binding (id BIGINT PRIMARY KEY, app_token TEXT, bot_token TEXT NOT NULL, "
                    + "signing_secret TEXT NOT NULL, bot_token_lookup VARCHAR(64) UNIQUE)");
            s.execute("CREATE TABLE telegram_binding (id BIGINT PRIMARY KEY, bot_token TEXT NOT NULL, webhook_secret TEXT, "
                    + "bot_token_lookup VARCHAR(64) UNIQUE)");
            s.execute("CREATE TABLE whatsapp_binding (id BIGINT PRIMARY KEY, access_token TEXT, app_secret TEXT, "
                    + "verify_token TEXT, verify_token_lookup VARCHAR(64))");
            s.execute("CREATE TABLE mcp_server (id BIGINT PRIMARY KEY, config_json TEXT NOT NULL)");
            s.execute("CREATE TABLE channel_config (id BIGINT PRIMARY KEY, config_json TEXT NOT NULL)");
        }
        conn.commit();
        return conn;
    }

    /** Plaintext credentials in every store, as a database written before the cipher holds them, and values that are none. */
    private static Connection seed() throws SQLException {
        var conn = throwaway();
        try (Statement s = conn.createStatement()) {
            s.execute("INSERT INTO config VALUES (1, 'provider.fake1455.apiKey', 'sk-fake-provider-key'), "
                    + "(2, 'provider.fake1455.baseUrl', 'http://127.0.0.1:1/v1'), (3, 'search.fake1455.apiKey', ''), "
                    + "(4, 'auth.admin.passwordHash', 'pbkdf2-fake-hash')");
            s.execute("INSERT INTO slack_binding VALUES (1, 'xapp-fake-1455', 'xoxb-fake-1455', 'fake-signing-1455', NULL)");
            s.execute("INSERT INTO telegram_binding VALUES (1, '1455:fake-bot', 'fake-webhook-1455', NULL)");
            s.execute("INSERT INTO whatsapp_binding VALUES (1, 'EAA-fake-1455', 'fake-app-secret-1455', "
                    + "'fake-verify-1455', NULL), (2, NULL, NULL, NULL, NULL)");
            s.execute("INSERT INTO mcp_server VALUES (1, '" + MCP_JSON + "'), (2, '" + MCP_STDIO_JSON + "'), "
                    + "(3, '{\"command\":\"echo\"}')");
            s.execute("INSERT INTO channel_config VALUES (1, '" + CHANNEL_JSON + "')");
        }
        conn.commit();
        return conn;
    }

    @Test
    void aStartEncryptsEveryPlaintextCredentialInPlaceAndASecondChangesNothing() throws Exception {
        try (var conn = seed()) {
            var first = CredentialEncryptionJob.reseal(conn, CIPHER);
            conn.commit();
            assertEquals(SEEDED.size(), first.sealed(), "each seeded credential, and nothing else, is sealed");
            assertEquals(0, first.rekeyed());
            assertEquals(0, first.unreadable());
            assertEveryCredentialOpens(conn, CredentialCipher.of(OLD, null));
            assertEquals("http://127.0.0.1:1/v1", configValue(conn, 2), "a value that is no credential stays as it was");
            assertEquals("", configValue(conn, 3), "an empty credential holds nothing to seal");
            assertEquals("pbkdf2-fake-hash", configValue(conn, 4), "the admin password hash is not a third-party credential");
            var mcp = json(conn, "mcp_server", 2);
            assertTrue(mcp.contains("\"NODE_ENV\":\"production\"") && mcp.contains("\"command\":\"npx\""), mcp);
            assertEquals("{\"command\":\"echo\"}", json(conn, "mcp_server", 3));
            assertTrue(json(conn, "channel_config", 1).contains("\"phoneNumberId\":\"1455\""));

            var sealed = snapshot(conn);
            var second = CredentialEncryptionJob.reseal(conn, CIPHER);
            conn.commit();
            assertEquals(0, second.sealed() + second.rekeyed() + second.unreadable());
            assertEquals(sealed, snapshot(conn), "a second start writes nothing");
        }
    }

    @Test
    void aStartThatDiesPartwayLeavesAStateTheNextStartFinishesWithoutSealingTwice() throws Exception {
        try (var conn = seed()) {
            var before = snapshot(conn);
            CredentialEncryptionJob.reseal(conn, CIPHER);
            conn.rollback();
            assertEquals(before, snapshot(conn), "the pass commits once, so a start that dies before it writes nothing");

            // Some stores sealed and some not: the state an interrupted pass that committed as it went would leave.
            try (Statement s = conn.createStatement()) {
                s.execute("UPDATE config SET config_value = '"
                        + CIPHER.encrypt("config.provider.fake1455.apiKey", "sk-fake-provider-key") + "' WHERE id = 1");
                s.execute("UPDATE telegram_binding SET bot_token = '"
                        + CIPHER.encrypt("telegram_binding.bot_token", "1455:fake-bot") + "' WHERE id = 1");
                var halfSealed = MCP_JSON.replace("Bearer fake-mcp-header", CIPHER.encrypt(
                        JsonCredential.MCP_SERVER.field("headers.Authorization"), "Bearer fake-mcp-header"));
                s.execute("UPDATE mcp_server SET config_json = '" + halfSealed + "' WHERE id = 1");
            }
            conn.commit();

            var rerun = CredentialEncryptionJob.reseal(conn, CIPHER);
            conn.commit();
            assertEquals(SEEDED.size() - 3, rerun.sealed(), "only what the first start left plaintext is sealed");
            assertEveryCredentialOpens(conn, CredentialCipher.of(OLD, null));
        }
    }

    @Test
    void aRotationReEncryptsEveryCredentialUnderTheNewSecretAndReKeysTheLookups() throws Exception {
        try (var conn = seed()) {
            CredentialEncryptionJob.reseal(conn, CIPHER);
            BindingCredentialColumnsJob.refreshLookups(conn, CIPHER);
            conn.commit();

            var rotated = CredentialCipher.of(NEW, OLD);
            BindingCredentialColumnsJob.refreshLookups(conn, rotated);
            var pass = CredentialEncryptionJob.reseal(conn, rotated);
            conn.commit();
            assertEquals(SEEDED.size(), pass.rekeyed(), "every credential is sealed again under the new secret");
            assertEquals(0, pass.sealed() + pass.unreadable());

            var afterRemoval = CredentialCipher.of(NEW, null);
            assertEveryCredentialOpens(conn, afterRemoval);
            assertEquals(afterRemoval.lookup("xoxb-fake-1455"), column(conn, "SELECT bot_token_lookup FROM slack_binding"));
            assertEquals(afterRemoval.lookup("1455:fake-bot"), column(conn, "SELECT bot_token_lookup FROM telegram_binding"));
            assertEquals(afterRemoval.lookup("fake-verify-1455"),
                    column(conn, "SELECT verify_token_lookup FROM whatsapp_binding WHERE id = 1"));
            var again = CredentialEncryptionJob.reseal(conn, afterRemoval);
            assertEquals(0, again.sealed() + again.rekeyed() + again.unreadable(),
                    "nothing needs the previous secret once the pass has run");
        }
    }

    @Test
    void underTheWrongSecretEveryCredentialIsCountedAndLeftAndTheRightOneRecoversThem() throws Exception {
        try (var conn = seed()) {
            CredentialEncryptionJob.reseal(conn, CIPHER);
            conn.commit();
            var sealed = snapshot(conn);

            var wrong = CredentialEncryptionJob.reseal(conn, CredentialCipher.of(NEW, null));
            conn.commit();
            assertEquals(SEEDED.size(), wrong.unreadable(), "the count the operator alert reports");
            assertEquals(0, wrong.sealed() + wrong.rekeyed());
            assertEquals(sealed, snapshot(conn), "the stored ciphertext is left exactly as it was");

            var restored = CredentialEncryptionJob.reseal(conn, CIPHER);
            assertEquals(0, restored.sealed() + restored.rekeyed() + restored.unreadable());
            assertEveryCredentialOpens(conn, CIPHER);
        }
    }

    @Test
    void theAlertSaysHowManyCredentialsCouldNotBeRead() {
        var alert = OperatorAlerts.unreadableCredentialsAlert(13);
        assertTrue(alert.startsWith("JClaw alert: 13 stored credential(s) did not decrypt"), alert);
        assertTrue(alert.contains("certs/.env") && alert.contains("Settings"), alert);
    }

    /** Each seeded credential is stored sealed and opens, as the current secret's, to its value. */
    private static void assertEveryCredentialOpens(Connection conn, CredentialCipher cipher) throws SQLException {
        var stored = new LinkedHashMap<String, String>();
        stored.put("config:provider.fake1455.apiKey", configValue(conn, 1));
        for (var credential : BindingCredential.values()) {
            stored.put(credential.field(), column(conn, "SELECT " + credential.column + " FROM " + credential.table
                    + " WHERE id = 1"));
        }
        var mcpHeader = JsonParser.parseString(json(conn, "mcp_server", 1)).getAsJsonObject();
        stored.put("mcp:1:headers.Authorization", mcpHeader.getAsJsonObject("headers").get("Authorization").getAsString());
        var mcpEnv = JsonParser.parseString(json(conn, "mcp_server", 2)).getAsJsonObject();
        stored.put("mcp:2:env.GITHUB_TOKEN", mcpEnv.getAsJsonObject("env").get("GITHUB_TOKEN").getAsString());
        var channel = JsonParser.parseString(json(conn, "channel_config", 1)).getAsJsonObject();
        stored.put("channel:accessToken", channel.get("accessToken").getAsString());
        stored.put("channel:appSecret", channel.get("appSecret").getAsString());

        assertEquals(SEEDED.keySet(), stored.keySet());
        for (var entry : SEEDED.entrySet()) {
            var value = stored.get(entry.getKey());
            var opened = cipher.open(fieldOf(entry.getKey()), value);
            assertEquals(CredentialCipher.Source.CURRENT, opened.source(), entry.getKey() + " is sealed once: " + value);
            assertEquals(entry.getValue(), opened.plaintext(), entry.getKey());
        }
    }

    private static String fieldOf(String seededKey) {
        if (seededKey.startsWith("config:")) return "config." + seededKey.substring("config:".length());
        if (seededKey.startsWith("mcp:")) return JsonCredential.MCP_SERVER.field(seededKey.substring("mcp:1:".length()));
        if (seededKey.startsWith("channel:")) {
            return JsonCredential.CHANNEL_CONFIG.field(seededKey.substring("channel:".length()));
        }
        return seededKey;
    }

    /** Every column of every row, in a fixed order. */
    private static String snapshot(Connection conn) throws SQLException {
        var out = new StringBuilder();
        for (var table : new String[] {"config", "slack_binding", "telegram_binding", "whatsapp_binding", "mcp_server",
                "channel_config"}) {
            try (Statement s = conn.createStatement(); var rows = s.executeQuery("SELECT * FROM " + table + " ORDER BY id")) {
                int columns = rows.getMetaData().getColumnCount();
                while (rows.next()) {
                    out.append(table);
                    for (int i = 1; i <= columns; i++) out.append('|').append(rows.getString(i));
                    out.append('\n');
                }
            }
        }
        return out.toString();
    }

    private static String configValue(Connection conn, long id) throws SQLException {
        return column(conn, "SELECT config_value FROM config WHERE id = " + id);
    }

    private static String json(Connection conn, String table, long id) throws SQLException {
        return column(conn, "SELECT config_json FROM " + table + " WHERE id = " + id);
    }

    private static String column(Connection conn, String query) throws SQLException {
        try (Statement s = conn.createStatement(); var rows = s.executeQuery(query)) {
            assertTrue(rows.next(), query);
            return rows.getString(1);
        }
    }
}
