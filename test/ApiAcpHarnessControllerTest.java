import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.AcpHarnessProbe;
import services.ConfigService;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * ACP harness detection endpoint (Settings → Subagents). The built-in probe
 * shells out to claude/pi/codex/…, so real results depend on the host PATH —
 * the detection test pins {@link AcpHarnessProbe#setForTest}. The custom-command
 * tests mint a temp executable (so {@code <script> --version} deterministically
 * exits 0 regardless of what's installed) and exercise the POST/DELETE CRUD.
 */
class ApiAcpHarnessControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-acp";

    private Path harness;

    @BeforeEach
    void seedAndLogin() throws IOException {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        harness = Files.createTempFile("jclaw-acp-harness-", ".sh");
        Files.writeString(harness, "#!/bin/sh\nexit 0\n");
        harness.toFile().setExecutable(true);
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json", loginBody));
    }

    @AfterEach
    void cleanup() throws IOException {
        AcpHarnessProbe.setForTest(null);
        ConfigService.delete(AcpHarnessProbe.CUSTOM_COMMANDS_KEY);
        if (harness != null) Files.deleteIfExists(harness);
        AuthFixture.clearAdminPassword();
    }

    @Test
    void reportsDetectedAndMissingHarnesses() {
        AcpHarnessProbe.setForTest(List.of(
                new AcpHarnessProbe.Detected("claude", "Claude Code", "claude -p", "claude", true, "available", false),
                new AcpHarnessProbe.Detected("codex", "Codex", "codex exec", "codex", false, "codex not found on PATH", false)));
        var response = GET("/api/subagents/acp-harnesses");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"id\":\"claude\""), "claude id present: " + body);
        assertTrue(body.contains("\"command\":\"claude -p\""), "suggested command present: " + body);
        assertTrue(body.contains("\"harness\":\"claude\""), "adapter id present: " + body);
        assertTrue(body.contains("\"available\":true"), "claude reported available: " + body);
        assertTrue(body.contains("\"available\":false"), "codex reported missing: " + body);
    }

    @Test
    void addsCustomHarnessWhenBinaryResolves() {
        var addResp = POST("/api/subagents/acp-harnesses", "application/json",
                "{\"command\":\"" + harness + "\"}");
        assertIsOk(addResp);
        var addBody = getContent(addResp);
        assertTrue(addBody.contains("\"available\":true"), "resolvable binary is available: " + addBody);
        assertTrue(addBody.contains("\"custom\":true"), "flagged as custom: " + addBody);
        assertTrue(addBody.contains("\"harness\":\"generic\""), "custom uses the generic adapter: " + addBody);

        // Persisted: it now shows up as a chip on the list endpoint.
        assertTrue(getContent(GET("/api/subagents/acp-harnesses")).contains(harness.toString()),
                "the custom command is listed after adding");

        // Removed: DELETE drops it from the list.
        var delResp = DELETE("/api/subagents/acp-harnesses?command="
                + URLEncoder.encode(harness.toString(), StandardCharsets.UTF_8));
        assertIsOk(delResp);
        assertFalse(getContent(delResp).contains(harness.toString()), "removed from the list after delete");
    }

    @Test
    void rejectsCustomHarnessWithMissingBinary() {
        var resp = POST("/api/subagents/acp-harnesses", "application/json",
                "{\"command\":\"jclaw-nonexistent-harness-xyz run\"}");
        assertIsOk(resp); // 200 with available:false — a probe result, not an error
        var body = getContent(resp);
        assertTrue(body.contains("\"available\":false"), "missing binary is unavailable: " + body);
        // Not persisted: absent from the list.
        assertFalse(getContent(GET("/api/subagents/acp-harnesses")).contains("jclaw-nonexistent-harness-xyz"),
                "an unresolved command is not stored");
    }

    @Test
    void requiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        assertEquals(401, GET("/api/subagents/acp-harnesses").status.intValue());
    }
}
