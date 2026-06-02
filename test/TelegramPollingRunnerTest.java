import channels.ChannelTransport;
import channels.TelegramOffsetStore;
import channels.TelegramPollingRunner;
import channels.TelegramPollingRunnerTestHooks;
import models.Agent;
import models.TelegramBinding;
import org.junit.jupiter.api.*;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import play.test.*;
import services.Tx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

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
    private Path offsetTmp;

    @BeforeEach
    void setup() throws Exception {
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
        // JCLAW-361: redirect the offset store at a per-test temp dir so the
        // runner's offset persist/seed lands in tmp, never production state.
        offsetTmp = Files.createTempDirectory("jclaw-tg-offset-runner-");
        System.setProperty(TelegramOffsetStore.OFFSET_PATH_PROPERTY, offsetTmp.toString());
    }

    @AfterEach
    void teardown() throws Exception {
        TelegramPollingRunnerTestHooks.clear();
        System.clearProperty(TelegramOffsetStore.OFFSET_PATH_PROPERTY);
        if (offsetTmp != null && Files.exists(offsetTmp)) {
            try (Stream<Path> walk = Files.walk(offsetTmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception _) { /* best-effort */ }
                });
            }
        }
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
            b.webhookBaseUrl = "https://example.com/tg";
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

    // ===== JCLAW-361: offset persistence + seeding wiring =====

    @Test
    void consumingAnUpdatePersistsItsOffset() {
        String token = "987:tokOffset";
        seedPollingBinding("agent-offset", token, "9", true);
        TelegramPollingRunner.reconcile();

        // Drive the consumer the runner registered with a bare Update carrying
        // only an update_id. dispatch() short-circuits (no message/callback) so
        // no agent/DB path runs; the runner's wrapper then records the offset.
        var consumer = fakeApp.consumerFor(token);
        assertNotNull(consumer, "runner should have registered a consumer for the token");
        Update update = new Update();
        update.setUpdateId(4242);
        ((LongPollingSingleThreadUpdateConsumer) consumer).consume(update);

        assertEquals(4242, TelegramOffsetStore.load(token),
                "consuming an update should persist its update_id as the new offset");
    }

    @Test
    void registerSeedsGetUpdatesOffsetFromPersistedValue() {
        String token = "654:tokSeed";
        // Pre-persist a high-water mark as if a prior JVM had consumed up to 7000.
        TelegramOffsetStore.record(token, 7000);

        seedPollingBinding("agent-seed", token, "6", true);
        TelegramPollingRunner.reconcile();

        // On a fresh BotSession the SDK passes lastReceivedUpdate=0; the runner's
        // seeding generator must clamp the requested offset to persisted+1 so
        // already-consumed updates (<= 7000) are skipped on restart.
        Function<Integer, GetUpdates> gen = fakeApp.generatorFor(token);
        assertNotNull(gen, "runner should have registered a getUpdates generator for the token");
        GetUpdates firstPoll = gen.apply(0);
        assertEquals(7001, firstPoll.getOffset(),
                "first poll after restart must request offset persisted+1, not 1");

        // Once the live session advances past the seed, the SDK's own value wins
        // (monotonic — the seed never rewinds a running session).
        GetUpdates laterPoll = gen.apply(8000);
        assertEquals(8001, laterPoll.getOffset(),
                "after the session advances past the seed, the SDK value drives the offset");
    }

    // ===== JCLAW-363: liveness watchdog + transport rebuild =====
    //
    // These drive the watchdog through TelegramPollingRunnerTestHooks: an
    // injectable clock (advance "now" without sleeping), a watchdog-ms override
    // (bypass application.conf), and a synchronous tick. A rebuild manifests as
    // the binding leaving activeBindingIds() with its token stamped into
    // cooldown — the existing scheduled cooldown reconcile then re-registers it
    // ~30.5s out, which never fires inside a fast test.

    @Test
    void consumingAnUpdateAdvancesLastProgress() {
        String token = "771:tokWd";
        seedPollingBinding("agent-wd-progress", token, "7", true);
        // Freeze the clock at t0 so register() seeds last-progress to a known value.
        TelegramPollingRunnerTestHooks.setClock(() -> 1_000L);
        TelegramPollingRunner.reconcile();
        assertEquals(Long.valueOf(1_000L), TelegramPollingRunnerTestHooks.lastProgress(token),
                "register should seed last-progress to the current clock value");

        // Advance the clock, then drive the consumer; the wrapper must restamp.
        TelegramPollingRunnerTestHooks.setClock(() -> 5_000L);
        var consumer = fakeApp.consumerFor(token);
        assertNotNull(consumer);
        Update update = new Update();
        update.setUpdateId(11);
        ((LongPollingSingleThreadUpdateConsumer) consumer).consume(update);

        assertEquals(Long.valueOf(5_000L), TelegramPollingRunnerTestHooks.lastProgress(token),
                "consuming an update should advance last-progress to the current clock value");
    }

    @Test
    void watchdogRebuildsAStaleBinding() {
        String token = "772:tokWd";
        Long id = seedPollingBinding("agent-wd-stale", token, "7", true);
        TelegramPollingRunnerTestHooks.setWatchdogMs(120_000L);
        TelegramPollingRunnerTestHooks.setClock(() -> 0L); // register seeds progress at t0
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        // Jump the clock well past the timeout with no intervening consume.
        TelegramPollingRunnerTestHooks.setClock(() -> 200_000L);
        TelegramPollingRunnerTestHooks.runWatchdogTick();

        assertFalse(TelegramPollingRunner.activeBindingIds().contains(id),
                "a binding stale past the watchdog timeout should be rebuilt (unregistered)");
        assertTrue(TelegramPollingRunner.cooldownRemainingMs(token) > 0L,
                "the rebuild should stamp the cooldown so re-register waits out Telegram's stale poll");
    }

    @Test
    void watchdogLeavesAFreshBindingAlone() {
        String token = "773:tokWd";
        Long id = seedPollingBinding("agent-wd-fresh", token, "7", true);
        TelegramPollingRunnerTestHooks.setWatchdogMs(120_000L);
        TelegramPollingRunnerTestHooks.setClock(() -> 0L);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        // Advance only a little — within the timeout — so the binding is healthy.
        TelegramPollingRunnerTestHooks.setClock(() -> 1_000L);
        TelegramPollingRunnerTestHooks.runWatchdogTick();

        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id),
                "a binding within the watchdog timeout must not be rebuilt");
        assertEquals(0L, TelegramPollingRunner.cooldownRemainingMs(token),
                "a healthy binding must not be stamped into cooldown");
    }

    @Test
    void watchdogDisabledWhenWatchdogMsIsZero() {
        String token = "774:tokWd";
        Long id = seedPollingBinding("agent-wd-off", token, "7", true);
        TelegramPollingRunnerTestHooks.setWatchdogMs(0L); // disabled
        TelegramPollingRunnerTestHooks.setClock(() -> 0L);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));
        assertFalse(TelegramPollingRunnerTestHooks.isWatchdogRunning(),
                "watchdogMs=0 must not schedule the periodic tick");

        // Even far past any timeout, a disabled watchdog tick is a no-op.
        TelegramPollingRunnerTestHooks.setClock(() -> 10_000_000L);
        TelegramPollingRunnerTestHooks.runWatchdogTick();

        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id),
                "watchdogMs=0 must never rebuild a binding, however stale");
        assertEquals(0L, TelegramPollingRunner.cooldownRemainingMs(token));
    }

    @Test
    void watchdogDoesNotRebuildABindingInCooldown() {
        String token = "775:tokWd";
        Long id = seedPollingBinding("agent-wd-cooldown", token, "7", true);
        TelegramPollingRunnerTestHooks.setWatchdogMs(120_000L);
        TelegramPollingRunnerTestHooks.setClock(() -> 0L);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        // Stamp a live cooldown on the still-active token, then make it stale.
        // The watchdog must defer to the in-flight cooldown reconcile rather than
        // double-rebuild and fight the 409 cooldown.
        TelegramPollingRunnerTestHooks.stampCooldown(token, 60_000L);
        TelegramPollingRunnerTestHooks.setClock(() -> 200_000L);
        TelegramPollingRunnerTestHooks.runWatchdogTick();

        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id),
                "a token in cooldown must not be rebuilt by the watchdog");
    }

    @Test
    void watchdogTickStartedOnRegisterWhenEnabled() {
        String token = "776:tokWd";
        seedPollingBinding("agent-wd-lifecycle", token, "7", true);
        TelegramPollingRunnerTestHooks.setWatchdogMs(120_000L);
        assertFalse(TelegramPollingRunnerTestHooks.isWatchdogRunning(),
                "no watchdog before the first registration");
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunnerTestHooks.isWatchdogRunning(),
                "registering a binding with the watchdog enabled should start the periodic tick");
    }

    // ===== JCLAW-375: inbound reaction notifications =====

    /** Deserialize a Telegram update JSON into an SDK {@link Update}, as production does. */
    private static Update updateFromJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Update.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String reactionUpdateJson(String chatType, long chatId, int messageId,
                                             long reactorId, String username, String newEmoji) {
        return "{"
                + "\"update_id\":1,"
                + "\"message_reaction\":{"
                + "  \"chat\":{\"id\":" + chatId + ",\"type\":\"" + chatType + "\"},"
                + "  \"message_id\":" + messageId + ","
                + "  \"user\":{\"id\":" + reactorId + ",\"is_bot\":false,"
                + "    \"first_name\":\"React\",\"username\":\"" + username + "\"},"
                + "  \"date\":1700000000,"
                + "  \"old_reaction\":[],"
                + "  \"new_reaction\":[{\"type\":\"emoji\",\"emoji\":\"" + newEmoji + "\"}]"
                + "}}";
    }

    @Test
    void pollingAllowedUpdatesIncludesMessageReaction() {
        // AC1: Telegram excludes message_reaction from its default set, so the
        // poller must name it explicitly in allowed_updates or reactions never
        // arrive. The seeding generator carries the list on every poll.
        String token = "880:tokReact";
        seedPollingBinding("agent-react-allowed", token, "7", true);
        TelegramPollingRunner.reconcile();

        Function<Integer, GetUpdates> gen = fakeApp.generatorFor(token);
        assertNotNull(gen, "runner should have registered a getUpdates generator");
        var allowed = gen.apply(0).getAllowedUpdates();
        assertNotNull(allowed, "allowed_updates must be set, not null/empty (= Telegram default)");
        assertTrue(allowed.contains("message_reaction"),
                "polling allowed_updates must include message_reaction; was " + allowed);
        assertTrue(allowed.contains("message") && allowed.contains("callback_query"),
                "the update types JClaw dispatches must remain in allowed_updates; was " + allowed);
    }

    @Test
    void parseReactionExtractsAddedEmojiAndReactor() {
        var update = updateFromJson(reactionUpdateJson("private", 100, 42, 5, "ada", "👍"));
        var delta = TelegramPollingRunner.parseReaction(update);
        assertNotNull(delta, "a message_reaction update must parse into a delta");
        assertEquals("100", delta.chatId());
        assertEquals("private", delta.chatType());
        assertEquals(Integer.valueOf(42), delta.messageId());
        assertEquals("@ada", delta.reactor());
        assertEquals(java.util.List.of("👍"), delta.added());
        assertTrue(delta.removed().isEmpty());
    }

    @Test
    void parseReactionReturnsNullForNonReactionUpdate() {
        // A plain text-message update carries no message_reaction → null.
        var update = updateFromJson("{\"update_id\":1,\"message\":{\"message_id\":1,"
                + "\"date\":1,\"chat\":{\"id\":1,\"type\":\"private\"},\"text\":\"hi\"}}");
        assertNull(TelegramPollingRunner.parseReaction(update),
                "a non-reaction update must not parse into a reaction delta");
    }

    @Test
    void parseReactionReturnsNullWhenNoNetChange() {
        // old == new (same single emoji) → no added/removed → nothing to report.
        var update = updateFromJson("{\"update_id\":1,\"message_reaction\":{"
                + "\"chat\":{\"id\":1,\"type\":\"private\"},\"message_id\":9,\"date\":1,"
                + "\"old_reaction\":[{\"type\":\"emoji\",\"emoji\":\"👍\"}],"
                + "\"new_reaction\":[{\"type\":\"emoji\",\"emoji\":\"👍\"}]}}");
        assertNull(TelegramPollingRunner.parseReaction(update),
                "a reaction update with no net change must produce no delta");
    }

    @Test
    void reactionEventTextRendersAddAndRemove() {
        var added = new TelegramPollingRunner.ReactionDelta("1", "private", 7, "5", "@ada",
                java.util.List.of("👍"), java.util.List.of());
        assertTrue(TelegramPollingRunner.reactionEventText(added).startsWith("[system] @ada reacted"),
                "added-only delta must read as a 'reacted' event");
        assertTrue(TelegramPollingRunner.reactionEventText(added).contains("message 7"));

        var removed = new TelegramPollingRunner.ReactionDelta("1", "private", 7, "5", "@ada",
                java.util.List.of(), java.util.List.of("👍"));
        assertTrue(TelegramPollingRunner.reactionEventText(removed).contains("removed reaction"),
                "removed-only delta must read as a 'removed reaction' event");
    }

    @Test
    void reactionNotifyModeDefaultsToOwnAndNormalizesUnknown() {
        play.Play.configuration.remove("telegram.reactions.notify"); // → default
        assertEquals("own", TelegramPollingRunner.reactionNotifyMode(),
                "missing config must default to 'own'");
        try {
            play.Play.configuration.setProperty("telegram.reactions.notify", "garbage");
            assertEquals("own", TelegramPollingRunner.reactionNotifyMode(),
                    "an unknown value must normalize to 'own'");
            play.Play.configuration.setProperty("telegram.reactions.notify", "ALL");
            assertEquals("all", TelegramPollingRunner.reactionNotifyMode(),
                    "case-insensitive parse of 'all'");
        } finally {
            play.Play.configuration.remove("telegram.reactions.notify");
        }
    }

    @Test
    void reactionGateOwnAllowsDmAndSuppressesGroup() {
        // AC1: default 'own' = reactions on messages the bot sent. We approximate
        // that as DM-only (a DM's only non-owner messages are the bot's); a group
        // reaction's target author is unattributable from the update, so suppress.
        assertTrue(TelegramPollingRunner.shouldNotifyReaction("own", "private"),
                "own must notify on a DM reaction");
        assertFalse(TelegramPollingRunner.shouldNotifyReaction("own", "supergroup"),
                "own must suppress a group reaction (author unattributable)");
    }

    @Test
    void reactionGateOffSuppressesEverythingAndAllPassesEverything() {
        // AC1: off suppresses entirely; all passes any chat type.
        assertFalse(TelegramPollingRunner.shouldNotifyReaction("off", "private"),
                "off must suppress even a DM reaction");
        assertFalse(TelegramPollingRunner.shouldNotifyReaction("off", "supergroup"));
        assertTrue(TelegramPollingRunner.shouldNotifyReaction("all", "private"));
        assertTrue(TelegramPollingRunner.shouldNotifyReaction("all", "supergroup"),
                "all must notify on a group reaction too");
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
