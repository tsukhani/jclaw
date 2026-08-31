import agents.AgentRunner;
import agents.RunCancelledException;
import jobs.DbSchedulerBootstrapJob;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TaskRunRegistry;

import java.util.Set;

/**
 * JCLAW-414: unit contract for {@link TaskRunRegistry} and the
 * {@link AgentRunner#checkTaskRunCancel} cooperative-cancel checkpoint.
 *
 * <p>The registry is a process-global static, and play1's TestEngine runs
 * unit + functional tests concurrently, so this test never calls
 * {@code clear()} — it uses high sentinel ids it owns end-to-end and asserts
 * with {@code contains}, never equality on the whole keyset (a concurrent
 * real task fire could legitimately have its own entry).
 */
class TaskRunRegistryTest extends UnitTest {

    // Sentinel ids unlikely to collide with a real TaskRun row id in a test run.
    private static final Long ID = 990_414L;
    private static final Long OTHER = 990_415L;

    // JCLAW-803: sentinel *task* ids for the dedup-claim contract, kept in a
    // distinct high range so they never collide with a concurrently-firing
    // real Task's id (the suite runs tests concurrently against one registry).
    private static final Long TASK = 990_803L;
    private static final Long OTHER_TASK = 990_804L;

    @Test
    void registerThenFlagThenUnregister() {
        try {
            TaskRunRegistry.register(ID);
            assertFalse(TaskRunRegistry.isCancelled(ID), "fresh registration is not cancelled");
            assertTrue(TaskRunRegistry.activeRunIds().contains(ID), "registered id is active");

            assertTrue(TaskRunRegistry.requestCancel(ID), "requestCancel flips a registered entry");
            assertTrue(TaskRunRegistry.isCancelled(ID), "flag is observable after requestCancel");
        } finally {
            TaskRunRegistry.unregister(ID);
        }
        assertFalse(TaskRunRegistry.isCancelled(ID), "unregistered id is not cancelled");
        assertFalse(TaskRunRegistry.activeRunIds().contains(ID), "unregistered id is gone");
    }

    @Test
    void requestCancelOnUnregisteredReturnsFalse() {
        // OTHER was never registered.
        assertFalse(TaskRunRegistry.requestCancel(OTHER), "no entry → nothing to flip");
        assertFalse(TaskRunRegistry.isCancelled(OTHER), "no entry → not cancelled");
    }

    @Test
    void nullArgsAreNoOps() {
        assertFalse(TaskRunRegistry.isCancelled(null));
        assertFalse(TaskRunRegistry.requestCancel(null));
        assertDoesNotThrow(() -> TaskRunRegistry.register(null));
        assertDoesNotThrow(() -> TaskRunRegistry.unregister(null));
    }

    @Test
    void checkpointThrowsOnlyWhenFlagged() {
        // Null + unregistered are no-ops (chat / subagent paths pass null).
        assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(null));
        assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(OTHER));

        try {
            TaskRunRegistry.register(ID);
            // Registered-but-unflagged → loop proceeds.
            assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(ID));

            TaskRunRegistry.requestCancel(ID);
            var ex = assertThrows(RunCancelledException.class,
                    () -> AgentRunner.checkTaskRunCancel(ID),
                    "a flagged run trips the checkpoint");
            assertEquals(ID, ex.runId(), "the exception carries the cancelled run id");
        } finally {
            TaskRunRegistry.unregister(ID);
        }
    }

    // === JCLAW-803: task-level dedup claim ===

    @Test
    void claimIsExclusiveUntilReleased() {
        try {
            assertFalse(TaskRunRegistry.isTaskActive(TASK), "unclaimed task is not active");
            assertTrue(TaskRunRegistry.tryClaimTask(TASK), "first claim on a free task succeeds");
            assertTrue(TaskRunRegistry.isTaskActive(TASK), "claimed task reads active");

            // A second claim while the first is live is refused — this is what
            // makes the handler drop a revived duplicate fire.
            assertFalse(TaskRunRegistry.tryClaimTask(TASK), "a second concurrent claim is refused");

            TaskRunRegistry.releaseTask(TASK);
            assertFalse(TaskRunRegistry.isTaskActive(TASK), "released task is no longer active");
            // Released → a fresh fire may claim it again.
            assertTrue(TaskRunRegistry.tryClaimTask(TASK), "claim succeeds again after release");
        } finally {
            TaskRunRegistry.releaseTask(TASK);
        }
    }

    @Test
    void claimsAreIndependentAcrossTaskIds() {
        try {
            assertTrue(TaskRunRegistry.tryClaimTask(TASK));
            // A different task id is unaffected by TASK's claim.
            assertTrue(TaskRunRegistry.tryClaimTask(OTHER_TASK),
                    "claiming one task must not block a different task");
            assertTrue(TaskRunRegistry.isTaskActive(OTHER_TASK));
        } finally {
            TaskRunRegistry.releaseTask(TASK);
            TaskRunRegistry.releaseTask(OTHER_TASK);
        }
    }

    @Test
    void nullTaskIdNeverBlocksAndReleaseIsSafe() {
        // An undecodable instance id yields a null task id; it must never block
        // (the handler has its own skip path), and release/isActive stay no-ops.
        assertTrue(TaskRunRegistry.tryClaimTask(null), "null task id never blocks a fire");
        assertFalse(TaskRunRegistry.isTaskActive(null));
        assertDoesNotThrow(() -> TaskRunRegistry.releaseTask(null));
    }

    // ---- JCLAW-1144: shutdown asks in-flight fires to stop before db-scheduler interrupts ----

    private static final Long SHUTDOWN_A = 991_144L;
    private static final Long SHUTDOWN_B = 991_145L;
    private static final Long NEVER_REGISTERED = 991_146L;

    /**
     * Uses the id-set overload deliberately: the no-arg form cancels everything registered
     * in this JVM, which under concurrent test classes would cancel another test's fire.
     */
    @Test
    void shutdownCancelFlagsEveryInFlightRunItIsGiven() {
        TaskRunRegistry.register(SHUTDOWN_A);
        TaskRunRegistry.register(SHUTDOWN_B);
        try {
            assertFalse(TaskRunRegistry.isCancelled(SHUTDOWN_A));
            assertFalse(TaskRunRegistry.isCancelled(SHUTDOWN_B));

            assertEquals(2, DbSchedulerBootstrapJob.cancelInFlightRuns(
                    Set.of(SHUTDOWN_A, SHUTDOWN_B)));

            assertTrue(TaskRunRegistry.isCancelled(SHUTDOWN_A), "run A must observe the cancel");
            assertTrue(TaskRunRegistry.isCancelled(SHUTDOWN_B), "run B must observe the cancel");
        } finally {
            TaskRunRegistry.unregister(SHUTDOWN_A);
            TaskRunRegistry.unregister(SHUTDOWN_B);
        }
    }

    @Test
    void shutdownCancelIgnoresRunsThatAlreadyFinished() {
        // A fire that terminated and unregistered has nothing to cancel; it must not be
        // counted, so the log line reports what was actually asked to stop.
        assertEquals(0, DbSchedulerBootstrapJob.cancelInFlightRuns(Set.of(NEVER_REGISTERED)));
    }

    @Test
    void shutdownCancelIsANoOpWhenNothingIsInFlight() {
        assertEquals(0, DbSchedulerBootstrapJob.cancelInFlightRuns(Set.of()));
    }
}
