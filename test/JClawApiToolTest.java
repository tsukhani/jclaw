import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.JClawApiTool;

import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

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
        // JCLAW-1020: the read-only halves of the two hidden system actions share their
        // paths, so hiding by verb is what keeps "what version am I on?" answerable.
        assertTrue(JClawApiTool.isCallable("GET", "/api/system/upgrade"),
                "the upgrade preflight is read-only and stays callable");
        assertTrue(JClawApiTool.isCallable("GET", "/api/system/restart"),
                "the restart preflight is read-only and stays callable");
    }

    @Test
    void isCallableRefusesHiddenDenyFlooredAndUnknown() {
        assertFalse(JClawApiTool.isCallable("DELETE", "/api/conversations"),
                "deleteConversations is @ChatHidden");
        assertFalse(JClawApiTool.isCallable("PUT", "/api/channels/web"),
                "channels save is @ChatHidden");
        assertFalse(JClawApiTool.isCallable("GET", "/api/tailscale"),
                "tailscale is deny-floored");
        // JCLAW-941: memory is cross-agent personal data — /api/memories is the operator's
        // admin view over every agent's corpus, so an agent reaching it could read, edit or
        // delete another agent's memories. The scoped `memory` tool is the agent path.
        assertFalse(JClawApiTool.isCallable("GET", "/api/memories"),
                "listing memories across agents is deny-floored");
        assertFalse(JClawApiTool.isCallable("DELETE", "/api/memories/7"),
                "deleting a memory is deny-floored");
        assertFalse(JClawApiTool.isCallable("POST", "/api/memories/recall"),
                "recall for an arbitrary agentId is deny-floored");
        assertFalse(JClawApiTool.isCallable("GET", "/api/logs"),
                "logs is deny-floored");
        // JCLAW-1020: the semver gate stopped the download leaving the pinned repo, but a
        // legitimate older release is still an attacker's goal — v0.17.77 predates this
        // sprint's fixes, so an agent that could reinstall it would reopen them and then
        // walk back through. Restart is the availability twin.
        assertFalse(JClawApiTool.isCallable("POST", "/api/system/upgrade"),
                "upgrade replaces the install and restarts -- @ChatHidden");
        assertFalse(JClawApiTool.isCallable("POST", "/api/system/restart"),
                "restart stops the instance -- @ChatHidden");
        // JCLAW-1022: the config table holds the instance's own security controls -- the shell
        // allowlist, the approval policy, the funnel switch -- so a caller able to write it
        // widens every other gate rather than defeating one. Reads stay callable and masked.
        assertFalse(JClawApiTool.isCallable("POST", "/api/config"),
                "writing config is @ChatHidden");
        assertFalse(JClawApiTool.isCallable("DELETE", "/api/config/shell.allowlist"),
                "deleting a config row is @ChatHidden");
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
        // Body hint mined from the Swagger @RequestBody record. Read off AgentRequest since
        // JCLAW-1022 hid config-save, which this previously sampled -- a @ChatHidden endpoint
        // contributes no hint because it is not in the catalog at all.
        assertTrue(out.contains("name, modelProvider"),
                "agent-write body hint from @RequestBody record missing: " + out);
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

    /**
     * The exact set of endpoints {@code jclaw_api} advertises and will invoke.
     *
     * <p>JCLAW-1036: the tool is deliberately <em>default-allow</em> — a new {@code /api/}
     * route is reachable with no annotation, which is what makes it worth giving an operator's
     * agent at all. Inverting that was considered and rejected: an allow-list would make every
     * endpoint added from here invisible until someone remembered to mark it, and the tool would
     * decay silently as the API grew.
     *
     * <p>What default-allow lacks is any moment where somebody has to decide. Four
     * privilege-bearing routes were found by hand after the fact (JCLAW-1023 gated three, the
     * workspace write was the fourth), each reachable simply because nothing asked. This pin is
     * that moment: adding a route fails this test until its author either lists it here or marks
     * it {@link controllers.ChatHidden}. The runtime default stays permissive; the decision moves
     * to review time.
     *
     * <p>So a diff to this list is not a chore — it is the review. Adding a line says "an agent
     * may drive this"; if that is wrong, the fix is {@code @ChatHidden} on the action, or the
     * deny-floor in {@link JClawApiTool} for a whole subsystem.
     */
    @Test
    void theCallableApiSurfaceIsExactlyTheseRoutes() {
        var expected = new TreeSet<>(List.of("""
            DELETE /api/agents/{id}
            DELETE /api/agents/{id}/skills/{name}/delete
            DELETE /api/apps/{slug}
            DELETE /api/attachments/{uuid}
            DELETE /api/channels/whatsapp/bindings/{id}
            DELETE /api/conversations/{id}/model-override
            DELETE /api/conversations/{id}/pin
            DELETE /api/conversations/{id}/star
            DELETE /api/conversations/{id}/thinking-override
            DELETE /api/logging/levels/{logger}
            DELETE /api/mcp-servers/{id}
            DELETE /api/metrics/compression
            DELETE /api/metrics/latency
            DELETE /api/metrics/latency/rows
            DELETE /api/metrics/logs
            DELETE /api/notifications/{id}
            DELETE /api/prompts/{id}
            DELETE /api/skills/{name}
            DELETE /api/subagent-runs
            DELETE /api/subagent-runs/{id}
            DELETE /api/subagents/acp-harnesses
            DELETE /api/tasks/{id}
            DELETE /api/tts/reference-voice
            GET /api/agents
            GET /api/agents/{agentId}/core-migration
            GET /api/agents/{id}
            GET /api/agents/{id}/files/{<.+>filePath}
            GET /api/agents/{id}/prompt-breakdown
            GET /api/agents/{id}/prompt-text
            GET /api/agents/{id}/shell/effective-allowlist
            GET /api/agents/{id}/skills
            GET /api/agents/{id}/skills/{name}/files
            GET /api/agents/{id}/skills/{name}/files/{<.+>filePath}
            GET /api/agents/{id}/tools
            GET /api/apps
            GET /api/apps/{slug}/files/{uuid}
            GET /api/attachments/{uuid}
            GET /api/breakers
            GET /api/channels
            GET /api/channels/active
            GET /api/channels/whatsapp/bindings
            GET /api/channels/whatsapp/bindings/{id}/qr
            GET /api/config
            GET /api/config/{key}
            GET /api/conversations
            GET /api/conversations/channels
            GET /api/conversations/{id}
            GET /api/conversations/{id}/messages
            GET /api/conversations/{id}/queue
            GET /api/imagegen/capability
            GET /api/imagegen/local/state
            GET /api/imagegen/models
            GET /api/imagegen/progress
            GET /api/logging/levels
            GET /api/mcp-servers
            GET /api/mcp-servers/{id}
            GET /api/metrics/compression
            GET /api/metrics/cost
            GET /api/metrics/db-pool
            GET /api/metrics/jvm
            GET /api/metrics/latency
            GET /api/metrics/latency/rows
            GET /api/metrics/logs
            GET /api/notifications
            GET /api/ocr/status
            GET /api/onboarding/tour-status
            GET /api/printers
            GET /api/printers/default
            GET /api/printers/default/status
            GET /api/printers/options
            GET /api/prompts
            GET /api/prompts/categories
            GET /api/prompts/export
            GET /api/providers
            GET /api/providers/{name}/embedding-models
            GET /api/providers/{name}/models
            GET /api/providers/{name}/reachable
            GET /api/providers/{name}/video-models
            GET /api/skills
            GET /api/skills/by-agent
            GET /api/skills/catalog/search
            GET /api/skills/catalogs
            GET /api/skills/{name}
            GET /api/skills/{name}/files
            GET /api/skills/{name}/files/{<.+>filePath}
            GET /api/slash-commands
            GET /api/status
            GET /api/subagent-runs
            GET /api/subagent-runs/{id}/steps
            GET /api/subagents/acp-command
            GET /api/subagents/acp-harnesses
            GET /api/system/restart
            GET /api/system/upgrade
            GET /api/system/upgrade/status
            GET /api/task-runs/recent
            GET /api/task-runs/search
            GET /api/task-runs/{id}/messages
            GET /api/tasks
            GET /api/tasks/stats
            GET /api/tasks/{id}/delivery-advisory
            GET /api/tasks/{id}/runs
            GET /api/timezones
            GET /api/tools
            GET /api/tools/meta
            GET /api/transcription/diarization/models
            GET /api/transcription/state
            GET /api/tts/state
            GET /api/videogen/capability
            GET /api/videogen/jobs
            GET /api/videogen/jobs/recent
            GET /api/videogen/models
            GET /api/videogen/state
            GET /api/workspace/stats
            PATCH /api/tasks/{id}
            POST /api/agents
            POST /api/agents/{agentId}/core-migration
            POST /api/apps/{slug}/invoke
            POST /api/channels/whatsapp/bindings
            POST /api/evals/capture
            POST /api/evals/memory-ingest
            POST /api/logging/levels
            POST /api/mcp-servers
            POST /api/mcp-servers/{id}/test
            POST /api/notifications/{id}/ack
            POST /api/onboarding/tour-progress
            POST /api/prompts
            POST /api/prompts/generate
            POST /api/prompts/import
            POST /api/providers/refresh-prices
            POST /api/providers/{name}/discover-models
            POST /api/providers/{name}/embedding-probe
            POST /api/providers/{name}/models
            POST /api/skills/catalog/import
            POST /api/skills/catalog/refresh
            POST /api/skills/promote
            POST /api/subagent-runs/{id}/kill
            POST /api/subagents/acp-harnesses
            POST /api/task-runs/reset
            POST /api/task-runs/{runId}/cancel
            POST /api/tasks
            POST /api/tasks/{id}/cancel
            POST /api/tasks/{id}/pause
            POST /api/tasks/{id}/reenable
            POST /api/tasks/{id}/resume
            POST /api/tasks/{id}/retry
            POST /api/tasks/{id}/run
            POST /api/tts/reference-voice
            PUT /api/agents/{id}
            PUT /api/channels/whatsapp/bindings/{id}
            PUT /api/conversations/{id}/model-override
            PUT /api/conversations/{id}/name
            PUT /api/conversations/{id}/pin
            PUT /api/conversations/{id}/star
            PUT /api/conversations/{id}/thinking-override
            PUT /api/mcp-servers/{id}
            PUT /api/printers/default
            PUT /api/prompts/{id}
            PUT /api/skills/{name}/rename
            WS /api/voice""".split("\n")));

        assertEquals(expected, callableSurface(),
                "The jclaw_api callable surface changed. Anything listed here can be invoked by "
                        + "an agent through the tool. If the new route writes privilege — config, "
                        + "grants, workspace files, instance lifecycle — it belongs behind "
                        + "@ChatHidden instead of on this list.");
    }

    /** What {@code discover} actually advertises, which is what an agent can act on. */
    private static SortedSet<String> callableSurface() {
        var found = new TreeSet<String>();
        for (var line : tool.execute("{\"action\":\"discover\"}", null).split("\n")) {
            var trimmed = line.strip();
            if (!trimmed.startsWith("- ")) continue;
            var cols = trimmed.substring(2).strip().split("\\s+");
            if (cols.length >= 2) found.add(cols[0] + " " + cols[1]);
        }
        return found;
    }

}
