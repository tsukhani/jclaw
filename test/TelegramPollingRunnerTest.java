import channels.ChannelTransport;
import channels.TelegramPollingRunner;
import models.Agent;
import models.TelegramBinding;
import org.junit.jupiter.api.*;
import play.test.*;
import services.Tx;

import java.util.function.Supplier;

/**
 * Unit-ish coverage for {@link TelegramPollingRunner} (JCLAW-313). The
 * runner owns three pieces of testable state outside the SDK's network
 * path:
 *
 * <ol>
 *   <li>{@code activeBindingIds()} — the set of bindings the runner has
 *       registered against the long-polling app.</li>
 *   <li>{@code cooldownRemainingMs/cooldownUntil} — the post-unregister
 *       cooldown that defers re-registration to avoid Telegram's HTTP 409
 *       "terminated by other getUpdates" race.</li>
 *   <li>{@code reconcile()} — the idempotent sync from DB → live sessions.</li>
 * </ol>
 *
 * <p>Happy-path long-poll dispatch is NOT covered here: the SDK calls
 * {@code api.telegram.org} directly through an inner {@code BotSession},
 * and rerouting that traffic would require modifying production code
 * (forbidden by the story). The dispatch logic itself is exercised
 * end-to-end by {@code TelegramChannelTest} / {@code TelegramMediaGroupBufferTest}.
 */
class TelegramPollingRunnerTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // Drain anything a prior test (or boot) left in the static maps.
        try { TelegramPollingRunner.stop(); } catch (Exception _) { /* best-effort */ }
    }

    @AfterEach
    void teardown() {
        try { TelegramPollingRunner.stop(); } catch (Exception _) { /* best-effort */ }
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
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
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private Long seedPollingBinding(String agentName, String token, String tgUserId, boolean enabled) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = agentName;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = token;
            b.telegramUserId = tgUserId;
            b.transport = ChannelTransport.POLLING;
            b.enabled = enabled;
            b.save();
            return b.id;
        });
    }

    private Long seedWebhookBinding(String agentName, String token, String tgUserId) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = agentName;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var b = new TelegramBinding();
            b.agent = agent;
            b.botToken = token;
            b.telegramUserId = tgUserId;
            b.transport = ChannelTransport.WEBHOOK;
            b.webhookSecret = "ws";
            b.webhookUrl = "https://example.com/tg";
            b.enabled = true;
            b.save();
            return b.id;
        });
    }

    // ===== cooldown helpers (callable on any thread) =====

    @Test
    void cooldownRemainingMsReturnsZeroForUnknownToken() {
        assertEquals(0L, TelegramPollingRunner.cooldownRemainingMs("not-a-real-token"));
    }

    @Test
    void cooldownRemainingMsReturnsZeroForNullToken() {
        assertEquals(0L, TelegramPollingRunner.cooldownRemainingMs(null));
    }

    @Test
    void cooldownUntilReturnsNullForUnknownToken() {
        assertNull(TelegramPollingRunner.cooldownUntil("not-a-real-token"));
    }

    @Test
    void cooldownUntilReturnsNullForNullToken() {
        assertNull(TelegramPollingRunner.cooldownUntil(null));
    }

    // ===== reconcile() against the DB =====

    @Test
    void reconcileWithEmptyDbLeavesNoActiveBindings() {
        // No rows seeded — reconcile should be a no-op and leave the
        // active set empty.
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty(),
                "active set should be empty when no enabled POLLING bindings exist");
    }

    @Test
    void reconcileSkipsWebhookTransportBindings() {
        // WEBHOOK bindings live on the inbound HTTP path; the polling
        // runner must not register them.
        seedWebhookBinding("webhook-only", "111:tok", "1");
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty(),
                "WEBHOOK transport must not be registered with the polling runner");
    }

    @Test
    void reconcileSkipsDisabledBindings() {
        // findAllEnabledByTransport filters out enabled=false rows; the
        // runner must respect that.
        seedPollingBinding("disabled-binding-agent", "111:tok", "1", /* enabled */ false);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty(),
                "disabled bindings must not be registered");
    }

    @Test
    @Disabled("JCLAW-316: app.registerBot makes a real HTTPS call to api.telegram.org; needs an injection seam on TelegramPollingRunner before this can pass")
    void reconcileRegistersEnabledPollingBinding() {
        // Happy-path registration: enabled POLLING binding → present in
        // activeBindingIds(). We don't assert isRunning() because the SDK
        // schedules a background poller that would talk to api.telegram.org;
        // the static map mutation is what we're verifying here.
        var bindingId = seedPollingBinding("polling-agent", "111:tok", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(bindingId),
                "enabled POLLING binding should land in the active set");
    }

    @Test
    @Disabled("JCLAW-316: depends on app.registerBot succeeding for the initial registration")
    void reconcileIsIdempotent() {
        // Calling reconcile twice with no DB change must leave the active
        // set unchanged — the start-loop guard depends on this.
        var bindingId = seedPollingBinding("idempotent-agent", "111:tok", "1", true);
        TelegramPollingRunner.reconcile();
        var first = new java.util.HashSet<>(TelegramPollingRunner.activeBindingIds());
        TelegramPollingRunner.reconcile();
        var second = TelegramPollingRunner.activeBindingIds();
        assertEquals(first, second,
                "reconcile() must be idempotent under unchanged DB state");
        assertTrue(second.contains(bindingId), "binding should still be active");
    }

    @Test
    @Disabled("JCLAW-316: depends on app.registerBot succeeding for the initial registration")
    void reconcileDropsBindingRemovedFromDb() {
        // Bring a binding up, then delete it and reconcile again — the
        // active set must drop it.
        var bindingId = seedPollingBinding("ephemeral-agent", "111:tok", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(bindingId));

        commitInFreshTx(() -> {
            TelegramBinding.<TelegramBinding>findById(bindingId).delete();
            return null;
        });
        TelegramPollingRunner.reconcile();
        assertFalse(TelegramPollingRunner.activeBindingIds().contains(bindingId),
                "deleted binding should be stripped from the active set");
    }

    @Test
    @Disabled("JCLAW-316: depends on app.registerBot succeeding for the initial registration")
    void reconcileUnregistersBindingFlippedToDisabled() {
        // Disabling a row is the operator's pause button: reconcile must
        // unregister the session so polling stops, and stamp the cooldown
        // on the prior token to prevent the HTTP 409 race when the
        // operator immediately re-enables.
        var bindingId = seedPollingBinding("pausable-agent", "111:tok", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(bindingId));

        commitInFreshTx(() -> {
            var b = TelegramBinding.<TelegramBinding>findById(bindingId);
            b.enabled = false;
            b.save();
            return null;
        });
        TelegramPollingRunner.reconcile();
        assertFalse(TelegramPollingRunner.activeBindingIds().contains(bindingId),
                "disabling a binding must unregister it");
        // Token cooldown stamped at unregister time so a quick re-enable
        // doesn't trip Telegram's "terminated by other getUpdates" 409.
        assertTrue(TelegramPollingRunner.cooldownRemainingMs("111:tok") > 0,
                "unregister should stamp a cooldown on the prior token");
        assertNotNull(TelegramPollingRunner.cooldownUntil("111:tok"),
                "cooldownUntil should expose an Instant during the active window");
    }

    @Test
    @Disabled("JCLAW-316: depends on app.registerBot succeeding for the initial registration")
    void reconcileRestartsOnTokenRotation() {
        // When the operator rotates the bot token, reconcile must drop the
        // old registration and create a new one for the same bindingId.
        var bindingId = seedPollingBinding("rotation-agent", "OLD:tok", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(bindingId));

        commitInFreshTx(() -> {
            var b = TelegramBinding.<TelegramBinding>findById(bindingId);
            b.botToken = "NEW:tok";
            b.save();
            return null;
        });
        TelegramPollingRunner.reconcile();
        // Binding id is still in the active set, just bound to the new token.
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(bindingId),
                "token rotation must not drop the binding from the active set");
        assertTrue(TelegramPollingRunner.cooldownRemainingMs("OLD:tok") > 0,
                "old token should carry a cooldown after rotation");
    }

    @Test
    @Disabled("JCLAW-316: depends on app.registerBot succeeding for the initial registration")
    void reconcileDefersRegistrationWhileTokenInCooldown() {
        // Create → register → disable to stamp cooldown → re-enable.
        // The second reconcile finds the token still in cooldown and
        // must defer rather than retry-and-409.
        var bindingId = seedPollingBinding("cooldown-agent", "CD:tok", "1", true);
        TelegramPollingRunner.reconcile();

        commitInFreshTx(() -> {
            var b = TelegramBinding.<TelegramBinding>findById(bindingId);
            b.enabled = false;
            b.save();
            return null;
        });
        TelegramPollingRunner.reconcile();
        // Cooldown is now stamped.
        assertTrue(TelegramPollingRunner.cooldownRemainingMs("CD:tok") > 0);

        // Re-enable mid-cooldown and reconcile: registration must be
        // deferred (binding NOT in active set).
        commitInFreshTx(() -> {
            var b = TelegramBinding.<TelegramBinding>findById(bindingId);
            b.enabled = true;
            b.save();
            return null;
        });
        TelegramPollingRunner.reconcile();
        assertFalse(TelegramPollingRunner.activeBindingIds().contains(bindingId),
                "registration must be deferred while the token is in cooldown");
    }

    // ===== stop() =====

    @Test
    void stopClearsActiveBindings() {
        seedPollingBinding("stop-agent", "STOP:tok", "1", true);
        TelegramPollingRunner.reconcile();
        // (May be empty if registration failed, but the contract under
        // test is: after stop(), the active set is empty regardless.)
        TelegramPollingRunner.stop();
        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty(),
                "stop() must drain the active set");
        assertFalse(TelegramPollingRunner.isRunning(),
                "stop() must mark the runner as not running");
    }

    @Test
    void stopIsSafeToCallWhenNothingRegistered() {
        // No reconcile() yet — APP is null. stop() must be a no-op rather
        // than NPE.
        TelegramPollingRunner.stop();
        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty());
        assertFalse(TelegramPollingRunner.isRunning());
    }
}
