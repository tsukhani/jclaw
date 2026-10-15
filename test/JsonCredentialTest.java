import channels.WhatsAppChannel;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mcp.McpConnectionManager;
import models.ChannelConfig;
import models.JsonCredential;
import models.McpServer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.McpServerService;
import utils.CredentialCipher;
import utils.GsonHolder;

import java.lang.reflect.InvocationTargetException;
import java.util.List;

/**
 * JCLAW-1455: the secret values of the MCP server and channel config JSON columns, sealed one by one under their own
 * field names, and what a reader sees of one that will not decrypt. Rows are built in memory: nothing here is stored.
 */
class JsonCredentialTest extends UnitTest {

    private static final CredentialCipher CIPHER = CredentialCipher.of("fake-secret-for-the-json-columns-01", null);
    private static final CredentialCipher ELSEWHERE = CredentialCipher.of("fake-secret-of-another-installation", null);

    private static final String HTTP_JSON =
            "{\"url\":\"http://127.0.0.1:1/mcp\",\"headers\":{\"Authorization\":\"Bearer fake-header\",\"X-Trace\":\"on\"}}";
    private static final String STDIO_JSON = "{\"command\":\"npx\",\"args\":[\"mcp-server-x\"],"
            + "\"env\":{\"GITHUB_TOKEN\":\"ghp-fake-token\",\"NODE_ENV\":\"production\"}}";

    @Test
    void eachSecretValueIsSealedUnderItsOwnNameAndTheRestOfTheJsonIsLeft() {
        var http = JsonParser.parseString(JsonCredential.MCP_SERVER.sealed(HTTP_JSON, CIPHER)).getAsJsonObject();
        var headers = http.getAsJsonObject("headers");
        assertEquals("http://127.0.0.1:1/mcp", http.get("url").getAsString());
        assertEquals("Bearer fake-header", CIPHER.open(JsonCredential.MCP_SERVER.field("headers.Authorization"),
                headers.get("Authorization").getAsString()).plaintext());
        assertTrue(CredentialCipher.isEncrypted(headers.get("X-Trace").getAsString()), "every header value is secret");

        var stdio = JsonParser.parseString(JsonCredential.MCP_SERVER.sealed(STDIO_JSON, CIPHER)).getAsJsonObject();
        assertTrue(CredentialCipher.isEncrypted(stdio.getAsJsonObject("env").get("GITHUB_TOKEN").getAsString()));
        assertEquals("production", stdio.getAsJsonObject("env").get("NODE_ENV").getAsString(),
                "an env value with no credential name is not");
        assertEquals("npx", stdio.get("command").getAsString());

        var channel = JsonParser.parseString(JsonCredential.CHANNEL_CONFIG.sealed(
                "{\"phoneNumberId\":\"1455\",\"accessToken\":\"EAA-fake\",\"enabled\":true}", CIPHER)).getAsJsonObject();
        assertTrue(CredentialCipher.isEncrypted(channel.get("accessToken").getAsString()));
        assertEquals("1455", channel.get("phoneNumberId").getAsString());
        assertTrue(channel.get("enabled").getAsBoolean());
    }

    @Test
    void aSealedColumnReadsBackAsTheJsonThatWasWritten() {
        for (var json : new String[] {HTTP_JSON, STDIO_JSON}) {
            var stored = JsonCredential.MCP_SERVER.sealed(json, CIPHER);
            assertFalse(stored.contains("fake-"), stored);
            assertEquals(json, JsonCredential.MCP_SERVER.opened(stored, CIPHER));
            assertEquals(List.of(), JsonCredential.MCP_SERVER.unreadable(JsonCredential.MCP_SERVER.opened(stored, CIPHER)));
        }
        assertSame(STDIO_JSON, JsonCredential.MCP_SERVER.opened(STDIO_JSON, CIPHER),
                "a row with no ciphertext is read as stored, unparsed");
        assertNull(JsonCredential.MCP_SERVER.sealed(null, CIPHER));
        assertEquals("not json", JsonCredential.MCP_SERVER.sealed("not json", CIPHER));
    }

    @Test
    void aCiphertextMovedToAnotherValueDoesNotOpen() {
        var stored = JsonParser.parseString(JsonCredential.MCP_SERVER.sealed(HTTP_JSON, CIPHER)).getAsJsonObject();
        var headers = stored.getAsJsonObject("headers");
        headers.addProperty("X-Trace", headers.get("Authorization").getAsString());
        var channel = "{\"accessToken\":\"" + headers.get("Authorization").getAsString() + "\"}";

        var read = JsonCredential.MCP_SERVER.opened(stored.toString(), CIPHER);
        assertEquals("Bearer fake-header", header(read, "Authorization"));
        assertEquals(List.of("headers.X-Trace"), JsonCredential.MCP_SERVER.unreadable(read));
        assertEquals(List.of("accessToken"),
                JsonCredential.CHANNEL_CONFIG.unreadable(JsonCredential.CHANNEL_CONFIG.opened(channel, CIPHER)),
                "nor in another column");
    }

