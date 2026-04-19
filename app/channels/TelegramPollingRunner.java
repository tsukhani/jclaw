package channels;

import agents.AgentRunner;
import models.Agent;
import models.TelegramBinding;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.objects.Update;
import services.EventLogger;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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

    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "telegram-cooldown-reconcile");
        t.setDaemon(true);
        return t;
    });

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
                EventLogger.info("channel", null, "telegram",
                        "Long-polling app started (%d binding(s) registered)".formatted(ACTIVE.size()));
            } catch (Exception e) {
                EventLogger.error("channel", null, "telegram",
                        "Failed to start polling app: %s".formatted(e.getMessage()));
            }
        }
    }

    /** Stop all active sessions. Safe to call at app shutdown. */
    public static synchronized void stop() {
        var app = APP.getAndSet(null);
        if (app == null) return;
        for (var entry : new HashMap<>(ACTIVE).entrySet()) {
            unregisterInternal(app, entry.getKey(), entry.getValue());
        }
        try {
            app.close();
            EventLogger.info("channel", null, "telegram", "Long-polling app closed");
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "Polling app shutdown error: %s".formatted(e.getMessage()));
        }
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
            EventLogger.info("channel", null, "telegram",
                    "Deferring register for binding %d — token in cooldown for %d ms".formatted(
                            bindingId, remaining));
            return;
        }
        COOLDOWN_UNTIL.remove(token);

        try {
            LongPollingSingleThreadUpdateConsumer consumer = update -> dispatch(bindingId, update);
            app.registerBot(token, consumer);
            ACTIVE.put(bindingId, token);
            EventLogger.info("channel", null, "telegram",
                    "Registered polling session for binding %d".formatted(bindingId));
        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "Failed to register binding %d: %s".formatted(bindingId, e.getMessage()));
        }
    }

    private static void unregisterInternal(TelegramBotsLongPollingApplication app,
                                            Long bindingId, String token) {
        try {
            if (token != null) app.unregisterBot(token);
        } catch (Exception e) {
            EventLogger.warn("channel", null, "telegram",
                    "Unregister failed for binding %d: %s".formatted(bindingId, e.getMessage()));
        }
        ACTIVE.remove(bindingId);
        TelegramChannel.evictToken(token);
        if (token != null) {
            COOLDOWN_UNTIL.put(token, System.currentTimeMillis() + COOLDOWN_MS);
            // Self-reconcile once the cooldown drains so a re-enabled binding
            // registered during the window gets picked up automatically.
            SCHEDULER.schedule(TelegramPollingRunner::reconcile, COOLDOWN_MS + 500, TimeUnit.MILLISECONDS);
        }
        EventLogger.info("channel", null, "telegram",
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

    /**
     * Per-update dispatch. Looks up the binding (re-read for freshness — the
     * admin may have disabled it between the registration and this callback),
     * verifies the sender matches {@link TelegramBinding#telegramUserId},
     * and hands the message off to the bound agent.
     */
    private static void dispatch(Long bindingId, Update update) {
        try {
            var msg = TelegramChannel.parseUpdate(update);
            if (msg == null) return;

            // Pull everything we need inside the transaction so the outbound send
            // path doesn't touch JPA-managed state on a non-request thread.
            record Ctx(String botToken, String telegramUserId, Agent agent, boolean enabled) {}
            Ctx ctx = services.Tx.run(() -> {
                TelegramBinding b = TelegramBinding.findById(bindingId);
                if (b == null) return null;
                // Force eager read of the agent's name/id to avoid detached-proxy
                // access later. @ManyToOne is EAGER by default but harmless to touch.
                if (b.agent != null) {
                    var _ = b.agent.name;
                }
                return new Ctx(b.botToken, b.telegramUserId, b.agent, b.enabled);
            });

            if (ctx == null || !ctx.enabled() || ctx.agent() == null) {
                EventLogger.warn("channel", null, "telegram",
                        "Dropping update for missing/disabled binding %d".formatted(bindingId));
                return;
            }

            if (!ctx.telegramUserId().equals(msg.fromId())) {
                EventLogger.warn("channel", ctx.agent().name, "telegram",
                        "Rejected inbound from %s (id=%s): binding %d is bound to user %s".formatted(
                                msg.fromUsername() != null ? msg.fromUsername() : "?",
                                msg.fromId(), bindingId, ctx.telegramUserId()));
                return;
            }

            EventLogger.info("channel", ctx.agent().name, "telegram",
                    "Polling received from %s: %s".formatted(
                            msg.fromUsername() != null ? msg.fromUsername() : msg.fromId(),
                            truncate(msg.text())));

            final String sendToken = ctx.botToken();
            final String sendChatId = msg.chatId();
            AgentRunner.processInboundForAgent(ctx.agent(), "telegram", ctx.telegramUserId(), msg.text(),
                    (peer, resp) -> TelegramChannel.sendMessage(sendToken, sendChatId, resp));
        } catch (Exception e) {
            EventLogger.error("channel", null, "telegram",
                    "Polling update processing error for binding %d: %s".formatted(
                            bindingId, e.getMessage()));
        }
    }

    private static String truncate(String s) {
        return s.length() > 50 ? s.substring(0, 50) + "..." : s;
    }
}
