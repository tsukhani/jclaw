import channels.ChannelTransport;
import channels.TelegramBotIdentityTestHooks;
import channels.TelegramChannel;
import channels.TelegramOffsetStore;
import channels.TelegramPollingRunner;
import channels.TelegramPollingRunnerTestHooks;
import models.Agent;
import models.EventLog;
import models.TelegramBinding;
import org.junit.jupiter.api.*;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.LongPollingUpdateConsumer;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import play.test.*;
import services.EventLogger;
import services.Tx;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Coverage for {@link TelegramPollingRunner}'s lifecycle edges and the
 * per-update {@code dispatch} gate (JCLAW-305 follow-up), complementing
 * {@code TelegramPollingRunnerTest} which owns reconcile/offset/probe basics:
 *
 * <ul>
 *   <li>{@code stop()} with live sessions — unregister + app close, and the
 *       close-failure warn path;</li>
 *   <li>registration/unregistration failures — error classification on the log
 *       line ({@code recoverable} vs {@code non-recoverable}) and state
 *       consistency;</li>
 *   <li>{@code dispatch} — driven synchronously through the consumer the runner
 *       registers with the (in-memory) polling app: disabled/deleted-binding
 *       drops, non-owner callback rejection, DM-stranger + unmentioned-group
 *       message rejection;</li>
 *   <li>the standalone token health-probe's negative/resilience paths, plus the
 *       REAL {@code getMe}-backed rejection predicate against
 *       {@link MockTelegramServer};</li>
 *   <li>pure reaction-parse/label/gate edges not covered by the base test.</li>
 * </ul>
 *
 * <p>The owner-accepted message lanes (text/media/forward buffers →
 * {@code dispatchMerged} → {@code AgentRunner}) are deliberately NOT driven
 * here: they run a full agent turn on a background virtual thread, which is a
 * live-LLM path. Their pure routing decision ({@code resolveTopicAgent}) is
 * covered by the base test.
 */
class TelegramPollingRunnerDispatchTest extends FunctionalTest {

    private InMemoryPollingApp fakeApp;
    private MockTelegramServer mock;
    private Path offsetTmp;

    @BeforeEach
    void setup() throws Exception {
        Fixtures.deleteDatabase();
        TelegramPollingRunnerTestHooks.clear();
        fakeApp = new InMemoryPollingApp();
        TelegramPollingRunnerTestHooks.setApp(fakeApp);
        // Default the health-probe to "token accepted" so nothing dials
        // api.telegram.org; individual tests override or restore the real probe.
        TelegramPollingRunnerTestHooks.setTokenRejectedCheck(t -> false);
        offsetTmp = Files.createTempDirectory("jclaw-tg-dispatch-");
        System.setProperty(TelegramOffsetStore.OFFSET_PATH_PROPERTY, offsetTmp.toString());
        mock = new MockTelegramServer();
        mock.start();
    }

