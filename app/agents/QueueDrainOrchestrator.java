package agents;

import java.util.concurrent.atomic.AtomicBoolean;

import services.ConversationQueue;
import services.ConversationService;
import services.EventLogger;
import services.Tx;

/**
 * Post-turn conversation-queue draining + ownership-release machinery.
 * Extracted from {@link AgentRunner} as part of JCLAW-299. The two
 * operations here cooperate to make sure that, no matter how a turn
 * terminates (normal complete, error, cancel, or exception), the
 * conversation's queue lock is released exactly once and any messages
 * queued during the turn get re-processed.
 *
 * <h3>JCLAW-117 ownership-transfer invariant</h3>
 * {@link ConversationQueue#drain} returns the pending messages and
 * <em>keeps</em> {@code processing=true} when the list is non-empty —
 * ownership transfers to the caller, who is then responsible for either
 * re-draining (which auto-releases on empty) or explicitly calling
 * {@link ConversationQueue#releaseOwnership} on early-exit paths. This
 * class implements that contract on the agent-runner side; the
 * {@code QueueDrainOwnershipTest} regression suite pins the underlying
 * invariant.
 *
 * <h3>Release timing (JCLAW-…fast-loop race)</h3>
 * The terminal callbacks (onComplete / onError / onCancel) call
 * {@link #releaseQueueOnce} BEFORE invoking the wrapped caller's handler,
 * so the queue lock is freed before the SSE terminal frame flushes to
 * the wire. Without this, a fast client (e.g. the loadtest worker firing
 * back-to-back turns inside the same conversation) would observe the
 * SSE close, immediately fire the next request, and race the post-
 * onComplete finally block — the next {@code tryAcquire} would see
 * {@code processing=true} and short-circuit with the canned "queued"
 * response. The {@code queueReleased} CAS makes the release single-shot
 * so the outer finally block remains a defense-in-depth path for
 * exception cases.
 */
public final class QueueDrainOrchestrator {

    private QueueDrainOrchestrator() {}

    /**
     * Idempotent queue release helper. The
     * {@link AtomicBoolean#compareAndSet} ensures exactly one drain
     * fires per turn even if multiple terminal callbacks race (which
     * shouldn't happen in normal flow, but the CAS makes the invariant
     * local to this method rather than relying on caller discipline).
     *
     * <p>{@code conversationIdRef} is a 1-element array so callers can
     * populate it after the wrapper that owns it has been constructed;
     * a {@code null} entry means the queue acquire never succeeded (e.g.
     * the queued-canned-response short-circuit) and the call is a safe
     * no-op.
     */
    static void releaseQueueOnce(Long[] conversationIdRef, AtomicBoolean queueReleased) {
        if (conversationIdRef[0] == null) return;
        if (!queueReleased.compareAndSet(false, true)) return;
        processQueueDrain(conversationIdRef[0]);
    }

    /**
     * Drain the conversation queue after processing completes, then
     * re-process any waiting messages on a virtual thread. Each drained
     * message gets a full {@link AgentRunner#runWithOwnedQueue}
     * invocation (which reuses the queue ownership transferred by this
     * method's {@link ConversationQueue#drain} call). For external
     * channels (Telegram, Slack, WhatsApp), the response is dispatched
     * back through the channel via {@link AgentRunner#dispatchToChannel}.
     * For web, the response is persisted to the DB and the user sees it
     * on next conversation load.
     *
     * <p>Called from the {@code finally} block of both
     * {@link AgentRunner#run} and {@link AgentRunner#runStreaming} (via
     * {@link #releaseQueueOnce}). Failures are logged but never
     * propagated — the primary request must not fail because a queued
     * message's re-processing fails.
     */
    static void processQueueDrain(Long conversationId) {
        // JCLAW-117: drain() now keeps processing=true when it returns a
        // non-empty list — ownership transfers to this call. The virtual
        // thread below must either re-drain on success (runAfterAcquire's
        // finally will re-invoke processQueueDrain, and a later empty drain
        // releases) or explicitly releaseOwnership on early-exit paths
        // (findById returned null, or run threw before finally could fire).
        var drained = ConversationQueue.drain(conversationId);
        if (drained.isEmpty()) return;

        Thread.ofVirtual().name("agent-drain").start(() -> {
            var combined = ConversationQueue.formatCollectedMessages(drained);
            var msg = drained.getFirst(); // channel info is the same for all queued messages
            boolean runStarted = false;
            try {
                var conversation = Tx.run(() -> ConversationService.findById(conversationId));
                if (conversation == null) {
                    ConversationQueue.releaseOwnership(conversationId);
                    return;
                }
                runStarted = true;
                // JCLAW-117: queue ownership was transferred to us by drain()
                // above — use the owned-queue variant to avoid re-acquire.
                var result = AgentRunner.runWithOwnedQueue(msg.agent(), conversation, combined);
                AgentRunner.dispatchToChannel(msg.agent(), msg.channelType(), msg.peerId(), result.response());
            } catch (Exception e) {
                EventLogger.error("queue", msg.agent().name, msg.channelType(),
                        "Failed to process queued message: %s".formatted(e.getMessage()));
                if (!runStarted) {
                    // Exception before run() — release so we don't wedge the queue.
                    // If runWithOwnedQueue started and threw, its own finally ran
                    // processQueueDrain which will observe pending and release
                    // ownership correctly on the empty path.
                    ConversationQueue.releaseOwnership(conversationId);
                }
            }
        });
    }
}
