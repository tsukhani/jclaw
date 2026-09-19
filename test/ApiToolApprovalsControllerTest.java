import com.google.gson.JsonParser;
import models.Agent;
import models.ToolApprovalGrant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.Tx;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-1062: the list/revoke surface for standing {@link ToolApprovalGrant} rows.
 *
 * <p>The security assertions are the point. An "always allow" tap suppresses the approval
 * prompt for a {@code (agent, tool)} pair permanently, so the list answers "which dangerous
 * tools can I run unchallenged" — reconnaissance against the exact boundary the prompt
 * holds. Both read and write are refused to the agent principal per JCLAW-1023/1058.
 *
 * <p>No {@code Fixtures.deleteDatabase()}: play1 runs test classes concurrently, so every
 * assertion here is scoped to agents this class created. The roll-up assertions deliberately
 * check for this class's own agents rather than global totals, which a sibling class writing
 * a grant would otherwise make flaky.
 */
class ApiToolApprovalsControllerTest extends FunctionalTest {

    private static final String DANGEROUS_TOOL = "exec";

    @BeforeEach
    void setup() {
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    // ==================== operator-only ====================

    @Test
    void listRefusesTheAgentPrincipal() {
        var agentId = createAgent("approvals-list-403");
        var response = asAgent(() -> GET(agentRequest(), "/api/agents/" + agentId + "/tool-approvals"));
        assertStatus(403, response);
    }

    @Test
    void revokeRefusesTheAgentPrincipal() {
        var agentId = createAgent("approvals-revoke-403");
        seedGrant(agentId, DANGEROUS_TOOL);
        var response = asAgent(() ->
                DELETE(agentRequest(), "/api/agents/" + agentId + "/tool-approvals/" + DANGEROUS_TOOL));
        assertStatus(403, response);
        assertTrue(grantExists(agentId, DANGEROUS_TOOL),
                "a refused revoke must leave the grant standing");
    }

    @Test
    void summaryRefusesTheAgentPrincipal() {
        var response = asAgent(() -> GET(agentRequest(), "/api/tool-approvals/summary"));
        assertStatus(403, response);
    }

    // ==================== operator paths ====================

    @Test
    void operatorListsGrantsForOneAgent() {
        var agentId = createAgent("approvals-list");
        seedGrant(agentId, DANGEROUS_TOOL);
        login();

        var response = GET("/api/agents/" + agentId + "/tool-approvals");
        assertIsOk(response);
        var body = getContent(response);
        assertTrue(body.contains(DANGEROUS_TOOL), "the seeded grant must be listed, got: " + body);
    }

    @Test
    void operatorRevokeRemovesTheRow() {
        var agentId = createAgent("approvals-revoke");
        seedGrant(agentId, DANGEROUS_TOOL);
        login();

        var response = DELETE("/api/agents/" + agentId + "/tool-approvals/" + DANGEROUS_TOOL);
        assertIsOk(response);
        assertFalse(grantExists(agentId, DANGEROUS_TOOL), "revoke must delete the row");
    }

    @Test
    void revokingAGrantThatDoesNotExistIs404() {
        // "removed it" and "there was nothing to remove" are different answers when an
        // operator is clearing a list they believe is stale.
        var agentId = createAgent("approvals-revoke-missing");
        login();

        var response = DELETE("/api/agents/" + agentId + "/tool-approvals/no_such_tool");
        assertStatus(404, response);
    }

    @Test
    void summaryReportsTheAgentsOwnGrants() {
        var agentId = createAgent("approvals-summary");
        seedGrant(agentId, DANGEROUS_TOOL);
        login();

        var response = GET("/api/tool-approvals/summary");
        assertIsOk(response);
        var root = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertTrue(root.get("totalGrants").getAsInt() >= 1, "roll-up must count grants");

        var mine = root.getAsJsonArray("agents").asList().stream()
                .map(e -> e.getAsJsonObject())
                .filter(o -> o.get("agentId").getAsLong() == agentId)
                .findFirst()
                .orElse(null);
        assertNotNull(mine, "this agent must appear in the roll-up");
        assertTrue(mine.getAsJsonArray("tools").toString().contains(DANGEROUS_TOOL),
                "the roll-up must name the granted tool");
    }

    // ==================== helpers ====================

    private void login() {
        // FunctionalTest.savedCookies is JVM-global; a bearer response left by a
        // concurrent class would otherwise carry the agent principal into this session.
        clearCookies();
        var response = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(response);
    }

    /** Run an agent-principal request, then wipe the shared cookie jar — AuthCheck's bearer
     *  branch answers with a Set-Cookie carrying the agent principal. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        }
        finally {
            clearCookies();
        }
    }

    private static Http.Request agentRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }

    private Long createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1").id);
    }

    private void seedGrant(Long agentId, String toolName) {
        fetchInFreshTx(() -> {
            ToolApprovalGrant.upsert(Agent.findById(agentId), toolName, "telegram", "555");
            return null;
        });
    }

    private static boolean grantExists(Long agentId, String toolName) {
        return fetchInFreshTx(() -> ToolApprovalGrant.exists(agentId, toolName));
    }

    private static <T> T fetchInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            }
            catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