    @Test
    void aValueThatWillNotDecryptKeepsItsCiphertextThroughARead() {
        var lost = ELSEWHERE.encrypt(JsonCredential.MCP_SERVER.field("headers.Authorization"), "Bearer fake-lost");
        var stored = "{\"url\":\"http://127.0.0.1:1/mcp\",\"headers\":{\"Authorization\":\"" + lost + "\"}}";

        var read = JsonCredential.MCP_SERVER.opened(stored, CIPHER);
        assertEquals(lost, header(read, "Authorization"));
        assertEquals("headers.Authorization does not decrypt under this installation's secret: enter it again in Settings",
                JsonCredential.MCP_SERVER.unreadableReason(read));
        assertEquals(lost, header(JsonCredential.MCP_SERVER.sealed(read, CIPHER), "Authorization"),
                "a save writes it back as it came, for the right secret to recover");
        assertEquals("Bearer fake-lost", header(JsonCredential.MCP_SERVER.opened(stored, ELSEWHERE), "Authorization"));
    }

    @Test
    void aCiphertextTypedInAsAValueIsStoredAsItsTextAndNeverOpened() {
        // A writer holding a ciphertext copied from a raw database must not have JClaw decrypt it into a header.
        var copied = CIPHER.encrypt(JsonCredential.MCP_SERVER.field("headers.Authorization"), "Bearer fake-victim");
        var written = "{\"url\":\"http://127.0.0.1:1/mcp\",\"headers\":{\"Authorization\":\"" + copied + "\"}}";

        var read = JsonCredential.MCP_SERVER.opened(JsonCredential.MCP_SERVER.sealed(written, CIPHER), CIPHER);
        assertEquals(copied, header(read, "Authorization"));
        assertEquals(List.of("headers.Authorization"), JsonCredential.MCP_SERVER.unreadable(read),
                "and a reader treats it as a value that did not decrypt");
    }

    @Test
    void anMcpServerWhoseCredentialWillNotDecryptReportsItInsteadOfConnecting() throws Exception {
        var lost = ELSEWHERE.encrypt(JsonCredential.MCP_SERVER.field("headers.Authorization"), "Bearer fake-lost");
        var row = new McpServer();
        row.name = "jclaw1455-unreadable";
        row.transport = McpServer.Transport.HTTP;
        row.configJson = "{\"url\":\"http://127.0.0.1:1/mcp\",\"headers\":{\"Authorization\":\"" + lost + "\"}}";

        var test = McpServerService.testConnection(row);
        assertFalse(test.success());
        assertTrue(test.message().contains("headers.Authorization does not decrypt"), test.message());

        var buildTransport = McpConnectionManager.class.getDeclaredMethod("buildTransport", McpServer.class);
        buildTransport.setAccessible(true);
        var thrown = assertThrows(InvocationTargetException.class, () -> buildTransport.invoke(null, row));
        assertInstanceOf(IllegalStateException.class, thrown.getCause());
        assertTrue(thrown.getCause().getMessage().contains("headers.Authorization does not decrypt"),
                "the connect fails with the reason its status shows: " + thrown.getCause().getMessage());
    }

    @Test
    void anMcpEditKeepsTheCiphertextOfAHeaderThatWillNotDecrypt() {
        var lost = ELSEWHERE.encrypt(JsonCredential.MCP_SERVER.field("headers.Authorization"), "Bearer fake-lost");
        var row = new McpServer();
        row.name = "jclaw1455-edit";
        row.transport = McpServer.Transport.HTTP;
        row.configJson = "{\"url\":\"http://127.0.0.1:1/mcp\",\"headers\":{\"Authorization\":\"" + lost + "\"}}";

        // The form saves back what the read showed it, with only the URL changed.
        var body = JsonParser.parseString(GsonHolder.GSON.toJson(McpServerService.View.of(row))).getAsJsonObject();
        assertEquals("enc:****", body.getAsJsonObject("headers").get("Authorization").getAsString());
        body.addProperty("url", "http://127.0.0.1:2/mcp");
        assertNull(McpServerService.restoreMaskedSecrets(body,
                McpServerService.explodeConfigJson(row.transport, row.configJson)));
        var saved = JsonCredential.MCP_SERVER.sealed(McpServerService.composeConfigJson(row.transport, body), CIPHER);

        assertEquals(lost, header(saved, "Authorization"));
        assertEquals("http://127.0.0.1:2/mcp", JsonParser.parseString(saved).getAsJsonObject().get("url").getAsString());
    }

    @Test
    void theAppWideWhatsAppConfigReportsAnAccessTokenThatWillNotDecryptAsMissing() {
        var lostToken = ELSEWHERE.encrypt(JsonCredential.CHANNEL_CONFIG.field("accessToken"), "EAA-fake-lost");
        var lostSecret = ELSEWHERE.encrypt(JsonCredential.CHANNEL_CONFIG.field("appSecret"), "fake-lost-secret");

        assertNull(WhatsAppChannel.WhatsAppConfig.from(whatsApp(
                "{\"phoneNumberId\":\"1455\",\"accessToken\":\"" + lostToken + "\"}")));
        var config = WhatsAppChannel.WhatsAppConfig.from(whatsApp(
                "{\"phoneNumberId\":\"1455\",\"accessToken\":\"EAA-fake\",\"appSecret\":\"" + lostSecret + "\"}"));
        assertNotNull(config);
        assertEquals("EAA-fake", config.accessToken());
        assertNull(config.appSecret(), "a secret that will not decrypt reads as unset");
    }

    private static ChannelConfig whatsApp(String configJson) {
        var cc = new ChannelConfig();
        cc.channelType = "whatsapp";
        cc.enabled = true;
        cc.configJson = configJson;
        return cc;
    }

    private static String header(String json, String name) {
        JsonObject parsed = JsonParser.parseString(json).getAsJsonObject();
        return parsed.getAsJsonObject("headers").get(name).getAsString();
    }
}
