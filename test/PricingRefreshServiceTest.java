import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Config;
import org.junit.jupiter.api.*;
import play.test.*;
import services.ConfigService;
import services.PricingRefreshService;

/**
 * Unit tests for {@link services.PricingRefreshService}. The service does
 * three things in different code paths: catalog ID matching, missing-price
 * filling, and operator-override preservation. Each is exercised here without
 * touching the network — tests pass a hand-built JsonObject mimicking
 * LiteLLM's shape to {@link PricingRefreshService#applyCatalog(JsonObject)}.
 */
class PricingRefreshServiceTest extends UnitTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        // Service short-circuits when the toggle is off; tests need it on
        // for applyCatalog to actually mutate provider configs through the
        // refresh() entrypoint (we mostly call applyCatalog directly here,
        // bypassing the toggle, but the few tests that exercise refresh()
        // need this seeded).
        ConfigService.set("pricing.refresh.enabled", "true");
    }

    /**
     * Build a minimal LiteLLM-shaped catalog. Keys are model ids; each value
     * carries per-token prices in the same field names LiteLLM publishes.
     */
    private static JsonObject catalog(Object... pairs) {
        var obj = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) {
            obj.add((String) pairs[i], JsonParser.parseString((String) pairs[i + 1]));
        }
        return obj;
    }

    private static String pricesJson(double inputPerToken, double outputPerToken) {
        return "{\"input_cost_per_token\":" + inputPerToken
                + ",\"output_cost_per_token\":" + outputPerToken + "}";
    }

    // ─── lookupCatalog: id-matching strategy ──────────────────────────

    @Test
    void lookupMatchesBareId() {
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));
        assertNotNull(PricingRefreshService.lookupCatalog(c, "gpt-4o"));
    }

    @Test
    void lookupStripsProviderPrefix() {
        // OpenRouter ids look like "openai/gpt-4o" — match the bare form.
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));
        assertNotNull(PricingRefreshService.lookupCatalog(c, "openai/gpt-4o"));
    }

    @Test
    void lookupStripsVersionSuffix() {
        // Date-pinned ids like "gpt-4o-2024-08-06" should match "gpt-4o".
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));
        assertNotNull(PricingRefreshService.lookupCatalog(c, "gpt-4o-2024-08-06"));
    }

    @Test
    void lookupStripsBothPrefixAndSuffix() {
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));
        assertNotNull(PricingRefreshService.lookupCatalog(c, "openai/gpt-4o-2024-08-06"));
    }

    @Test
    void lookupStripsOllamaTag() {
        // Local Ollama ids carry :tag suffix — should fall through to bare.
        var c = catalog("kimi-k2.5", pricesJson(0.0, 0.0));
        assertNotNull(PricingRefreshService.lookupCatalog(c, "kimi-k2.5:latest"));
    }

    @Test
    void lookupReturnsNullForUnknownModel() {
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));
        assertNull(PricingRefreshService.lookupCatalog(c, "imaginary-model-9000"));
    }

    @Test
    void lookupPrefersExactMatchOverNormalizedFallback() {
        // Both keys exist; the as-is candidate wins so the more specific
        // entry is used over the bare form.
        var c = catalog(
                "gpt-4o", pricesJson(2.5e-6, 1.0e-5),
                "gpt-4o-2024-08-06", pricesJson(5.0e-6, 2.0e-5)
        );
        var match = PricingRefreshService.lookupCatalog(c, "gpt-4o-2024-08-06");
        assertEquals(5.0e-6, match.get("input_cost_per_token").getAsDouble(), 1e-12);
    }

    // ─── applyCatalog: filling missing prices ────────────────────────

    @Test
    void applyFillsMissingPromptAndCompletionPrices() {
        seedProviderModels("openai", """
                [{"id":"gpt-4o","name":"GPT-4o","supportsThinking":false}]""");
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));

        var result = PricingRefreshService.applyCatalog(c);
        assertEquals(1, result.providersScanned());
        assertEquals(1, result.modelsUpdated());

        var saved = ConfigService.get("provider.openai.models");
        var savedModel = JsonParser.parseString(saved).getAsJsonArray().get(0).getAsJsonObject();
        // Per-million conversion: 2.5e-6 × 1_000_000 = 2.5
        assertEquals(2.5, savedModel.get("promptPrice").getAsDouble(), 1e-9);
        assertEquals(10.0, savedModel.get("completionPrice").getAsDouble(), 1e-9);
    }

    @Test
    void applyFillsCacheReadAndCacheWritePrices() {
        seedProviderModels("openai", """
                [{"id":"gpt-4o","name":"GPT-4o"}]""");
        var c = catalog("gpt-4o",
                "{\"input_cost_per_token\":2.5e-6,\"output_cost_per_token\":1.0e-5,"
                + "\"cache_read_input_token_cost\":1.25e-7,"
                + "\"cache_creation_input_token_cost\":3.125e-6}");

        PricingRefreshService.applyCatalog(c);

        var saved = ConfigService.get("provider.openai.models");
        var savedModel = JsonParser.parseString(saved).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(0.125, savedModel.get("cachedReadPrice").getAsDouble(), 1e-9);
        assertEquals(3.125, savedModel.get("cacheWritePrice").getAsDouble(), 1e-9);
    }

    @Test
    void applyDoesNotOverwriteOperatorSetPrices() {
        // Operator manually set prompt price to 99.99; the refresh must
        // leave it alone even when LiteLLM has a different value.
        seedProviderModels("openai", """
                [{"id":"gpt-4o","promptPrice":99.99}]""");
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));

        var result = PricingRefreshService.applyCatalog(c);
        assertEquals(1, result.modelsUpdated()); // completionPrice was filled
        var saved = ConfigService.get("provider.openai.models");
        var savedModel = JsonParser.parseString(saved).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(99.99, savedModel.get("promptPrice").getAsDouble(), 1e-9);
        assertEquals(10.0, savedModel.get("completionPrice").getAsDouble(), 1e-9);
    }

    @Test
    void applyTreatsExplicitMinusOneAsMissing() {
        // -1 means "unset" in JClaw's price convention; refresh should
        // treat it the same as field-absent and fill it.
        seedProviderModels("openai", """
                [{"id":"gpt-4o","promptPrice":-1,"completionPrice":-1}]""");
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));

        PricingRefreshService.applyCatalog(c);

        var saved = ConfigService.get("provider.openai.models");
        var savedModel = JsonParser.parseString(saved).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(2.5, savedModel.get("promptPrice").getAsDouble(), 1e-9);
        assertEquals(10.0, savedModel.get("completionPrice").getAsDouble(), 1e-9);
    }

    @Test
    void applyPreservesOperatorSetZeroAsKnownFree() {
        // Operator sets 0 deliberately for a known-free model. The refresh
        // must NOT overwrite that with a non-zero LiteLLM value (LiteLLM
        // can be wrong about free tiers, especially for new model IDs).
        seedProviderModels("openai", """
                [{"id":"gpt-4o","promptPrice":0,"completionPrice":0}]""");
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));

        var result = PricingRefreshService.applyCatalog(c);
        assertEquals(0, result.modelsUpdated());
        var saved = ConfigService.get("provider.openai.models");
        var savedModel = JsonParser.parseString(saved).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(0.0, savedModel.get("promptPrice").getAsDouble(), 1e-9);
        assertEquals(0.0, savedModel.get("completionPrice").getAsDouble(), 1e-9);
    }

    @Test
    void applySkipsFreeAndLocalProviders() {
        // ollama-cloud, ollama-local, lm-studio, and loadtest-mock are in
        // the SKIPPED_PROVIDERS set — they're free / local / synthetic and
        // shouldn't appear in LiteLLM lookups.
        seedProviderModels("ollama-cloud", """
                [{"id":"kimi-k2.5"}]""");
        seedProviderModels("ollama-local", """
                [{"id":"kimi-k2.5"}]""");
        seedProviderModels("lm-studio", """
                [{"id":"qwen3-7b"}]""");
        seedProviderModels("loadtest-mock", """
                [{"id":"mock-model"}]""");
        var c = catalog("kimi-k2.5", pricesJson(2.5e-6, 1.0e-5));

        var result = PricingRefreshService.applyCatalog(c);
        assertEquals(0, result.providersScanned());
        assertEquals(0, result.modelsUpdated());

        // Saved models JSON must remain untouched (no promptPrice key
        // sneaked in from the LiteLLM entry above).
        var saved = ConfigService.get("provider.ollama-cloud.models");
        var savedModel = JsonParser.parseString(saved).getAsJsonArray().get(0).getAsJsonObject();
        assertFalse(savedModel.has("promptPrice"));
    }

    @Test
    void applyHandlesProviderWithNoModels() {
        seedProviderModels("openai", "[]");
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));

        var result = PricingRefreshService.applyCatalog(c);
        assertEquals(1, result.providersScanned());
        assertEquals(0, result.modelsUpdated());
    }

    @Test
    void applyHandlesProviderWithCorruptModelsJson() {
        // Defensive: malformed JSON in provider.X.models shouldn't crash the
        // refresh — that provider is skipped and the others still process.
        seedProviderModels("openai", "this-is-not-valid-json");
        seedProviderModels("anthropic", """
                [{"id":"claude-3-5-sonnet"}]""");
        var c = catalog("claude-3-5-sonnet", pricesJson(3.0e-6, 1.5e-5));

        var result = PricingRefreshService.applyCatalog(c);
        assertEquals(2, result.providersScanned());
        assertEquals(1, result.modelsUpdated());
    }

    @Test
    void applyDoesNotMatchModelAbsentFromCatalog() {
        seedProviderModels("openai", """
                [{"id":"unknown-future-model"}]""");
        var c = catalog("gpt-4o", pricesJson(2.5e-6, 1.0e-5));

        var result = PricingRefreshService.applyCatalog(c);
        assertEquals(1, result.providersScanned());
        assertEquals(0, result.modelsUpdated());

        var saved = ConfigService.get("provider.openai.models");
        var savedModel = JsonParser.parseString(saved).getAsJsonArray().get(0).getAsJsonObject();
        assertFalse(savedModel.has("promptPrice"));
    }

    // ─── refresh: toggle gating ──────────────────────────────────────

    @Test
    void refreshSkippedWhenToggleDisabled() {
        ConfigService.set("pricing.refresh.enabled", "false");
        var result = PricingRefreshService.refresh();
        assertTrue(result.skipped());
        assertEquals(0, result.providersScanned());
        assertEquals(0, result.modelsUpdated());
    }

    @Test
    void refreshSkippedWhenToggleAbsent() {
        // Default state: key isn't set at all. Should also skip.
        new Config(); // ensure model class loads under JPA
        ConfigService.set("pricing.refresh.enabled", null);
        var result = PricingRefreshService.refresh();
        assertTrue(result.skipped());
    }

    private void seedProviderModels(String name, String modelsJson) {
        ConfigService.set("provider." + name + ".models", modelsJson);
    }
}
