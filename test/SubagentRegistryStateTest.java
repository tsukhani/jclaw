import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SubagentRegistry;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * In-memory state-transition coverage for {@link SubagentRegistry} (JCLAW-707).
 * The existing {@code SubagentRegistryTest} covers {@code kill}'s DB arms; this
 * suite targets the registration / cancellation / activity / yield-watchdog
 * decision branches, all of which are pure in-memory operations.
 *
 * <p>To stay safe under the concurrent unit+functional test lanes, it never
 * calls {@code clear()} and never asserts on the global active-set size — it
 * uses distinctive run ids, asserts membership of its own ids only, and
 * unregisters every id it created in teardown.
 */
class SubagentRegistryStateTest extends UnitTest {

    private final Set<Long> mine = new HashSet<>();

    private Long reg(long id, Future<?> future) {
        mine.add(id);
        SubagentRegistry.register(id, future);
        return id;
    }

    @AfterEach
    void teardown() {
        for (var id : mine) SubagentRegistry.unregister(id);
        mine.clear();
    }

    // ─── register ────────────────────────────────────────────────────────────

    @Test
    void registerIgnoresNullRunIdOrFuture() {
        SubagentRegistry.register(null, new CompletableFuture<>());
        var id = 707_1001L;
        mine.add(id);
        SubagentRegistry.register(id, null);
        assertFalse(SubagentRegistry.isActive(id), "null future → nothing registered");
    }

    @Test
    void registerMarksRunActiveAndNotCancelled() {
        var id = reg(707_1002L, new CompletableFuture<>());
        assertTrue(SubagentRegistry.isActive(id));
        assertFalse(SubagentRegistry.isCancelled(id), "a fresh entry is not cancelled");
        assertTrue(SubagentRegistry.activeRunIds().contains(id));

        SubagentRegistry.unregister(id);
        assertFalse(SubagentRegistry.isActive(id), "unregister removes the entry");
    }

    @Test
    void unregisterNullIsNoOpAndLeavesOthersIntact() {
        var id = reg(707_1003L, new CompletableFuture<>());
        SubagentRegistry.unregister(null);
        assertTrue(SubagentRegistry.isActive(id), "null unregister must not touch other entries");
    }

    // ─── touch / nanosSinceActivity ──────────────────────────────────────────

    @Test
    void nanosSinceActivityIsNegativeOneForNullAndUnregistered() {
        SubagentRegistry.touch(null); // no-op, must not throw
        assertEquals(-1L, SubagentRegistry.nanosSinceActivity(null));
        assertEquals(-1L, SubagentRegistry.nanosSinceActivity(707_9999L),
                "unregistered run → -1 (caller falls back to wall clock)");
    }

    @Test
    void nanosSinceActivityIsNonNegativeForRegisteredRun() {
        var id = reg(707_1004L, new CompletableFuture<>());
        assertTrue(SubagentRegistry.nanosSinceActivity(id) >= 0, "registered run reports elapsed nanos");
        SubagentRegistry.touch(id); // resets the clock; still a valid non-negative reading
        assertTrue(SubagentRegistry.nanosSinceActivity(id) >= 0);
    }

    // ─── activity of a run being waited on (JCLAW-1564) ──────────────────────

    private static final long SETTLE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    @Test
    void activityCarriesUpEveryRunWaitingOnTheOneBelow() throws Exception {
        var top = reg(1564_0001L, new CompletableFuture<>());
        var middle = reg(1564_0002L, new CompletableFuture<>());
        var bottom = reg(1564_0003L, new CompletableFuture<>());
        SubagentRegistry.callAsRun(top, () -> {
            try (var _ = SubagentRegistry.waitingOn(List.of(middle))) {
                return SubagentRegistry.callAsRun(middle, () -> {
                    try (var _ = SubagentRegistry.waitingOn(List.of(bottom))) {
                        Thread.sleep(60);
                        SubagentRegistry.touch(bottom);
                        assertTrue(SubagentRegistry.nanosSinceActivity(middle) < SETTLE_NANOS, "middle refreshed");
                        assertTrue(SubagentRegistry.nanosSinceActivity(top) < SETTLE_NANOS, "top refreshed");
                        return null;
                    }
                });
            }
        });
    }

    @Test
    void activityStopsCountingForARunOnceItStopsWaiting() throws Exception {
        var waiter = reg(1564_0004L, new CompletableFuture<>());
        var child = reg(1564_0005L, new CompletableFuture<>());
        SubagentRegistry.callAsRun(waiter, () -> {
            try (var _ = SubagentRegistry.waitingOn(List.of(child))) {
                SubagentRegistry.touch(child);
            }
            return null;
        });
        Thread.sleep(60);
        SubagentRegistry.touch(child);
        assertTrue(SubagentRegistry.nanosSinceActivity(waiter) >= SETTLE_NANOS, "the wait has ended");
    }

