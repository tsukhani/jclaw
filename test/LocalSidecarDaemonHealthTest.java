import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.LocalSidecarDaemon;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** JCLAW-637: the /health identity-parsing path. JCLAW-830: the single-flight /
 *  safe-publication concurrency contract of the daemon lifecycle. */
class LocalSidecarDaemonHealthTest extends UnitTest {

    /** A side-effect-free config: none of the spawn/health paths that would touch
     *  Play or a real process are exercised — only {@code singleFlight}/{@code stop}/
     *  {@code hasProcess}, which are pure lock + field bookkeeping. */
    private static LocalSidecarDaemon.Config testConfig() {
        return new LocalSidecarDaemon.Config(
                "sidecar/none", "data/none", "test.sidecar.jclaw830", 9999, 5,
                "test", "test-sidecar", "test sidecar", "hint", RuntimeException::new);
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void healthModel_parsesTheServedModel() {
        assertEquals("pyannote/speaker-diarization-community-1",
                LocalSidecarDaemon.healthModel(
                        "{\"status\":\"ok\",\"device\":\"mps\","
                        + "\"model\":\"pyannote/speaker-diarization-community-1\",\"loaded\":true}"));
    }

    @Test
    void healthModel_toleratesMissingFieldAndGarbage() {
        assertNull(LocalSidecarDaemon.healthModel("{\"status\":\"ok\"}"),
                "older sidecars without the field must not be treated as mismatched");
        assertNull(LocalSidecarDaemon.healthModel("not json"),
                "garbage must parse to null, never throw");
    }

    /** JCLAW-830: two concurrent starters must produce exactly one spawn — the
     *  loser waits on the single-flight lock, then its own health re-check
     *  short-circuits it to a no-op (a double-spawn would poison the cooldown). */
    @Test
    void singleFlight_runsExactlyOneSpawnUnderConcurrentStarters() throws Exception {
        var daemon = new LocalSidecarDaemon(testConfig());
        var healthy = new AtomicBoolean(false);
        var spawns = new AtomicInteger(0);
        var ready = new CountDownLatch(2);
        var go = new CountDownLatch(1);

        Runnable starter = () -> {
            ready.countDown();
            awaitUninterruptibly(go);
            daemon.singleFlight(() -> {
                if (healthy.get()) return null;       // re-check: in-flight spawn already succeeded
                spawns.incrementAndGet();             // the single real spawn
                try {
                    Thread.sleep(50);                 // widen the overlap window
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                healthy.set(true);
                return null;
            });
        };

        var t1 = Thread.ofPlatform().start(starter);
        var t2 = Thread.ofPlatform().start(starter);
        assertTrue(ready.await(2, TimeUnit.SECONDS), "both starters reached the barrier");
        go.countDown();
        t1.join();
        t2.join();

        assertEquals(1, spawns.get(),
                "single-flight: the second concurrent starter must wait then no-op, not double-spawn");
    }

    /** JCLAW-830: the single-flight lock serializes spawn bodies — no two run at once. */
    @Test
    void singleFlight_actionsNeverOverlap() throws Exception {
        var daemon = new LocalSidecarDaemon(testConfig());
        var inside = new AtomicInteger(0);
        var maxConcurrent = new AtomicInteger(0);
        int n = 8;
        var ready = new CountDownLatch(n);
        var go = new CountDownLatch(1);
        var threads = new ArrayList<Thread>();

        for (int i = 0; i < n; i++) {
            threads.add(Thread.ofPlatform().start(() -> {
                ready.countDown();
                awaitUninterruptibly(go);
                daemon.singleFlight(() -> {
                    int c = inside.incrementAndGet();
                    maxConcurrent.accumulateAndGet(c, Math::max);
                    try {
                        Thread.sleep(5);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    inside.decrementAndGet();
                    return null;
                });
            }));
        }
        assertTrue(ready.await(2, TimeUnit.SECONDS), "all workers reached the barrier");
        go.countDown();
        for (var t : threads) t.join();

        assertEquals(1, maxConcurrent.get(),
                "single-flight must serialize: never two spawn bodies at once");
    }

    /** JCLAW-830: the core fix — {@code stop()} must not block behind an in-flight
     *  spawn holding the single-flight lock (it uses a separate short lock). */
    @Test
    void stop_doesNotStallBehindInFlightSpawn() throws Exception {
        var daemon = new LocalSidecarDaemon(testConfig());
        var inSpawn = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        var spawner = Thread.ofPlatform().start(() -> daemon.singleFlight(() -> {
            inSpawn.countDown();
            awaitUninterruptibly(release); // hold the single-flight lock (simulate a long health-await)
            return null;
        }));
        assertTrue(inSpawn.await(2, TimeUnit.SECONDS), "spawn entered the single-flight section");

        var stopper = Thread.ofPlatform().start(daemon::stop);
        stopper.join(1000); // stop() must return promptly, NOT wait for the spawner
        boolean stopReturned = !stopper.isAlive();

        release.countDown();
        spawner.join();
        stopper.join();

        assertTrue(stopReturned,
                "stop() must not stall behind an in-flight spawn holding the single-flight lock");
    }

    /** JCLAW-830: stop() on a daemon that never spawned is a safe, idempotent no-op. */
    @Test
    void stop_onIdleDaemonIsSafeNoop() {
        var daemon = new LocalSidecarDaemon(testConfig());
        daemon.stop();
        daemon.stop();
        assertFalse(daemon.hasProcess(), "no process handle after stop on an idle daemon");
    }
}
