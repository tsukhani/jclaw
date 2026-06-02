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
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

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

    /**
     * JCLAW-375: the {@code allowed_updates} list requested on every
     * {@code getUpdates} poll (see {@link #seedingGetUpdatesGenerator}). Telegram
     * EXCLUDES {@code message_reaction} from its default set, so reactions are
     * only delivered when the type is named explicitly. We enumerate exactly the
     * update types JClaw dispatches — {@code message} and {@code callback_query}
     * (the only two {@link #dispatch} acts on) plus {@code edited_message} for
     * parity with the historical empty-list behavior — and add
     * {@code message_reaction} so the inbound reaction-notification path fires.
     */
    static final java.util.List<String> ALLOWED_UPDATES = java.util.List.of(
            "message", "edited_message", "callback_query", "message_reaction");

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

    /**
     * JCLAW-363 liveness watchdog. Token → epoch-millis of the last consumed
     * update for that bot (seeded to "now" at register time). The consume path
     * JCLAW-361 already wraps stamps this on every update; the watchdog tick
     * compares it against {@link #watchdogMs()} to detect a wedged poller.
     */
    private static final ConcurrentHashMap<String, Long> LAST_PROGRESS = new ConcurrentHashMap<>();

    /** Config key for the watchdog timeout in ms; 0 disables. */
    public static final String CFG_WATCHDOG_MS = "telegram.polling.watchdogMs";

    /** Default watchdog timeout: 2 minutes of no progress before a rebuild. */
    public static final long DEFAULT_WATCHDOG_MS = 120_000L;

    /**
     * Handle to the periodic watchdog tick scheduled on {@link #scheduler}, or
     * {@code null} when the watchdog isn't running ({@code watchdogMs == 0}, or
     * nothing registered yet). Cancelled on {@link #stop}/{@link #clearForTest}.
     * Volatile: published from {@link #ensureWatchdogStarted} (under the
     * {@code reconcile}/register monitor) and read/cleared from {@link #stop}.
     */
    @SuppressWarnings("java:S3077") // pure publish-then-read of a single reference, no compound mutation
    private static volatile ScheduledFuture<?> watchdogTask;

    /**
     * Test-only watchdog timeout override (ms). When non-null, {@link #watchdogMs}
     * returns this instead of reading {@code application.conf}, so tests don't
     * depend on the deployed config. {@code null} = use config/default.
     */
    private static volatile Long watchdogMsOverride;

    /**
     * Time source for progress timestamps and staleness checks. Defaults to wall
     * clock; a test can swap in a controllable supplier via
     * {@link #setClockForTest} to simulate a stale poller without sleeping.
     */
    @SuppressWarnings("java:S3077") // single-reference publish-then-read
    private static volatile LongSupplier clock = System::currentTimeMillis;

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
        // JCLAW-363: stop the periodic watchdog before draining the scheduler it
        // rides on, so the shutdown awaitTermination isn't held by a fixed-delay
        // task.
        stopWatchdog();
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
        // JCLAW-363: drop the watchdog tick + its tracking state and restore the
        // wall clock / config-driven timeout so each test starts clean.
        stopWatchdog();
        LAST_PROGRESS.clear();
        watchdogMsOverride = null;
        clock = System::currentTimeMillis;
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
                // JCLAW-363: stamp liveness on every consumed update so the
                // watchdog can tell a healthy poller from a wedged one.
                markProgress(token);
                dispatch(bindingId, update);
                if (update.getUpdateId() != null) {
                    TelegramOffsetStore.record(token, update.getUpdateId());
                }
            };
            // JCLAW-363: seed last-progress to "now" so a freshly-registered
            // (or just-rebuilt) binding isn't treated as stale before its first
            // poll completes.
            markProgress(token);
            app.registerBot(token, () -> TelegramUrl.DEFAULT_URL,
                    seedingGetUpdatesGenerator(persistedOffset), consumer);
            ACTIVE.put(bindingId, token);
            ensureWatchdogStarted();
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
     * {@code DefaultGetUpdatesGenerator} (limit 100, timeout 50) but clamps the
     * requested offset to {@code max(sdkLastReceived, persistedOffset) + 1}.
     *
     * <p>The clamp is what makes seeding race-free and monotonic: the SDK passes
     * its in-memory {@code lastReceivedUpdate} (0 on a fresh session) on every
     * poll. On the first poll {@code max(0, persisted) + 1 == persisted + 1}, so
     * Telegram skips everything already consumed before the restart. Once the
     * session has advanced past {@code persistedOffset}, {@code max} picks the
     * SDK's own (now higher) value, so the persisted seed never rewinds a
     * running session.
     *
     * <p>JCLAW-375: {@code allowed_updates} is no longer empty. Telegram's
     * default (empty list) excludes {@code message_reaction}, so the inbound
     * reaction-notification handler in {@link #dispatch} would never fire.
     * Passing {@link #ALLOWED_UPDATES} (the default set PLUS
     * {@code message_reaction}) opts the poller into reaction deliveries while
     * keeping every update type Telegram sends by default.
     */
    private static java.util.function.Function<Integer, GetUpdates> seedingGetUpdatesGenerator(int persistedOffset) {
        return sdkLastReceived -> GetUpdates.builder()
                .limit(100)
                .timeout(50)
                .offset(Math.max(sdkLastReceived, persistedOffset) + 1)
                .allowedUpdates(new ArrayList<>(ALLOWED_UPDATES))
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
        if (token != null) LAST_PROGRESS.remove(token);
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

    // ===== JCLAW-363: polling liveness watchdog + transport rebuild =====

    /** Stamp {@code token}'s last-progress timestamp to the current clock value. */
    private static void markProgress(String token) {
        if (token != null) LAST_PROGRESS.put(token, clock.getAsLong());
    }

    /**
     * Configured watchdog timeout in ms. Reads {@link #CFG_WATCHDOG_MS} from
     * {@code application.conf} (default {@link #DEFAULT_WATCHDOG_MS}); a value of
     * {@code 0} (or negative, or unparseable) disables the watchdog. A test-only
     * override ({@link #setWatchdogMsForTest}) takes precedence so tests don't
     * depend on the deployed config. Never throws.
     */
    public static long watchdogMs() {
        Long override = watchdogMsOverride;
        if (override != null) return Math.max(0L, override);
        try {
            var cfg = play.Play.configuration;
            String raw = cfg != null ? cfg.getProperty(CFG_WATCHDOG_MS) : null;
            if (raw == null || raw.isBlank()) return DEFAULT_WATCHDOG_MS;
            return Math.max(0L, Long.parseLong(raw.trim()));
        } catch (RuntimeException e) {
            return DEFAULT_WATCHDOG_MS;
        }
    }

    /**
     * Start the periodic watchdog tick if it isn't already running and the
     * watchdog is enabled ({@code watchdogMs > 0}). Idempotent and cheap to call
     * on every register. The tick fires every {@code watchdogMs} on the shared
     * cooldown-reconcile {@link #scheduler} (virtual-thread backed), so no new
     * thread pool is created. Called under the {@code reconcile}/register
     * monitor so the {@link #watchdogTask} check-then-set is single-threaded.
     */
    private static void ensureWatchdogStarted() {
        long timeout = watchdogMs();
        if (timeout <= 0 || watchdogTask != null || scheduler.isShutdown()) return;
        watchdogTask = scheduler.scheduleWithFixedDelay(
                TelegramPollingRunner::watchdogTick, timeout, timeout, TimeUnit.MILLISECONDS);
        EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                "Polling watchdog started (timeout %d ms)".formatted(timeout));
    }

    /** Cancel the periodic watchdog tick if running. */
    private static void stopWatchdog() {
        var task = watchdogTask;
        if (task != null) {
            task.cancel(false);
            watchdogTask = null;
        }
    }

    /**
     * One watchdog pass: for each active binding whose token has made no progress
     * within {@link #watchdogMs} and is NOT currently in cooldown, rebuild the
     * transport. The rebuild is {@link #unregisterInternal}, which stamps the
     * cooldown and schedules a self-{@link #reconcile} ~30.5 s out on the same
     * cooldown-aware {@link #scheduler}; that deferred reconcile re-registers the
     * binding (cleanly, after Telegram's stale long-poll has drained) and reseeds
     * its offset from {@link TelegramOffsetStore}. Reusing the cooldown path is
     * what keeps the watchdog from fighting the 409 cooldown — it never
     * re-registers inside an open cooldown window.
     *
     * <p>{@code synchronized} on the class monitor so it can't race a concurrent
     * {@link #reconcile} or {@link #stop} mutating {@link #ACTIVE}. Runs on the
     * virtual-thread-backed scheduler; it touches no JPA-managed state and never
     * interrupts a DB-touching thread (consistent with the existing cooldown
     * reconcile). A disabled watchdog ({@code watchdogMs == 0}) is a no-op even
     * if a stale tick slips through after a config flip.
     */
    static synchronized void watchdogTick() {
        long timeout = watchdogMs();
        if (timeout <= 0) return;
        long now = clock.getAsLong();
        var app = APP.get();
        if (app == null) return;
        for (var entry : new HashMap<>(ACTIVE).entrySet()) {
            Long bindingId = entry.getKey();
            String token = entry.getValue();
            if (token == null) continue;
            // Don't fight the 409 cooldown: a token mid-cooldown is already being
            // re-registered by the scheduled cooldown reconcile.
            if (cooldownRemainingMs(token) > 0) continue;
            Long last = LAST_PROGRESS.get(token);
            long elapsed = last == null ? 0L : now - last;
            if (last == null || elapsed < timeout) continue;
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Polling watchdog: binding %d stale for %d ms (>= %d) — rebuilding transport".formatted(
                            bindingId, elapsed, timeout));
            unregisterInternal(app, bindingId, token);
        }
    }

    // ===== JCLAW-363 test seams (documented in TelegramPollingRunnerTestHooks) =====

    /**
     * Test-only: override the watchdog timeout, bypassing {@code application.conf}.
     * Pass {@code null} to fall back to the configured value/default.
     */
    static void setWatchdogMsForTest(Long ms) {
        watchdogMsOverride = ms;
    }

    /**
     * Test-only: swap the time source so a test can advance "now" past the
     * timeout without sleeping. Pass {@code null} to restore the wall clock.
     */
    static void setClockForTest(LongSupplier supplier) {
        clock = supplier != null ? supplier : System::currentTimeMillis;
    }

    /** Test-only: stamp {@code token}'s last-progress to the current clock value. */
    static void markProgressForTest(String token) {
        markProgress(token);
    }

    /** Test-only: read {@code token}'s last-progress timestamp, or {@code null}. */
    static Long lastProgressForTest(String token) {
        return LAST_PROGRESS.get(token);
    }

    /** Test-only: run one watchdog pass synchronously on the calling thread. */
    static void runWatchdogTickForTest() {
        watchdogTick();
    }

    /** Test-only: is the periodic watchdog tick currently scheduled? */
    static boolean isWatchdogRunningForTest() {
        return watchdogTask != null;
    }

    /**
     * Test-only: stamp a cooldown on {@code token} for {@code ms} from now,
     * without going through an unregister. Lets a test put a still-active
     * binding's token into cooldown to verify the watchdog's cooldown guard.
     */
    static void stampCooldownForTest(String token, long ms) {
        // cooldownRemainingMs() reads the wall clock (it's called from the API
        // thread), so stamp against System time, not the injectable test clock.
        if (token != null) COOLDOWN_UNTIL.put(token, System.currentTimeMillis() + ms);
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
            // JCLAW-375: an inbound message_reaction is neither a callback nor a
            // parseable message — surface it as a gated system event before the
            // non-message short-circuit below would drop it.
            ReactionDelta reaction = callback == null ? parseReaction(update) : null;

            TelegramChannel.InboundMessage msg = null;
            if (callback == null && reaction == null) {
                var identity = TelegramBotIdentity.resolve(ACTIVE.get(bindingId));
                msg = TelegramChannel.parseUpdate(update, identity.username(), identity.userId());
            }
            if (callback == null && reaction == null && msg == null) return;

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

            if (reaction != null) {
                handleReaction(ctx.agent(), ctx.botToken(), ctx.telegramUserId(), reaction);
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
                                sendToken, sendChatId, sendAgent, convId, sendChatType,
                                merged.messageId(), merged.messageThreadId()),
                        inputs);
            } catch (Exception e) {
                EventLogger.error(LOG_CATEGORY, sendAgent != null ? sendAgent.name : null,
                        LOG_SOURCE, "Polling dispatch error: %s".formatted(e.getMessage()));
            }
        });
    }

    // ===== JCLAW-375: inbound reaction notifications =====

    private static final String CFG_REACTIONS_NOTIFY = "telegram.reactions.notify";
    /** Notify policy values for {@link #CFG_REACTIONS_NOTIFY}. */
    public static final String NOTIFY_OFF = "off";
    public static final String NOTIFY_OWN = "own";
    public static final String NOTIFY_ALL = "all";
    private static final String CHAT_TYPE_PRIVATE = "private";

    /**
     * JCLAW-375: a normalized inbound reaction delta extracted from a
     * {@code message_reaction} {@link Update}. Shared by the polling runner and
     * {@link controllers.WebhookTelegramController} so both surface reactions
     * identically.
     *
     * @param chatId    the chat the reacted message lives in
     * @param chatType  Telegram {@code chat.type} string (nullable → treated as group)
     * @param messageId the reacted message's id
     * @param reactorId the reacting user's id, or null (anonymous/channel actor)
     * @param reactor   the reacting user's display label for the event text
     * @param added     newly-added reaction emoji (new minus old); may be empty
     * @param removed   removed reaction emoji (old minus new); may be empty
     */
    public record ReactionDelta(String chatId, String chatType, Integer messageId,
                                String reactorId, String reactor,
                                java.util.List<String> added, java.util.List<String> removed) {}

    /**
     * Resolve the configured reaction-notify policy, normalizing unknown/blank
     * values to {@link #NOTIFY_OWN}. Public so default-package tests can assert
     * the config-read contract (matches the {@code *ForTest} convention).
     */
    public static String reactionNotifyMode() {
        var raw = play.Play.configuration.getProperty(CFG_REACTIONS_NOTIFY, NOTIFY_OWN);
        if (raw == null) return NOTIFY_OWN;
        var v = raw.trim().toLowerCase();
        return switch (v) {
            case NOTIFY_OFF, NOTIFY_OWN, NOTIFY_ALL -> v;
            default -> NOTIFY_OWN;
        };
    }

    /**
     * Parse a {@code message_reaction} {@link Update} into a {@link ReactionDelta},
     * or null when the update carries no reaction (every other update type) or no
     * usable message id. Computes the added/removed emoji sets from the
     * old/new reaction lists; only emoji reactions
     * ({@code ReactionTypeEmoji}) contribute (custom/paid reactions have no emoji
     * string to render). Never throws.
     */
    public static ReactionDelta parseReaction(Update update) {
        if (update == null) return null;
        var mr = update.getMessageReaction();
        if (mr == null || mr.getMessageId() == null || mr.getChat() == null) return null;

        var oldEmoji = emojiSet(mr.getOldReaction());
        var newEmoji = emojiSet(mr.getNewReaction());
        var added = new ArrayList<String>(newEmoji);
        added.removeAll(oldEmoji);
        var removed = new ArrayList<String>(oldEmoji);
        removed.removeAll(newEmoji);
        if (added.isEmpty() && removed.isEmpty()) return null; // no net change to report

        var chat = mr.getChat();
        String chatId = chat.getId() != null ? String.valueOf(chat.getId()) : null;
        String reactorId = null;
        String reactor = null;
        if (mr.getUser() != null) {
            reactorId = mr.getUser().getId() != null ? String.valueOf(mr.getUser().getId()) : null;
            reactor = displayLabel(mr.getUser());
        }
        return new ReactionDelta(chatId, chat.getType(), mr.getMessageId(),
                reactorId, reactor, added, removed);
    }

    private static java.util.LinkedHashSet<String> emojiSet(
            java.util.List<org.telegram.telegrambots.meta.api.objects.reactions.ReactionType> reactions) {
        var out = new java.util.LinkedHashSet<String>();
        if (reactions == null) return out;
        for (var r : reactions) {
            if (r instanceof org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji emoji
                    && emoji.getEmoji() != null && !emoji.getEmoji().isBlank()) {
                out.add(emoji.getEmoji());
            }
        }
        return out;
    }

    private static String displayLabel(org.telegram.telegrambots.meta.api.objects.User u) {
        if (u.getUserName() != null && !u.getUserName().isBlank()) return "@" + u.getUserName();
        if (u.getFirstName() != null && !u.getFirstName().isBlank()) return u.getFirstName();
        return u.getId() != null ? String.valueOf(u.getId()) : "someone";
    }

    /**
     * JCLAW-375: gate an inbound reaction delta against the notify policy and,
     * when allowed, hand it to the bound agent as a synthetic system message.
     *
     * <p>Policy ({@link #reactionNotifyMode}):
     * <ul>
     *   <li>{@code off} — never notify.</li>
     *   <li>{@code own} (default) — only reactions on messages the bot sent. We
     *       can't read the reacted message's author off the update, so we use the
     *       one signal that IS present and correct in the dominant case: a private
     *       (DM) chat, where the only non-owner messages are the bot's, so an
     *       owner reaction is necessarily on a bot-sent message. Group reactions
     *       are suppressed under {@code own} (author unattributable from the
     *       update).</li>
     *   <li>{@code all} — every reaction delta, any chat type.</li>
     * </ul>
     *
     * <p>Shared by the polling runner and {@link controllers.WebhookTelegramController}.
     * The event is delivered through {@link AgentRunner#processInboundForAgent}
     * (non-streaming — a reaction notification is a low-volume system event), and
     * any agent reply is sent back via {@link TelegramChannel#sendMessage}. Runs
     * the agent on a virtual thread so the caller's dispatch loop isn't blocked.
     */
    public static void handleReaction(Agent agent, String botToken, String ownerTelegramUserId,
                                      ReactionDelta reaction) {
        if (agent == null || reaction == null || reaction.chatId() == null) return;
        String mode = reactionNotifyMode();
        if (!shouldNotifyReaction(mode, reaction.chatType())) return;

        final String eventText = reactionEventText(reaction);
        final String peerId = AgentRunner.telegramConversationPeerId(
                ownerTelegramUserId, reaction.chatType(), reaction.chatId(), null);
        EventLogger.info(LOG_CATEGORY, agent.name, LOG_SOURCE,
                "Reaction notification (mode=%s): %s".formatted(mode, eventText));
        Thread.ofVirtual().name("telegram-reaction").start(() -> {
            try {
                AgentRunner.processInboundForAgent(agent, LOG_SOURCE, peerId, eventText,
                        (pid, response) -> TelegramChannel.sendMessage(
                                botToken, reaction.chatId(), response, agent));
            } catch (Exception e) {
                EventLogger.error(LOG_CATEGORY, agent.name, LOG_SOURCE,
                        "Reaction dispatch error: %s".formatted(e.getMessage()));
            }
        });
    }

    /**
     * The pure gate decision for an inbound reaction, split out so it's testable
     * without standing up the agent dispatch path. {@code off} → never;
     * {@code all} → always; {@code own} → only a private (DM) chat, where the
     * only non-owner messages are the bot's so an owner reaction is necessarily
     * on a bot-sent message (a group reaction's target author is unattributable
     * from the update, so {@code own} suppresses it).
     */
    public static boolean shouldNotifyReaction(String mode, String chatType) {
        if (NOTIFY_OFF.equals(mode)) return false;
        if (NOTIFY_ALL.equals(mode)) return true;
        // NOTIFY_OWN (and any normalized-to-own default)
        return CHAT_TYPE_PRIVATE.equals(chatType);
    }

    /**
     * Render a {@link ReactionDelta} into the system-event text the agent sees.
     * Public for default-package tests. Examples:
     * {@code "[system] @ada reacted 👍 to message 42."} /
     * {@code "[system] @ada removed reaction 👍 from message 42."}
     */
    public static String reactionEventText(ReactionDelta r) {
        var who = r.reactor() != null ? r.reactor() : "Someone";
        var sb = new StringBuilder("[system] ").append(who).append(' ');
        if (!r.added().isEmpty()) {
            sb.append("reacted ").append(String.join(" ", r.added()))
              .append(" to message ").append(r.messageId());
            if (!r.removed().isEmpty()) {
                sb.append(" (and removed ").append(String.join(" ", r.removed())).append(')');
            }
        } else {
            sb.append("removed reaction ").append(String.join(" ", r.removed()))
              .append(" from message ").append(r.messageId());
        }
        return sb.append('.').toString();
    }

}
