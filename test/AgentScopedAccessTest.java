import controllers.RequestPrincipal;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;
import utils.AppClock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The {@code OWN_ONLY} level actually scopes per agent, driven end to end (JCLAW-1270).
 *
 * <p>{@code CapabilityRulesTest} shows each {@code OWN_ONLY} action <em>reaches</em> an ownership
 * check; that is a property of the bytecode. This is the other half, and it is the half that
 * matters here, because the whole level rests on a header: one token serves every agent, so
 * {@code JClawApiTool} stamps the calling agent's id and {@code RequestPrincipal} reads it back.
 * Nothing else proves the two ends agree.
 *
 * <p><b>Why each case pairs a refusal with a permission.</b> A 403 is also what a broken header
 * produces, and every one of these routes would 403 for every caller if
 * {@code callingAgent()} always returned null. Each block therefore shows the same route
 * answering differently for two principals, which a blanket failure cannot fake.
 *
 * <p>The {@code main} half of the asymmetry is <em>not</em> here. {@code main} is provisioned at
 * boot, not per test, and a sibling's {@code Fixtures.deleteDatabase} removes it mid-suite — this
 * class passed alone and failed in the full run for exactly that reason. Seeding one is forbidden
 * (AGENTS.md), so the policy matrix lives in {@code RequestPrincipalPolicyTest} instead, against
 * the request-free overloads.
 */
class AgentScopedAccessTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";

    private Long alienConversationId;
    private Long ownConversationId;
    private Long callerAgentId;
    private Long alienAgentId;
    private Long ownRunId;
    private Long alienRunId;

    // No Fixtures.deleteDatabase(): play1 runs test classes concurrently and these rows are
    // scoped to this class by name. Wiping the shared H2 would only break the siblings.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        var seeded = commit(() -> {
            var caller = agentNamed("scope-test-caller");
            var alien = agentNamed("scope-test-alien");
            var ownConversation = conversationFor(caller);
            var alienConversation = conversationFor(alien);
            return new Long[]{
                    caller.id, alien.id, ownConversation.id, alienConversation.id,
                    settledRunFor(caller, ownConversation).id,
                    settledRunFor(alien, alienConversation).id};
        });
        callerAgentId = seeded[0];
        alienAgentId = seeded[1];
        ownConversationId = seeded[2];
        alienConversationId = seeded[3];
        ownRunId = seeded[4];
        alienRunId = seeded[5];
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    // --- conversations: the read that spans every agent -------------------------------------

    @Test
    void anAgentReadsItsOwnConversationAndNotAnotherAgents() {
        var own = asAgent(() -> GET(agentRequest(callerAgentId), "/api/conversations/" + ownConversationId));
        assertStatus(200, own);

        var alien = asAgent(() -> GET(agentRequest(callerAgentId), "/api/conversations/" + alienConversationId));
        assertStatus(403, alien);
        assertTrue(getContent(alien).contains(AGENT_SCOPE), getContent(alien));
    }

    @Test
    void anAgentCannotReadAnotherAgentsMessages() {
        // The specific leak this level exists for: history, not metadata.
        var resp = asAgent(() -> GET(agentRequest(callerAgentId),
                "/api/conversations/" + alienConversationId + "/messages"));
        assertStatus(403, resp);
    }

    @Test
    void anUnstampedBearerIsNotTreatedAsAnyAgent() {
        // Fail-closed: a bearer used outside jclaw_api carries no agent header, and an
        // unidentified agent must get less reach than an identified one, never more.
        var resp = asAgent(() -> GET(bareBearerRequest(),
                "/api/conversations/" + ownConversationId));
        assertStatus(403, resp);
    }

    // --- MCP servers: a STDIO command is main-only ------------------------------------------

    @Test
    void aCustomAgentCannotConfigureAStdioMcpServer() {
        // A STDIO server is a command this JVM spawns unconfined — outside HarnessSandbox and
        // outside the shell allowlist — so it is the operator's authority to delegate.
        var resp = asAgent(() -> POST(agentRequest(callerAgentId), "/api/mcp-servers",
                "application/json",
                "{\"name\":\"scope-test-stdio\",\"transport\":\"STDIO\",\"command\":\"/bin/sh\"}"));
        assertStatus(403, resp);
        assertTrue(getContent(resp).contains(AGENT_SCOPE), getContent(resp));
    }

    @Test
    void aCustomAgentMayStillConfigureAnHttpMcpServer() {
        // The permission half: the route stays agent-reachable, only the transport is bounded.
        // A non-403 is the assertion — the row may still be refused on validation grounds.
        var resp = asAgent(() -> POST(agentRequest(callerAgentId), "/api/mcp-servers",
                "application/json",
                "{\"name\":\"scope-test-http\",\"transport\":\"HTTP\",\"url\":\"https://example.invalid/mcp\"}"));
        assertFalse(getContent(resp).contains(AGENT_SCOPE),
                "an HTTP transport must not hit the STDIO guard; got: " + getContent(resp));
    }

    // --- subagent runs: an agent owns what it spawned ----------------------------------------

    @Test
    void anAgentKillsItsOwnRunAndNotAnotherAgents() {
        var own = asAgent(() -> POST(agentRequest(callerAgentId),
                "/api/subagent-runs/" + ownRunId + "/kill", "application/json", "{}"));
        assertStatus(200, own);

        var alien = asAgent(() -> POST(agentRequest(callerAgentId),
                "/api/subagent-runs/" + alienRunId + "/kill", "application/json", "{}"));
        assertStatus(403, alien);
        assertTrue(getContent(alien).contains(AGENT_SCOPE), getContent(alien));
    }

    @Test
    void anAgentDeletesItsOwnRunAndNotAnotherAgents() {
        var own = asAgent(() -> DELETE(agentRequest(callerAgentId),
                "/api/subagent-runs/" + ownRunId));
        assertStatus(200, own);

        var alien = asAgent(() -> DELETE(agentRequest(callerAgentId),
                "/api/subagent-runs/" + alienRunId));
        assertStatus(403, alien);
        assertTrue(getContent(alien).contains(AGENT_SCOPE), getContent(alien));
    }

    @Test
    void anUnstampedBearerCannotKillARun() {
        // The destructive half of the unstamped case above: an unidentified agent gets less
        // reach than an identified one, never more.
        var resp = asAgent(() -> POST(bareBearerRequest(),
                "/api/subagent-runs/" + ownRunId + "/kill", "application/json", "{}"));
        assertStatus(403, resp);
    }

    // --- subagent runs: bulk delete narrows, it does not refuse -------------------------------

    @Test
    void aBulkDeleteByIdsNarrowsToTheCallersOwnRuns() {
        var resp = asAgent(() -> deleteWithBody(agentRequest(callerAgentId), "/api/subagent-runs",
                "{\"ids\":[" + ownRunId + "," + alienRunId + "]}"));
        assertStatus(200, resp);
        assertTrue(getContent(resp).contains("\"deleted\":1"), getContent(resp));
        assertFalse(runExists(ownRunId), "the caller named its own run and it should have gone");
        assertTrue(runExists(alienRunId), "the caller named another agent's run and it must survive");
    }

    @Test
    void aBulkDeleteByFilterCannotBeAimedAtAnotherAgent() {
        // The sharp case for narrowing: the filter names the other agent outright, and
        // scopedParentAgentId rewrites it to the caller — so the query is incapable of matching
        // that row rather than refusing it after reading it.
        var resp = asAgent(() -> deleteWithBody(agentRequest(callerAgentId), "/api/subagent-runs",
                "{\"filter\":{\"parentAgentId\":" + alienAgentId + "}}"));
        assertStatus(200, resp);
        assertTrue(runExists(alienRunId), "a filter aimed at another agent must not reach its runs");
        // The permission half: the filter was rewritten, not ignored or rejected.
        assertFalse(runExists(ownRunId), "the rewritten filter still sweeps the caller's own runs");
    }

    // --- seam ---------------------------------------------------------------------------------

    private static Agent agentNamed(String name) {
        var existing = Agent.findByName(name);
        if (existing != null) return existing;
        var agent = new Agent();
        agent.name = name;
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    private static Conversation conversationFor(Agent agent) {
        var convo = new Conversation();
        convo.agent = agent;
        convo.channelType = "web";
        convo.peerId = "scope-test";
        convo.save();
        return convo;
    }

    /**
     * A settled run owned by {@code parent}. Terminal rather than RUNNING because delete refuses a
     * live row with 409 and kill answers an already-terminal one with 200 — both after the
     * ownership check, which is what these cases are about.
     *
     * <p>Each run gets its own child agent: delete cascades through
     * {@link services.AgentService#delete} on the child, so a shared one would take the other
     * run's row with it and the refusal would pass for the wrong reason.
     */
    private static SubagentRun settledRunFor(Agent parent, Conversation parentConversation) {
        var child = freshAgentNamed("scope-test-child");
        var run = new SubagentRun();
        run.parentAgent = parent;
        run.childAgent = child;
        run.parentConversation = parentConversation;
        run.childConversation = conversationFor(child);
        run.status = SubagentRun.Status.COMPLETED;
        run.startedAt = AppClock.now();
        run.endedAt = AppClock.now();
        run.save();
        return run;
    }

    /**
     * {@code DELETE} carrying a body. Play 1.x {@code FunctionalTest} ships only
     * {@code DELETE(String)} and {@code DELETE(Request, Object)}, and {@code deleteBulk} answers
     * 400 without a body — the same harness gap {@code ApiTasksControllerCoverageTest} works
     * around for PATCH. The bearer header is already on {@code request}; no cookie jar is folded
     * in, because this class drives the agent principal and clears cookies after every call.
     */
    private static Http.Response deleteWithBody(Http.Request request, String url, String body) {
        request.method = "DELETE";
        request.contentType = "application/json";
        request.url = url;
        request.path = url;
        request.querystring = "";
        request.body = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
        return makeRequest(request);
    }

    /** Committed read: the test body's own transaction cannot see what the request committed. */
    private static boolean runExists(Long id) {
        return commit(() -> SubagentRun.findById(id) != null);
    }

    /** Unique-named, unlike {@link #agentNamed}: a reused child would accumulate runs across
     *  cases, and one delete would then cascade over another case's row. */
    private static Agent freshAgentNamed(String prefix) {
        return agentNamed(prefix + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** Commit off-thread: the test body is already in a transaction this request would not see. */
    private static <T> T commit(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }

    /** A bearer request stamped with the calling agent, exactly as {@code JClawApiTool} builds it. */
    private static Http.Request agentRequest(Long agentId) {
        var request = bareBearerRequest();
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** A bearer with no agent stamp — a token used outside the tool. */
    private static Http.Request bareBearerRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }

    /** AuthCheck answers a bearer call with a Set-Cookie carrying the agent principal, and
     *  FunctionalTest.savedCookies is a JVM-global folded into every later request — from any
     *  class, since play1 runs test classes concurrently. Left behind, it 403s a sibling. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }
}
