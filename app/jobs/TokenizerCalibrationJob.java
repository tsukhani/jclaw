package jobs;

import agents.ContextWindowManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Message;
import models.MessageRole;
import play.jobs.Every;
import play.jobs.Job;
import services.ConfigService;
import services.EventLogger;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Self-calibrating per-(provider, model) safety multiplier maintenance.
 *
 * <p>JClaw's {@link ContextWindowManager#resolveSafetyMultiplier} normally
 * applies a global {@code jtokkit.safetyMultiplier.unmatched} = 1.4 to non-
 * OpenAI models so the cl100k_base fallback doesn't under-count and ship
 * over-window payloads. That global is conservative but inefficient: Kimi
 * runs ~1.45×, DeepSeek may be 1.20×, Gemma differs again. Each model has
 * a distinct tokenizer-bias signature.
 *
 * <p>This job runs every 30 minutes off the chat hot path. It scans recent
 * assistant {@link Message} rows, parses their {@code usageJson} for
 * {@code jtokkitModelMatched=false} entries, groups by
 * {@code (modelProvider, modelId)}, and computes a conservative ratio
 * estimate per group:
 *
 * <ul>
 *   <li><b>P95 ratio</b> when n &ge; 20 — covers the typical content-type
 *       mix without over-fitting to a single bad turn.</li>
 *   <li><b>Max ratio</b> when 5 &le; n &lt; 20 — more conservative for
 *       small samples, where one outlier still matters.</li>
 *   <li><b>Skip</b> when n &lt; 5 — not enough data; the global fallback
 *       holds until samples accumulate.</li>
 * </ul>
 *
 * <p>The computed ratio is bumped by 5% headroom and clamped to
 * [{@link ContextWindowManager#MIN_SAFETY_MULTIPLIER},
 * {@link ContextWindowManager#MAX_SAFETY_MULTIPLIER}]. We only write to
 * Config when the new value differs from the stored one by more than 0.05
 * — this keeps {@code config} table writes infrequent and the
 * EventLogger feed readable.
 *
 * <p>Performance: zero contribution to chat latency (background job, never
 * blocks). One DB read of recent messages (LIMIT bounded), in-memory JSON
 * parsing, a handful of Config writes per cycle. Typical runtime: under
 * 5 seconds for &lt; 10k recent messages.
 */
@Every("30min")
public class TokenizerCalibrationJob extends Job<Void> {

    /** How far back to look for samples each run. */
    private static final int LOOKBACK_DAYS = 7;

    /** Hard cap on rows scanned per run, in case lookback covers a heavy traffic week. */
    private static final int MAX_SAMPLES_PER_RUN = 10_000;

    /** Per-group minimum sample size to compute any multiplier at all. */
    private static final int MIN_SAMPLES_PER_GROUP = 5;

    /** At/above this group size we switch from worst-observed to P95. */
    private static final int P95_THRESHOLD_SAMPLES = 20;

    /** Conservative cushion: multiply the computed ratio by this before writing. */
    private static final double HEADROOM = 1.05;

    /** Only update stored multiplier when the new value differs by &gt; this. */
    private static final double UPDATE_DELTA_THRESHOLD = 0.05;

    @Override
    public void doJob() {
        var since = Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS);
        List<Message> recent = Message.find(
                        "role = ?1 AND usageJson IS NOT NULL AND createdAt > ?2 ORDER BY createdAt DESC",
                        MessageRole.ASSISTANT.value, since)
                .fetch(MAX_SAMPLES_PER_RUN);

        if (recent.isEmpty()) return;

        // Group ratios by "provider/modelId" key. Each entry is the list of
        // observed (provider_prompt / jtokkit_prompt) ratios for that pair.
        Map<String, List<Double>> ratiosByKey = new HashMap<>();
        for (var msg : recent) {
            var sample = parseSample(msg.usageJson);
            if (sample == null) continue;
            ratiosByKey.computeIfAbsent(sample.key(), k -> new ArrayList<>()).add(sample.ratio());
        }

        int updated = 0;
        for (var entry : ratiosByKey.entrySet()) {
            var key = entry.getKey();
            var ratios = entry.getValue();
            if (ratios.size() < MIN_SAMPLES_PER_GROUP) continue;

            double baseRatio = ratios.size() >= P95_THRESHOLD_SAMPLES
                    ? p95(ratios)
                    : maxOf(ratios);
            double newMultiplier = clamp(baseRatio * HEADROOM,
                    ContextWindowManager.MIN_SAFETY_MULTIPLIER,
                    ContextWindowManager.MAX_SAFETY_MULTIPLIER);

            var configKey = ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + key;
            var existingRaw = ConfigService.get(configKey, null);
            double existing = parseDouble(existingRaw, -1.0);

            if (existing < 0 || Math.abs(newMultiplier - existing) > UPDATE_DELTA_THRESHOLD) {
                ConfigService.set(configKey, "%.2f".formatted(newMultiplier));
                EventLogger.info("tokenizer-calibration", null, null,
                        "Updated %s = %.2f (samples=%d, %s=%.2f, prior=%s)".formatted(
                                configKey, newMultiplier, ratios.size(),
                                ratios.size() >= P95_THRESHOLD_SAMPLES ? "p95" : "max",
                                baseRatio,
                                existingRaw == null ? "unset" : existingRaw));
                updated++;
            }
        }

        if (updated > 0) {
            EventLogger.info("tokenizer-calibration", null, null,
                    "Calibration cycle complete: scanned %d messages across %d (provider,model) groups, updated %d"
                            .formatted(recent.size(), ratiosByKey.size(), updated));
        }
    }

    /**
     * Parse one usageJson row into a calibration sample, or return null if
     * the row is missing fields, the model matched its native encoding
     * (multiplier doesn't apply), or the ratio is degenerate.
     */
    private static Sample parseSample(String usageJson) {
        if (usageJson == null || usageJson.isBlank()) return null;
        try {
            var obj = JsonParser.parseString(usageJson).getAsJsonObject();
            // Skip OpenAI-family turns — their encoding matches and the
            // multiplier is 1.0 regardless. Captured by the sentinel field
            // UsageMetricsBuilder writes.
            if (asBool(obj, "jtokkitModelMatched", true)) return null;

            var provider = asString(obj, "modelProvider");
            var modelId = asString(obj, "modelId");
            if (provider == null || modelId == null) return null;

            int providerPrompt = asInt(obj, "prompt", 0);
            int jtokkitPrompt = asInt(obj, "jtokkitPrompt", 0);
            if (providerPrompt <= 0 || jtokkitPrompt <= 0) return null;

            double ratio = (double) providerPrompt / jtokkitPrompt;
            // Defensive: ratios below 1.0 mean jtokkit OVER-counted (safe
            // direction); we still record them so a long run of safe
            // measurements doesn't get masked by one bad outlier.
            return new Sample(provider + "." + modelId, ratio);
        }
        catch (Exception e) {
            return null;
        }
    }

    private record Sample(String key, double ratio) {}

    private static String asString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static int asInt(JsonObject obj, String key, int fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsInt() : fallback;
    }

    private static boolean asBool(JsonObject obj, String key, boolean fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsBoolean() : fallback;
    }

    private static double parseDouble(String raw, double fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try { return Double.parseDouble(raw); }
        catch (NumberFormatException e) { return fallback; }
    }

    private static double p95(List<Double> values) {
        var sorted = new ArrayList<>(values);
        java.util.Collections.sort(sorted);
        int idx = (int) Math.ceil(0.95 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private static double maxOf(List<Double> values) {
        double max = 0;
        for (var v : values) if (v > max) max = v;
        return max;
    }

    private static double clamp(double value, double min, double max) {
        return Math.min(max, Math.max(min, value));
    }
}
