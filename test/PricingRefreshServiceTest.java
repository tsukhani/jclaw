import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
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

    // --- private helper branch coverage (reflection) ---

    private static double callExtractPerMillion(com.google.gson.JsonObject entry, String key)
            throws Exception {
        var m = PricingRefreshService.class.getDeclaredMethod(
                "extractPerMillion", com.google.gson.JsonObject.class, String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, entry, key);
    }

    private static boolean callFillIfMissing(com.google.gson.JsonObject model, String field, double value)
            throws Exception {
        var m = PricingRefreshService.class.getDeclaredMethod(
                "fillIfMissing", com.google.gson.JsonObject.class, String.class, double.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, model, field, value);
    }

    @Test
    void extractPerMillionReturnsNegativeOneForMissingKey() throws Exception {
        var entry = new com.google.gson.JsonObject();
        assertEquals(-1.0, callExtractPerMillion(entry, "absent"), 0.0001);
    }

    @Test
    void extractPerMillionReturnsNegativeOneForNullValue() throws Exception {
        var entry = new com.google.gson.JsonObject();
        entry.add("k", com.google.gson.JsonNull.INSTANCE);
        assertEquals(-1.0, callExtractPerMillion(entry, "k"), 0.0001);
    }

    @Test
    void extractPerMillionConvertsPerTokenToPerMillion() throws Exception {
        var entry = new com.google.gson.JsonObject();
        entry.addProperty("input_cost_per_token", 0.000003);
        assertEquals(3.0, callExtractPerMillion(entry, "input_cost_per_token"), 0.0001);
    }

    @Test
    void fillIfMissingReturnsFalseForNegativeNewValue() throws Exception {
        var model = new com.google.gson.JsonObject();
        assertFalse(callFillIfMissing(model, "promptPrice", -1.0));
        assertFalse(model.has("promptPrice"));
    }

    @Test
    void fillIfMissingPersistsWhenFieldAbsent() throws Exception {
        var model = new com.google.gson.JsonObject();
        assertTrue(callFillIfMissing(model, "promptPrice", 2.5));
        assertEquals(2.5, model.get("promptPrice").getAsDouble(), 0.0001);
    }

    @Test
    void fillIfMissingPreservesExistingNonSentinelValue() throws Exception {
        // Operator-set value (anything other than -1) must survive.
        var model = new com.google.gson.JsonObject();
        model.addProperty("promptPrice", 5.0);
        assertFalse(callFillIfMissing(model, "promptPrice", 99.0));
        assertEquals(5.0, model.get("promptPrice").getAsDouble(), 0.0001);
    }

    @Test
    void fillIfMissingOverwritesSentinelValue() throws Exception {
        // -1 sentinel means "no value yet" → overwrite with the new value.
        var model = new com.google.gson.JsonObject();
        model.addProperty("promptPrice", -1.0);
        assertTrue(callFillIfMissing(model, "promptPrice", 4.2));
        assertEquals(4.2, model.get("promptPrice").getAsDouble(), 0.0001);
    }

    // ─── JCLAW-1077: capabilities from the same catalogue ─────────────

    /** LiteLLM capability shape. Unknown flags are null there, not false. */
    private static String capsJson(String... flags) {
        var sb = new StringBuilder("{\"input_cost_per_token\":1.0e-6");
        for (var f : flags) sb.append(",\"").append(f).append("\":true");
        return sb.append('}').toString();
    }

    private static JsonObject savedModel(String provider) {
        return JsonParser.parseString(ConfigService.get("provider." + provider + ".models"))
                .getAsJsonArray().get(0).getAsJsonObject();
    }

    @Test
    void fillsVisionThinkingAndAudioFromTheCatalog() {
        seedProviderModels("together", """
                [{"id":"moonshotai/Kimi-K2.5","supportsVision":false,"supportsThinking":false}]""");
        var c = catalog("together_ai/moonshotai/Kimi-K2.5",
                capsJson("supports_vision", "supports_reasoning", "supports_audio_input"));

        PricingRefreshService.applyCatalog(c);

        var m = savedModel("together");
        assertTrue(m.get("supportsVision").getAsBoolean());
        assertTrue(m.get("supportsThinking").getAsBoolean());
        assertTrue(m.get("supportsAudio").getAsBoolean());
    }

    @Test
    void neverClearsACapabilityTheProviderAlreadyReported() {
        // Upward only: a community catalogue may add to what the provider said,
        // never contradict it.
        seedProviderModels("together", """
                [{"id":"some-model","supportsVision":true,"supportsThinking":true}]""");
        var c = catalog("some-model", capsJson()); // no capability flags at all

        PricingRefreshService.applyCatalog(c);

        var m = savedModel("together");
        assertTrue(m.get("supportsVision").getAsBoolean(), "vision survived");
        assertTrue(m.get("supportsThinking").getAsBoolean(), "thinking survived");
    }

    @Test
    void neverOverridesAnExplicitToolsAnswer() {
        // A stored false is the operator's checkbox or a negative learned from a
        // real provider rejection (JCLAW-1076) — not a community file's to undo.
        seedProviderModels("together", """
                [{"id":"chatonly","supportsTools":false}]""");
        var c = catalog("chatonly", capsJson("supports_function_calling"));

        PricingRefreshService.applyCatalog(c);

        assertFalse(savedModel("together").get("supportsTools").getAsBoolean(),
                "an authoritative no is not overturned by the catalogue");
    }

    @Test
    void makesAnImplicitToolsYesExplicit() {
        seedProviderModels("together", """
                [{"id":"agentic"}]""");
        var c = catalog("agentic", capsJson("supports_function_calling"));

        PricingRefreshService.applyCatalog(c);

        assertTrue(savedModel("together").get("supportsTools").getAsBoolean());
    }

    @Test
    void matchesCapabilitiesAcrossHostsButNotPrices() {
        // The real shape of the Together miss: LiteLLM keys Kimi under other
        // hosts and in lower case, so the strict chain finds nothing. Capability
        // belongs to the model and may cross hosts; price belongs to the host
        // and must not.
        seedProviderModels("together", """
                [{"id":"moonshotai/Kimi-K2.6"}]""");
        var c = catalog("azure_ai/kimi-k2.6",
                capsJson("supports_vision", "supports_reasoning", "supports_function_calling"));

        PricingRefreshService.applyCatalog(c);

        var m = savedModel("together");
        assertTrue(m.get("supportsVision").getAsBoolean(), "capability crossed hosts");
        assertTrue(m.get("supportsThinking").getAsBoolean());
        assertFalse(m.has("promptPrice"),
                "Azure's price must not be imported for a model served by Together");
    }

    @Test
    void leavesAModelTheCatalogDoesNotCoverAlone() {
        seedProviderModels("together", """
                [{"id":"unknown/Model-X","supportsVision":false}]""");
        var c = catalog("something-else", capsJson("supports_vision"));

        PricingRefreshService.applyCatalog(c);

        var m = savedModel("together");
        assertFalse(m.get("supportsVision").getAsBoolean(), "no capability invented");
        assertFalse(m.has("supportsTools"));
    }
}