    @Test
    void runsThatWaitOnEachOtherStillTouchInFiniteTime() throws Exception {
        // subagent_yield takes any run id, so a child can wait on the run that waits on it.
        var a = reg(1564_0006L, new CompletableFuture<>());
        var b = reg(1564_0007L, new CompletableFuture<>());
        SubagentRegistry.callAsRun(a, () -> {
            try (var _ = SubagentRegistry.waitingOn(List.of(b))) {
                return SubagentRegistry.callAsRun(b, () -> {
                    try (var _ = SubagentRegistry.waitingOn(List.of(a))) {
                        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> SubagentRegistry.touch(a));
                        return null;
                    }
                });
            }
        });
    }

    // ─── requestStop / cancel flag ───────────────────────────────────────────

    @Test
    void requestStopNullIsNoOp() {
        SubagentRegistry.requestStop(null); // must not throw
        assertFalse(SubagentRegistry.isCancelled(707_9998L));
    }

    @Test
    void requestStopFlipsCancelFlag() {
        var id = reg(707_1005L, new CompletableFuture<>());
        assertFalse(SubagentRegistry.isCancelled(id));
        SubagentRegistry.requestStop(id);
        assertTrue(SubagentRegistry.isCancelled(id), "requestStop sets the cooperative-cancel flag");
    }

    @Test
    void isCancelledFalseForNullUnregisteredAndUnflipped() {
        assertFalse(SubagentRegistry.isCancelled(null));
        assertFalse(SubagentRegistry.isCancelled(707_9997L));
        var id = reg(707_1006L, new CompletableFuture<>());
        assertFalse(SubagentRegistry.isCancelled(id), "registered but not flipped → false");
    }

    @Test
    void cancelForTestReturnsFalseForNullAndUnregistered() {
        assertFalse(SubagentRegistry.cancelForTest(null));
        assertFalse(SubagentRegistry.cancelForTest(707_9996L));
    }

    @Test
    void cancelForTestFlipsRegisteredEntry() {
        var id = reg(707_1007L, new CompletableFuture<>());
        assertTrue(SubagentRegistry.cancelForTest(id));
        assertTrue(SubagentRegistry.isCancelled(id));
    }

    @Test
    void isActiveFalseForNull() {
        assertFalse(SubagentRegistry.isActive(null));
    }

    // ─── scheduleYieldTimeout ────────────────────────────────────────────────

    @Test
    void scheduleYieldTimeoutRejectsNullOrNonPositiveTimeout() {
        var id = reg(707_1008L, new CompletableFuture<>());
        assertFalse(SubagentRegistry.scheduleYieldTimeout(null, 5), "null run id → not scheduled");
        assertFalse(SubagentRegistry.scheduleYieldTimeout(id, 0), "zero timeout → not scheduled");
        assertFalse(SubagentRegistry.scheduleYieldTimeout(id, -1), "negative timeout → not scheduled");
    }

    @Test
    void scheduleYieldTimeoutRejectsUnregisteredRun() {
        assertFalse(SubagentRegistry.scheduleYieldTimeout(707_9995L, 5));
    }

    @Test
    void scheduleYieldTimeoutRejectsDoneOrNonCompletableFuture() {
        var doneId = reg(707_1009L, CompletableFuture.completedFuture(null));
        assertFalse(SubagentRegistry.scheduleYieldTimeout(doneId, 30),
                "an already-done future has nothing to interrupt");

        var plainId = reg(707_1010L, new FutureTask<>(() -> null));
        assertFalse(SubagentRegistry.scheduleYieldTimeout(plainId, 30),
                "a non-CompletableFuture cannot be completed exceptionally");
    }

    @Test
    void scheduleYieldTimeoutSchedulesForLiveCompletableFuture() {
        var cf = new CompletableFuture<>();
        var id = reg(707_1011L, cf);
        assertTrue(SubagentRegistry.scheduleYieldTimeout(id, 60),
                "a live CompletableFuture + positive timeout arms the watchdog");
        // Complete it now so the watchdog observes isDone() and exits without
        // firing a synthetic timeout during the suite.
        cf.complete(null);
    }

    @Test
    void unregisterDisarmsAPendingYieldTimeout() throws Exception {
        var cf = new CompletableFuture<>();
        var id = reg(707_1013L, cf);
        assertTrue(SubagentRegistry.scheduleYieldTimeout(id, 1));

        SubagentRegistry.unregister(id);
        Thread.sleep(1_500);  // well past the 1 s budget

        assertFalse(cf.isDone(),
                "a subagent that returned before its yield budget must leave no armed watchdog");
    }

    @Test
    void armedYieldTimeoutCompletesTheFutureExceptionally() {
        // Known-zero guard for the test above: proves the watchdog still fires
        // when the run is NOT unregistered, so a no-op scheduler couldn't pass both.
        var cf = new CompletableFuture<>();
        var id = reg(707_1014L, cf);
        assertTrue(SubagentRegistry.scheduleYieldTimeout(id, 1));

        var thrown = assertThrows(ExecutionException.class, () -> cf.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, thrown.getCause(),
                "the watchdog completes the future with a synthetic TimeoutException");
    }

    // ─── DB-free null guards on the scoping helpers ──────────────────────────

    @Test
    void rowExistsNullReturnsFalse() {
        assertFalse(SubagentRegistry.rowExists(null), "null run id short-circuits before any DB read");
    }

    @Test
    void isOwnedByNullArgumentsReturnFalse() {
        assertFalse(SubagentRegistry.isOwnedBy(null, null), "null run id → false pre-DB");
        assertFalse(SubagentRegistry.isOwnedBy(707_1012L, null), "null parent → false pre-DB");
    }
}
