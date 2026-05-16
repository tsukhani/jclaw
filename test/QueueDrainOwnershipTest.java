import models.Agent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConversationQueue;
import services.ConversationQueue.QueuedMessage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization tests for the JCLAW-117 queue-ownership transfer
 * contract that {@code AgentRunner.processQueueDrain} relies on, ahead
 * of the JCLAW-299 refactor that extracts {@code QueueDrainOrchestrator}.
 *
 * <p>The contract is subtle: {@code drain()} sometimes releases ownership
 * (when pending is empty) and sometimes transfers it to the caller (when
 * pending is non-empty). The caller — currently {@code processQueueDrain}'s
 * VT body — is then responsible for either re-draining (which auto-releases
 * on empty) or calling {@code releaseOwnership} explicitly on exception
 * paths. Getting the release wrong wedges the queue: subsequent inbound
 * messages on that conversation queue forever instead of acquiring.
 *
 * <p>What this test pins:
 * <ul>
 *   <li>drain with non-empty pending transfers ownership (processing
 *       stays {@code true}) — the AgentRunner drain VT must therefore
 *       release explicitly on early-exit.</li>
 *   <li>{@code releaseOwnership} clears the lock — the fail-safe exit
 *       path works.</li>
 *   <li>{@code releaseOwnership} on never-acquired or already-released
 *       queues is a safe no-op — defensive paths can call it without
 *       prior-state knowledge.</li>
 *   <li>The full cycle works: tryAcquire → ownership → releaseOwnership
 *       → next tryAcquire succeeds.</li>
 * </ul>
 *
 * <p>The existing {@code ConversationQueueTest} covers the per-message
 * acquire / queue / drain mechanics but doesn't isolate the
 * ownership-transfer invariant the JCLAW-299 refactor must preserve.
 * Any extracted {@code QueueDrainOrchestrator} that wraps this contract
 * has to keep these guarantees.
 *
 * <p>Conversation IDs in this file are in the 2100-2199 range to avoid
 * collision with the 1000-1099 range used by {@code ConversationQueueTest}.
 */
class QueueDrainOwnershipTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        agent = new Agent();
        agent.name = "queue-drain-test-agent";
    }

    @Test
    void drainTransfersOwnershipWhenPendingMessagesExist() {
        var convId = 2100L;
        var m1 = new QueuedMessage("first", "web", "u1", agent);
        var m2 = new QueuedMessage("second", "web", "u1", agent);

        assertTrue(ConversationQueue.tryAcquire(convId, m1),
                "first message must acquire immediately");
        // Second message queues; m2 is in pending now.
        assertFalse(ConversationQueue.tryAcquire(convId, m2),
                "second message must queue while first is being processed");
        assertTrue(ConversationQueue.isBusy(convId),
                "queue must be busy while processing m1");

        // Drain returns the pending messages — ownership transfers to caller.
        var drained = ConversationQueue.drain(convId);
        assertEquals(1, drained.size(),
                "drain must return the pending message");
        assertTrue(ConversationQueue.isBusy(convId),
                "drain on non-empty pending must KEEP processing=true "
                + "(ownership transferred to caller; caller is responsible "
                + "for the final release)");

        // Cleanup so we don't wedge the static map between tests.
        ConversationQueue.releaseOwnership(convId);
        assertFalse(ConversationQueue.isBusy(convId), "releaseOwnership clears the lock");
    }

    @Test
    void releaseOwnershipFromOwnedStateClearsLock() {
        var convId = 2101L;
        var m = new QueuedMessage("only", "web", "u1", agent);
        assertTrue(ConversationQueue.tryAcquire(convId, m));
        assertTrue(ConversationQueue.isBusy(convId), "pre-condition: queue owned");

        ConversationQueue.releaseOwnership(convId);
        assertFalse(ConversationQueue.isBusy(convId),
                "releaseOwnership must flip processing to false");
    }

    @Test
    void releaseOwnershipOnNeverAcquiredQueueIsSafe() {
        // Defensive: error-recovery paths may call releaseOwnership without
        // checking prior state. A conversation id that was never used must
        // not throw, even if state map has no entry.
        assertDoesNotThrow(() -> ConversationQueue.releaseOwnership(2102L),
                "releaseOwnership on an unknown conversation id must be a safe no-op");
        assertFalse(ConversationQueue.isBusy(2102L), "still not busy");
    }

    @Test
    void releaseOwnershipIsIdempotent() {
        var convId = 2103L;
        var m = new QueuedMessage("once", "web", "u1", agent);
        assertTrue(ConversationQueue.tryAcquire(convId, m));

        ConversationQueue.releaseOwnership(convId);
        // Calling release again must not throw or wedge the state — the
        // processQueueDrain finally block can be reached more than once
        // under odd error paths, and idempotence is the safe contract.
        assertDoesNotThrow(() -> ConversationQueue.releaseOwnership(convId),
                "second releaseOwnership must be a safe no-op (idempotent)");
        assertFalse(ConversationQueue.isBusy(convId),
                "queue still released after the second call");
    }

    @Test
    void tryAcquireSucceedsAfterReleaseOwnership() {
        var convId = 2104L;
        var first = new QueuedMessage("first", "web", "u1", agent);
        var afterRelease = new QueuedMessage("after-release", "web", "u2", agent);

        assertTrue(ConversationQueue.tryAcquire(convId, first));
        ConversationQueue.releaseOwnership(convId);

        // Next inbound message must acquire immediately, NOT queue.
        assertTrue(ConversationQueue.tryAcquire(convId, afterRelease),
                "after explicit release, the next tryAcquire must succeed "
                + "(this is the JCLAW-117 fail-safe — the drain VT's "
                + "exception-path release unblocks subsequent inbound traffic)");
        assertTrue(ConversationQueue.isBusy(convId),
                "the new acquirer now owns the queue");

        ConversationQueue.releaseOwnership(convId);
    }

    @Test
    void drainOnEmptyPendingReleasesOwnership() {
        var convId = 2105L;
        var m = new QueuedMessage("only", "web", "u1", agent);
        assertTrue(ConversationQueue.tryAcquire(convId, m));
        assertTrue(ConversationQueue.isBusy(convId));

        // No further messages queued. drain on empty pending must release
        // ownership and return an empty list.
        var drained = ConversationQueue.drain(convId);
        assertTrue(drained.isEmpty(),
                "drain on empty pending returns empty list");
        assertFalse(ConversationQueue.isBusy(convId),
                "drain on empty pending must release ownership "
                + "(this is the re-drain auto-release the AgentRunner's "
                + "finally chain relies on for normal completion)");
    }
}
