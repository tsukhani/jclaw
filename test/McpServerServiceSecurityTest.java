import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.McpServer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.McpServerService;

import java.util.List;
import java.util.Map;

/**
 * Security-facing contract for {@link McpServerService} (JCLAW-778 + JCLAW-780).
 *
 * <p>Both surfaces are agent-reachable: {@code validate} screens an
 * agent-settable HTTP endpoint against the SSRF guard, and {@link
 * McpServerService.View} is the serialization seam an agent reads MCP config
 * through — its {@code env} and {@code headers} maps must never carry raw
 * secrets. Pure in-memory rows; no DB or node fixture needed.
 */
class McpServerServiceSecurityTest extends UnitTest {

    // ── JCLAW-778: validate screens the agent-settable url ──

    @Test
    void validateRejectsMetadataHttpUrl() {
        var row = new McpServer();
        row.name = "evil";
        row.transport = McpServer.Transport.HTTP;
        row.configJson = "{\"url\":\"http://169.254.169.254/mcp\"}";
        var ex = assertThrows(IllegalArgumentException.class,
                () -> McpServerService.validate(row));
        assertTrue(ex.getMessage().contains("SSRF guard"),
                "metadata url must be rejected by the guard: " + ex.getMessage());
    }

    @Test
    void validateRejectsIpv6LinkLocalHttpUrl() {
        var row = new McpServer();
        row.name = "evil6";
        row.transport = McpServer.Transport.HTTP;
        row.configJson = "{\"url\":\"http://[fe80::1]/mcp\"}";
        assertThrows(IllegalArgumentException.class,
                () -> McpServerService.validate(row));
    }

    @Test
    void validateAllowsLoopbackHttpUrl() {
        // Local MCP servers on loopback must still validate.
        var row = new McpServer();
        row.name = "local";
        row.transport = McpServer.Transport.HTTP;
        row.configJson = "{\"url\":\"http://127.0.0.1:8080/mcp\"}";
        McpServerService.validate(row); // must not throw
    }

    // ── JCLAW-780: View.of masks secret values at the serialization seam ──

    @Test
    void viewMasksHeaderSecretValues() {
        var row = new McpServer();
        row.id = 1L;
        row.name = "mask-hdr-test";
        row.transport = McpServer.Transport.HTTP;
        row.configJson = "{\"url\":\"http://127.0.0.1:9/mcp\","
                + "\"headers\":{\"Authorization\":\"Bearer secrettoken123\"}}";
        var view = McpServerService.View.of(row);
        var authz = view.headers().get("Authorization");
        assertNotNull(authz, "header key must survive masking");
        assertEquals("****", authz);
    }

    @Test
    void viewMasksEnvSecretValues() {
        var row = new McpServer();
        row.id = 2L;
        row.name = "mask-env-test";
        row.transport = McpServer.Transport.STDIO;
        row.configJson = "{\"command\":\"node\",\"env\":{\"GITHUB_TOKEN\":\"ghp_secretvalue\"}}";
        var view = McpServerService.View.of(row);
        var token = view.env().get("GITHUB_TOKEN");
        assertNotNull(token, "env key must survive masking");
        assertEquals("****", token);
    }

    // ── JF-291: a mask stands for the secret saved for the stored URL's origin ──

    private static final McpServerService.TransportConfig SAVED_REMOTE = new McpServerService.TransportConfig(
            null, List.of(), Map.of(), "https://mcp.example.test/v1", Map.of("Authorization", "Bearer real-token"));

    private static JsonObject body(String json) {
        return JsonParser.parseString(json).getAsJsonObject();
    }

    @Test
    void aMaskSentWithAUrlOnTheSameOriginIsSwappedForTheSavedSecret() {
        for (var json : List.of(
                "{\"headers\":{\"Authorization\":\"****\"}}",
                "{\"url\":\"https://mcp.example.test/v2\",\"headers\":{\"Authorization\":\"****\"}}")) {
            var body = body(json);
            assertNull(McpServerService.restoreMaskedSecrets(body, SAVED_REMOTE), json);
            assertEquals("Bearer real-token", body.getAsJsonObject("headers").get("Authorization").getAsString(), json);
        }
    }

    @Test
    void aMaskSentWithAUrlOnAnotherOriginIsRefusedAndNeverSwapped() {
        for (var url : List.of("https://other.example.test/v1", "https://mcp.example.test:8443/v1",
                "http://mcp.example.test/v1", "not a url")) {
            var body = body("{\"url\":\"" + url + "\",\"headers\":{\"Authorization\":\"****\"}}");
            assertEquals("Authorization was saved for the server's previous address and is not sent to a new one: "
                    + "enter the value again.", McpServerService.restoreMaskedSecrets(body, SAVED_REMOTE), url);
            assertEquals("****", body.getAsJsonObject("headers").get("Authorization").getAsString(), url);
        }
    }

    @Test
    void aValueSentWithAUrlOnAnotherOriginIsNotRefused() {
        var body = body("{\"url\":\"https://other.example.test/v1\",\"headers\":{\"Authorization\":\"Bearer for-other\"}}");
        assertNull(McpServerService.restoreMaskedSecrets(body, SAVED_REMOTE));
        assertEquals("Bearer for-other", body.getAsJsonObject("headers").get("Authorization").getAsString());
    }

    @Test
    void aMaskThatMatchesNoSavedHeaderIsRefusedAsBeforeWhateverTheUrl() {
        for (var url : List.of("https://mcp.example.test/v1", "https://other.example.test/v1")) {
            var body = body("{\"url\":\"" + url + "\",\"headers\":{\"X-Api-Key\":\"abcd****\"}}");
            assertEquals("X-Api-Key holds a saved secret's mask rather than a value: enter the value again.",
                    McpServerService.restoreMaskedSecrets(body, SAVED_REMOTE), url);
        }
    }

    // ── JF-294: a mask shows none of the secret, and still stands for it on a save ──

    @Test
    void aShortSecretIsMaskedLikeAnyOtherAndItsMaskIsSwappedBack() {
        var row = new McpServer();
        row.id = 3L;
        row.name = "mask-short-test";
        row.transport = McpServer.Transport.STDIO;
        row.configJson = "{\"command\":\"node\",\"env\":{\"API_KEY\":\"abc\",\"NODE_ENV\":\"dev\",\"EMPTY_TOKEN\":\"\"}}";

        assertEquals(Map.of("API_KEY", "****", "NODE_ENV", "dev", "EMPTY_TOKEN", ""), McpServerService.View.of(row).env());

        var body = body("{\"env\":{\"API_KEY\":\"****\",\"NODE_ENV\":\"****\"}}");
        assertNull(McpServerService.restoreMaskedSecrets(body,
                McpServerService.explodeConfigJson(row.transport, row.configJson)));
        assertEquals("{\"API_KEY\":\"abc\",\"NODE_ENV\":\"****\"}", body.getAsJsonObject("env").toString(),
                "only a name that is masked on read has its mask swapped");
    }

    @Test
    void aMaskReadWhileItStillShowedFourCharactersIsRefusedNotSaved() {
        var body = body("{\"headers\":{\"Authorization\":\"Bear****\"}}");
        assertEquals("Authorization holds a saved secret's mask rather than a value: enter the value again.",
                McpServerService.restoreMaskedSecrets(body, SAVED_REMOTE));
        assertEquals("Bear****", body.getAsJsonObject("headers").get("Authorization").getAsString());
    }
}
