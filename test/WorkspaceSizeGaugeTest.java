import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.WorkspaceSizeGauge;
import services.WorkspaceSizeGauge.Reading;
import utils.AppClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** JF-35: the dashboard's workspace size is served from the last count, recounted off the request path. */
class WorkspaceSizeGaugeTest extends UnitTest {

    private static final Duration MAX_AGE = Duration.ofSeconds(60);
    private static final Instant T0 = Instant.parse("2026-10-27T00:00:00Z");

    /** The workspace walk under the test's control: each call reports the size at its start, and a held call waits for release. */
    private static final class Walk implements LongSupplier {
        record Call(long bytes, Thread thread, CompletableFuture<Void> release) {}

        final AtomicLong size;
        final AtomicInteger peakConcurrent = new AtomicInteger();
        private final AtomicInteger running = new AtomicInteger();
        private final List<Call> calls = new ArrayList<>();
        private volatile boolean held;

        Walk(long bytes) {
            size = new AtomicLong(bytes);
        }

        @Override
        public long getAsLong() {
            peakConcurrent.accumulateAndGet(running.incrementAndGet(), Math::max);
            try {
                var call = new Call(size.get(), Thread.currentThread(), new CompletableFuture<>());
                synchronized (this) {
                    calls.add(call);
                    notifyAll();
                }
                if (held) call.release().join();
                return call.bytes();
            } finally {
                running.decrementAndGet();
            }
        }

        void hold() {
            held = true;
        }

        synchronized int started() {
            return calls.size();
        }

        /** The {@code n}th walk, counting from 1, once it has started. */
        synchronized Call call(int n) throws InterruptedException {
            var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (calls.size() < n) {
                var left = deadline - System.nanoTime();
                assertTrue(left > 0, "walk " + n + " never started");
                TimeUnit.NANOSECONDS.timedWait(this, left);
            }
            return calls.get(n - 1);
        }

        /** Releases {@code call} and waits until the gauge has taken its result. */
        void finish(Call call) throws InterruptedException {
            call.release().complete(null);
            assertTrue(call.thread().join(Duration.ofSeconds(5)), "the walk did not finish");
        }
    }

    /** A read at {@code at}, on another thread with a deadline, so a read that waits on a held walk fails rather than hangs. */
    private static Reading read(WorkspaceSizeGauge gauge, Instant at) throws Exception {
        return CompletableFuture.supplyAsync(() -> AppClock.callWith(Clock.fixed(at, ZoneOffset.UTC), gauge::read))
                .get(5, TimeUnit.SECONDS);
    }

    private static void invalidate(WorkspaceSizeGauge gauge, Instant at) {
        AppClock.runWith(Clock.fixed(at, ZoneOffset.UTC), gauge::invalidate);
    }

    @Test
    void theFirstReadWaitsForTheFirstCount() throws Exception {
        var walk = new Walk(100);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);

