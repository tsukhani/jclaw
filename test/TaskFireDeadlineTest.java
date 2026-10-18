import agents.AgentRunner;
import agents.RunCancelledException;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.TaskFireDeadline;
import services.TaskRunRegistry;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

/**
 * JCLAW-803 / JCLAW-1567: unit contract for {@link TaskFireDeadline}, which cuts a Task fire after
 * an idle limit without activity and at an absolute ceiling.
 *
 * <p>Like {@code TaskRunRegistryTest}, this never calls the process-global
 * registry's {@code clear()} — it uses high sentinel run ids it owns end-to-end
 * (the suite runs tests concurrently) and cleans up in {@code finally}.
 *
 * <p>The firing path is driven through the package-private
 * {@code arm(Long, Duration, Duration, Duration)} seam via reflection (the repo idiom for
 * exercising package-private members from the default-package test tree — see
 * {@code TaskExecutionHandlerTest}) so the limits land in milliseconds rather than the
 * production defaults, without mutating the shared config a concurrent fire's watchdog
 * would also read.
 */
class TaskFireDeadlineTest extends UnitTest {

    // Distinct sentinel run ids per test so concurrent methods don't interfere.
    private static final Long RUN_FIRE = 990_803_101L;
    private static final Long RUN_DISARM = 990_803_102L;
    private static final Long RUN_DISABLED = 990_803_103L;
    private static final Long RUN_UNKNOWN = 990_803_104L;
    private static final Long RUN_ACTIVE = 990_803_105L;
    private static final Long RUN_CEILING = 990_803_106L;

    private static final Duration EVERY = Duration.ofMillis(25);

