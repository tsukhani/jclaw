import channels.ChannelTransport;
import models.Agent;
import models.SlackBinding;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;

/**
 * Unit coverage for {@link SlackBinding} finders (JCLAW-441) — chiefly
 * {@link SlackBinding#findByAgentOrAncestor}, the parent-chain walking finder
 * used by the delivery-side callers ({@link services.DeliveryDispatcher},
 * {@link channels.ChannelRegistry}) so sub-agents spawned by an agent reach the
 * user via the bot its root ancestor owns. Mirrors {@code TelegramBindingTest}.
 *
 * <p>The 1:1 agent invariant (strict {@code findByAgent}) and the model defaults
 * (HTTP transport, enabled) are pinned here since they're easy to regress when
 * touching the binding or spawn code paths.
 */
class SlackBindingTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void findByAgentOrAncestorReturnsOwnBindingWhenPresent() {
        // Baseline: an agent with its own binding gets that binding back — the
        // walk short-circuits on the first hit before reaching any parent.
        var owner = createAgent("sb-direct-owner");
        Tx.run(() -> seedBinding(owner, "xoxb-owner", "UOWNER", true));

        var found = Tx.run(() -> SlackBinding.findByAgentOrAncestor(owner));
        assertNotNull(found, "agent with own binding must resolve to that binding");
        assertEquals("xoxb-owner", found.botToken);
        assertEquals(owner.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorWalksOneHopToParent() {
        // The common subagent case: parent owns the binding, child is a fresh
        // Agent row with parentAgent set. Child lookup must surface the parent's
        // binding so an agent-initiated Slack send reaches the user without
        // per-subagent binding setup.
        var parent = createAgent("sb-one-hop-parent");
        Tx.run(() -> seedBinding(parent, "xoxb-parent", "UPARENT", true));
        var child = Tx.run(() -> {
            var c = AgentService.create("sb-one-hop-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> SlackBinding.findByAgentOrAncestor(child));
        assertNotNull(found, "child without binding must inherit parent's binding via walk");
        assertEquals("xoxb-parent", found.botToken);
        assertEquals(parent.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorWalksMultipleHopsToGrandparent() {
        // Sub-agents can spawn sub-agents. A grandchild's lookup must keep walking
        // past the binding-less parent to the grandparent.
        var grandparent = createAgent("sb-multi-hop-grand");
        Tx.run(() -> seedBinding(grandparent, "xoxb-grand", "UGRAND", true));
        var parent = Tx.run(() -> {
            var p = AgentService.create("sb-multi-hop-mid", "openrouter", "gpt-4.1");
            p.parentAgent = grandparent;
            p.save();
            return p;
        });
        var child = Tx.run(() -> {
            var c = AgentService.create("sb-multi-hop-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> SlackBinding.findByAgentOrAncestor(child));
        assertNotNull(found, "grandchild must inherit grandparent's binding through 2-hop walk");
        assertEquals("xoxb-grand", found.botToken);
        assertEquals(grandparent.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorReturnsNullWhenNoChainHasBinding() {
        // Sub-agent under a parent with no binding either — walk completes and
        // returns null cleanly. Caller translates null → "configure a bot" error.
        var orphanParent = createAgent("sb-no-chain-parent");
        var child = Tx.run(() -> {
            var c = AgentService.create("sb-no-chain-child", "openrouter", "gpt-4.1");
            c.parentAgent = orphanParent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> SlackBinding.findByAgentOrAncestor(child));
        assertNull(found, "chain with no bindings must return null, not crash or loop");
    }

    @Test
    void findByAgentOrAncestorNullSafeOnNullAgent() {
        // Defensive: callers may pass null when an agent context is missing (e.g.
        // ChannelRegistry.forChannel with a null agent on queue dispatch). Walk
        // must return null without NPE so the caller's guard fires.
        var found = Tx.run(() -> SlackBinding.findByAgentOrAncestor(null));
        assertNull(found, "null agent must return null without NPE");
    }

    @Test
    void findByAgentIsStrictAndDoesNotInherit() {
        // CRUD callers (admin add/remove) need exact-row identity: a child must
        // NOT resolve to the parent's binding via the strict finder, or the 1:1
        // agent uniqueness check would wrongly think the child is already bound.
        var parent = createAgent("sb-strict-parent");
        Tx.run(() -> seedBinding(parent, "xoxb-strict", "USTRICT", true));
        var child = Tx.run(() -> {
            var c = AgentService.create("sb-strict-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        assertNotNull(Tx.run(() -> SlackBinding.findByAgent(parent)), "parent has its own binding");
        assertNull(Tx.run(() -> SlackBinding.findByAgent(child)),
                "strict findByAgent must not inherit the parent's binding");
    }

    @Test
    void findByBotTokenResolvesBinding() {
        var owner = createAgent("sb-token-owner");
        Tx.run(() -> seedBinding(owner, "xoxb-find-me", "UTOK", true));

        var found = Tx.run(() -> SlackBinding.findByBotToken("xoxb-find-me"));
        assertNotNull(found);
        assertEquals(owner.id, found.agent.id);
    }

    @Test
    void defaultsTransportToHttpAndEnabledTrue() {
        // A binding saved without an explicit transport/enabled keeps the model
        // defaults: HTTP (Events API) and enabled. Pins the contract the webhook
        // path + dashboard count rely on.
        var owner = createAgent("sb-defaults-owner");
        Tx.run(() -> {
            var b = new SlackBinding();
            b.agent = owner;
            b.botToken = "xoxb-defaults";
            b.signingSecret = "sec";
            b.save();
        });

        var found = Tx.run(() -> SlackBinding.findByBotToken("xoxb-defaults"));
        assertNotNull(found);
        assertEquals(ChannelTransport.HTTP, found.transport, "default transport must be HTTP");
        assertTrue(found.enabled, "binding must default to enabled");
    }

    private Agent createAgent(String name) {
        return Tx.run(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    private static void seedBinding(Agent agent, String token, String botUserId, boolean enabled) {
        var b = new SlackBinding();
        b.agent = agent;
        b.botToken = token;
        b.signingSecret = "signing-secret-fixture";
        b.botUserId = botUserId;
        b.transport = ChannelTransport.HTTP;
        b.enabled = enabled;
        b.save();
    }
}