        assertEquals(new Reading(100, false), read(gauge, T0));
        assertEquals(1, walk.started());
    }

    @Test
    void aCountYoungerThanTheMaxAgeIsServedWithoutAnotherWalk() throws Exception {
        var walk = new Walk(100);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);
        assertEquals(new Reading(100, false), read(gauge, T0));
        walk.size.set(250);

        assertEquals(new Reading(100, false), read(gauge, T0.plus(MAX_AGE).minusMillis(1)));
        assertEquals(1, walk.started());
    }

    @Test
    void aStaleCountIsServedAtOnceWhileTheRecountRunsBehindIt() throws Exception {
        var walk = new Walk(100);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);
        assertEquals(new Reading(100, false), read(gauge, T0));
        walk.hold();
        walk.size.set(250);

        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE)),
                "the stale count, served while the recount is still walking, says a newer one is coming");
        walk.finish(walk.call(2));

        assertEquals(new Reading(250, false), read(gauge, T0.plus(MAX_AGE).plusSeconds(1)));
        assertEquals(2, walk.started());
    }

    @Test
    void readsDuringARecountShareIt() throws Exception {
        var walk = new Walk(100);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);
        assertEquals(new Reading(100, false), read(gauge, T0));
        walk.hold();

        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE)));
        var recount = walk.call(2);
        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE).plusSeconds(5)));
        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE).plusSeconds(10)));

        assertEquals(2, walk.started(), "three stale reads, one recount");
        walk.finish(recount);
    }

    @Test
    void aFailedRecountKeepsTheLastCountAndTheNextStaleReadTriesAgain() throws Exception {
        var walk = new Walk(100);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);
        assertEquals(new Reading(100, false), read(gauge, T0));
        walk.size.set(-1);

        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE)));
        walk.finish(walk.call(2));
        walk.size.set(300);
        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE).plusSeconds(1)),
                "a failed walk does not replace the last count, and the next stale read walks again");
        walk.finish(walk.call(3));

        assertEquals(new Reading(300, false), read(gauge, T0.plus(MAX_AGE).plusSeconds(2)));
        assertEquals(3, walk.started());
    }

    @Test
    void withNoCountYetAFailedWalkReadsAsMinusOneAndTheNextReadWalksAgain() throws Exception {
        var walk = new Walk(-1);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);

        assertEquals(new Reading(-1, false), read(gauge, T0));
        walk.size.set(80);
        assertEquals(new Reading(80, false), read(gauge, T0.plusSeconds(1)));
        assertEquals(2, walk.started());
    }

    @Test
    void aChangeWithNoWalkRunningRecountsAtOnce() throws Exception {
        var walk = new Walk(100);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);
        assertEquals(new Reading(100, false), read(gauge, T0));
        walk.hold();
        walk.size.set(80);

        invalidate(gauge, T0.plusSeconds(1));
        var recount = walk.call(2);
        assertEquals(new Reading(100, true), read(gauge, T0.plusSeconds(2)), "the last count is served until the recount lands");
        walk.finish(recount);

        assertEquals(new Reading(80, false), read(gauge, T0.plusSeconds(3)));
        assertEquals(2, walk.started());
    }

    @Test
    void changesDuringAWalkQueueOneRecountAfterItAndNeverASecondWalkAtOnce() throws Exception {
        var walk = new Walk(100);
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);
        assertEquals(new Reading(100, false), read(gauge, T0));
        walk.hold();
        walk.size.set(150);
        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE)));
        var running = walk.call(2);

        walk.size.set(120);
        for (int i = 1; i <= 10; i++) invalidate(gauge, T0.plus(MAX_AGE).plusSeconds(i));
        assertEquals(2, walk.started(), "ten deletes during a walk start nothing until it ends");
        assertEquals(new Reading(100, true), read(gauge, T0.plus(MAX_AGE).plusSeconds(11)));

        walk.finish(running);
        var followUp = walk.call(3);
        assertEquals(new Reading(150, true), read(gauge, T0.plus(MAX_AGE).plusSeconds(12)),
                "the walk that predates the deletes lands, and the one queued behind it is running");
        walk.finish(followUp);

        assertEquals(new Reading(120, false), read(gauge, T0.plus(MAX_AGE).plusSeconds(13)));
        assertEquals(3, walk.started(), "one recount for ten deletes");
        assertEquals(1, walk.peakConcurrent.get(), "never two walks at once");
    }

    @Test
    void refreshStartsTheFirstCountWithoutWaitingForIt() throws Exception {
        var walk = new Walk(100);
        walk.hold();
        var gauge = new WorkspaceSizeGauge(walk, MAX_AGE);

        CompletableFuture.runAsync(() -> AppClock.runWith(Clock.fixed(T0, ZoneOffset.UTC), gauge::refresh))
                .get(5, TimeUnit.SECONDS);
        walk.finish(walk.call(1));

        assertEquals(new Reading(100, false), read(gauge, T0.plusSeconds(1)));
        assertEquals(1, walk.started());
    }
}
