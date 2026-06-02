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

    // ── JCLAW-378: per-binding setting overrides ──────────────────────────

    @Test
    void perBindingOverrideFieldsPersistAndRoundTrip() {
        // The three nullable override fields persist verbatim and reload.
        var owner = createAgent("tb-overrides-owner");
        Tx.run(() -> {
            var b = new TelegramBinding();
            b.agent = owner;
            b.botToken = "tok-overrides";
            b.telegramUserId = "500";
            b.replyToMode = "all";
            b.errorReplyPolicy = "silent";
            b.notifierCooldownMs = 12_345L;
            b.save();
        });

        var found = Tx.run(() -> TelegramBinding.findByBotToken("tok-overrides"));
        assertNotNull(found);
        assertEquals("all", found.replyToMode);
        assertEquals("silent", found.errorReplyPolicy);
        assertEquals(Long.valueOf(12_345L), found.notifierCooldownMs);
    }

    @Test
    void perBindingOverrideFieldsDefaultToNull() {
        // A binding seeded without overrides keeps all three fields null so the
        // consumers fall back to global config (existing-binding behavior).
        var owner = createAgent("tb-null-overrides");
        Tx.run(() -> seedBinding(owner, "tok-null-overrides", "501", true));

        var found = Tx.run(() -> TelegramBinding.findByBotToken("tok-null-overrides"));
        assertNotNull(found);
        assertNull(found.replyToMode);
        assertNull(found.errorReplyPolicy);
        assertNull(found.notifierCooldownMs);
    }

    @Test
    void overridesForTokenSnapshotsFields() {
        var owner = createAgent("tb-snapshot-owner");
        Tx.run(() -> {
            var b = new TelegramBinding();
            b.agent = owner;
            b.botToken = "tok-snapshot";
            b.telegramUserId = "502";
            b.replyToMode = "first";
            b.errorReplyPolicy = "reply";
            b.notifierCooldownMs = 90_000L;
            b.save();
        });

        var ov = TelegramBinding.overridesForToken("tok-snapshot");
        assertEquals("first", ov.replyToMode());
        assertEquals("reply", ov.errorReplyPolicy());
        assertEquals(Long.valueOf(90_000L), ov.cooldownMs());
    }

    @Test
    void overridesForTokenNormalizesNonPositiveCooldownToNull() {
        // A stored non-positive cooldown is treated as absent so the resolver
        // falls back to config rather than rate-limiting on a 0/negative window.
        var owner = createAgent("tb-bad-cooldown-owner");
        Tx.run(() -> {
            var b = new TelegramBinding();
            b.agent = owner;
            b.botToken = "tok-bad-cooldown";
            b.telegramUserId = "503";
            b.notifierCooldownMs = 0L;
            b.save();
        });

        var ov = TelegramBinding.overridesForToken("tok-bad-cooldown");
        assertNull(ov.cooldownMs(), "non-positive stored cooldown must snapshot as null");
    }

    @Test
    void overridesForTokenEmptyForBlankOrMissingToken() {
        // Blank token and unknown token both resolve to EMPTY so callers never
        // need a null check.
        assertSame(TelegramBinding.SettingOverrides.EMPTY,
                TelegramBinding.overridesForToken("  "));
        var missing = Tx.run(() -> TelegramBinding.overridesForToken("tok-does-not-exist"));
        assertNull(missing.replyToMode());
        assertNull(missing.errorReplyPolicy());
        assertNull(missing.cooldownMs());
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
