package utils;

import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires {@code onExpire} once after {@code budget} passes with no {@link #touch()}, as long as
 * {@link #cancel()} was not called first. Re-arming is throttled to once per {@code rearmMin},
 * so a token stream touching it thousands of times a minute reschedules only a few times.
 * Runs on one daemon platform thread: a timed wait on a virtual thread is the JDK-8373224 trap.
 */
public final class InactivityTimer {
    private static final ScheduledExecutorService SCHEDULER = Executors.newSingleThreadScheduledExecutor(r -> {
        var t = new Thread(r, "inactivity-timer");
        t.setDaemon(true);
        return t;
    });

    private final long budgetMs;
    private final long rearmMinNanos;
    private final Runnable onExpire;
    private final AtomicBoolean fired = new AtomicBoolean(false);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile @Nullable ScheduledFuture<?> task;
    private volatile long armedAtNanos;

    private InactivityTimer(Duration budget, Duration rearmMin, Runnable onExpire) {
        this.budgetMs = budget.toMillis();
        this.rearmMinNanos = rearmMin.toNanos();
        this.onExpire = onExpire;
    }

    /** Start a timer that expires {@code budget} after now or after the last touch, whichever is later. */
    public static InactivityTimer start(Duration budget, Duration rearmMin, Runnable onExpire) {
        var timer = new InactivityTimer(budget, rearmMin, onExpire);
        timer.arm();
        return timer;
    }

    /** Note activity: pushes the expiry out to {@code budget} from now, at most once per {@code rearmMin}. */
    public void touch() {
        if (fired.get() || cancelled.get()) return;
        if (System.nanoTime() - armedAtNanos < rearmMinNanos) return;
        arm();
    }

    /** Stop the timer; a cancelled timer never fires. */
    public void cancel() {
        cancelled.set(true);
        var t = task;
        if (t != null) t.cancel(false);
    }

    public boolean fired() {
        return fired.get();
    }

    private synchronized void arm() {
        if (fired.get() || cancelled.get()) return;
        var t = task;
        if (t != null) t.cancel(false);
        armedAtNanos = System.nanoTime();
        task = SCHEDULER.schedule(this::expire, budgetMs, TimeUnit.MILLISECONDS);
    }

    private void expire() {
        if (cancelled.get() || !fired.compareAndSet(false, true)) return;
        onExpire.run();
    }
}
