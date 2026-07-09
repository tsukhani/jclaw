package tools;

import agents.AgentRunner;
import agents.ToolContext;

import java.util.concurrent.ConcurrentHashMap;

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

    private SubagentChatBridge() {}

    /** JCLAW-661: register the open chat turn's streaming callbacks under its
     *  conversation id so a coding run spawned during the turn can pick them up.
     *  Null-safe on both args — a closed tab simply never registers. */
    static void registerChatCallbacks(Long conversationId, AgentRunner.StreamingCallbacks cb) {
        if (conversationId == null || cb == null) return;
        CHAT_CALLBACKS.put(conversationId, cb);
    }

    /** JCLAW-661: drop the chat-turn callbacks for a conversation (turn closed). */
    static void unregisterChatCallbacks(Long conversationId) {
        if (conversationId == null) return;
        CHAT_CALLBACKS.remove(conversationId);
    }

    /** JCLAW-661: the live streaming callbacks bound to {@code runId}, or null when
     *  no chat turn is watching it (Rail A off; Rail B still fires). */
    static AgentRunner.StreamingCallbacks callbacksFor(Long runId) {
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