    @AfterEach
    void teardown() throws Exception {
        TelegramPollingRunnerTestHooks.clear();
        if (fakeApp != null) fakeApp.shutdownExecutors();
        if (mock != null) mock.close();
        play.Play.configuration.remove("telegram.reactions.notify");
        System.clearProperty(TelegramOffsetStore.OFFSET_PATH_PROPERTY);
        if (offsetTmp != null && Files.exists(offsetTmp)) {
            try (Stream<Path> walk = Files.walk(offsetTmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception _) { /* best-effort */ }
                });
            }
        }
    }

    // ===== helpers (mirroring TelegramPollingRunnerTest) =====

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

    /** Deserialize a Telegram update JSON into an SDK {@link Update}, as production does. */
    private static Update updateFromJson(String json) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Update.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private LongPollingSingleThreadUpdateConsumer consumerFor(String token) {
        var consumer = fakeApp.consumerFor(token);
        assertNotNull(consumer, "runner should have registered a consumer for " + token);
        return (LongPollingSingleThreadUpdateConsumer) consumer;
    }

    private static long channelEvents(String likePattern) {
        return EventLog.count("category = ?1 AND message LIKE ?2", "channel", likePattern);
    }

    // ===== stop() with live sessions =====

    @Test
    void stopUnregistersEverySessionAndClosesTheApp() {
        Long id = seedPollingBinding("stop-agent", "701:tokStop", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));
        assertTrue(TelegramPollingRunner.isRunning(),
                "the installed app reports running from construction (SDK contract)");

        EventLogger.clear();
        TelegramPollingRunner.stop();
        EventLogger.flush();

        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty(),
                "stop() must unregister every active binding");
        assertFalse(TelegramPollingRunner.isRunning(),
                "stop() nulls the APP reference so isRunning() is false");
        assertEquals(1L, channelEvents("%Long-polling app closed%"),
                "the successful app close is logged");
        assertTrue(channelEvents("%Unregistered polling session for binding " + id + "%") >= 1,
                "the per-binding unregister is logged");
    }

    @Test
    void stopLogsAWarningWhenAppCloseThrows() {
        // A close() failure must be absorbed (warn), never thrown to the caller —
        // stop() runs at app shutdown.
        var closeThrowingApp = new TelegramBotsLongPollingApplication() {
            @Override
            public BotSession registerBot(String botToken,
                                          Supplier<TelegramUrl> telegramUrlSupplier,
                                          Function<Integer, GetUpdates> getUpdatesGenerator,
                                          LongPollingUpdateConsumer updatesConsumer) {
                return null; // inert — no network, no session
            }

            @Override
            public void unregisterBot(String botToken) {
                // inert
            }

            @Override
            public void close() {
                throw new RuntimeException("close-boom");
            }
        };
        TelegramPollingRunnerTestHooks.setApp(closeThrowingApp);
        Long id = seedPollingBinding("stop-close-fail-agent", "702:tokCloseFail", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        EventLogger.clear();
        TelegramPollingRunner.stop(); // must not throw
        EventLogger.flush();

        assertTrue(TelegramPollingRunner.activeBindingIds().isEmpty(),
                "bindings are unregistered even when close() later fails");
        assertEquals(1L, channelEvents("%Polling app shutdown error: close-boom%"),
                "the close failure is logged at warn, not thrown");
    }

    // ===== registration / unregistration failure classification (JCLAW-387 D2) =====

    @Test
    void registrationFailureLogsRecoverableCurveAndLeavesBindingInactive() {
        var throwingApp = new TelegramBotsLongPollingApplication() {
            @Override
            public BotSession registerBot(String botToken,
                                          Supplier<TelegramUrl> telegramUrlSupplier,
                                          Function<Integer, GetUpdates> getUpdatesGenerator,
                                          LongPollingUpdateConsumer updatesConsumer) {
                throw new RuntimeException("connection reset by peer");
            }
        };
        TelegramPollingRunnerTestHooks.setApp(throwingApp);
        Long id = seedPollingBinding("reg-fail-agent", "703:tokRegFail", "1", true);

        EventLogger.clear();
        TelegramPollingRunner.reconcile(); // must not throw
        EventLogger.flush();

        assertFalse(TelegramPollingRunner.activeBindingIds().contains(id),
                "a binding whose registration failed must not be marked active");
        assertEquals(1L, channelEvents(
                        "%Failed to register binding " + id + "%[recoverable%"),
                "a network-ish failure is classified recoverable on the log line");
    }

    @Test
    void registrationAuthFailureLogsNonRecoverableCurve() {
        // A 401 buried in the cause chain must classify non-recoverable —
        // telegramErrorCode walks getCause().
        var throwingApp = new TelegramBotsLongPollingApplication() {
            @Override
            public BotSession registerBot(String botToken,
                                          Supplier<TelegramUrl> telegramUrlSupplier,
                                          Function<Integer, GetUpdates> getUpdatesGenerator,
                                          LongPollingUpdateConsumer updatesConsumer) {
                var api = new org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException(
                        "unauthorized",
                        org.telegram.telegrambots.meta.api.objects.ApiResponse.builder()
                                .ok(false).errorCode(401).errorDescription("Unauthorized").build());
                throw new RuntimeException(api);
            }
        };
        TelegramPollingRunnerTestHooks.setApp(throwingApp);
        Long id = seedPollingBinding("reg-401-agent", "704:tokReg401", "1", true);

        EventLogger.clear();
        TelegramPollingRunner.reconcile();
        EventLogger.flush();

        assertFalse(TelegramPollingRunner.activeBindingIds().contains(id));
        assertEquals(1L, channelEvents(
                        "%Failed to register binding " + id + "%[non-recoverable%"),
                "an auth failure is classified non-recoverable (operator action)");
    }

    @Test
    void unregisterFailureIsLoggedButBindingIsStillDropped() {
        var unregisterThrowingApp = new TelegramBotsLongPollingApplication() {
            @Override
            public BotSession registerBot(String botToken,
                                          Supplier<TelegramUrl> telegramUrlSupplier,
                                          Function<Integer, GetUpdates> getUpdatesGenerator,
                                          LongPollingUpdateConsumer updatesConsumer) {
                return null; // register succeeds, no session/network
            }

            @Override
            public void unregisterBot(String botToken) {
                throw new RuntimeException("boom-unregister");
            }
        };
        TelegramPollingRunnerTestHooks.setApp(unregisterThrowingApp);
        Long id = seedPollingBinding("unreg-fail-agent", "705:tokUnregFail", "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            if (b != null) b.delete();
            return null;
        });

        EventLogger.clear();
        TelegramPollingRunner.reconcile(); // must not throw
        EventLogger.flush();

        assertFalse(TelegramPollingRunner.activeBindingIds().contains(id),
                "the binding must leave the active set even when the SDK unregister throws");
        assertEquals(1L, channelEvents("%Unregister failed for binding " + id + "%"),
                "the unregister failure is logged at warn");
        assertEquals(1L, channelEvents("%Unregistered polling session for binding " + id + "%"),
                "the unregister completion is still logged after the swallowed failure");
    }

    // ===== dispatch: freshness re-read + owner gates =====

    @Test
    void dispatchDropsUpdatesForDisabledThenDeletedBinding() {
        String token = "706:tokDrop";
        Long id = seedPollingBinding("drop-agent", token, "1", true);
        TelegramPollingRunner.reconcile();
        var consumer = consumerFor(token);

        // Disable AFTER registration: the live consumer must re-read the binding
        // per update and drop, not serve, the stale session.
        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            b.enabled = false;
            b.save();
            return null;
        });
        play.db.jpa.JPA.em().clear(); // evict the L1-cached (still-enabled) entity

        EventLogger.clear();
        consumer.consume(updateFromJson("{\"update_id\":10,\"message\":{\"message_id\":1,"
                + "\"date\":1,\"from\":{\"id\":1,\"is_bot\":false,\"first_name\":\"O\"},"
                + "\"chat\":{\"id\":1,\"type\":\"private\"},\"text\":\"hi\"}}"));
        EventLogger.flush();
        assertEquals(1L, channelEvents("%Dropping update for missing/disabled binding " + id + "%"),
                "an update for a just-disabled binding must be dropped with a warn");
        assertEquals(10, TelegramOffsetStore.load(token),
                "the offset still advances for a dropped update (it was consumed)");

        // Delete the row entirely: same drop path via the null-Ctx branch.
        commitInFreshTx(() -> {
            TelegramBinding b = TelegramBinding.findById(id);
            if (b != null) b.delete();
            return null;
        });
        play.db.jpa.JPA.em().clear();
        consumer.consume(updateFromJson("{\"update_id\":11,\"message\":{\"message_id\":2,"
                + "\"date\":1,\"from\":{\"id\":1,\"is_bot\":false,\"first_name\":\"O\"},"
                + "\"chat\":{\"id\":1,\"type\":\"private\"},\"text\":\"hi\"}}"));
        EventLogger.flush();
        assertEquals(2L, channelEvents("%Dropping update for missing/disabled binding " + id + "%"),
                "an update for a deleted binding must be dropped with a warn");

        // An update with NO update_id must not move the persisted offset.
        consumer.consume(new Update());
        assertEquals(11, TelegramOffsetStore.load(token),
                "an update without update_id must not advance the persisted offset");
    }

    @Test
    void dispatchRejectsCallbackFromNonOwner() {
        String token = "707:tokCb";
        Long id = seedPollingBinding("cb-agent", token, "1", true);
        TelegramPollingRunner.reconcile();
        var consumer = consumerFor(token);

        EventLogger.clear();
        consumer.consume(updateFromJson("{\"update_id\":20,\"callback_query\":{"
                + "\"id\":\"cb-1\",\"chat_instance\":\"ci\","
                + "\"from\":{\"id\":999,\"is_bot\":false,\"first_name\":\"Mallory\",\"username\":\"mallory\"},"
                + "\"message\":{\"message_id\":5,\"date\":1,\"chat\":{\"id\":100,\"type\":\"private\"}},"
                + "\"data\":\"model:pick\"}}"));
        EventLogger.flush();

        assertEquals(1L, channelEvents(
                        "%Rejected callback from user 999: binding " + id + " is bound to user 1%"),
                "callbacks stay owner-only: a non-owner tap must be rejected and logged");
        assertEquals(0L, channelEvents("%Polling update processing error%"),
                "the rejection is a clean gate, not an exception");
    }

    @Test
    void dispatchRejectsDmStrangerAndUnmentionedGroupMessage() {
        String token = "880:tokAccess";
        seedPollingBinding("access-agent", token, "5", true);
        TelegramPollingRunner.reconcile();
        var consumer = consumerFor(token);

        // The message path resolves the bot's own identity via getMe — point it
        // at the mock so no traffic leaves localhost.
        TelegramChannel.installForTest(token, mock.telegramUrl());
        mock.respondWith("getMe", 200,
                "{\"ok\":true,\"result\":{\"id\":880,\"is_bot\":true,"
                        + "\"first_name\":\"JClaw\",\"username\":\"jclawbot\"}}");
        try {
            EventLogger.clear();
            // 1) DM from a stranger (owner is telegram user 5, sender is 999).
            consumer.consume(updateFromJson("{\"update_id\":30,\"message\":{\"message_id\":7,"
                    + "\"date\":1,\"from\":{\"id\":999,\"is_bot\":false,\"first_name\":\"Mallory\","
                    + "\"username\":\"mallory\"},"
                    + "\"chat\":{\"id\":999,\"type\":\"private\"},\"text\":\"hi\"}}"));
            // 2) Group message from the OWNER but without addressing the bot —
            //    groups are served only when the bot is directly addressed.
            consumer.consume(updateFromJson("{\"update_id\":31,\"message\":{\"message_id\":8,"
                    + "\"date\":1,\"from\":{\"id\":5,\"is_bot\":false,\"first_name\":\"Owner\","
                    + "\"username\":\"owner\"},"
                    + "\"chat\":{\"id\":-100200,\"type\":\"group\"},\"text\":\"hello all\"}}"));
            EventLogger.flush();

            assertEquals(2L, channelEvents("%Rejected inbound from%"),
                    "both the DM stranger and the unmentioned group message must be rejected");
            assertEquals(0L, channelEvents("%Polling received from%"),
                    "no rejected message may reach the accepted-dispatch path");
        } finally {
            TelegramChannel.clearForTest(token);
            TelegramBotIdentityTestHooks.clear(token);
        }
    }

    // ===== token health-probe: negative + resilience + REAL getMe predicate =====

    @Test
    void probeLeavesAcceptedTokensEnabledAndRegistered() {
        String token = "708:tokHealthy";
        Long id = seedPollingBinding("healthy-agent", token, "1", true);
        TelegramPollingRunner.reconcile();
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

        EventLogger.clear();
        TelegramPollingRunnerTestHooks.runTokenHealthProbe(); // stub says "accepted"
        EventLogger.flush();

        assertTrue(TelegramPollingRunner.activeBindingIds().contains(id),
                "an accepted token's session must stay registered");
        assertTrue(TelegramBinding.<TelegramBinding>findById(id).enabled,
                "an accepted token's binding must stay enabled");
        assertEquals(0L, channelEvents("%disabled: Telegram rejected its bot token%"),
                "no operator alert for a healthy token");
    }

    @Test
    void probeSurvivesAThrowingCheckAndStillDisablesTheOtherBinding() {
        String tokenA = "709:tokProbeBoom";
        String tokenB = "710:tokProbeBad";
        Long idA = seedPollingBinding("probe-boom-agent", tokenA, "1", true);
        Long idB = seedPollingBinding("probe-bad-agent", tokenB, "2", true);
        TelegramPollingRunner.reconcile();

        TelegramPollingRunnerTestHooks.setTokenRejectedCheck(t -> {
            if (tokenA.equals(t)) throw new RuntimeException("probe-boom");
            return tokenB.equals(t);
        });

        EventLogger.clear();
        TelegramPollingRunnerTestHooks.runTokenHealthProbe(); // must not throw
        EventLogger.flush();

        assertTrue(TelegramBinding.<TelegramBinding>findById(idA).enabled,
                "a binding whose probe merely errored must NOT be disabled");
        assertTrue(TelegramPollingRunner.activeBindingIds().contains(idA),
                "the errored binding's session stays registered");
        assertFalse(TelegramBinding.<TelegramBinding>findById(idB).enabled,
                "one bad probe must not abort the sweep — the rejected token is still disabled");
        assertFalse(TelegramPollingRunner.activeBindingIds().contains(idB),
                "the rejected token's session is unregistered");
        assertEquals(1L, channelEvents("%Token health probe error for binding " + idA + "%"),
                "the probe error is logged per-binding at warn");
    }

    @Test
    void realGetMeProbeAcceptsOn200AndDisablesOn401() {
        String token = "8801:realprobe";
        Long id = seedPollingBinding("real-probe-agent", token, "1", true);
        TelegramPollingRunner.reconcile();
        TelegramChannel.installForTest(token, mock.telegramUrl());
        try {
            // Restore the REAL getMe-backed rejection predicate — its traffic now
            // targets the mock through the installed per-token client.
            TelegramPollingRunnerTestHooks.setTokenRejectedCheck(null);

            // Phase 1: Telegram accepts the token → nothing changes.
            mock.respondWith("getMe", 200,
                    "{\"ok\":true,\"result\":{\"id\":8801,\"is_bot\":true,"
                            + "\"first_name\":\"JClaw\",\"username\":\"jclawbot\"}}");
            TelegramPollingRunnerTestHooks.runTokenHealthProbe();
            assertTrue(TelegramBinding.<TelegramBinding>findById(id).enabled,
                    "a 200 getMe must not disable the binding");
            assertTrue(TelegramPollingRunner.activeBindingIds().contains(id));

            // Phase 2: Telegram permanently rejects the token (401) → auto-disable.
            mock.respondWith("getMe", 401,
                    "{\"ok\":false,\"error_code\":401,\"description\":\"Unauthorized\"}");
            EventLogger.clear();
            TelegramPollingRunnerTestHooks.runTokenHealthProbe();
            EventLogger.flush();

            assertFalse(TelegramBinding.<TelegramBinding>findById(id).enabled,
                    "a 401 getMe means a revoked/invalid token → the binding is disabled");
            assertFalse(TelegramPollingRunner.activeBindingIds().contains(id),
                    "the live session is unregistered after the disable");
            assertEquals(1L, channelEvents("%disabled: Telegram rejected its bot token%"),
                    "the operator alert names the token rejection");
            assertEquals(2L, mock.countRequests("getMe"),
                    "exactly one getMe per probe run went over the wire");
        } finally {
            TelegramChannel.clearForTest(token);
        }
    }

    // ===== pure reaction-parse / label / event-text edges (JCLAW-375) =====

    @Test
    void parseReactionIgnoresCustomEmojiAndHandlesAnonymousReactor() {
        // Only ReactionTypeEmoji entries contribute to the delta; a custom-emoji
        // reaction present on BOTH sides must not create phantom add/remove
        // entries. An update without a user (anonymous/channel actor) yields
        // null reactor fields rather than failing the parse.
        var update = updateFromJson("{\"update_id\":40,\"message_reaction\":{"
                + "\"chat\":{\"id\":100,\"type\":\"supergroup\"},\"message_id\":42,\"date\":1,"
                + "\"old_reaction\":[{\"type\":\"custom_emoji\",\"custom_emoji_id\":\"555\"}],"
                + "\"new_reaction\":[{\"type\":\"emoji\",\"emoji\":\"🔥\"},"
                + "{\"type\":\"custom_emoji\",\"custom_emoji_id\":\"555\"}]}}");
        var delta = TelegramPollingRunner.parseReaction(update);
        assertNotNull(delta, "an emoji delta must parse even alongside custom-emoji noise");
        assertEquals(java.util.List.of("🔥"), delta.added(),
                "only the plain emoji contributes to added");
        assertTrue(delta.removed().isEmpty(),
                "the unchanged custom emoji must not surface as a removal");
        assertNull(delta.reactorId(), "no user on the update → null reactor id");
        assertNull(delta.reactor(), "no user on the update → null reactor label");
        assertEquals("supergroup", delta.chatType());
    }

    @Test
    void parseReactionReactorLabelFallsBackFromUsernameToFirstName() {
        // first_name only → label is the first name (no @-handle available).
        // The further id-string fallback (neither username nor first name) is
        // unreachable with SDK 9.5: User.firstName is lombok @NonNull on a
        // @Jacksonized builder, so no wire payload can produce a null one.
        var byFirstName = TelegramPollingRunner.parseReaction(updateFromJson(
                "{\"update_id\":41,\"message_reaction\":{"
                        + "\"chat\":{\"id\":1,\"type\":\"private\"},\"message_id\":9,\"date\":1,"
                        + "\"user\":{\"id\":77,\"is_bot\":false,\"first_name\":\"React\"},"
                        + "\"old_reaction\":[],"
                        + "\"new_reaction\":[{\"type\":\"emoji\",\"emoji\":\"👍\"}]}}"));
        assertNotNull(byFirstName);
        assertEquals("React", byFirstName.reactor(),
                "a user with no username labels by first name");
        assertEquals("77", byFirstName.reactorId());
    }

    @Test
    void reactionEventTextCombinesAddWithRemoveAndDefaultsAnonymousToSomeone() {
        var swap = new TelegramPollingRunner.ReactionDelta("1", "private", 7, "5", "@ada",
                java.util.List.of("👍"), java.util.List.of("👎"));
        String text = TelegramPollingRunner.reactionEventText(swap);
        assertTrue(text.contains("reacted 👍"), "the added emoji leads the event: " + text);
        assertTrue(text.contains("(and removed 👎)"),
                "a simultaneous removal is appended parenthetically: " + text);

        var anonymous = new TelegramPollingRunner.ReactionDelta("1", "private", 7, null, null,
                java.util.List.of("👍"), java.util.List.of());
        assertTrue(TelegramPollingRunner.reactionEventText(anonymous).startsWith("[system] Someone "),
                "a null reactor renders as 'Someone'");
    }

    @Test
    void handleReactionNullArgsAndSuppressedGroupProduceNoNotification() {
        play.Play.configuration.remove("telegram.reactions.notify"); // default: own
        var agent = new Agent();
        agent.name = "reaction-noop-agent";
        var groupDelta = new TelegramPollingRunner.ReactionDelta("100", "supergroup", 42,
                "5", "@ada", java.util.List.of("👍"), java.util.List.of());
        var nullChatDelta = new TelegramPollingRunner.ReactionDelta(null, "private", 42,
                "5", "@ada", java.util.List.of("👍"), java.util.List.of());

        EventLogger.clear();
        // Guards: none of these may throw or notify.
        TelegramPollingRunner.handleReaction(null, "711:tokNull", "1", groupDelta);
        TelegramPollingRunner.handleReaction(agent, "711:tokNull", "1", null);
        TelegramPollingRunner.handleReaction(agent, "711:tokNull", "1", nullChatDelta);
        // Gate: mode=own + group reaction + no bot-sent cache entry → suppressed
        // BEFORE any agent dispatch (the never-seen token has no cache).
        TelegramPollingRunner.handleReaction(agent, "711:tokNull", "1", groupDelta);
        EventLogger.flush();

        assertEquals(0L, channelEvents("%Reaction notification%"),
                "guards and the own-group suppression must produce zero notifications");
    }

    @Test
    void classifyPollingErrorSurvivesSelfReferentialCauseChain() {
        // A pathological exception whose cause is itself must not spin the
        // cause-walk into an infinite loop — the guard breaks and the error
        // classifies conservatively as recoverable.
        var selfCaused = new Exception("self") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };
        assertEquals(TelegramPollingRunner.ERR_RECOVERABLE,
                TelegramPollingRunner.classifyPollingError(selfCaused),
                "a self-referential cause chain terminates and classifies recoverable");
    }
}
