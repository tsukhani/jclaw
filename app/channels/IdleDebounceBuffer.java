package channels;

import services.EventLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * JCLAW-397: shared idle-debounce coalescing scaffold for the three Telegram
 * inbound buffers — long-message text reassembly
 * ({@link TelegramInboundTextBuffer}), forwarded-burst coalescing
 * ({@link TelegramForwardCoalesceBuffer}), and media-group albums
 * ({@link TelegramMediaGroupBuffer}). Each lane previously carried its own copy
 * of the same machinery: a {@link ConcurrentHashMap} of per-key buckets, a
 * single-threaded daemon {@link ScheduledExecutorService} that fires an
 * idle-timeout flush, the (re)start-the-timer-on-every-arrival idiom, and a
 * flush path that swallows {@link Throwable} so a dispatcher failure can't kill
 * the scheduler thread and silently freeze the lane for the JVM lifetime. That
 * duplication had already drifted (one lane lost {@code resetForTest} and used a
 * lossy merge ctor), and the swallow is correctness-load-bearing, so a fix to
 * one copy and not the others was the live risk.
 *
 * <p>This type owns that machinery once. A lane supplies only what differs: a
 * bucket factory, how to fold an incoming message into its bucket
 * ({@link Accumulator}), how to build the merged inbound on flush
 * ({@link Merger}), the idle window, and the scheduler thread name.
 *
 * <p>Keys are opaque strings minted by the lane —
 * {@code (chatId, threadId, fromId)} for the text / forward lanes, the
 * {@code media_group_id} for albums. The open-vs-extend decision runs inside
 * {@link ConcurrentHashMap#compute}, so concurrent pieces for one key on the
 * virtual-threaded receive path can't race the create-then-append.
 *
 * @param <B> the lane's per-key accumulator (caption builder, attachment list,
 *            first-seen message, …)
 */
final class IdleDebounceBuffer<B> {

    /** Folds an incoming message into a bucket; see {@link #offer}. */
    @FunctionalInterface
    interface Accumulator<B> {
        /**
         * Fold {@code incoming} into {@code bucket}. Return {@code true} to keep
         * buffering, or {@code false} to pass {@code incoming} straight through
         * to the dispatcher unbuffered (the bucket is discarded). A {@code false}
         * return is only honored when {@code freshBucket} is {@code true} — i.e.
         * the first message seen for the key — so an in-progress bucket is never
         * dropped; implementations must append (and return {@code true}) for any
         * arrival into an already-open bucket.
         */
        boolean accumulate(B bucket, InboundMessage incoming, boolean freshBucket);
    }

    /** Builds the merged inbound from an accumulated bucket on flush. */
    @FunctionalInterface
    interface Merger<B> {
        /** The merged inbound, or {@code null} if the bucket has nothing to
         *  dispatch (no first message yet). Implementations may log. */
        InboundMessage merge(B bucket);
    }

    private static final class Holder<B> {
        final B bucket;
        ScheduledFuture<?> flushTask;
        Consumer<InboundMessage> dispatcher;
        Holder(B bucket) { this.bucket = bucket; }
    }

    private final ConcurrentHashMap<String, Holder<B>> buffers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler;
    private final Supplier<B> bucketFactory;
    private final Accumulator<B> accumulator;
    private final Merger<B> merger;
    private final LongSupplier windowMs;
    private final String errorLabel;

    /**
     * @param threadName    name of the single daemon scheduler thread, per lane,
     *                      for log/diagnostic visibility
     * @param errorLabel    prefix for the swallowed-dispatcher-error log line
     *                      (e.g. {@code "Media group"})
     * @param bucketFactory makes a fresh empty bucket for a new key
     * @param accumulator   folds an incoming message into a bucket
     * @param merger        builds the merged inbound on flush
     * @param windowMs      idle window before flush, read per-schedule so a
     *                      config change takes effect without a restart
     */
    IdleDebounceBuffer(String threadName, String errorLabel,
                       Supplier<B> bucketFactory, Accumulator<B> accumulator,
                       Merger<B> merger, LongSupplier windowMs) {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            var t = new Thread(r, threadName);
            t.setDaemon(true);
            return t;
        });
        this.bucketFactory = bucketFactory;
        this.accumulator = accumulator;
        this.merger = merger;
        this.windowMs = windowMs;
        this.errorLabel = errorLabel;
    }

    /**
     * Offer {@code incoming} under {@code key}. When the lane's
     * {@link Accumulator} keeps it, the bucket's idle timer is (re)started; once
     * the window elapses the {@link Merger} output is handed to {@code dispatcher}
     * exactly once. When the accumulator passes it through (fresh bucket, not
     * buffered), {@code dispatcher} receives {@code incoming} unchanged and no
     * bucket is retained.
     */
    void offer(String key, InboundMessage incoming, Consumer<InboundMessage> dispatcher) {
        boolean[] passThrough = {false};
        buffers.compute(key, (k, existing) -> {
            boolean fresh = existing == null;
            Holder<B> holder = fresh ? new Holder<>(bucketFactory.get()) : existing;
            if (!accumulator.accumulate(holder.bucket, incoming, fresh)) {
                passThrough[0] = true;
                // Never drop an in-progress bucket: a pass-through only discards a
                // brand-new (fresh) bucket; an already-open one is left untouched.
                return fresh ? null : existing;
            }
            holder.dispatcher = dispatcher;
            if (holder.flushTask != null) holder.flushTask.cancel(false);
            holder.flushTask = scheduler.schedule(() -> flush(k), windowMs.getAsLong(), TimeUnit.MILLISECONDS);
            return holder;
        });
        if (passThrough[0]) dispatcher.accept(incoming);
    }

    /**
     * Remove and dispatch the bucket for {@code key}. Normally fired by the
     * scheduler after the idle window; reachable directly via
     * {@link #flushForTest(String)}.
     */
    // Catches Throwable on purpose: this runs on the scheduler thread, so an
    // unhandled Error from the dispatcher would kill the timer's worker and
    // permanently stop this lane's flushes for the JVM lifetime.
    @SuppressWarnings("java:S1181")
    private void flush(String key) {
        var holder = buffers.remove(key);
        if (holder == null || holder.dispatcher == null) return;
        var merged = merger.merge(holder.bucket);
        if (merged == null) return;
        try {
            holder.dispatcher.accept(merged);
        } catch (Throwable t) {
            EventLogger.error("channel", null, "telegram",
                    errorLabel + " dispatcher error: " + t.getMessage());
        }
    }

    /** Visible for tests: flush {@code key} now, bypassing the idle timer. */
    void flushForTest(String key) {
        flush(key);
    }

    /** Visible for tests: clear all buffered state between test cases. */
    void resetForTest() {
        buffers.clear();
    }
}
