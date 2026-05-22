import agents.ContextWindowManager;
import llm.TokenUsageEstimator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;

/**
 * Coverage tests for the safety-multiplier lookup chain and the
 * tool-result truncation helper added in v0.12.26.
 *
 * <p>Pairs with the JtokkitCalibrationLogicTest below; together they
 * exercise the per-(provider, model) calibration loop end to end.
 */
class ContextWindowSafetyMultiplierTest extends UnitTest {

    @AfterEach
    void cleanup() {
        // Reset any per-test Config writes so sibling tests start clean.
        ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider.test-model");
        ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider");
        ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_KEY);
    }

    @Test
    void resolveSafetyMultiplierFallsBackToDefaultWhenNoConfig() {
        double m = ContextWindowManager.resolveSafetyMultiplier("test-provider", "test-model");
        assertEquals(ContextWindowManager.DEFAULT_SAFETY_MULTIPLIER, m, 1e-9);
    }

    @Test
    void resolveSafetyMultiplierUsesGlobalOverride() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_KEY, "1.7");
        assertEquals(1.7, ContextWindowManager.resolveSafetyMultiplier("test-provider", "test-model"), 1e-9);
    }

    @Test
    void resolveSafetyMultiplierPrefersPerProviderOverGlobal() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_KEY, "1.2");
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider", "1.8");
        assertEquals(1.8, ContextWindowManager.resolveSafetyMultiplier("test-provider", "test-model"), 1e-9);
    }

    @Test
    void resolveSafetyMultiplierPrefersPerModelOverPerProvider() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_KEY, "1.2");
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider", "1.6");
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider.test-model", "1.9");
        assertEquals(1.9, ContextWindowManager.resolveSafetyMultiplier("test-provider", "test-model"), 1e-9);
    }

    @Test
    void resolveSafetyMultiplierClampsToValidRange() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider.test-model", "5.0");
        // 5.0 is above MAX_SAFETY_MULTIPLIER (2.5), should clamp.
        assertEquals(ContextWindowManager.MAX_SAFETY_MULTIPLIER,
                ContextWindowManager.resolveSafetyMultiplier("test-provider", "test-model"), 1e-9);

        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider.test-model", "0.5");
        // 0.5 is below MIN_SAFETY_MULTIPLIER (1.0), should clamp.
        assertEquals(ContextWindowManager.MIN_SAFETY_MULTIPLIER,
                ContextWindowManager.resolveSafetyMultiplier("test-provider", "test-model"), 1e-9);
    }

    @Test
    void resolveSafetyMultiplierIgnoresMalformedValue() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider.test-model", "not-a-number");
        // Malformed should fall through to the next tier — default in this case.
        assertEquals(ContextWindowManager.DEFAULT_SAFETY_MULTIPLIER,
                ContextWindowManager.resolveSafetyMultiplier("test-provider", "test-model"), 1e-9);
    }

    @Test
    void adjustedPromptTokensSkipsMultiplierWhenMatched() {
        var estimate = new TokenUsageEstimator.ChatRequestTokens(
                100, 0, 100, "o200k_base", true);
        // Matched encoding → no multiplier applied even with provider/model set
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "openai.gpt-4o", "2.0");
        assertEquals(100, ContextWindowManager.adjustedPromptTokens("openai", "gpt-4o", estimate));
    }

    @Test
    void adjustedPromptTokensAppliesMultiplierWhenUnmatched() {
        var estimate = new TokenUsageEstimator.ChatRequestTokens(
                100, 0, 100, "cl100k_base", false);
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + "test-provider.test-model", "1.5");
        // 100 * 1.5 = 150, with Math.ceil
        assertEquals(150, ContextWindowManager.adjustedPromptTokens("test-provider", "test-model", estimate));
    }

    @Test
    void adjustedMessageTokensRespectsMatchedFlag() {
        assertEquals(50, ContextWindowManager.adjustedMessageTokens(50, true, "openai", "gpt-4o"));
    }

    @Test
    void truncateToolResultContentReducesLongStrings() {
        var original = "a".repeat(10_000);
        var truncated = ContextWindowManager.truncateToolResultContent(original, 500, 500);
        assertTrue(truncated.length() < original.length(),
                "10k-char body should shrink past head + tail + marker");
        assertTrue(truncated.startsWith("a".repeat(500)),
                "should preserve the first 500 chars verbatim");
        assertTrue(truncated.endsWith("a".repeat(500)),
                "should preserve the last 500 chars verbatim");
        assertTrue(truncated.contains("JClaw elided"),
                "should mark the elision so the model and operator see it");
    }

    @Test
    void truncateToolResultContentLeavesShortBodiesAlone() {
        // 200 chars < 500 head + 500 tail + 96 marker = 1096 threshold.
        var original = "x".repeat(200);
        var truncated = ContextWindowManager.truncateToolResultContent(original, 500, 500);
        assertSame(original, truncated, "below-threshold input should be returned identically");
    }

    @Test
    void truncateToolResultContentBoundedAtThreshold() {
        // Exactly at the threshold — should NOT truncate (saves nothing).
        var original = "y".repeat(500 + 500 + 96);
        var truncated = ContextWindowManager.truncateToolResultContent(original, 500, 500);
        assertSame(original, truncated);
    }

    @Test
    void truncateToolResultContentJustOverThresholdDoesTruncate() {
        var original = "z".repeat(500 + 500 + 96 + 100);  // 100 chars over the threshold
        var truncated = ContextWindowManager.truncateToolResultContent(original, 500, 500);
        assertTrue(truncated.length() < original.length(),
                "just over threshold should still shrink");
    }
}
