package tools;

import agents.AgentRunner;
import agents.ToolContext;
import agents.TurnBroadcast;
import org.jspecify.annotations.Nullable;
import services.SubagentRegistry;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JCLAW-677 / JCLAW-661: Rail A bridge between an open chat turn and the subagents it waits on, extracted from
 * {@link SubagentSpawnTool}: a coding run spawned mid-turn streams its harness events into the turn that spawned it,
 * and a long wait relays that the subagent is still working.
 *
 * <p>A turn a chat stream tab holds ({@code POST /api/chat/stream}) registers its callbacks under its conversation,
 * and they get these frames, so the tab shows them and its idle timer hears the wait; the registered set publishes
 * each to the conversation's watchers too (JF-200). A turn no request holds ({@code POST /api/chat/turn}, JF-202)
 * registers none, so they go to the turn open on the conversation through a joining {@link TurnBroadcast} instead.
 * Either way the conversation's stream gets each frame once.
 */
final class SubagentChatBridge {

    /**
     * JCLAW-661: Rail A — the callbacks (if any) of the chat turn watching a run, keyed by runId and consulted by
     * {@link SubagentAcpRunner}. Absent (so {@link #callbacksFor} returns null) whenever no turn was open on the
     * spawning conversation, in which case only Rail B (bus + transcript persist) fires. Populated at spawn time — see
     * {@link #bindChatCallbacksToRun}.
     */
    private static final ConcurrentHashMap<Long, AgentRunner.StreamingCallbacks>
            RUN_CALLBACKS = new ConcurrentHashMap<>();

    /**
     * JCLAW-661: the open chat stream turn's callbacks, keyed by conversation id.
     * {@link controllers.ApiChatController#streamChat} registers the current turn here so a coding run spawned mid-turn
     * can promote them onto its runId — the conversation is the only id the freshly-minted run and the chat turn both
     * know (the runId does not exist yet when the turn opens).
     */
    private static final ConcurrentHashMap<Long, AgentRunner.StreamingCallbacks>
            CHAT_CALLBACKS = new ConcurrentHashMap<>();

    /** How often a chat turn waiting on a subagent hears that the subagent is still working. */
    static final Duration ACTIVITY_RELAY_INTERVAL = Duration.ofMinutes(1);

    static final String STILL_WORKING_STATUS = "Subagent still working";

    // A platform thread keeps the periodic wait off the virtual-thread scheduler (JDK-8373224).
    private static final ScheduledExecutorService ACTIVITY_RELAY =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "subagent-activity-relay");
                t.setDaemon(true);
                return t;
            });

    private SubagentChatBridge() {}

    /**
     * While the current chat turn waits on {@code runIds}, send it a status once per {@code every} in which one of them
     * recorded activity: a chat stream tab's idle cut then measures the children's silence rather than the wait, and
     * the conversation's watchers see the wait is work. Cancel the returned future when the wait ends.
     */
    static Future<?> relayActivity(List<Long> runIds, Duration every) {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) return CompletableFuture.completedFuture(null);
        var turn = TurnBroadcast.joining(conversationId);
        if (CHAT_CALLBACKS.get(conversationId) == null && !turn.isOpen()) {
            return CompletableFuture.completedFuture(null);
        }
        var lastRelay = new AtomicLong(System.nanoTime());
        long nanos = every.toNanos();
        boolean pinned = turn.isOpen();
        return ACTIVITY_RELAY.scheduleAtFixedRate(() -> relayIfActive(conversationId, turn, pinned, runIds, lastRelay),
                nanos, nanos, TimeUnit.NANOSECONDS);
    }

    private static void relayIfActive(Long conversationId, TurnBroadcast turn, boolean pinned, List<Long> runIds,
                                      AtomicLong lastRelay) {
        // A stopped turn's wait can outlast it, and the tab registered by then is a later turn's. A tab whose turn
        // never opened on the stream still hears the wait, so its idle cut does not depend on the watchers.
        if (pinned && !turn.isOpen()) return;
        long sinceRelay = System.nanoTime() - lastRelay.get();
        for (var runId : runIds) {
            long idle = SubagentRegistry.nanosSinceActivity(runId);
            if (idle >= 0 && idle < sinceRelay) {
                var tab = CHAT_CALLBACKS.get(conversationId);
                // A tab's registered callbacks publish to the watchers themselves; the broadcast then would repeat it.
                if (tab != null) tab.onStatus().accept(STILL_WORKING_STATUS);
                else turn.status(STILL_WORKING_STATUS);
                lastRelay.set(System.nanoTime());
                return;
            }
        }
    }

    /** JCLAW-661: register the open chat turn's streaming callbacks under its
     *  conversation id so a coding run spawned during the turn can pick them up.
     *  Null-safe on both args — a closed tab simply never registers. */
    static void registerChatCallbacks(@Nullable Long conversationId, AgentRunner.@Nullable StreamingCallbacks cb) {
        if (conversationId == null || cb == null) return;
        CHAT_CALLBACKS.put(conversationId, cb);
    }

    /** JCLAW-661: drop the chat-turn callbacks for a conversation (turn closed). Only
     *  {@code cb}'s own entry goes: a stopped turn closes after the next turn registered (GH-12). */
    static void unregisterChatCallbacks(@Nullable Long conversationId, AgentRunner.@Nullable StreamingCallbacks cb) {
        if (conversationId == null || cb == null) return;
        CHAT_CALLBACKS.computeIfPresent(conversationId, (_, registered) -> registered == cb ? null : registered);
    }

    static AgentRunner.@Nullable StreamingCallbacks chatCallbacks(Long conversationId) {
        return CHAT_CALLBACKS.get(conversationId);
    }

    /** JCLAW-661: the live streaming callbacks bound to {@code runId}, or null when
     *  no chat turn is watching it (Rail A off; Rail B still fires). */
    static AgentRunner.@Nullable StreamingCallbacks callbacksFor(Long runId) {
        return runId == null ? null : RUN_CALLBACKS.get(runId);
    }

    /**
     * JCLAW-661: promote the chat turn of the current tool dispatch's conversation onto {@code runId}, so the run's
     * harness events reach it: the tab's registered callbacks, or, for a turn no tab holds, a joining broadcast on the
     * turn open there now, which a later turn never gets. No-op outside a chat dispatch or with no turn open.
     */
    static void bindChatCallbacksToRun(Long runId) {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) return;
        var cb = CHAT_CALLBACKS.get(conversationId);
        if (cb == null) {
            var turn = TurnBroadcast.joining(conversationId);
            if (turn.isOpen()) cb = publishingTo(turn);
        }
        if (cb != null) RUN_CALLBACKS.put(runId, cb);
    }

    /** JCLAW-661: unbind a finished run's chat callbacks so a completed run can't
     *  leak the promotion. */
    static void clearRunCallbacks(Long runId) {
        if (runId == null) return;
        RUN_CALLBACKS.remove(runId);
    }

    /** The frames a run sends a turn no tab holds, published to its watchers; never an init or a terminal. */
    private static AgentRunner.StreamingCallbacks publishingTo(TurnBroadcast turn) {
        return new AgentRunner.StreamingCallbacks(_ -> { }, turn::token, turn::reasoning, turn::status,
                turn::toolCall, _ -> { }, _ -> { }, () -> { });
    }
}
