import org.junit.jupiter.api.*;
import play.test.*;
import tools.JClawApiTool;

/**
 * Unit coverage for {@link JClawApiTool}'s argument parsing and path
 * blocklist (JCLAW-282).
 *
 * <p>Skips the live-HTTP round trip — the bearer-auth + controller
 * dispatch is already exercised by {@code ApiTokensControllerTest} and
 * the rest of the FunctionalTest suite. What only this layer can verify
 * is that the tool's blocklist and arg validation refuse bad inputs
 * before any socket opens.
 */
class JClawApiToolTest extends UnitTest {

    private static JClawApiTool tool;

    @BeforeAll
    static void setup() {
        tool = new JClawApiTool();
    }

    // ==================== argument validation ====================

    @Test
    void rejectsMalformedJson() {
        var result = tool.execute("this is not json", null);
        assertTrue(result.startsWith("Error: arguments are not valid JSON"),
                "expected JSON-parse error; got: " + result);
    }

    @Test
    void rejectsMissingMethod() {
        var result = tool.execute("{\"path\":\"/api/agents\"}", null);
        assertTrue(result.startsWith("Error: both 'method' and 'path' are required"),
                "got: " + result);
    }

    @Test
    void rejectsMissingPath() {
        var result = tool.execute("{\"method\":\"GET\"}", null);
        assertTrue(result.startsWith("Error: both 'method' and 'path' are required"),
                "got: " + result);
    }

    @Test
    void rejectsUnknownMethod() {
        var result = tool.execute(
                "{\"method\":\"OPTIONS\",\"path\":\"/api/agents\"}", null);
        assertTrue(result.contains("method must be one of"),
                "got: " + result);
        assertTrue(result.contains("OPTIONS"),
                "error should echo the offending verb so the model can correct it; got: " + result);
    }

    @Test
    void rejectsNonApiPath() {
        var result = tool.execute(
                "{\"method\":\"GET\",\"path\":\"/somewhere-else\"}", null);
        assertTrue(result.contains("path must start with /api/"),
                "got: " + result);
    }

    // ==================== path blocklist (security) ====================

    @Test
    void blocksChatSendPath() {
        // Recursion guard: a chat agent calling /api/chat/send to send
        // another chat would invoke itself. Refused regardless of method.
        var result = tool.execute(
                "{\"method\":\"POST\",\"path\":\"/api/chat/send\"," +
                "\"body\":{\"agentId\":1,\"message\":\"hi\"}}", null);
        assertTrue(result.contains("reserved and cannot be invoked"),
                "expected blocklist refusal; got: " + result);
        assertTrue(result.contains("/api/chat/"),
                "error should name the blocked prefix; got: " + result);
    }

    @Test
    void blocksAuthLoginPath() {
        var result = tool.execute(
                "{\"method\":\"POST\",\"path\":\"/api/auth/login\"," +
                "\"body\":{\"username\":\"admin\",\"password\":\"x\"}}", null);
        assertTrue(result.contains("/api/auth/"),
                "auth endpoints must be blocked; got: " + result);
    }

    @Test
    void blocksApiTokensPath() {
        // Privilege-escalation surface: a tool that can mint another
        // token could survive its own revocation. Blocked at the tool
        // boundary, with AuthCheck.requireSessionAuth as belt-and-suspenders.
        var result = tool.execute(
                "{\"method\":\"POST\",\"path\":\"/api/api-tokens\"," +
                "\"body\":{\"name\":\"escalation\"}}", null);
        assertTrue(result.contains("/api/api-tokens"),
                "token CRUD must be blocked; got: " + result);
    }

    @Test
    void blocksWebhookPath() {
        var result = tool.execute(
                "{\"method\":\"POST\",\"path\":\"/api/webhooks/telegram/x/y\"}", null);
        assertTrue(result.contains("/api/webhooks/"),
                "webhook endpoints must be blocked; got: " + result);
    }

    @Test
    void blocksEventsPath() {
        // SSE — full-response buffering doesn't match streaming semantics.
        var result = tool.execute(
                "{\"method\":\"GET\",\"path\":\"/api/events\"}", null);
        assertTrue(result.contains("/api/events"),
                "events SSE must be blocked; got: " + result);
    }

    @Test
    void allowsSafePathsToReachUrlConstruction() {
        // /api/agents is on the curated allowlist (SKILL.md). The tool's
        // execute path proceeds past validation and into the HTTP call.
        // In the unit-test JVM there's no Play listener bound, so the
        // call will error at the socket layer — we just verify it didn't
        // get short-circuited by the blocklist or arg validation.
        var result = tool.execute(
                "{\"method\":\"GET\",\"path\":\"/api/agents\"}", null);
        // Either a successful HTTP response (if a port happens to be
        // listening) or a network error — but NOT a blocklist refusal.
        assertFalse(result.contains("reserved and cannot be invoked"),
                "/api/agents should not be blocked; got: " + result);
        assertFalse(result.contains("must start with /api/"),
                "/api/agents should clear the path-prefix check; got: " + result);
    }

    // ==================== schema-level checks ====================

    @Test
    void parametersDeclareRequiredFields() {
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) tool.parameters().get("required");
        // The model's host validates required fields against this schema
        // before dispatching the call. Documenting both as required is
        // the only way to keep "ToolUse.params is empty" from being a
        // silent no-op.
        assertTrue(required.contains("method"));
        assertTrue(required.contains("path"));
    }

    @Test
    void nameMatchesPublicConstant() {
        // External callers (AgentService.create, ToolRegistrationJob)
        // reference TOOL_NAME; the instance must return the same name
        // or the lookup goes through ToolRegistry inconsistently.
        assertEquals(JClawApiTool.TOOL_NAME, tool.name());
    }
}
