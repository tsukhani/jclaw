import channels.ChannelTransport;
import channels.TelegramPollingRunner;
import channels.TelegramPollingRunnerTestHooks;
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
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TelegramPollingRunnerTest extends FunctionalTest {

    private InMemoryPollingApp fakeApp;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // JCLAW-316: clear the runner's static state without shutting down
        // its background scheduler — stop() would kill SCHEDULER, and a
        // later unregister via reconcile() schedules a delayed re-reconcile
        // that would then RejectedExecutionException. clear() is the test-
        // only state reset; stop() stays the production shutdown path.
        TelegramPollingRunnerTestHooks.clear();
        // Install an in-memory long-polling app so reconcile() can register
        // bindings without dialing api.telegram.org.
        fakeApp = new InMemoryPollingApp();
        TelegramPollingRunnerTestHooks.setApp(fakeApp);
    }

    @AfterEach
    void teardown() {
        TelegramPollingRunnerTestHooks.clear();
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

    // ===== Registration-dependent reconcile() state machine (JCLAW-316) =====
    //
    // These six exercise reconcile() against the in-memory polling app
    // installed in @BeforeEach. Each test seeds DB rows, optionally mutates
    // them in a fresh committed transaction, and asserts on activeBindingIds()
    // / cooldownRemainingMs() — no api.telegram.org traffic.

    @Test
    void reconcileRegistersEnabledPollingBinding() {
        Long id = seedPollingBinding("agent-a", "111:tokA", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id),
                "enabled POLLING binding should be registered");
    }

    @Test
    void reconcileIsIdempotent() {
        Long id = seedPollingBinding("agent-b", "222:tokB", "2", true);
        TelegramPollingRunner.reconcile();
        var first = TelegramPollingRunner.activeBindingIds();
        TelegramPollingRunner.reconcile();
        var second = TelegramPollingRunner.activeBindingIds();
        assertEquals(first, second,
                "two reconciles with unchanged DB state should yield the same active set");
        assertTrue(second.contains(id));
    }

    @Test
    void reconcileDropsBindingRemovedFromDb() {
        Long id = seedPollingBinding("agent-c", "333:tokC", "3", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        // Delete the row in a committed transaction so reconcile()'s read
        // sees it gone.
        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            if (b != null) b.delete();
            return null;
        });

        TelegramPollingRunner.reconcile();
        assertFalse(TelegramPollingRunner.activeBindingIds().contains(id),
                "binding removed from DB should be unregistered on next reconcile");
    }

    @Test
    void reconcileUnregistersBindingFlippedToDisabled() {
        String token = "444:tokD";
        Long id = seedPollingBinding("agent-d", token, "4", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            b.enabled = false;
            b.save();
            return null;
        });

        TelegramPollingRunner.reconcile();
        assertFalse(TelegramPollingRunner.activeBindingIds().contains(id),
                "binding flipped to disabled should be unregistered");
        assertTrue(TelegramPollingRunner.cooldownRemainingMs(token) > 0L,
                "unregister should stamp cooldown on the prior token");
    }

    @Test
    void cooldownSchedulerDropsDeferredReconcileOnShutdown() throws Exception {
        // JCLAW-335: a binding unregister schedules a reconcile ~30.5s out
        // (COOLDOWN_MS + 500). At shutdown the scheduler must not wait on that
        // deferred task — gracefulShutdown only awaits 5s, and an unconfigured
        // ScheduledThreadPoolExecutor would force-kill it with a WARN after the
        // 5s tail. Verify newScheduler() drops queued delayed tasks on
        // shutdown() so the drain is immediate.
        var newScheduler = TelegramPollingRunner.class.getDeclaredMethod("newScheduler");
        newScheduler.setAccessible(true);
        var exec = (java.util.concurrent.ScheduledExecutorService) newScheduler.invoke(null);
        try {
            exec.schedule(() -> { }, 60, java.util.concurrent.TimeUnit.SECONDS); // stand in for the deferred reconcile
            exec.shutdown();
            assertTrue(exec.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS),
                    "scheduler must terminate promptly, not wait out the deferred reconcile");
        } finally {
            exec.shutdownNow();
        }
    }

    @Test
    void reconcileRestartsOnTokenRotation() {
        String tokenA = "555:tokA";
        String tokenB = "555:tokB";
        Long id = seedPollingBinding("agent-e", tokenA, "5", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            b.botToken = tokenB;
            b.save();
            return null;
        });
        // L1-cache eviction: the prior reconcile() loaded `binding(tokenA)`
        // into the test thread's EntityManager. commitInFreshTx mutated the
        // row in a separate-thread Tx, but JPA's L1 cache on this thread
        // still has the old entity. Without clear(), the next reconcile()'s
        // findAllEnabledByTransport returns the cached binding (still
        // tokenA), so the rotation branch never trips. delete- and
        // disable-flavoured tests don't hit this because their filters
        // exclude the row entirely after the mutation, so cache hit is
        // moot.
        play.db.jpa.JPA.em().clear();

        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id),
                "binding should still be active under the rotated token");
        assertTrue(TelegramPollingRunner.cooldownRemainingMs(tokenA) > 0L,
                "old token should have cooldown stamped after rotation");
        assertEquals(0L, TelegramPollingRunner.cooldownRemainingMs(tokenB),
                "new token should be live (no cooldown)");
    }

    @Test
    void reconcileDefersRegistrationWhileTokenInCooldown() {
        String token = "666:tokF";
        Long id = seedPollingBinding("agent-f", token, "6", true);

        // Register, then disable so reconcile() unregisters + stamps cooldown.
        TelegramPollingRunner.reconcile();
        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            b.enabled = false;
            b.save();
            return null;
        });
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.cooldownRemainingMs(token) > 0L);

        // Re-enable and reconcile again — registerInternal must see the live
        // cooldown and bail out without populating ACTIVE.
        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            b.enabled = true;
            b.save();
            return null;
        });
        TelegramPollingRunner.reconcile();
        assertFalse(TelegramPollingRunner.activeBindingIds().contains(id),
                "re-enable during cooldown must defer registration");
    }

    // ===== stop() =====

    /**
     * Runs last because {@code stop()} also shuts down the runner's static
     * {@code SCHEDULER}; any subsequent test that triggers an unregister
     * would then hit a {@code RejectedExecutionException} from
     * {@code SCHEDULER.schedule(...)}.
     */
    @Test
    @Order(Integer.MAX_VALUE)
    void stopIsSafeToCallWhenNothingRegistered() {
        // No reconcile() yet — APP is null. stop() must be a no-op rather
        // than NPE.
        TelegramPollingRunner.stop();
        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty());
        assertFalse(TelegramPollingRunner.isRunning());
    }
}
