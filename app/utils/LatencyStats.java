package utils;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.HdrHistogram.AtomicHistogram;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory HdrHistogram-backed latency stats. Not persisted — resets on
 * JVM restart and via {@link #reset()}.
 *
 * <p>Uses {@link AtomicHistogram} for lock-free multi-writer recording;
 * values below 1ms are clamped to 1 since HdrHistogram requires positive
 * integers. The range covers 1ms..1h with 3 significant digits (≈0.1%
 * relative error), which replaces the hand-rolled log-linear buckets that
 * rounded tail percentiles up to the next power of two.
 */
public final class LatencyStats {

    // 1ms..1h, 3 sig digits — memory footprint is ~50 KB per histogram.
    private static final long HIGHEST_TRACKABLE_MS = 3_600_000L;
    private static final int NUMBER_OF_SIG_DIGITS = 3;

    private static final ConcurrentHashMap<String, Histogram> HISTOGRAMS =
            new ConcurrentHashMap<>();

    private LatencyStats() {}

    public static void record(String segment, long valueMs) {
        HISTOGRAMS.computeIfAbsent(segment, _ -> new Histogram()).record(valueMs);
    }

    public static JsonObject snapshot() {
        var root = new JsonObject();
        for (Map.Entry<String, Histogram> e : HISTOGRAMS.entrySet()) {
            root.add(e.getKey(), e.getValue().toJson());
        }
        return root;
    }

    public static void reset() {
        HISTOGRAMS.clear();
    }

    /**
     * Capture the current histogram state and return a {@link Runnable} that,
     * when invoked, restores the exact state at capture time — dropping any
     * samples recorded between capture and restore, and removing any segments
     * that were created after capture.
     *
     * <p>Used by the load-test harness to discard warmup samples without
     * wiping data accumulated from previous runs. Safe to call while other
     * threads are recording, but concurrent writes happening <em>between</em>
     * the reset and add steps of the restore are lost — keep the window
     * narrow (snapshot → single warmup request → restore).
     */
    public static Runnable captureResetPoint() {
        java.util.Map<String, HistogramCopy> snap = new java.util.HashMap<>();
        for (Map.Entry<String, Histogram> e : HISTOGRAMS.entrySet()) {
            snap.put(e.getKey(), e.getValue().copy());
        }
        return () -> {
            // Drop any segments created after capture (e.g. first-ever warmup
            // against a fresh JVM populates segments that didn't exist at
            // snapshot time).
            HISTOGRAMS.keySet().retainAll(snap.keySet());
            // Restore each surviving segment to its captured state.
            for (var e : snap.entrySet()) {
                var hist = HISTOGRAMS.get(e.getKey());
                if (hist != null) hist.restoreFrom(e.getValue());
            }
        };
    }

    /** Immutable per-segment snapshot used by {@link #captureResetPoint()}. */
    private record HistogramCopy(AtomicHistogram hdr, long sumMs) {}

    private static final class Histogram {
        private final AtomicHistogram hdr =
                new AtomicHistogram(HIGHEST_TRACKABLE_MS, NUMBER_OF_SIG_DIGITS);
        // HdrHistogram doesn't track raw sum internally; we keep it alongside
        // to support sum_ms / avg downstream without a second histogram pass.
        private final AtomicLong sumMs = new AtomicLong();

        void record(long value) {
            long clamped = Math.max(1, Math.min(value, HIGHEST_TRACKABLE_MS));
            hdr.recordValue(clamped);
            sumMs.addAndGet(value);
        }

        HistogramCopy copy() {
            return new HistogramCopy(hdr.copy(), sumMs.get());
        }

        void restoreFrom(HistogramCopy c) {
            hdr.reset();
            hdr.add(c.hdr);
            sumMs.set(c.sumMs);
        }

        JsonObject toJson() {
            long n = hdr.getTotalCount();
            var o = new JsonObject();
            o.addProperty("count", n);
            o.addProperty("sum_ms", sumMs.get());
            if (n == 0) {
                o.addProperty("min_ms", 0);
                o.addProperty("max_ms", 0);
                o.addProperty("p50_ms", 0);
                o.addProperty("p90_ms", 0);
                o.addProperty("p99_ms", 0);
                o.addProperty("p999_ms", 0);
                return o;
            }
            o.addProperty("min_ms", hdr.getMinValue());
            o.addProperty("max_ms", hdr.getMaxValue());
            o.addProperty("p50_ms", hdr.getValueAtPercentile(50.0));
            o.addProperty("p90_ms", hdr.getValueAtPercentile(90.0));
            o.addProperty("p99_ms", hdr.getValueAtPercentile(99.0));
            o.addProperty("p999_ms", hdr.getValueAtPercentile(99.9));
            // Log buckets at fractional factors of 2 (base = 2^(1/4) ≈ 1.189),
            // giving ~4× the resolution of plain log-2 so tight distributions
            // (e.g. TTFT with p50 and max within 1.3×) split into multiple bars
            // instead of collapsing into one. Boundaries iterate in double
            // precision and ceil to integer ms; consecutive duplicates after
            // ceiling are skipped. Interior zero-count buckets are preserved
            // so the frontend can draw a continuous empirical distribution.
            var buckets = new JsonArray();
            final double logBase = Math.pow(2.0, 0.25);
            long maxValue = hdr.getMaxValue();
            long prevLe = 0;
            double boundary = 1.0;
            while (prevLe < maxValue) {
                long le = (long) Math.ceil(boundary);
                if (le > prevLe) {
                    long bucketCount = hdr.getCountBetweenValues(prevLe + 1, le);
                    var b = new JsonObject();
                    b.addProperty("le_ms", le);
                    b.addProperty("count", bucketCount);
                    buckets.add(b);
                    prevLe = le;
                }
                boundary *= logBase;
            }
            o.add("buckets", buckets);
            return o;
        }
    }
}
