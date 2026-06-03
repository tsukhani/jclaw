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
 *
 * <p>JCLAW-329 adds the {@code discover} action: a route-table scan that
 * returns only {@code @ChatSafe}-annotated {@code /api/} endpoints, minus the
 * PATH_BLOCKLIST. Those tests run the real scan (UnitTest boots Play, so
 * {@code Router.routes} is populated and the controllers carry their runtime
 * annotations), and the call-path tests assert the existing validation still
 * fires — the discover branch must not regress the original call path.
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

    // ============ allowlist enforcement on call (default-deny) ============

    @Test
    void callRejectsUnannotatedEndpoint() {
        // /api/status is a real route but not @ChatSafe. Previously callable
        // (only the blocklist gated `call`); now refused by the allowlist.
        var result = tool.execute(
                "{\"method\":\"GET\",\"path\":\"/api/status\"}", null);
        assertTrue(result.contains("is not a chat-safe endpoint"),
                "unannotated endpoint must be denied; got: " + result);
    }

    @Test
    void callRejectsUnannotatedVerbOnAnnotatedPath() {
        // GET /api/config/{key} is @ChatSafe, but DELETE on the same path
        // (delete) is not — the allowlist is verb-specific.
        var result = tool.execute(
                "{\"method\":\"DELETE\",\"path\":\"/api/config/some-key\"}", null);
        assertTrue(result.contains("is not a chat-safe endpoint"),
                "unannotated verb must be denied even on an annotated path; got: " + result);
    }

    @Test
    void isChatSafeCallMatchesAnnotatedConcretePath() {
        // Concrete paths resolve against the route patterns
        // (/api/providers/{name}/models, /api/agents).
        assertTrue(JClawApiTool.isChatSafeCall("GET", "/api/providers/openrouter/models"),
                "GET providers/{name}/models is @ChatSafe");
        assertTrue(JClawApiTool.isChatSafeCall("GET", "/api/agents"),
                "GET agents is @ChatSafe");
    }

    @Test
    void providerModelMutationsAreChatSafe() {
        // Both provider model-management endpoints are @ChatSafe so the agent
        // can discover a catalog and add a model through chat.
        assertTrue(JClawApiTool.isChatSafeCall("POST", "/api/providers/openrouter/models"),
                "addModel must be @ChatSafe");
        assertTrue(JClawApiTool.isChatSafeCall("POST", "/api/providers/openrouter/discover-models"),
                "discoverModels must be @ChatSafe");
    }

    @Test
    void isChatSafeCallRejectsUnannotatedAndWrongVerb() {
        assertFalse(JClawApiTool.isChatSafeCall("GET", "/api/status"),
                "unannotated route must not be allowlisted");
        assertFalse(JClawApiTool.isChatSafeCall("POST", "/api/providers/refresh-prices"),
                "refreshPrices (POST) is not @ChatSafe");
        assertFalse(JClawApiTool.isChatSafeCall("DELETE", "/api/agents/5"),
                "delete agent is not @ChatSafe");
    }

    @Test
    void isChatSafeCallIgnoresQueryString() {
        // A query string in the path must not defeat the pattern match.
        assertTrue(JClawApiTool.isChatSafeCall("GET", "/api/config?foo=bar"),
                "query string should be stripped before matching");
    }

    // ==================== discover (JCLAW-329) ====================

    @Test
    void discoverListsChatSafeEndpoints() {
        var out = tool.execute("{\"action\":\"discover\"}", null);
        assertTrue(out.contains("/api/agents"), "agents endpoint missing: " + out);
        assertTrue(out.toLowerCase().contains("list agents"), "agents summary missing: " + out);
        assertTrue(out.contains("/api/mcp-servers"), "mcp-servers endpoint missing: " + out);
        // body hint surfaced for a mutating endpoint (POST /api/agents)
        assertTrue(out.contains("modelProvider"), "create-agent body hint missing: " + out);
    }

    @Test
    void discoverExcludesUnannotatedAndBlocklisted() {
        var out = tool.execute("{\"action\":\"discover\"}", null);
        // /api/status is a real route but not @ChatSafe — must not appear
        assertFalse(out.contains("/api/status"), "unannotated /api/status leaked: " + out);
        // blocklisted families must never appear even if a marker slipped through
        assertFalse(out.contains("/api/chat"), "blocklisted /api/chat leaked: " + out);
        assertFalse(out.contains("/api/auth"), "blocklisted /api/auth leaked: " + out);
    }

    @Test
    void discoverFilterNarrowsResults() {
        var out = tool.execute("{\"action\":\"discover\",\"filter\":\"mcp-servers\"}", null);
        assertTrue(out.contains("/api/mcp-servers"), "mcp-servers endpoint missing under filter: " + out);
        assertFalse(out.contains("/api/agents"), "filter=mcp-servers should exclude agents: " + out);
        assertFalse(out.contains("/api/providers"), "filter=mcp-servers should exclude providers: " + out);
    }

    // ==================== schema-level checks ====================

    @Test
    void parametersNoLongerForceMethodAndPath() {
        // JCLAW-329: method/path moved from schema-required to runtime-validated.
        // A discover call (action="discover") supplies neither, so declaring them
        // required would make the host reject the discover round-trip before it
        // ever reaches execute(). The required-field error now comes from the
        // tool itself (see rejectsMissingMethod / rejectsMissingPath).
        assertNull(tool.parameters().get("required"),
                "method/path must not be schema-required so discover can omit them");
    }

    @Test
    void nameMatchesPublicConstant() {
        // External callers (AgentService.create, ToolRegistrationJob)
        // reference TOOL_NAME; the instance must return the same name
        // or the lookup goes through ToolRegistry inconsistently.
        assertEquals(JClawApiTool.TOOL_NAME, tool.name());
    }
}
