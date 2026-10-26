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
 * last count at once and, once that count is {@code maxAge} old, starts one recount behind it,
 * saying so in {@link Reading#counting()} so the page can re-read until the new count lands. Only
 * a read with no count yet waits, joining the count in flight. A failed walk keeps the last count.
 */
public final class WorkspaceSizeGauge {

    /** A count in bytes (-1 while none has succeeded), and whether a newer count is running to replace it. */
    public record Reading(long bytes, boolean counting) {}

    private record Count(long bytes, Instant countedAt) {}

    private record Recount(long generation, CompletableFuture<Long> bytes) {}

    private final LongSupplier walk;
    private final Duration maxAge;

    private @Nullable Count last;
    private long generation;
    private @Nullable Recount inFlight;

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
                return new Reading(count.bytes(), recounting());
            }
            first = recount(now);
        }
        var bytes = first.join();
        synchronized (this) {
            return new Reading(bytes, recounting());
        }
    }

    /** Starts a count unless one is already running, without waiting for it. */
    public void refresh() {
        recount(AppClock.now());
    }

    /** Recounts after a change; a count already running may predate the change, so its result is dropped. */
    public synchronized void invalidate() {
        generation++;
        recount(AppClock.now());
    }

    private synchronized boolean recounting() {
        return inFlight != null && inFlight.generation() == generation;
    }

    private synchronized CompletableFuture<Long> recount(Instant startedAt) {
        var running = inFlight;
        if (running != null && running.generation() == generation) return running.bytes();
        var next = new Recount(generation, new CompletableFuture<>());
        inFlight = next;
        Thread.ofVirtual().name("workspace-size").start(() -> {
            long bytes = -1;
            try {
                bytes = walk.getAsLong();
            } finally {
                settle(next, bytes, startedAt);
            }
        });
        return next.bytes();
    }

    private void settle(Recount recount, long bytes, Instant startedAt) {
        synchronized (this) {
            if (inFlight == recount) inFlight = null;
            if (bytes >= 0 && recount.generation() == generation) last = new Count(bytes, startedAt);
        }
        recount.bytes().complete(bytes);
    }
}
