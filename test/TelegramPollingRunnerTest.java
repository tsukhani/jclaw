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

    // Registration-dependent tests (reconcileRegistersEnabledPollingBinding,
    // reconcileIsIdempotent, reconcileDropsBindingRemovedFromDb,
    // reconcileUnregistersBindingFlippedToDisabled, reconcileRestartsOnTokenRotation,
    // reconcileDefersRegistrationWhileTokenInCooldown) live under JCLAW-316: they
    // need a TelegramBotsLongPollingApplication injection seam before they can
    // verify our state-machine contracts without dragging a real api.telegram.org
    // round-trip into the test. JCLAW-316 ships the seam plus the tests together.

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
