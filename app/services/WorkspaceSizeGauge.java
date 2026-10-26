package services;

import org.jspecify.annotations.Nullable;
import utils.AppClock;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * The workspace's on-disk size as last counted, for the dashboard's runaway-growth figure (JF-35).
 * A count stats every file, which took 20–28 s over 167k files under load, so a read serves the
 * last count at once and, once that count is {@code maxAge} old, starts a recount behind it, saying
 * so in {@link Reading#counting()} so the page can re-read until the new count lands. Only a read
 * with no count yet waits, joining the count in flight. One walk runs at a time: a change during a
 * walk queues a single recount after it, however many changes arrive. A failed walk keeps the last count.
 */
public final class WorkspaceSizeGauge {

    /** A count in bytes (-1 while none has succeeded), and whether a newer count is running to replace it. */
    public record Reading(long bytes, boolean counting) {}

    private record Count(long bytes, Instant countedAt) {}

    private final LongSupplier walk;
    private final Duration maxAge;

    private @Nullable Count last;
    private @Nullable CompletableFuture<Long> inFlight;
    private @Nullable Instant changedDuringWalk;

    /** {@code walk} returns the size in bytes, or -1 when the walk failed. */
    public WorkspaceSizeGauge(LongSupplier walk, Duration maxAge) {
        this.walk = walk;
        this.maxAge = maxAge;
    }

    public Reading read() {
        var now = AppClock.now();
        CompletableFuture<Long> first;
        synchronized (this) {
            var count = last;
            if (count != null) {
                if (!now.isBefore(count.countedAt().plus(maxAge))) recount(now);
                return new Reading(count.bytes(), inFlight != null);
            }
            first = recount(now);
        }
        var bytes = first.join();
        synchronized (this) {
            return new Reading(bytes, inFlight != null);
        }
    }

    /** Starts a count unless one is already running, without waiting for it. */
    public void refresh() {
        recount(AppClock.now());
    }

    /** Recounts after a change: at once, or once the walk already running has ended, since it may predate the change. */
    public synchronized void invalidate() {
        var now = AppClock.now();
        if (inFlight == null) recount(now);
        else changedDuringWalk = now;
    }

    private synchronized CompletableFuture<Long> recount(Instant startedAt) {
        var running = inFlight;
        if (running != null) return running;
        var result = new CompletableFuture<Long>();
        inFlight = result;
        Thread.ofVirtual().name("workspace-size").start(() -> {
            long bytes = -1;
            try {
                bytes = walk.getAsLong();
            } finally {
                settle(result, bytes, startedAt);
            }
        });
        return result;
    }

    private void settle(CompletableFuture<Long> result, long bytes, Instant startedAt) {
        synchronized (this) {
            inFlight = null;
            if (bytes >= 0) last = new Count(bytes, startedAt);
            var changedAt = changedDuringWalk;
            if (changedAt != null) {
                changedDuringWalk = null;
                recount(changedAt);
            }
        }
        result.complete(bytes);
    }
}
