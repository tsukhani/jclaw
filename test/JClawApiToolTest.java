import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.JClawApiTool;

/**
 * Unit coverage for {@link JClawApiTool}'s argument parsing, deny-floor, and the
 * blacklist discover/call gate.
 *
 * <p>Skips the live-HTTP round trip -- bearer-auth + controller dispatch is
 * exercised by the FunctionalTest suite. What only this layer verifies is that
 * the deny layers and arg validation refuse bad inputs before any socket opens.
 *
 * <p>Blacklist model: {@code jclaw_api} discovers/invokes <em>every</em>
 * {@code /api/} route that resolves to a controller action by default, minus the
 * {@code PATH_BLOCKLIST} deny-floor and minus {@code @ChatHidden} actions. These
 * tests run the real route-table scan (UnitTest boots Play, so {@code Router.routes}
 * is populated and controllers carry their runtime annotations).
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

    // ==================== deny-floor (security) ====================

    @Test
    void blocksChatSendPath() {
        // Recursion guard: a chat agent calling /api/chat/send to send
        // another chat would invoke itself. Refused regardless of method.
        var result = tool.execute(
                "{\"method\":\"POST\",\"path\":\"/api/chat/send\"," +
                "\"body\":{\"agentId\":1,\"message\":\"hi\"}}", null);
        assertTrue(result.contains("reserved and cannot be invoked"),
                "expected deny-floor refusal; got: " + result);
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
        var result = tool.execute(
                "{\"method\":\"GET\",\"path\":\"/api/events\"}", null);
        assertTrue(result.contains("/api/events"),
                "events SSE must be blocked; got: " + result);
    }

    @Test
    void blocksBindingsPath() {
        var result = tool.execute("{\"method\":\"GET\",\"path\":\"/api/bindings\"}", null);
        assertTrue(result.contains("/api/bindings"),
                "bindings (comms routing) must be deny-floored; got: " + result);
    }

    @Test
    void blocksTelegramBindingsPath() {
        var result = tool.execute(
                "{\"method\":\"GET\",\"path\":\"/api/channels/telegram/bindings\"}", null);
        assertTrue(result.contains("reserved and cannot be invoked"),
                "telegram bindings (bot tokens) must be deny-floored; got: " + result);
    }

    @Test
    void blocksTailscalePath() {
        var result = tool.execute("{\"method\":\"GET\",\"path\":\"/api/tailscale\"}", null);
        assertTrue(result.contains("/api/tailscale"),
                "tailscale (infra config) must be deny-floored; got: " + result);
    }

    @Test
    void blocksLogsPath() {
        var result = tool.execute("{\"method\":\"GET\",\"path\":\"/api/logs\"}", null);
        assertTrue(result.contains("/api/logs"),
                "logs (secret leak) must be deny-floored; got: " + result);
    }

    @Test
    void blocksLoadtestPath() {
        var result = tool.execute("{\"method\":\"POST\",\"path\":\"/api/metrics/loadtest\"}", null);
        assertTrue(result.contains("/api/metrics/loadtest"),
                "load-test harness (resource abuse) must be deny-floored; got: " + result);
    }

    // ==================== blacklist call gate ====================

    @Test
    void callAllowsUnannotatedEndpoint() {
        // /api/status is a real route carrying no annotation. Under the blacklist
        // it is callable by default -- execute proceeds past the gate to the HTTP
        // call (which then errors at the socket in the unit JVM).
        var result = tool.execute("{\"method\":\"GET\",\"path\":\"/api/status\"}", null);
        assertFalse(result.contains("is not callable"),
                "unannotated /api/status must be callable under the blacklist; got: " + result);
        assertFalse(result.contains("reserved and cannot be invoked"),
                "/api/status is not deny-floored; got: " + result);
    }

    @Test
    void callRejectsChatHiddenEndpoint() {
        // DELETE /api/conversations (bulk wipe) is @ChatHidden -- refused even
        // though /api/conversations is not deny-floored.
        var result = tool.execute("{\"method\":\"DELETE\",\"path\":\"/api/conversations\"}", null);
        assertTrue(result.contains("is not callable"),
                "@ChatHidden endpoint must be refused; got: " + result);
    }

    @Test
    void isCallableAllowsUnannotatedRoutes() {
        assertTrue(JClawApiTool.isCallable("GET", "/api/status"),
                "unannotated GET /api/status is callable under the blacklist");
        assertTrue(JClawApiTool.isCallable("DELETE", "/api/agents/5"),
                "DELETE agent is callable (real route, not hidden/floored)");
        assertTrue(JClawApiTool.isCallable("POST", "/api/providers/refresh-prices"),
                "refresh-prices is callable (real route, not hidden/floored)");
        assertTrue(JClawApiTool.isCallable("GET", "/api/providers/openrouter/models"),
                "concrete path resolves against the route pattern");
    }

    @Test
    void isCallableRefusesHiddenDenyFlooredAndUnknown() {
        assertFalse(JClawApiTool.isCallable("DELETE", "/api/conversations"),
                "deleteConversations is @ChatHidden");
        assertFalse(JClawApiTool.isCallable("PUT", "/api/channels/web"),
                "channels save is @ChatHidden");
        assertFalse(JClawApiTool.isCallable("GET", "/api/tailscale"),
                "tailscale is deny-floored");
        assertFalse(JClawApiTool.isCallable("GET", "/api/logs"),
                "logs is deny-floored");
        assertFalse(JClawApiTool.isCallable("GET", "/api/no-such-endpoint-xyz"),
                "nonexistent path matches only the @ChatHidden catch-all -> refused");
    }

    @Test
    void isCallableIgnoresQueryString() {
        assertTrue(JClawApiTool.isCallable("GET", "/api/config?foo=bar"),
                "query string should be stripped before matching");
    }

    // ==================== discover ====================

    @Test
    void discoverUsesOperationSummaryAndBodyHint() {
        var out = tool.execute("{\"action\":\"discover\"}", null);
        assertTrue(out.contains("/api/agents"), "agents endpoint missing: " + out);
        assertTrue(out.toLowerCase().contains("list agents"), "@Operation summary missing: " + out);
        assertTrue(out.contains("/api/mcp-servers"), "mcp-servers endpoint missing: " + out);
        // body hint mined from the Swagger @RequestBody record (ConfigSaveRequest -> "key, value")
        assertTrue(out.contains("key, value"), "config-save body hint from @RequestBody record missing: " + out);
    }

    @Test
    void discoverIncludesPreviouslyHiddenEndpoints() {
        // The blacklist inversion: routes that were never @ChatSafe now appear too.
        var out = tool.execute("{\"action\":\"discover\"}", null);
        assertTrue(out.contains("/api/status"), "/api/status must now be discovered: " + out);
        assertTrue(out.contains("/api/tasks"), "/api/tasks must now be discovered: " + out);
    }

    @Test
    void discoverExcludesDenyFlooredAndCatchAll() {
        var out = tool.execute("{\"action\":\"discover\"}", null);
        assertFalse(out.contains("/api/chat"), "deny-floored /api/chat leaked: " + out);
        assertFalse(out.contains("/api/auth"), "deny-floored /api/auth leaked: " + out);
        assertFalse(out.contains("/api/tailscale"), "deny-floored /api/tailscale leaked: " + out);
        assertFalse(out.contains("/api/logs"), "deny-floored /api/logs leaked: " + out);
        assertFalse(out.contains("ANY /api/"), "404 catch-all (@ChatHidden) leaked into discover: " + out);
    }

    @Test
    void discoverFilterNarrowsResults() {
        var out = tool.execute("{\"action\":\"discover\",\"filter\":\"mcp-servers\"}", null);
        assertTrue(out.contains("/api/mcp-servers"), "mcp-servers endpoint missing under filter: " + out);
        assertFalse(out.contains("/api/agents"), "filter=mcp-servers should exclude agents: " + out);
    }

    // ==================== schema-level checks ====================

    @Test
    void parametersNoLongerForceMethodAndPath() {
        assertNull(tool.parameters().get("required"),
                "method/path must not be schema-required so discover can omit them");
    }

    @Test
    void nameMatchesPublicConstant() {
        assertEquals(JClawApiTool.TOOL_NAME, tool.name());
    }

    // ============ JCLAW-844: per-call danger classification ============

    @Test
    void dangerousForMutatingVerbs() {
        assertTrue(tool.dangerous("{\"method\":\"POST\",\"path\":\"/api/mcp-servers\"}"),
                "POST is a mutation and must be gated");
        assertTrue(tool.dangerous("{\"method\":\"PUT\",\"path\":\"/api/config\"}"), "PUT must be gated");
        assertTrue(tool.dangerous("{\"method\":\"PATCH\",\"path\":\"/api/agents/1\"}"), "PATCH must be gated");
        assertTrue(tool.dangerous("{\"method\":\"DELETE\",\"path\":\"/api/agents/1\"}"), "DELETE must be gated");
    }

    @Test
    void dangerousIsCaseInsensitiveOnVerb() {
        assertTrue(tool.dangerous("{\"method\":\"post\",\"path\":\"/api/mcp-servers\"}"),
                "a lowercase verb must still classify as a mutation");
    }

    @Test
    void notDangerousForReadsAndDiscover() {
        assertFalse(tool.dangerous("{\"method\":\"GET\",\"path\":\"/api/mcp-servers\"}"),
                "GET is a read; masking (JCLAW-780) handles read exposure, no gate");
        assertFalse(tool.dangerous("{\"action\":\"discover\"}"),
                "discover only lists endpoints; not a mutation");
        assertFalse(tool.dangerous("{\"action\":\"discover\",\"method\":\"POST\"}"),
                "discover is a read even if a stray method is present");
    }

    @Test
    void notDangerousForMalformedOrMethodlessArgs() {
        assertFalse(tool.dangerous(null), "null args -> no-op -> not dangerous");
        assertFalse(tool.dangerous("not json"), "malformed args are rejected by execute() -> not dangerous");
        assertFalse(tool.dangerous("[1,2,3]"), "non-object JSON -> not dangerous");
        assertFalse(tool.dangerous("{\"path\":\"/api/agents\"}"), "method-less call is a no-op -> not dangerous");
    }
}