    @Test
    void anIdleFireIsCancelledSoTheCheckpointThrows() throws Exception {
        TaskRunRegistry.register(RUN_FIRE);
        ScheduledFuture<?> future = null;
        try {
            // Registered-but-untimed → the loop proceeds.
            assertFalse(TaskRunRegistry.isCancelled(RUN_FIRE));
            assertFalse(TaskFireDeadline.wasTimedOut(RUN_FIRE));
            assertDoesNotThrow(() -> AgentRunner.checkTaskRunCancel(RUN_FIRE));

            future = arm(RUN_FIRE, Duration.ofMillis(100), Duration.ZERO);
            assertNotNull(future, "a positive idle limit arms a watchdog");

            assertTrue(awaitCancelled(RUN_FIRE, 2_000),
                    "the watchdog must flip the fire's cancel flag once it has been idle past the limit");
            assertTrue(TaskFireDeadline.wasTimedOut(RUN_FIRE),
                    "a deadline-driven cancel is tagged as a timeout (vs an operator cancel)");
            assertTrue(TaskFireDeadline.timeoutNote(RUN_FIRE).contains("no activity"),
                    TaskFireDeadline.timeoutNote(RUN_FIRE));

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
    void aFireThatKeepsRecordingActivityOutlivesItsIdleLimit() throws Exception {
        TaskRunRegistry.register(RUN_ACTIVE);
        ScheduledFuture<?> future = null;
        try {
            future = arm(RUN_ACTIVE, Duration.ofMillis(300), Duration.ZERO);
            // The loop records activity at each checkpoint: ~0.9 s of work, three idle limits long.
            for (int i = 0; i < 18; i++) {
                AgentRunner.checkTaskRunCancel(RUN_ACTIVE);
                Thread.sleep(50);
            }
            assertFalse(TaskRunRegistry.isCancelled(RUN_ACTIVE), "an active fire is not idle");

            assertTrue(awaitCancelled(RUN_ACTIVE, 2_000), "once it falls silent, the idle limit cuts it");
        } finally {
            TaskFireDeadline.disarm(future, RUN_ACTIVE);
            TaskRunRegistry.unregister(RUN_ACTIVE);
        }
    }

    @Test
    void theCeilingCutsEvenAnActiveFire() throws Exception {
        TaskRunRegistry.register(RUN_CEILING);
        ScheduledFuture<?> future = null;
        try {
            future = arm(RUN_CEILING, Duration.ofMillis(300), Duration.ofMillis(600));
            long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
            while (!TaskRunRegistry.isCancelled(RUN_CEILING) && System.nanoTime() < deadline) {
                TaskRunRegistry.touch(RUN_CEILING);
                Thread.sleep(20);
            }
            assertTrue(TaskRunRegistry.isCancelled(RUN_CEILING), "the ceiling cuts a fire however busy");
            assertTrue(TaskFireDeadline.timeoutNote(RUN_CEILING).contains("max run duration"),
                    TaskFireDeadline.timeoutNote(RUN_CEILING));
        } finally {
            TaskFireDeadline.disarm(future, RUN_CEILING);
            TaskRunRegistry.unregister(RUN_CEILING);
        }
    }

    @Test
    void disarmBeforeDeadlineLeavesRunUncancelled() throws Exception {
        TaskRunRegistry.register(RUN_DISARM);
        try {
            var future = arm(RUN_DISARM, Duration.ofMillis(200), Duration.ZERO);
            assertNotNull(future);
            // A fire that finishes on its own disarms the watchdog well before it fires.
            TaskFireDeadline.disarm(future, RUN_DISARM);

            Thread.sleep(400);
            assertFalse(TaskRunRegistry.isCancelled(RUN_DISARM),
                    "a disarmed deadline must not cancel the run");
            assertFalse(TaskFireDeadline.wasTimedOut(RUN_DISARM));
        } finally {
            TaskRunRegistry.unregister(RUN_DISARM);
        }
    }

    @Test
    void nonPositiveLimitsDisableTheWatchdog() throws Exception {
        assertNull(arm(RUN_DISABLED, Duration.ZERO, Duration.ZERO), "no idle limit and no ceiling: no watchdog");
        assertNull(arm(RUN_DISABLED, Duration.ofSeconds(-5), Duration.ofSeconds(-5)), "negative limits too");
        assertFalse(TaskFireDeadline.wasTimedOut(RUN_DISABLED));
    }

    @Test
    void publicArmUsesConfigDefaultAndIsSafelyDisarmable() {
        // The public arm() reads config; with nothing set it uses the defaults, so it returns a
        // live (non-null) future we can cancel cleanly.
        TaskRunRegistry.register(RUN_UNKNOWN);
        try {
            var future = TaskFireDeadline.arm(RUN_UNKNOWN);
            assertNotNull(future, "watchdog is enabled by default");
            assertDoesNotThrow(() -> TaskFireDeadline.disarm(future, RUN_UNKNOWN));
            assertFalse(TaskRunRegistry.isCancelled(RUN_UNKNOWN),
                    "the default limits must not have fired in-test");
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
    void theDefaultsCutAnIdleFireAtTenMinutesAndAnyFireAtHalfAnHour() throws Exception {
        assertEquals(600L, defaultSeconds("DEFAULT_IDLE_SECONDS"));
        assertEquals(1800L, defaultSeconds("DEFAULT_MAX_DURATION_SECONDS"));
    }

    // === helpers ===

    private static long defaultSeconds(String field) throws Exception {
        Field f = TaskFireDeadline.class.getDeclaredField(field);
        f.setAccessible(true);
        return (long) f.get(null);
    }

    /** Arm via the package-private explicit-limit seam (reflection), so a limit can land in millis. */
    private static ScheduledFuture<?> arm(Long runId, Duration idle, Duration ceiling) throws Exception {
        Method arm = TaskFireDeadline.class.getDeclaredMethod(
                "arm", Long.class, Duration.class, Duration.class, Duration.class);
        arm.setAccessible(true);
        return (ScheduledFuture<?>) arm.invoke(null, runId, idle, ceiling, EVERY);
    }

    private static boolean awaitCancelled(Long runId, long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + timeoutMs * 1_000_000;
        while (System.nanoTime() < deadline) {
            if (TaskRunRegistry.isCancelled(runId)) return true;
            Thread.sleep(20);
        }
        return TaskRunRegistry.isCancelled(runId);
    }
}
