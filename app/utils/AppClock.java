package utils;

import org.jspecify.annotations.NonNull;

import java.lang.ScopedValue.CallableOp;
import java.time.Clock;
import java.time.Instant;

/**
 * The application's single wall-clock reader (JCLAW-1150).
 *
 * <p>Production reads {@link Clock#systemUTC()}; a test binds a {@link Clock#fixed}
 * instance for the duration of a call via {@link #runWith} / {@link #callWith}. The
 * binding is a {@link ScopedValue} (final in JDK 25, JEP 506) rather than a static
 * setter on purpose: the play1 fork runs test classes concurrently, so a
 * process-global flip would leak a frozen clock into whatever else is running.
 *
 * <p><strong>Propagation boundary.</strong> A {@code ScopedValue} binding is visible
 * to the binding thread and inherited by {@code StructuredTaskScope} forks. It is
 * <em>not</em> inherited by threads handed to {@code Executors.newVirtualThreadPerTaskExecutor()},
 * {@code CompletableFuture.supplyAsync}, a raw {@code Thread.start()}, or db-scheduler's
 * worker pool — those read the unbound default, the system clock. Code under test that
 * crosses one of those boundaries must either read the clock before spawning or rebind
 * inside the task; a test that forgets sees real time and fails loudly rather than
 * silently reading a stale value.
 *
 * <p>Out of scope: {@code System.nanoTime()}, which measures elapsed intervals and has
 * no relation to wall-clock time. {@link utils.LatencyStats} and the throttle/rate-limit
 * call sites that read {@code System.currentTimeMillis()} as an interval baseline are
 * frozen in {@code archunit_store}, not migrated — see {@code WallClockDisciplineTest}.
 */
public final class AppClock {

    private static final ScopedValue<Clock> BOUND = ScopedValue.newInstance();

    private static final Clock SYSTEM = Clock.systemUTC();

    private AppClock() {}

    /** The current instant, from the bound clock if there is one and the system clock otherwise. */
    public static @NonNull Instant now() {
        return clock().instant();
    }

    /** The clock behind {@link #now()}, for call sites that need a zone or a {@code Clock}-taking API. */
    public static @NonNull Clock clock() {
        return BOUND.orElse(SYSTEM);
    }

    /** Run {@code body} with {@code clock} bound on this thread. */
    public static void runWith(@NonNull Clock clock, @NonNull Runnable body) {
        ScopedValue.where(BOUND, clock).run(body);
    }

    /** Call {@code body} with {@code clock} bound on this thread, propagating its checked exception. */
    public static <R, X extends Throwable> R callWith(
            @NonNull Clock clock, @NonNull CallableOp<R, X> body) throws X {
        return ScopedValue.where(BOUND, clock).call(body);
    }
}
