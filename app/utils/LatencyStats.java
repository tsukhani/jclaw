package utils;

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
            return o;
        }
    }
}
