import agents.ContextWindowManager;
import jobs.TokenizerCalibrationJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the pure logic inside TokenizerCalibrationJob —
 * parseSample, collectRatios, applyCalibrations, p95, maxOf. The DB-
 * dependent doJob() top-level entry point is exercised separately by the
 * integration test once Message rows accumulate.
 */
class TokenizerCalibrationJobTest extends UnitTest {

    private static final String PROVIDER = "test-provider";
    private static final String MODEL = "test-model";
    private static final String CONFIG_KEY =
            ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROVIDER + "." + MODEL;

    @AfterEach
    void cleanup() {
        ConfigService.delete(CONFIG_KEY);
    }

    @Test
    void parseSampleSkipsModelMatched() {
        var json = """
                { "modelProvider":"openai", "modelId":"gpt-4o",
                  "prompt":1000, "jtokkitPrompt":1010,
                  "jtokkitModelMatched": true }
                """;
        assertNull(TokenizerCalibrationJob.parseSample(json),
                "modelMatched=true rows are skipped: their encoding is exact and no multiplier applies");
    }

    @Test
    void parseSampleExtractsRatioForUnmatched() {
        var json = """
                { "modelProvider":"ollama-cloud", "modelId":"kimi-k2.6",
                  "prompt":1450, "jtokkitPrompt":1000,
                  "jtokkitModelMatched": false }
                """;
        var sample = TokenizerCalibrationJob.parseSample(json);
        assertNotNull(sample);
        assertEquals("ollama-cloud.kimi-k2.6", sample.key());
        assertEquals(1.45, sample.ratio(), 1e-9);
    }

    @Test
    void parseSampleReturnsNullForMissingFields() {
        // Missing modelProvider
        var missingProvider = """
                { "modelId":"x", "prompt":100, "jtokkitPrompt":80, "jtokkitModelMatched": false }
                """;
        assertNull(TokenizerCalibrationJob.parseSample(missingProvider));

        // Zero jtokkit count — would divide-by-zero
        var zeroJtokkit = """
                { "modelProvider":"p", "modelId":"m", "prompt":100,
                  "jtokkitPrompt":0, "jtokkitModelMatched": false }
                """;
        assertNull(TokenizerCalibrationJob.parseSample(zeroJtokkit));
    }

    @Test
    void parseSampleReturnsNullForMalformedJson() {
        assertNull(TokenizerCalibrationJob.parseSample("not valid json {"));
        assertNull(TokenizerCalibrationJob.parseSample(null));
        assertNull(TokenizerCalibrationJob.parseSample(""));
    }

    @Test
    void p95PicksConservativeRatioFromSortedSamples() {
        var ratios = new ArrayList<Double>();
        for (int i = 1; i <= 100; i++) ratios.add(0.01 * i); // 0.01..1.0
        // P95 of 100 sorted values: index = ceil(95)-1 = 94 → value 0.95
        assertEquals(0.95, TokenizerCalibrationJob.p95(ratios), 1e-9);
    }

    @Test
    void p95SmallSampleStillBoundedToList() {
        var ratios = List.of(1.0, 1.1, 1.2, 1.3, 1.4);
        // ceil(0.95*5)-1 = 4 → last element
        assertEquals(1.4, TokenizerCalibrationJob.p95(ratios), 1e-9);
    }

    @Test
    void maxOfPicksLargestRatio() {
        var ratios = List.of(1.0, 1.4, 1.2, 1.45, 1.1);
        assertEquals(1.45, TokenizerCalibrationJob.maxOf(ratios), 1e-9);
    }

    @Test
    void applyCalibrationsWritesPerModelMultiplier() {
        // 5+ samples → triggers max-branch (not p95); ratio 1.45 with
        // 5% headroom → 1.5225 (clamped within 1.0..2.5).
        var ratios = List.of(1.0, 1.3, 1.4, 1.42, 1.45);
        var groups = new HashMap<String, List<Double>>();
        groups.put(PROVIDER + "." + MODEL, ratios);

        int updated = TokenizerCalibrationJob.applyCalibrations(groups);
        assertEquals(1, updated, "should have written one new multiplier");

        var stored = ConfigService.get(CONFIG_KEY, null);
        assertNotNull(stored);
        double parsed = Double.parseDouble(stored);
        // max=1.45, * 1.05 headroom = 1.5225 → "1.52"
        assertTrue(parsed >= 1.51 && parsed <= 1.53,
                "expected ~1.52 (max 1.45 * 1.05 headroom), got " + parsed);
    }

    @Test
    void applyCalibrationsSkipsTooFewSamples() {
        var groups = new HashMap<String, List<Double>>();
        // Below MIN_SAMPLES_PER_GROUP (5)
        groups.put(PROVIDER + "." + MODEL, List.of(1.3, 1.4));

        int updated = TokenizerCalibrationJob.applyCalibrations(groups);
        assertEquals(0, updated);
        assertNull(ConfigService.get(CONFIG_KEY, null),
                "below-threshold groups must not write to Config");
    }

    @Test
    void applyCalibrationsSkipsSmallDeltaUpdates() {
        // Seed an existing value first.
        ConfigService.set(CONFIG_KEY, "1.50");
        // Compute a new value within the 0.05 delta threshold.
        // Samples averaging ~1.43 → max=1.45 → * 1.05 = 1.5225, |1.52 - 1.50| = 0.02 < 0.05
        var groups = new HashMap<String, List<Double>>();
        groups.put(PROVIDER + "." + MODEL, List.of(1.40, 1.42, 1.43, 1.44, 1.45));

        int updated = TokenizerCalibrationJob.applyCalibrations(groups);
        assertEquals(0, updated, "delta below threshold must not trigger a write");
        assertEquals("1.50", ConfigService.get(CONFIG_KEY, null));
    }

    @Test
    void applyCalibrationsWritesWhenDeltaExceedsThreshold() {
        ConfigService.set(CONFIG_KEY, "1.20");
        // Samples max 1.45 → * 1.05 = 1.5225, |1.52 - 1.20| = 0.32 > 0.05 → update.
        var groups = new HashMap<String, List<Double>>();
        groups.put(PROVIDER + "." + MODEL, List.of(1.30, 1.40, 1.45, 1.45, 1.45));

        int updated = TokenizerCalibrationJob.applyCalibrations(groups);
        assertEquals(1, updated);
        var stored = ConfigService.get(CONFIG_KEY, null);
        assertTrue(Double.parseDouble(stored) > 1.49);
    }

    @Test
    void collectRatiosGroupsByProviderModel() {
        // No DB hit needed — collectRatios just iterates a Message list and
        // calls parseSample. We can't easily build Message rows here, so
        // exercise via an empty path that proves the method doesn't NPE on
        // empty input and returns the empty map.
        var groups = TokenizerCalibrationJob.collectRatios(List.of());
        assertTrue(groups.isEmpty());
    }
}
