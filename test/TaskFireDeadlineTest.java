import agents.AgentRunner;
import agents.RunCancelledException;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TaskFireDeadline;
import services.TaskRunRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * JCLAW-803: unit contract for {@link TaskFireDeadline}, the per-fire
 * execution-deadline watchdog that bounds how long a Task fire may run.
 *
 * <p>Like {@code TaskRunRegistryTest}, this never calls the process-global
 * registry's {@code clear()} — it uses high sentinel run ids it owns end-to-end
 * (the suite runs tests concurrently) and cleans up in {@code finally}.
 *
 * <p>The firing path is driven through the package-private
 * {@code arm(Long, long, TimeUnit)} seam via reflection (the repo idiom for
 * exercising package-private members from the default-package test tree — see
 * {@code TaskExecutionHandlerTest}) so the deadline lands in ~100ms rather than
 * the 10-minute production default, without mutating the shared
 * {@code Play.configuration} a concurrent fire's watchdog would also read.
 */
class TaskFireDeadlineTest extends UnitTest {

    // Distinct sentinel run ids per test so concurrent methods don't interfere.
    private static final Long RUN_FIRE = 990_803_101L;
    private static final Long RUN_DISARM = 990_803_102L;
    private static final Long RUN_DISABLED = 990_803_103L;
    private static final Long RUN_UNKNOWN = 990_803_104L;

    @Test
    void deadlineFlipsCancelFlagSoCheckpointThrows() throws Exception {
        TaskRunRegistry.register(RUN_FIRE);
        ScheduledFuture<?> future = null;
        try {
            // Registered-but-untimed → the loop proceeds.
            assertFalse(TaskRunRegistry.isCancelled(RUN_FIRE));
            assertFalse(TaskFireDeadline.wasTimedOut(RUN_FIRE));
            assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(RUN_FIRE));

            future = armMillis(RUN_FIRE, 100);
            assertNotNull(future, "a positive deadline arms a timer");

            assertTrue(awaitCancelled(RUN_FIRE, 2_000),
                    "the watchdog must flip the fire's cancel flag once the deadline elapses");
            assertTrue(TaskFireDeadline.wasTimedOut(RUN_FIRE),
                    "a deadline-driven cancel is tagged as a timeout (vs an operator cancel)");

            // The whole cooperative-cancel chain: the tool-loop checkpoint now trips.
            var ex = assertThrows(RunCancelledException.class,
                    () -> AgentRunner.checkTaskRunCancel(RUN_FIRE),
                    "after the deadline the loop checkpoint must throw");
            assertEquals(RUN_FIRE, ex.runId());
        } finally {
            TaskFireDeadline.disarm(future, RUN_FIRE);
            TaskRunRegistry.unregister(RUN_FIRE);
        }
        // disarm cleared the timeout tag.
        assertFalse(TaskFireDeadline.wasTimedOut(RUN_FIRE), "disarm clears the timeout tag");
    }

    @Test
    void disarmBeforeDeadlineLeavesRunUncancelled() throws Exception {
        TaskRunRegistry.register(RUN_DISARM);
        try {
            var future = armMillis(RUN_DISARM, 5_000);
            assertNotNull(future);
            // A fire that finishes on its own disarms the timer well before it fires.
            TaskFireDeadline.disarm(future, RUN_DISARM);

            // Give the (cancelled) timer more than a poll interval to prove it never runs.
            Thread.sleep(300);
            assertFalse(TaskRunRegistry.isCancelled(RUN_DISARM),
                    "a disarmed deadline must not cancel the run");
            assertFalse(TaskFireDeadline.wasTimedOut(RUN_DISARM));
        } finally {
            TaskRunRegistry.unregister(RUN_DISARM);
        }
    }

    @Test
    void nonPositiveDurationDisablesTheWatchdog() throws Exception {
        // <= 0 configured duration → arm is a no-op (null future), fire runs unbounded.
        assertNull(armMillis(RUN_DISABLED, 0), "zero duration disables the watchdog");
        assertNull(armMillis(RUN_DISABLED, -5), "negative duration disables the watchdog");
        assertFalse(TaskFireDeadline.wasTimedOut(RUN_DISABLED));
    }

    @Test
    void publicArmUsesConfigDefaultAndIsSafelyDisarmable() {
        // The public arm() reads config; with nothing set it uses the 600s
        // default, so it returns a live (non-null) future we can cancel cleanly.
        TaskRunRegistry.register(RUN_UNKNOWN);
        try {
            var future = TaskFireDeadline.arm(RUN_UNKNOWN);
            assertNotNull(future, "watchdog is enabled by default (600s)");
            assertDoesNotThrow(() -> TaskFireDeadline.disarm(future, RUN_UNKNOWN));
            assertFalse(TaskRunRegistry.isCancelled(RUN_UNKNOWN),
                    "the 600s default must not have fired in-test");
        } finally {
            TaskRunRegistry.unregister(RUN_UNKNOWN);
        }
    }

    @Test
    void nullArgsAndUnknownIdsAreNoOps() {
        assertNull(TaskFireDeadline.arm(null), "null run id → no timer");
        assertFalse(TaskFireDeadline.wasTimedOut(null));
        assertFalse(TaskFireDeadline.wasTimedOut(770_000_000L), "never-armed id is not timed out");
        assertDoesNotThrow(() -> TaskFireDeadline.disarm(null, null));
    }

    @Test
    void defaultMaxDurationIsTenMinutes() throws Exception {
        Field f = TaskFireDeadline.class.getDeclaredField("DEFAULT_MAX_DURATION_SECONDS");
        f.setAccessible(true);
        assertEquals(600L, (long) f.get(null),
                "default per-fire bound is 10 minutes");
    }

    // === helpers ===

    /** Arm via the package-private explicit-delay seam (reflection), so the
     *  deadline can land in millis without touching the shared config. */
    private static ScheduledFuture<?> armMillis(Long runId, long millis) throws Exception {
        Method arm = TaskFireDeadline.class.getDeclaredMethod(
                "arm", Long.class, long.class, TimeUnit.class);
        arm.setAccessible(true);
        return (ScheduledFuture<?>) arm.invoke(null, runId, millis, TimeUnit.MILLISECONDS);
    }

    private static boolean awaitCancelled(Long runId, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (TaskRunRegistry.isCancelled(runId)) return true;
            Thread.sleep(20);
        }
        return TaskRunRegistry.isCancelled(runId);
    }
}
