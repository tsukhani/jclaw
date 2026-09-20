import controllers.RequestPrincipal;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;

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

    // No Fixtures.deleteDatabase(): play1 runs test classes concurrently and these rows are
    // scoped to this class by name. Wiping the shared H2 would only break the siblings.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        var seeded = commit(() -> {
            var caller = agentNamed("scope-test-caller");
            var alien = agentNamed("scope-test-alien");
            return new Long[]{
                    caller.id, conversationFor(caller).id, conversationFor(alien).id};
        });
        callerAgentId = seeded[0];
        ownConversationId = seeded[1];
        alienConversationId = seeded[2];
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
