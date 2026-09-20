import controllers.RequestPrincipal;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * The whole agent-scoping matrix, as a table (JCLAW-1270).
 *
 * <p>{@code AgentScopedAccessTest} drives the wire — header in, 403 or 200 out — but it cannot
 * cover the {@code main} row: {@code main} is provisioned at boot rather than per test, a
 * sibling's {@code Fixtures.deleteDatabase} removes it mid-suite, and AGENTS.md forbids seeding
 * one. So the policy is tested here against the request-free overloads, where every combination
 * is reachable and none of it depends on what else the fork is running.
 *
 * <p>No rows are persisted: {@code isMain} is a name comparison and the ownership test is an id
 * comparison, so a detached entity exercises both.
 */
class RequestPrincipalPolicyTest extends UnitTest {

    private static final boolean AS_AGENT = true;
    private static final boolean AS_OPERATOR = false;

    private static Agent agent(Long id, String name) {
        var a = new Agent();
        a.id = id;
        a.name = name;
        return a;
    }

    private static Agent main() {
        return agent(1L, Agent.MAIN_AGENT_NAME);
    }

    @Test
    void theOperatorReachesEverything() {
        // The caller is null on the operator path — no header is read — so a policy that
        // dereferenced it would fail here rather than in production.
        assertTrue(RequestPrincipal.isOperatorOrMain(AS_OPERATOR, null));
        assertTrue(RequestPrincipal.mayReachRow(AS_OPERATOR, null, agent(7L, "someone-else")));
        assertTrue(RequestPrincipal.mayReachRow(AS_OPERATOR, null, null));
    }

    @Test
    void mainReachesEveryAgentsRows() {
        assertTrue(RequestPrincipal.isOperatorOrMain(AS_AGENT, main()));
        assertTrue(RequestPrincipal.mayReachRow(AS_AGENT, main(), agent(7L, "custom")));
    }

    @Test
    void aCustomAgentReachesOnlyItsOwn() {
        var custom = agent(7L, "custom");
        assertFalse(RequestPrincipal.isOperatorOrMain(AS_AGENT, custom));
        assertTrue(RequestPrincipal.mayReachRow(AS_AGENT, custom, agent(7L, "custom")),
                "same id is the same agent even across two loads of the row");
        assertFalse(RequestPrincipal.mayReachRow(AS_AGENT, custom, agent(8L, "other")));
    }

    @Test
    void nothingReachesUpOrAcross() {
        // The direction that matters: main sees down the tree, a custom agent sees neither main
        // nor a sibling. Asserted explicitly because it is the one asymmetry a future edit is
        // most likely to flatten into "agents see each other".
        var custom = agent(7L, "custom");
        assertFalse(RequestPrincipal.mayReachRow(AS_AGENT, custom, main()));
        assertFalse(RequestPrincipal.mayReachRow(AS_AGENT, custom, agent(8L, "sibling")));
    }

    @Test
    void anUnidentifiedAgentReachesNothing() {
        // A bearer used outside jclaw_api carries no agent header. It must get less reach than
        // an identified agent, never more — including against an ownerless row.
        assertFalse(RequestPrincipal.isOperatorOrMain(AS_AGENT, null));
        assertFalse(RequestPrincipal.mayReachRow(AS_AGENT, null, agent(7L, "custom")));
        assertFalse(RequestPrincipal.mayReachRow(AS_AGENT, null, null));
    }

    @Test
    void anOwnerlessRowIsOperatorAndMainOnly() {
        // mayReachRow(_, _, null) is how a caller-only question is asked — the STDIO transport
        // guard is the live example. A custom agent must not slip through on a null owner.
        assertTrue(RequestPrincipal.mayReachRow(AS_AGENT, main(), null));
        assertFalse(RequestPrincipal.mayReachRow(AS_AGENT, agent(7L, "custom"), null));
    }

    @Test
    void mainIsMatchedByNameCaseInsensitively() {
        // Agent.isMain lowercases, so a row named "Main" is still main. Pinned because the
        // scoping policy would otherwise hand a differently-cased row a custom agent's reach.
        assertTrue(RequestPrincipal.isOperatorOrMain(AS_AGENT, agent(1L, "Main")));
    }
}
