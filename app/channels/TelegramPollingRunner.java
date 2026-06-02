package channels;

import agents.AgentRunner;
import models.Agent;
import models.TelegramBinding;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import services.EventLogger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Multi-bot long-polling runner for JCLAW-89 per-user Telegram bindings. Owns a
 * single {@link TelegramBotsLongPollingApplication} and registers one polling
 * session per enabled POLLING-transport binding. {@link #reconcile()} is
 * idempotent: run it at startup (see {@code ChannelRunnerJob}) and after every
 * mutation to {@link TelegramBinding} (see {@code ApiTelegramBindingsController}).
 *
 * <p>Per-binding consumers carry the binding id so {@link #dispatch} knows which
 * binding received the update, without depending on the token-to-binding lookup
 * at callback time. The SDK's {@link LongPollingSingleThreadUpdateConsumer}
 * already fans updates through a shared executor — we just own the dispatch logic.
 */
public final class TelegramPollingRunner {

    private static final String LOG_CATEGORY = "channel";
    private static final String LOG_SOURCE = "telegram";

    private static final AtomicReference<TelegramBotsLongPollingApplication> APP = new AtomicReference<>();

    /** bindingId → active bot token. Used to detect token rotation on reconcile. */
    private static final ConcurrentHashMap<Long, String> ACTIVE = new ConcurrentHashMap<>();

    /**
     * Token → epoch-millis timestamp until which re-registration is blocked. When
     * a binding is unregistered, Telegram's server-side long-poll can linger for
     * up to its timeout (default 30 s) — if we re-register within that window,
     * Telegram returns HTTP 409 "Conflict: terminated by other getUpdates". We
     * defer re-registration until the prior poll has drained.
     */
    private static final ConcurrentHashMap<String, Long> COOLDOWN_UNTIL = new ConcurrentHashMap<>();

    /** Default Telegram long-poll timeout is 30 s; match it, plus a small margin. */
    public static final long COOLDOWN_MS = 30_000L;

    /**
     * Cooldown-reconcile scheduler. Non-final so {@link #clearForTest} can
     * replace a terminated instance with a fresh one — tests that invoke
     * {@link #stop} (which drains the scheduler) would otherwise poison
     * every later test that triggers an unregister via {@link #reconcile}.
     * Volatile so the test-side reassignment is visible to the production
     * code path on the next polling tick. Production never reassigns;
     * {@link #stop} is one-shot at app shutdown.
     */
    @SuppressWarnings("java:S3077") // Volatile non-primitive is correct here: pure publish-then-read, no compound mutation
    private static volatile ScheduledExecutorService scheduler = newScheduler();

    private static ScheduledExecutorService newScheduler() {
        var exec = new ScheduledThreadPoolExecutor(1,
                r -> Thread.ofVirtual().name("telegram-cooldown-reconcile").unstarted(r));
        // Each unregister schedules a cooldown-reconcile ~30.5s out
        // (COOLDOWN_MS + 500). A ScheduledThreadPoolExecutor by default keeps
        // such delayed tasks queued after shutdown(), so this single-thread
        // scheduler would stay alive until the task fires — past
        // gracefulShutdown's 5s await, which then force-kills it with a noisy
        // WARN. A deferred reconcile is pointless once we're stopping, so drop
        // pending delayed tasks on shutdown; the executor then terminates
        // immediately and the drain is clean.
        exec.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        exec.setRemoveOnCancelPolicy(true);
        return exec;
    }

    private TelegramPollingRunner() {}

    /**
     * Reconcile the active set against the desired set derived from
     * {@link TelegramBinding#findAllEnabled()} filtered to POLLING transport:
     * start new bindings, stop removed/disabled ones, restart ones whose token
     * changed. Synchronized so concurrent admin saves don't race.
     */
    public static synchronized void reconcile() {
        var desired = services.Tx.run(() ->
                TelegramBinding.findAllEnabledByTransport(ChannelTransport.POLLING));
        var desiredById = new HashMap<Long, TelegramBinding>();
        for (var b : desired) desiredById.put(b.id, b);

        var app = APP.updateAndGet(existing -> existing != null
                ? existing : new TelegramBotsLongPollingApplication());

        // Stop active bindings that vanished or had their token rotated. Snapshot
        // the active map first so we can mutate it inside the loop.
        var activeSnapshot = new HashMap<>(ACTIVE);
        for (var entry : activeSnapshot.entrySet()) {
            Long bindingId = entry.getKey();
            String activeToken = entry.getValue();
            var target = desiredById.get(bindingId);
            if (target == null || !Objects.equals(target.botToken, activeToken)) {
                unregisterInternal(app, bindingId, activeToken);
            }
        }

        // Start any desired binding that isn't currently active.
        for (var target : desired) {
            if (!ACTIVE.containsKey(target.id)) {
                registerInternal(app, target);
            }
        }

        // Start the app once we have at least one registration; it's a no-op if
        // already running. We deliberately leave the app instance in place when
        // the registration set drops to zero so a subsequent reconcile can reuse
        // the dispatcher without recreating threads.
        if (!ACTIVE.isEmpty() && !app.isRunning()) {
            try {
                app.start();
                EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                        "Long-polling app started (%d binding(s) registered)".formatted(ACTIVE.size()));
            } catch (Exception e) {
                EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                        "Failed to start polling app: %s".formatted(e.getMessage()));
            }
        }
    }

    /** Stop all active sessions. Safe to call at app shutdown. */
    public static synchronized void stop() {
        var app = APP.getAndSet(null);
        if (app != null) {
            for (var entry : new HashMap<>(ACTIVE).entrySet()) {
                unregisterInternal(app, entry.getKey(), entry.getValue());
            }
            try {
                app.close();
                EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE, "Long-polling app closed");
            } catch (Exception e) {
                EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                        "Polling app shutdown error: %s".formatted(e.getMessage()));
            }
        }
        // Time-bounded drain of the cooldown-reconcile scheduler. Without this
        // the static scheduler accumulates a thread per dev hot reload.
        utils.VirtualThreads.gracefulShutdown(scheduler, "telegram-cooldown-reconcile");
    }

    /** Test/admin introspection: set of binding ids with live polling sessions. */
    public static Set<Long> activeBindingIds() {
        return new HashSet<>(ACTIVE.keySet());
    }

    /** Test helper: is the SDK app currently running? */
    public static boolean isRunning() {
        var app = APP.get();
        return app != null && app.isRunning();
    }

    /**
     * Test-only injection seam (JCLAW-316). Installs {@code app} into the
     * {@link #APP} reference so {@link #reconcile} reuses it instead of
     * instantiating a real {@link TelegramBotsLongPollingApplication} that
     * would dial {@code api.telegram.org}. Pass {@code null} to clear.
     * Package-private — see {@code TelegramPollingRunnerTestHooks} for the
     * default-package bridge tests use.
     */
    static void setAppForTest(TelegramBotsLongPollingApplication app) {
        APP.set(app);
    }

    /**
     * Test-only state reset (JCLAW-316). Clears {@link #ACTIVE} and
     * {@link #COOLDOWN_UNTIL} and nulls the {@link #APP} reference. If a
     * prior test (or {@link jobs.ShutdownJob} invoked directly from
     * {@code JobLifecycleTest}) drained {@link #scheduler} via
     * {@link #stop}, swap in a fresh executor so subsequent
     * {@link #reconcile} calls can schedule cooldown re-reconciles without
     * hitting {@code RejectedExecutionException}. Production never
     * reassigns scheduler: {@link #stop} is one-shot at app shutdown.
     */
    static void clearForTest() {
        ACTIVE.clear();
        COOLDOWN_UNTIL.clear();
        APP.set(null);
        if (scheduler.isShutdown()) {
            scheduler = newScheduler();
        }
    }

    private static void registerInternal(TelegramBotsLongPollingApplication app, TelegramBinding binding) {
        final Long bindingId = binding.id;
        final String token = binding.botToken;

        // Skip if this token was recently unregistered — Telegram's stale long
        // poll would otherwise cause an HTTP 409 on the new getUpdates. The
        // scheduled reconcile stamped at unregister-time will pick this up once
        // the cooldown drains, so the admin's toggle-on eventually takes effect
        // without a manual retry.
        long remaining = cooldownRemainingMs(token);
        if (remaining > 0) {
            EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                    "Deferring register for binding %d — token in cooldown for %d ms".formatted(
                            bindingId, remaining));
            return;
        }
        COOLDOWN_UNTIL.remove(token);

        try {
            // JCLAW-361: seed the getUpdates start offset from the durable
            // per-bot store and persist the high-water update id as we consume.
            // The SDK keeps the offset only in an in-memory AtomicInteger that
            // resets to 0 on restart, so without this a restart re-fetches and
            // re-dispatches every update Telegram still buffers.
            int persistedOffset = TelegramOffsetStore.load(token);
            LongPollingSingleThreadUpdateConsumer consumer = update -> {
                dispatch(bindingId, update);
                if (update.getUpdateId() != null) {
                    TelegramOffsetStore.record(token, update.getUpdateId());
                }
            };
            app.registerBot(token, () -> TelegramUrl.DEFAULT_URL,
                    seedingGetUpdatesGenerator(persistedOffset), consumer);
            ACTIVE.put(bindingId, token);
            EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                    "Registered polling session for binding %d (offset seeded from %d)".formatted(
                            bindingId, persistedOffset));
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                    "Failed to register binding %d: %s".formatted(bindingId, e.getMessage()));
        }
    }

    /**
     * {@code getUpdates} generator that seeds the start offset from
     * {@code persistedOffset} (JCLAW-361). Mirrors the SDK's
     * {@code DefaultGetUpdatesGenerator} (limit 100, timeout 50, empty
     * allowedUpdates) but clamps the requested offset to
     * {@code max(sdkLastReceived, persistedOffset) + 1}.
     *
     * <p>The clamp is what makes seeding race-free and monotonic: the SDK passes
     * its in-memory {@code lastReceivedUpdate} (0 on a fresh session) on every
     * poll. On the first poll {@code max(0, persisted) + 1 == persisted + 1}, so
     * Telegram skips everything already consumed before the restart. Once the
     * session has advanced past {@code persistedOffset}, {@code max} picks the
     * SDK's own (now higher) value, so the persisted seed never rewinds a
     * running session.
     */
    private static java.util.function.Function<Integer, GetUpdates> seedingGetUpdatesGenerator(int persistedOffset) {
        return sdkLastReceived -> GetUpdates.builder()
                .limit(100)
                .timeout(50)
                .offset(Math.max(sdkLastReceived, persistedOffset) + 1)
                .allowedUpdates(new ArrayList<>())
                .build();
    }

    private static void unregisterInternal(TelegramBotsLongPollingApplication app,
                                            Long bindingId, String token) {
        try {
            if (token != null) app.unregisterBot(token);
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Unregister failed for binding %d: %s".formatted(bindingId, e.getMessage()));
        }
        ACTIVE.remove(bindingId);
        TelegramChannel.evictToken(token);
        if (token != null) {
            COOLDOWN_UNTIL.put(token, System.currentTimeMillis() + COOLDOWN_MS);
            // Self-reconcile once the cooldown drains so a re-enabled binding
            // registered during the window gets picked up automatically.
            scheduler.schedule(TelegramPollingRunner::reconcile, COOLDOWN_MS + 500, TimeUnit.MILLISECONDS);
        }
        EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                "Unregistered polling session for binding %d".formatted(bindingId));
    }

    /**
     * Remaining cooldown in milliseconds for {@code token}, or 0 if no active
     * cooldown. Thread-safe; callable from any thread (used by the API layer
     * to surface the countdown to the frontend).
     */
    public static long cooldownRemainingMs(String token) {
        if (token == null) return 0L;
        Long until = COOLDOWN_UNTIL.get(token);
        if (until == null) return 0L;
        long remaining = until - System.currentTimeMillis();
        return Math.max(0L, remaining);
    }

    /**
     * {@link Instant} when the cooldown for {@code token} expires, or {@code null}
     * if no cooldown is active. Used by the API to serialise ISO-8601 strings.
     */
    public static Instant cooldownUntil(String token) {
        if (token == null) return null;
        Long until = COOLDOWN_UNTIL.get(token);
        if (until == null) return null;
        long now = System.currentTimeMillis();
        if (until <= now) return null;
        return Instant.ofEpochMilli(until);
    }

    /** Snapshot of binding state pulled inside the transaction, then read off-thread. */
    private record Ctx(String botToken, String telegramUserId, Agent agent, boolean enabled) {}

    /**
     * Per-update dispatch. Looks up the binding (re-read for freshness — the
     * admin may have disabled it between the registration and this callback),
     * verifies the sender matches {@link TelegramBinding#telegramUserId},
     * and hands the message off to the bound agent.
     */
    private static void dispatch(Long bindingId, Update update) {
        try {
            // JCLAW-109: parse inline-keyboard callbacks first so text-message
            // parsing stays uncluttered. parseCallback returns null for any
            // non-callback update, so we fall through to the message path below.
            var callback = TelegramChannel.parseCallback(update);
            // JCLAW-371: resolve the bot's own identity so parseUpdate can
            // detect an @mention / text_mention / /cmd@botname / reply-to-bot
            // addressing THIS bot — the group access gate in handleMessage
            // reads InboundMessage.botMentioned. We only need it for the
            // message path; callbacks stay owner-only and don't consult it.
            TelegramChannel.InboundMessage msg = null;
            if (callback == null) {
                var identity = TelegramBotIdentity.resolve(ACTIVE.get(bindingId));
                msg = TelegramChannel.parseUpdate(update, identity.username(), identity.userId());
            }
            if (callback == null && msg == null) return;

            Ctx ctx = loadCtx(bindingId);
            if (ctx == null || !ctx.enabled() || ctx.agent() == null) {
                EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                        "Dropping update for missing/disabled binding %d".formatted(bindingId));
                return;
            }

            if (callback != null) {
                handleCallback(bindingId, ctx, callback);
                return;
            }

            handleMessage(bindingId, ctx, msg);
        } catch (Exception e) {
            EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                    "Polling update processing error for binding %d: %s".formatted(
                            bindingId, e.getMessage()));
        }
    }

    /**
     * Pull everything we need inside the transaction so the outbound send
     * path doesn't touch JPA-managed state on a non-request thread.
     */
    private static Ctx loadCtx(Long bindingId) {
        return services.Tx.run(() -> {
            TelegramBinding b = TelegramBinding.findById(bindingId);
            if (b == null) return null;
            // Force eager read of the agent's name/id to avoid detached-proxy
            // access later. @ManyToOne is EAGER by default but harmless to touch.
            if (b.agent != null) {
                var _ = b.agent.name;
            }
            return new Ctx(b.botToken, b.telegramUserId, b.agent, b.enabled);
        });
    }

    private static void handleCallback(Long bindingId, Ctx ctx, TelegramChannel.InboundCallback callback) {
        if (!ctx.telegramUserId().equals(callback.fromId())) {
            EventLogger.warn(LOG_CATEGORY, ctx.agent().name, LOG_SOURCE,
                    "Rejected callback from user %s: binding %d is bound to user %s".formatted(
                            callback.fromId(), bindingId, ctx.telegramUserId()));
            return;
        }
        TelegramCallbackDispatcher.dispatch(ctx.botToken(), ctx.agent(), callback);
    }

    private static void handleMessage(Long bindingId, Ctx ctx, TelegramChannel.InboundMessage msg) {
        // JCLAW-371: DM owner-only; group/supergroup served only when the bot
        // was directly addressed (msg.botMentioned). See TelegramAccessPolicy.
        boolean ownerMatches = ctx.telegramUserId().equals(msg.fromId());
        if (!TelegramAccessPolicy.isAllowed(ownerMatches, msg.chatType(), msg.botMentioned())) {
            EventLogger.warn(LOG_CATEGORY, ctx.agent().name, LOG_SOURCE,
                    "Rejected inbound from %s (id=%s) in %s chat: binding %d (owner %s, mentioned=%s)".formatted(
                            msg.fromUsername() != null ? msg.fromUsername() : "?",
                            msg.fromId(), msg.chatType(), bindingId, ctx.telegramUserId(),
                            msg.botMentioned()));
            return;
        }

        EventLogger.info(LOG_CATEGORY, ctx.agent().name, LOG_SOURCE,
                "Polling received from %s: %s".formatted(
                        msg.fromUsername() != null ? msg.fromUsername() : msg.fromId(),
                        utils.Strings.truncate(msg.text(), 50)));

        final String sendToken = ctx.botToken();
        final Agent sendAgent = ctx.agent();
        // JCLAW-370: a DM keys off the binding owner (unchanged); a group/
        // supergroup keys off the chat id (one shared conversation per chat,
        // per forum topic) so every allowed member shares one transcript owned
        // by the binding's JClaw peer rather than fragmenting per-member.
        final String ownerKey = ctx.telegramUserId();
        // JCLAW-136: media_group_id reassembly runs BEFORE attachment
        // download + dispatch so multi-photo albums collapse into one
        // turn. Plain-text and single-attachment messages skip the
        // buffer (null media_group_id → immediate dispatch).
        TelegramMediaGroupBuffer.add(msg, merged -> dispatchMerged(
                sendToken, sendAgent, ownerKey, merged));
    }

    /**
     * Invoked by {@link TelegramMediaGroupBuffer} either immediately (for
     * non-group messages) or after the idle window (for reassembled
     * albums). Applies attachment gates, downloads pending files, then
     * hands off to {@link AgentRunner#processInboundForAgentStreaming}.
     * Runs on a virtual thread so a long download doesn't block the
     * single-threaded scheduler used by the reassembly buffer.
     */
    private static void dispatchMerged(String sendToken, Agent sendAgent, String ownerKey,
                                        TelegramChannel.InboundMessage merged) {
        Thread.ofVirtual().name("telegram-dispatch").start(() -> {
            try {
                final String sendChatId = merged.chatId();
                var inputs = TelegramChannel.prepareInboundAttachments(
                        sendToken, sendChatId, sendAgent, merged);
                if (inputs == null) return; // helper already replied + logged

                // JCLAW-94: stream via the same sink as the webhook path. Sink
                // owns the placeholder/edit/delete lifecycle; the planner takes
                // over on seal for media-rich or oversize responses. JCLAW-95:
                // factory defers construction until the conversation id is known.
                final String sendChatType = merged.chatType();
                // JCLAW-370: shared chat/topic-scoped conversation key for
                // groups (owner key for DMs), with sender attribution prefixed
                // onto group messages so the agent can tell members apart. The
                // outbound sink still routes to the chat id (sendChatId).
                final String peerId = AgentRunner.telegramConversationPeerId(
                        ownerKey, sendChatType, sendChatId, merged.messageThreadId());
                final String attributedText = AgentRunner.telegramSenderAttributed(
                        merged.text(), sendChatType, merged.fromDisplayName(), merged.fromId());
                AgentRunner.processInboundForAgentStreaming(
                        sendAgent, LOG_SOURCE, peerId, attributedText,
                        convId -> new TelegramStreamingSink(
                                sendToken, sendChatId, sendAgent, convId, sendChatType),
                        inputs);
            } catch (Exception e) {
                EventLogger.error(LOG_CATEGORY, sendAgent != null ? sendAgent.name : null,
                        LOG_SOURCE, "Polling dispatch error: %s".formatted(e.getMessage()));
            }
        });
    }

}
