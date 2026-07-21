package channels;

import agents.AgentRunner;
import models.Agent;
import models.TelegramBinding;
import org.telegram.telegrambots.longpolling.BotSession;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.TelegramUrl;
import org.telegram.telegrambots.meta.api.methods.GetMe;
import org.telegram.telegrambots.meta.api.methods.updates.GetUpdates;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.User;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionType;
import org.telegram.telegrambots.meta.api.objects.reactions.ReactionTypeEmoji;
import org.telegram.telegrambots.meta.exceptions.TelegramApiRequestException;
import play.Play;
import services.EventLogger;
import services.Tx;
import utils.Strings;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Predicate;

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
    static final List<String> ALLOWED_UPDATES = List.of(
            "message", "edited_message", "callback_query", "message_reaction");

    private static final AtomicReference<TelegramBotsLongPollingApplication> APP = new AtomicReference<>();

    /** bindingId → active bot token. Used to detect token rotation on reconcile. */
    private static final ConcurrentHashMap<Long, String> ACTIVE = new ConcurrentHashMap<>();

    /**
     * JCLAW-429: bindingId → the {@link BotSession} returned by registerBot, so
     * unregister can shut down its per-session executor. The SDK's
     * {@code unregisterBot} only calls {@code BotSession.stop()} (cancels the
     * poll) and neither {@code stop()} nor {@code close()} shuts the
     * {@code ScheduledExecutorService} down — so without this every unregister
     * leaks a {@code pool-N-thread-1}.
     */
    private static final ConcurrentHashMap<Long, BotSession> SESSIONS = new ConcurrentHashMap<>();

    /** getUpdates long-poll timeout (seconds) requested on every poll (JCLAW-375). */
    static final int GET_UPDATES_TIMEOUT_SECONDS = 50;

    /**
     * JCLAW-436: predicate answering "has Telegram permanently rejected this bot
     * token?" — getMe returning 401/403/404 (auth/not-found). The standalone
     * token health-probe ({@link #probeTokenHealth}) uses it to disable a binding
     * whose token is invalid/revoked. Injectable (volatile, publish-then-read) so
     * the probe's tests can stub it without dialing {@code api.telegram.org};
     * production uses {@link #tokenRejectedByTelegram}.
     */
    @SuppressWarnings("java:S3077") // volatile predicate: pure publish-then-read, no compound mutation
    private static volatile Predicate<String> tokenRejectedCheck =
            TelegramPollingRunner::tokenRejectedByTelegram;

    private TelegramPollingRunner() {}

    /**
     * Reconcile the active set against the desired set derived from
     * {@link TelegramBinding#findAllEnabled()} filtered to POLLING transport:
     * start new bindings, stop removed/disabled ones, restart ones whose token
     * changed. Synchronized so concurrent admin saves don't race.
     */
    public static synchronized void reconcile() {
        var desired = Tx.run(() ->
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

        // No app.start() here — and there must not be one. JCLAW-431:
        // telegrambots 9.5.0 constructs TelegramBotsLongPollingApplication with
        // isAppRunning=true (the ctor does `new AtomicBoolean(true)`), so the app
        // is "running" from construction and registerBot auto-starts each
        // session's poller on registration. app.start() can therefore ONLY throw
        // "App is already running" — it never has anything to start — which
        // logged a spurious ERROR on every binding add/delete/rotation. We
        // deliberately leave the app instance in place
        // when the registration set drops to zero so a later reconcile reuses the
        // dispatcher without recreating threads; the next register's session
        // auto-starts the same way.
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
     * {@link #SESSIONS}, restores the real token-rejection probe, and nulls the
     * {@link #APP} reference so each test starts clean.
     */
    static void clearForTest() {
        ACTIVE.clear();
        // JCLAW-429: drop the session map so each test starts clean.
        SESSIONS.clear();
        // JCLAW-436: restore the real getMe-backed token-rejection probe.
        tokenRejectedCheck = TelegramPollingRunner::tokenRejectedByTelegram;
        APP.set(null);
    }

    private static void registerInternal(TelegramBotsLongPollingApplication app, TelegramBinding binding) {
        final Long bindingId = binding.id;
        final String token = binding.botToken;

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
                    TelegramOffsetStore.persist(token, update.getUpdateId());
                }
            };
            BotSession session = app.registerBot(token, () -> TelegramUrl.DEFAULT_URL,
                    seedingGetUpdatesGenerator(persistedOffset), consumer);
            ACTIVE.put(bindingId, token);
            // JCLAW-429: keep the session so unregisterInternal can shut down its
            // executor (the SDK never does). The in-memory test app returns null.
            if (session != null) SESSIONS.put(bindingId, session);
            EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                    "Registered polling session for binding %d (offset seeded from %d)".formatted(
                            bindingId, persistedOffset));
        } catch (Exception e) {
            // JCLAW-387 D2: classify the registration failure + name its recovery
            // curve. A recoverable failure (network/5xx/429/409) clears on the
            // SDK's own getUpdates backoff or the next reconcile; a non-recoverable
            // one (401/403/404) needs an operator. No competing retry is added —
            // the SDK owns the poll loop.
            EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                    "Failed to register binding %d: %s [%s]".formatted(
                            bindingId, e.getMessage(), describePollingErrorCurve(e)));
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
    private static Function<Integer, GetUpdates> seedingGetUpdatesGenerator(int persistedOffset) {
        return sdkLastReceived -> GetUpdates.builder()
                .limit(100)
                .timeout(GET_UPDATES_TIMEOUT_SECONDS)
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
        // JCLAW-429: the SDK's unregisterBot calls BotSession.stop() (cancels the
        // poll) but never shuts down the per-session ScheduledExecutorService — so
        // without this every unregister leaks a pool-N thread. shutdown()
        // (not shutdownNow) so an in-flight dispatch isn't interrupted mid-DB
        // write; stop() already cancelled the poll, so the executor is idle.
        var leaked = SESSIONS.remove(bindingId);
        if (leaked != null) {
            try {
                leaked.getExecutor().shutdown();
            } catch (Exception e) {
                EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                        "Executor shutdown failed for binding %d: %s".formatted(bindingId, e.getMessage()));
            }
        }
        TelegramChannel.evictToken(token);
        EventLogger.info(LOG_CATEGORY, null, LOG_SOURCE,
                "Unregistered polling session for binding %d".formatted(bindingId));
    }

    // ===== JCLAW-436: standalone token health-probe =====

    /** A (binding id, bot token) pair snapshotted out of a transaction. */
    private record TokenTarget(Long id, String token) {}

    /**
     * JCLAW-436: probe every enabled POLLING binding's bot token against Telegram
     * and disable any the server permanently rejects (getMe 401/403/404 → the
     * token is invalid or revoked). For each rejected token we log an operator
     * alert, persist {@code enabled = false} so no future reconcile re-registers
     * it, and unregister its live polling session if one is active.
     *
     * <p>Driven by {@link jobs.TelegramTokenHealthJob} on a periodic cadence. It
     * replaces the old liveness watchdog, whose SDK-level rebuild logic never
     * actually fired in production (a registered {@code BotSession} always reports
     * {@code isRunning() == true}, so the staleness path was inert).
     *
     * <p>Must NOT throw: a probe failure cannot be allowed to crash the scheduled
     * job. The DB snapshot and each per-binding action are wrapped so one bad
     * binding (or a transient probe error) doesn't abort the rest.
     */
    public static void probeTokenHealth() {
        List<TokenTarget> targets;
        try {
            // Snapshot (id, token) pairs into a plain list inside the tx so no
            // JPA-managed entity escapes to the off-request probe thread.
            targets = Tx.run(() -> {
                var out = new ArrayList<TokenTarget>();
                for (var b : TelegramBinding.findAllEnabledByTransport(ChannelTransport.POLLING)) {
                    out.add(new TokenTarget(b.id, b.botToken));
                }
                return out;
            });
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Token health probe could not load bindings: %s".formatted(e.getMessage()));
            return;
        }

        for (var target : targets) {
            try {
                if (target.token() == null || !tokenRejectedCheck.test(target.token())) continue;
                EventLogger.error(LOG_CATEGORY, null, LOG_SOURCE,
                        ("Polling binding %d disabled: Telegram rejected its bot token (getMe "
                                + "401/403/404). The token is invalid or revoked — fix it and "
                                + "re-enable the binding.").formatted(target.id()));
                disableBindingForInvalidToken(target.id());
                var app = APP.get();
                if (app != null && ACTIVE.containsKey(target.id())) {
                    unregisterInternal(app, target.id(), target.token());
                }
            } catch (Exception e) {
                // One bad binding must not abort the rest of the sweep.
                EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                        "Token health probe error for binding %d: %s".formatted(
                                target.id(), e.getMessage()));
            }
        }
    }

    /**
     * JCLAW-434: does Telegram reject {@code token} as permanently invalid? Calls
     * {@code getMe} and treats a 401/403/404 (auth / bot-not-found) as a
     * definitive rejection — a revoked or wrong token. A transient/network
     * failure is NOT a rejection (returns false) so a blip never disables a good
     * binding. Backs the default {@link #tokenRejectedCheck}; tests inject a stub.
     */
    private static boolean tokenRejectedByTelegram(String token) {
        if (token == null) return false;
        try {
            TelegramChannel.forToken(token).client()
                    .execute(GetMe.builder().build());
            return false; // Telegram accepted the token
        } catch (Exception e) {
            return ERR_NON_RECOVERABLE.equals(classifyPollingError(e));
        }
    }

    /**
     * JCLAW-434: persist {@code enabled = false} for the binding whose token
     * Telegram rejected, so every future reconcile skips it instead of
     * re-registering a poller that can only 401. Runs in its own transaction (the
     * probe thread is outside one in prod); failures are logged, never thrown — a
     * disable that can't persist must not crash the health probe.
     */
    private static void disableBindingForInvalidToken(Long bindingId) {
        try {
            Tx.run(() -> {
                TelegramBinding b = TelegramBinding.<TelegramBinding>findById(bindingId);
                if (b != null) {
                    b.enabled = false;
                    b.save();
                }
                return null;
            });
        } catch (Exception e) {
            EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                    "Failed to disable binding %d after token rejection: %s".formatted(
                            bindingId, e.getMessage()));
        }
    }

    // ===== JCLAW-436 test seam (documented in TelegramPollingRunnerTestHooks) =====

    /**
     * JCLAW-434/436: override the token-rejection check so the health-probe tests
     * can drive the auto-disable path without dialing {@code api.telegram.org}.
     * Pass {@code null} to restore the real {@code getMe} probe.
     */
    static void setTokenRejectedCheckForTest(Predicate<String> p) {
        tokenRejectedCheck = (p != null) ? p : TelegramPollingRunner::tokenRejectedByTelegram;
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

            Ctx ctx = loadCtx(bindingId);
            if (ctx == null || !ctx.enabled() || ctx.agent() == null) {
                EventLogger.warn(LOG_CATEGORY, null, LOG_SOURCE,
                        "Dropping update for missing/disabled binding %d".formatted(bindingId));
                return;
            }

            InboundMessage msg = null;
            if (callback == null && reaction == null) {
                // Resolve the bot's identity from the same Ctx snapshot owner/agent
                // derive from, rather than ACTIVE — keeps token/owner/agent consistent.
                var identity = TelegramBotIdentity.resolve(ctx.botToken());
                msg = TelegramChannel.parseUpdate(update, identity.username(), identity.userId());
            }
            if (callback == null && reaction == null && msg == null) return;

            if (callback != null) {
                handleCallback(bindingId, ctx, callback);
                return;
            }

            if (reaction != null) {
                handleReaction(ctx.agent(), ctx.botToken(), ctx.telegramUserId(), reaction);
                return;
            }

            // JCLAW-387 B1: detect a forward off the RAW update (the parsed
            // InboundMessage drops the forward fields) so handleMessage can route
            // a forward burst through the coalesce lane.
            handleMessage(bindingId, ctx, msg, isForward(update));
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
        return Tx.run(() -> {
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

    private static void handleCallback(Long bindingId, Ctx ctx, InboundCallback callback) {
        if (!ctx.telegramUserId().equals(callback.fromId())) {
            EventLogger.warn(LOG_CATEGORY, ctx.agent().name, LOG_SOURCE,
                    "Rejected callback from user %s: binding %d is bound to user %s".formatted(
                            callback.fromId(), bindingId, ctx.telegramUserId()));
            return;
        }
        TelegramCallbackDispatcher.dispatch(ctx.botToken(), ctx.agent(), callback);
    }

    private static void handleMessage(Long bindingId, Ctx ctx, InboundMessage msg,
                                      boolean isForward) {
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
                        Strings.truncate(msg.text(), 50)));

        final String sendToken = ctx.botToken();
        final Agent sendAgent = ctx.agent();
        // JCLAW-370: a DM keys off the binding owner (unchanged); a group/
        // supergroup keys off the chat id (one shared conversation per chat,
        // per forum topic) so every allowed member shares one transcript owned
        // by the binding's JClaw peer rather than fragmenting per-member.
        final String ownerKey = ctx.telegramUserId();
        // JCLAW-387 B1: a FORWARD takes priority over the text/media lanes — a
        // burst of forwards (text or media) coalesces into ONE turn. Checked
        // first because a forwarded plain text would otherwise fall into the
        // text-reassembly lane (which targets client auto-split pastes, not
        // forward bursts) and a forwarded photo into the media-group lane.
        //
        // JCLAW-136: media_group_id reassembly runs BEFORE attachment
        // download + dispatch so multi-photo albums collapse into one
        // turn. Plain-text and single-attachment messages skip the
        // buffer (null media_group_id → immediate dispatch).
        //
        // M2 inbound reassembly: an eligible plain-text message (no
        // attachments, no media_group_id) routes through the inbound-text
        // buffer so a long paste auto-split by the client into consecutive
        // pieces coalesces into ONE turn. Sub-threshold pieces dispatch
        // immediately there, so normal messages keep today's zero-latency
        // path. Everything else stays on the media-group buffer unchanged.
        if (isForward) {
            TelegramForwardCoalesceBuffer.add(msg, merged -> dispatchMerged(
                    sendToken, sendAgent, ownerKey, merged));
        } else if (TelegramInboundTextBuffer.isEligible(msg)) {
            TelegramInboundTextBuffer.add(msg, merged -> dispatchMerged(
                    sendToken, sendAgent, ownerKey, merged));
        } else {
            TelegramMediaGroupBuffer.add(msg, merged -> dispatchMerged(
                    sendToken, sendAgent, ownerKey, merged));
        }
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
                                        InboundMessage merged) {
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
                // JCLAW-377: route a forum-topic message to its per-topic override
                // agent when one is mapped; falls back to the binding default for
                // non-topic / unmapped messages. peerId + sink are unchanged — only
                // which agent runs the turn changes.
                final Agent runAgent = resolveTopicAgent(sendToken, sendChatId, merged.messageThreadId(), sendAgent);
                // JCLAW-387 B4 follow-up: pass the Telegram chat.type so the new
                // conversation is stamped with it (plain DM vs group history caps).
                AgentRunner.processInboundForAgentStreaming(
                        runAgent, LOG_SOURCE, peerId, attributedText,
                        convId -> new TelegramStreamingSink(
                                sendToken, sendChatId, sendAgent, convId, sendChatType,
                                merged.messageId(), merged.messageThreadId()),
                        inputs, sendChatType);
            } catch (Exception e) {
                EventLogger.error(LOG_CATEGORY, Agent.nameOf(sendAgent),
                        LOG_SOURCE, "Polling dispatch error: %s".formatted(e.getMessage()));
            }
        });
    }

    /**
     * JCLAW-377: resolve which agent should run a turn for {@code (chatId,
     * threadId)}. Reads the binding by its bot token and delegates to
     * {@link TelegramBinding#resolveAgentForTopic} — returning the per-topic
     * override agent when mapped, otherwise the binding's default. The read
     * runs in a {@link services.Tx} (the dispatch virtual thread has no ambient
     * JPA transaction), and the resolved agent's name is touched eagerly to
     * avoid detached-proxy access on the streaming path. Falls back to
     * {@code defaultAgent} if the binding can't be found (e.g. removed between
     * receive and dispatch).
     */
    private static Agent resolveTopicAgent(String botToken, String chatId, Integer threadId, Agent defaultAgent) {
        return Tx.run(() -> {
            TelegramBinding binding = TelegramBinding.findByBotToken(botToken);
            if (binding == null) return defaultAgent;
            Agent resolved = binding.resolveAgentForTopic(chatId, threadId);
            if (resolved != null) {
                var _ = resolved.name; // touch inside tx to avoid detached-proxy access later
            }
            // Fall back to the binding default if a topic-override's agent FK was
            // orphaned (agent deleted): resolveAgentForTopic returns null then, and a
            // null agent NPEs in ConversationService downstream.
            return resolved != null ? resolved : defaultAgent;
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
                                List<String> added, List<String> removed) {}

    /**
     * Resolve the configured reaction-notify policy, normalizing unknown/blank
     * values to {@link #NOTIFY_OWN}. Public so default-package tests can assert
     * the config-read contract (matches the {@code *ForTest} convention).
     */
    public static String reactionNotifyMode() {
        var raw = Play.configuration.getProperty(CFG_REACTIONS_NOTIFY, NOTIFY_OWN);
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

    private static LinkedHashSet<String> emojiSet(
            List<ReactionType> reactions) {
        var out = new LinkedHashSet<String>();
        if (reactions == null) return out;
        for (var r : reactions) {
            if (r instanceof ReactionTypeEmoji emoji
                    && emoji.getEmoji() != null && !emoji.getEmoji().isBlank()) {
                out.add(emoji.getEmoji());
            }
        }
        return out;
    }

    private static String displayLabel(User u) {
        if (u.getUserName() != null && !u.getUserName().isBlank()) return "@" + u.getUserName();
        if (u.getFirstName() != null && !u.getFirstName().isBlank()) return u.getFirstName();
        return u.getId() != null ? String.valueOf(u.getId()) : "someone";
    }

    // ===== JCLAW-387 B1: forwarded-message detection =====

    /**
     * True when {@code update} carries a forwarded message. Detection reads the
     * RAW SDK {@link Message}
     * because the parsed {@link InboundMessage} does NOT retain
     * the forward fields. Both dispatch sites
     * ({@link controllers.WebhookTelegramController} and {@link #dispatch}) call
     * this on the same update they pass to
     * {@link TelegramChannel#parseUpdate(Update, String, String)} so a forward
     * routes through {@link TelegramForwardCoalesceBuffer} instead of the
     * text-reassembly / media-group lanes.
     *
     * <p>Bot API 7.0+ uses {@code forward_origin} (a {@code MessageOrigin}
     * variant) as the canonical forward marker; the SDK still populates the
     * legacy {@code forward_date} for backward compatibility. We treat EITHER as
     * a forward so detection holds across both payload shapes. Public so
     * default-package tests can assert the detection contract.
     */
    public static boolean isForward(Update update) {
        if (update == null) return false;
        var msg = update.getMessage();
        if (msg == null) return false;
        return msg.getForwardOrigin() != null || msg.getForwardDate() != null;
    }

    /**
     * JCLAW-375: gate an inbound reaction delta against the notify policy and,
     * when allowed, hand it to the bound agent as a synthetic system message.
     *
     * <p>Policy ({@link #reactionNotifyMode}):
     * <ul>
     *   <li>{@code off} — never notify.</li>
     *   <li>{@code own} (default) — only reactions on messages the bot sent. In a
     *       private (DM) chat the only non-owner messages are the bot's, so an
     *       owner reaction is necessarily on a bot-sent message. In a group the
     *       update doesn't carry the reacted message's author, so JCLAW-383
     *       consults {@link TelegramChannel#wasSentByBot} (the bot-sent-id cache):
     *       a hit notifies; a miss (non-bot message, or a cold cache after a
     *       restart) stays suppressed — conservative under-notify, never
     *       over-notify.</li>
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
        // JCLAW-383: under mode=own in a group, the message_reaction update
        // doesn't carry the reacted message's author — so we can't tell from the
        // update alone whether it was a bot message. Consult the bot-sent-id
        // cache: a hit means the reacted message is one we sent, so own should
        // notify; a miss (or cold cache after restart) keeps own group-silent.
        // Only own consults the cache — off/all ignore it, so skip the lookup.
        boolean botSent = NOTIFY_OWN.equals(mode)
                && TelegramChannel.wasSentByBot(botToken, reaction.chatId(), reaction.messageId());
        if (!shouldNotifyReaction(mode, reaction.chatType(), botSent)) return;

        final String eventText = reactionEventText(reaction);
        final String peerId = AgentRunner.telegramConversationPeerId(
                ownerTelegramUserId, reaction.chatType(), reaction.chatId(), null);
        EventLogger.info(LOG_CATEGORY, agent.name, LOG_SOURCE,
                "Reaction notification (mode=%s): %s".formatted(mode, eventText));
        Thread.ofVirtual().name("telegram-reaction").start(() -> {
            try {
                AgentRunner.processInboundForAgent(agent, LOG_SOURCE, peerId, eventText,
                        (pid, response) -> TelegramChannel.forToken(botToken).sendText(
                                reaction.chatId(), response, agent));
            } catch (Exception e) {
                EventLogger.error(LOG_CATEGORY, agent.name, LOG_SOURCE,
                        "Reaction dispatch error: %s".formatted(e.getMessage()));
            }
        });
    }

    /**
     * The pure gate decision for an inbound reaction, split out so it's testable
     * without standing up the agent dispatch path. Two-arg form with no bot-sent
     * signal available (the JCLAW-375 shape): delegates with {@code botSent =
     * false}, so a group reaction stays suppressed under {@code own}. Retained for
     * the config-contract tests and any caller that lacks the cache.
     */
    public static boolean shouldNotifyReaction(String mode, String chatType) {
        return shouldNotifyReaction(mode, chatType, false);
    }

    /**
     * JCLAW-383: the pure gate decision, now able to recognize a group reaction
     * on a bot-sent message via {@code botSentMessage} (resolved from
     * {@link TelegramChannel#wasSentByBot}). {@code off} → never; {@code all} →
     * always; {@code own} → a private (DM) chat (its only non-owner messages are
     * the bot's), OR a group/supergroup reaction on a message the bot actually
     * sent. A group reaction on a non-bot message (or one missing from the cache)
     * stays suppressed under {@code own} — conservative under-notify, never
     * over-notify.
     */
    public static boolean shouldNotifyReaction(String mode, String chatType, boolean botSentMessage) {
        if (NOTIFY_OFF.equals(mode)) return false;
        if (NOTIFY_ALL.equals(mode)) return true;
        // NOTIFY_OWN (and any normalized-to-own default): a DM is always the
        // bot's conversation; a group reaction qualifies only when it lands on
        // a message the bot sent.
        return CHAT_TYPE_PRIVATE.equals(chatType) || botSentMessage;
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

    // ===== JCLAW-387 D2: own the polling-error classification (NOT the backoff) =====
    //
    // Scope note: the pengrad/telegrambots long-polling SDK owns the getUpdates
    // network loop AND its own backoff/retry — JClaw never sees a per-poll network
    // error to retry. We deliberately do NOT add a competing retry loop (that would
    // fight the SDK's own backoff and risk double-polling → HTTP 409). What JClaw
    // CAN own cleanly is the *classification* of the polling-related errors that DO
    // surface on its side — registration (registerInternal) and app.start() — into
    // recoverable vs non-recoverable, plus a log line that names the recovery curve.
    // This is observability over the SDK's curve, not a replacement for it.

    /** Recoverable: a transient condition the SDK's own retry/backoff (or the
     *  cooldown reconcile) will clear without operator action. */
    public static final String ERR_RECOVERABLE = "recoverable";
    /** Non-recoverable: a config/auth condition that won't clear by retrying;
     *  needs an operator (bad token, bot blocked, binding misconfigured). */
    public static final String ERR_NON_RECOVERABLE = "non-recoverable";

    /**
     * Classify a polling-related {@link Throwable} as {@link #ERR_RECOVERABLE} or
     * {@link #ERR_NON_RECOVERABLE}. Non-recoverable means an operator must act —
     * auth/permission failures (HTTP 401/403) and not-found (404) on a bad token;
     * everything else (timeouts, connection resets, 5xx, 429 rate-limit, the
     * 409 stale-poll conflict the cooldown already handles) is treated as
     * recoverable. Conservative by design: when in doubt, recoverable — so we
     * never tell an operator to act on a transient blip.
     *
     * <p>Pure + public so default-package tests can assert the contract without
     * standing up the SDK poll loop.
     */
    public static String classifyPollingError(Throwable t) {
        if (t == null) return ERR_RECOVERABLE;
        Integer code = telegramErrorCode(t);
        if (code != null && (code == 401 || code == 403 || code == 404)) {
            return ERR_NON_RECOVERABLE;
        }
        return ERR_RECOVERABLE;
    }

    /**
     * The Telegram HTTP error code carried by {@code t} (or a cause in its chain),
     * or null when none is present. Walks the cause chain because the SDK may wrap
     * a {@code TelegramApiRequestException} inside a registration failure.
     */
    private static Integer telegramErrorCode(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof TelegramApiRequestException req) {
                return req.getErrorCode();
            }
            if (c.getCause() == c) break; // self-referential chain guard
        }
        return null;
    }

    /**
     * One-line description of how a classified polling error recovers, for the
     * log. Recoverable errors recover on the SDK's own backoff curve (network /
     * 5xx / 429) or, for registration, on the next reconcile; non-recoverable
     * errors need operator action. Public for the classification-contract test.
     */
    public static String describePollingErrorCurve(Throwable t) {
        String cls = classifyPollingError(t);
        if (ERR_NON_RECOVERABLE.equals(cls)) {
            return "non-recoverable (auth/config) — will NOT clear on retry; operator action required";
        }
        return "recoverable — SDK owns the getUpdates backoff curve; "
                + "registration retries on the next reconcile";
    }

}
