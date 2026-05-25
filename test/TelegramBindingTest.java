import models.Agent;
import models.TelegramBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;

/**
 * Unit coverage for {@link TelegramBinding#findByAgentOrAncestor} — the
 * parent-chain walking finder used by delivery-side callers
 * ({@link services.DeliveryDispatcher}, {@link channels.TelegramModelSelector})
 * so sub-agents created by {@code subagent_spawn} reach the user via the
 * binding their root ancestor owns.
 *
 * <p>Plain {@code findByAgent} is exercised implicitly across many
 * existing tests; this file focuses on the parent-walk semantics that
 * delivery resolution relies on, since they're easy to regress when
 * touching the spawn/binding code paths.
 */
class TelegramBindingTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void findByAgentOrAncestorReturnsOwnBindingWhenPresent() {
        // Baseline: an agent with its own binding gets that binding back —
        // walking never reaches the parent because the lookup short-circuits
        // on the first hit.
        var owner = createAgent("tb-direct-owner");
        Tx.run(() -> seedBinding(owner, "tok-owner", "100", true));

        var found = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(owner));
        assertNotNull(found, "agent with own binding must resolve to that binding");
        assertEquals("tok-owner", found.botToken);
        assertEquals(owner.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorWalksOneHopToParent() {
        // The common subagent_spawn case: parent owns the binding, child is
        // a fresh Agent row with parentAgent set. Child lookup must surface
        // the parent's binding so the message tool can deliver to the user's
        // chat without per-subagent binding setup.
        var parent = createAgent("tb-one-hop-parent");
        Tx.run(() -> seedBinding(parent, "tok-parent", "200", true));
        var child = Tx.run(() -> {
            var c = AgentService.create("tb-one-hop-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(child));
        assertNotNull(found, "child without binding must inherit parent's binding via walk");
        assertEquals("tok-parent", found.botToken);
        assertEquals(parent.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorWalksMultipleHopsToGrandparent() {
        // Sub-agents can themselves spawn sub-agents. A grandchild's lookup
        // must keep walking past the binding-less parent to the grandparent.
        var grandparent = createAgent("tb-multi-hop-grand");
        Tx.run(() -> seedBinding(grandparent, "tok-grandparent", "300", true));
        var parent = Tx.run(() -> {
            var p = AgentService.create("tb-multi-hop-mid", "openrouter", "gpt-4.1");
            p.parentAgent = grandparent;
            p.save();
            return p;
        });
        var child = Tx.run(() -> {
            var c = AgentService.create("tb-multi-hop-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(child));
        assertNotNull(found, "grandchild must inherit grandparent's binding through 2-hop walk");
        assertEquals("tok-grandparent", found.botToken);
        assertEquals(grandparent.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorReturnsNullWhenNoChainHasBinding() {
        // Sub-agent under a parent with no binding either — walk completes
        // and returns null cleanly, mirroring strict findByAgent's "absent"
        // shape. Caller is responsible for translating null → user-facing
        // "configure a bot" error.
        var orphanParent = createAgent("tb-no-chain-parent");
        var child = Tx.run(() -> {
            var c = AgentService.create("tb-no-chain-child", "openrouter", "gpt-4.1");
            c.parentAgent = orphanParent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(child));
        assertNull(found, "chain with no bindings must return null, not crash or loop");
    }

    @Test
    void findByAgentOrAncestorNullSafeOnNullAgent() {
        // Defensive: callers may pass null when an agent context is missing
        // (e.g. dispatch invoked from a non-agent code path). Walk must
        // return null without NPE so the caller's guard fires.
        var found = Tx.run(() -> TelegramBinding.findByAgentOrAncestor(null));
        assertNull(found, "null agent must return null without NPE");
    }

    private Agent createAgent(String name) {
        return Tx.run(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    private static void seedBinding(Agent agent, String token, String tgUserId, boolean enabled) {
        var b = new TelegramBinding();
        b.agent = agent;
        b.botToken = token;
        b.telegramUserId = tgUserId;
        b.enabled = enabled;
        b.save();
    }
}
