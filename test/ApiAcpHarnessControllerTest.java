import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.AcpHarnessProbe;

import java.util.List;

/**
 * ACP harness detection endpoint (Settings → Subagents). The probe shells out
 * to claude/pi/codex, so real results depend on the host PATH — each test pins
 * {@link AcpHarnessProbe#setForTest} for a deterministic payload, then clears
 * it so real probing is restored for other suites.
 */
class ApiAcpHarnessControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-acp";

    @BeforeEach
    void seedAndLogin() {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json", loginBody));
    }

    @AfterEach
    void cleanup() {
        AcpHarnessProbe.setForTest(null);
        AuthFixture.clearAdminPassword();
    }

    @Test
    void reportsDetectedAndMissingHarnesses() {
        AcpHarnessProbe.setForTest(List.of(
                new AcpHarnessProbe.Detected("claude", "Claude Code", "claude -p", true, "available"),
                new AcpHarnessProbe.Detected("codex", "Codex", "codex exec", false, "codex not found on PATH")));
        var response = GET("/api/subagents/acp-harnesses");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"id\":\"claude\""), "claude id present: " + body);
        assertTrue(body.contains("\"command\":\"claude -p\""), "suggested command present: " + body);
        assertTrue(body.contains("\"available\":true"), "claude reported available: " + body);
        assertTrue(body.contains("\"available\":false"), "codex reported missing: " + body);
    }

    @Test
    void requiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        assertEquals(401, GET("/api/subagents/acp-harnesses").status.intValue());
    }
}
