import models.Agent;
import models.WhatsAppBinding;
import models.WhatsAppTransport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.Tx;

/**
 * Unit coverage for {@link WhatsAppBinding} finders (JCLAW-444) — chiefly
 * {@link WhatsAppBinding#findByAgentOrAncestor}, the parent-chain walking finder
 * sub-agents use to reach the number their root ancestor owns, and
 * {@link WhatsAppBinding#findByPhoneNumberId}, the Cloud-API routing/uniqueness
 * key. Mirrors {@code SlackBindingTest}.
 *
 * <p>The 1:1 agent invariant (strict {@code findByAgent}) and the model defaults
 * (CLOUD_API transport, enabled) are pinned here since they're easy to regress.
 */
class WhatsAppBindingTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void findByAgentOrAncestorReturnsOwnBindingWhenPresent() {
        var owner = createAgent("wb-direct-owner");
        Tx.run(() -> seedCloudBinding(owner, "phone-owner", true));

        var found = Tx.run(() -> WhatsAppBinding.findByAgentOrAncestor(owner));
        assertNotNull(found, "agent with own binding must resolve to that binding");
        assertEquals("phone-owner", found.phoneNumberId);
        assertEquals(owner.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorWalksOneHopToParent() {
        var parent = createAgent("wb-one-hop-parent");
        Tx.run(() -> seedCloudBinding(parent, "phone-parent", true));
        var child = Tx.run(() -> {
            var c = AgentService.create("wb-one-hop-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> WhatsAppBinding.findByAgentOrAncestor(child));
        assertNotNull(found, "child without binding must inherit parent's binding via walk");
        assertEquals("phone-parent", found.phoneNumberId);
        assertEquals(parent.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorWalksMultipleHopsToGrandparent() {
        var grandparent = createAgent("wb-multi-hop-grand");
        Tx.run(() -> seedCloudBinding(grandparent, "phone-grand", true));
        var parent = Tx.run(() -> {
            var p = AgentService.create("wb-multi-hop-mid", "openrouter", "gpt-4.1");
            p.parentAgent = grandparent;
            p.save();
            return p;
        });
        var child = Tx.run(() -> {
            var c = AgentService.create("wb-multi-hop-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> WhatsAppBinding.findByAgentOrAncestor(child));
        assertNotNull(found, "grandchild must inherit grandparent's binding through 2-hop walk");
        assertEquals("phone-grand", found.phoneNumberId);
        assertEquals(grandparent.id, found.agent.id);
    }

    @Test
    void findByAgentOrAncestorReturnsNullWhenNoChainHasBinding() {
        var orphanParent = createAgent("wb-no-chain-parent");
        var child = Tx.run(() -> {
            var c = AgentService.create("wb-no-chain-child", "openrouter", "gpt-4.1");
            c.parentAgent = orphanParent;
            c.save();
            return c;
        });

        var found = Tx.run(() -> WhatsAppBinding.findByAgentOrAncestor(child));
        assertNull(found, "chain with no bindings must return null, not crash or loop");
    }

    @Test
    void findByAgentOrAncestorNullSafeOnNullAgent() {
        var found = Tx.run(() -> WhatsAppBinding.findByAgentOrAncestor(null));
        assertNull(found, "null agent must return null without NPE");
    }

    @Test
    void findByAgentIsStrictAndDoesNotInherit() {
        var parent = createAgent("wb-strict-parent");
        Tx.run(() -> seedCloudBinding(parent, "phone-strict", true));
        var child = Tx.run(() -> {
            var c = AgentService.create("wb-strict-child", "openrouter", "gpt-4.1");
            c.parentAgent = parent;
            c.save();
            return c;
        });

        assertNotNull(Tx.run(() -> WhatsAppBinding.findByAgent(parent)), "parent has its own binding");
        assertNull(Tx.run(() -> WhatsAppBinding.findByAgent(child)),
                "strict findByAgent must not inherit the parent's binding");
    }

    @Test
    void findByPhoneNumberIdResolvesBinding() {
        var owner = createAgent("wb-phone-owner");
        Tx.run(() -> seedCloudBinding(owner, "phone-find-me", true));

        var found = Tx.run(() -> WhatsAppBinding.findByPhoneNumberId("phone-find-me"));
        assertNotNull(found);
        assertEquals(owner.id, found.agent.id);
    }

    @Test
    void findByPhoneNumberIdNullSafeOnNullOrBlank() {
        // WhatsApp-Web bindings have a null phoneNumberId; the finder must not
        // resolve them (and must not NPE) on a null/blank lookup.
        assertNull(Tx.run(() -> WhatsAppBinding.findByPhoneNumberId(null)));
        assertNull(Tx.run(() -> WhatsAppBinding.findByPhoneNumberId("  ")));
    }

    @Test
    void defaultsTransportToCloudApiAndEnabledTrue() {
        // A binding saved without an explicit transport/enabled keeps the model
        // defaults: CLOUD_API and enabled.
        var owner = createAgent("wb-defaults-owner");
        Tx.run(() -> {
            var b = new WhatsAppBinding();
            b.agent = owner;
            b.phoneNumberId = "phone-defaults";
            b.accessToken = "token";
            b.save();
        });

        var found = Tx.run(() -> WhatsAppBinding.findByPhoneNumberId("phone-defaults"));
        assertNotNull(found);
        assertEquals(WhatsAppTransport.CLOUD_API, found.transport, "default transport must be CLOUD_API");
        assertTrue(found.enabled, "binding must default to enabled");
    }

    @Test
    void whatsappWebBindingNeedsNoCredentials() {
        // A WhatsApp-Web binding carries no credentials at create time — it's
        // QR-paired later (JCLAW-448). It must persist with just an agent.
        var owner = createAgent("wb-web-owner");
        Tx.run(() -> {
            var b = new WhatsAppBinding();
            b.agent = owner;
            b.transport = WhatsAppTransport.WHATSAPP_WEB;
            b.save();
        });

        var found = Tx.run(() -> WhatsAppBinding.findByAgent(owner));
        assertNotNull(found, "a credential-less WhatsApp-Web binding must persist");
        assertEquals(WhatsAppTransport.WHATSAPP_WEB, found.transport);
        assertNull(found.phoneNumberId, "WhatsApp-Web has no phone number id until paired");
    }

    private Agent createAgent(String name) {
        return Tx.run(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    private static void seedCloudBinding(Agent agent, String phoneNumberId, boolean enabled) {
        var b = new WhatsAppBinding();
        b.agent = agent;
        b.transport = WhatsAppTransport.CLOUD_API;
        b.phoneNumberId = phoneNumberId;
        b.accessToken = "access-token-fixture";
        b.enabled = enabled;
        b.save();
    }
}
