import models.McpServer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.McpServerService;

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
        assertFalse(authz.contains("secrettoken"),
                "raw Authorization secret must not leak: " + authz);
        assertEquals("Bear****", authz);
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
        assertFalse(token.contains("secretvalue"),
                "raw env secret must not leak: " + token);
        assertEquals("ghp_****", token);
    }
}
