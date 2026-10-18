package tools;

import agents.AgentRunner;
import agents.ToolContext;
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
 * JCLAW-677 / JCLAW-661: Rail A bridge between an open chat SSE turn and a
 * coding run, extracted from {@link SubagentSpawnTool}. Owns both callback maps
 * and the promote/lookup/clear operations so a coding run spawned mid-turn can
 * stream its harness events into the turn that spawned it.
 */
final class SubagentChatBridge {

    /**
     * JCLAW-661: Rail A bridge — the live streaming callbacks (if any) of a chat
     * turn watching a run, keyed by runId and consulted by
     * {@link SubagentAcpRunner}. Absent (so {@link #callbacksFor} returns null)
     * whenever no chat SSE is open for the run, in which case only Rail B (bus +
     * transcript persist) fires. Populated from {@link #CHAT_CALLBACKS} at spawn
     * time — see {@link #bindChatCallbacksToRun}.
     */
    private static final ConcurrentHashMap<Long, AgentRunner.StreamingCallbacks>
            RUN_CALLBACKS = new ConcurrentHashMap<>();

    /**
     * JCLAW-661: the open chat turn's streaming callbacks, keyed by conversation
     * id. {@link controllers.ApiChatController#streamChat} registers the current
     * turn here so a coding run spawned mid-turn can promote them onto its runId —
     * the conversation is the only id the freshly-minted run and the chat turn
     * both know (the runId does not exist yet when the turn opens).
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
     * While the current chat turn waits on {@code runIds}, send its stream a status once per
     * {@code every} in which one of them recorded activity, so the stream's idle cut measures the
     * children's silence rather than the wait. Cancel the returned future when the wait ends.
     */
    static Future<?> relayActivity(List<Long> runIds, Duration every) {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null || CHAT_CALLBACKS.get(conversationId) == null) {
            return CompletableFuture.completedFuture(null);
        }
        var lastRelay = new AtomicLong(System.nanoTime());
        long nanos = every.toNanos();
        return ACTIVITY_RELAY.scheduleAtFixedRate(() -> relayIfActive(conversationId, runIds, lastRelay),
                nanos, nanos, TimeUnit.NANOSECONDS);
    }

    private static void relayIfActive(Long conversationId, List<Long> runIds, AtomicLong lastRelay) {
        var cb = CHAT_CALLBACKS.get(conversationId);
        if (cb == null) return;
        long sinceRelay = System.nanoTime() - lastRelay.get();
        for (var runId : runIds) {
            long idle = SubagentRegistry.nanosSinceActivity(runId);
            if (idle >= 0 && idle < sinceRelay) {
                cb.onStatus().accept(STILL_WORKING_STATUS);
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

    /** JCLAW-661: promote the chat SSE bound to the current tool dispatch's
     *  conversation onto {@code runId} so the run's harness events reach the open
     *  turn. No-op outside a chat dispatch or when no tab is watching. */
    static void bindChatCallbacksToRun(Long runId) {
        var conversationId = ToolContext.conversationId();
        if (conversationId == null) return;
        var cb = CHAT_CALLBACKS.get(conversationId);
        if (cb != null) RUN_CALLBACKS.put(runId, cb);
    }

    /** JCLAW-661: unbind a finished run's chat callbacks so a completed run can't
     *  leak the promotion. */
    static void clearRunCallbacks(Long runId) {
        if (runId == null) return;
        RUN_CALLBACKS.remove(runId);
    }
}
