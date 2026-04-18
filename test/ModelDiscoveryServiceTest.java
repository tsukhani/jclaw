import org.junit.jupiter.api.*;
import play.test.*;
import services.ModelDiscoveryService;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Tests for ModelDiscoveryService static parsing and inference methods.
 * No network calls — exercises the pure-logic helpers only.
 */
public class ModelDiscoveryServiceTest extends UnitTest {

    // --- stripVariant ---

    @Test
    public void stripVariantRemovesSuffix() {
        assertEquals("openai/gpt-4", ModelDiscoveryService.stripVariant("openai/gpt-4:extended"));
    }

    @Test
    public void stripVariantPreservesIdWithoutVariant() {
        assertEquals("openai/gpt-4", ModelDiscoveryService.stripVariant("openai/gpt-4"));
    }

    @Test
    public void stripVariantHandlesMultipleColons() {
        assertEquals("vendor/model", ModelDiscoveryService.stripVariant("vendor/model:v1:extra"));
    }

    // --- stripVersionSuffix ---

    @Test
    public void stripVersionSuffixRemovesDateSuffix() {
        assertEquals("openai/gpt-4", ModelDiscoveryService.stripVersionSuffix("openai/gpt-4-20250101"));
    }

    @Test
    public void stripVersionSuffixRemovesShortSuffix() {
        assertEquals("openai/gpt-4", ModelDiscoveryService.stripVersionSuffix("openai/gpt-4-0125"));
    }

    @Test
    public void stripVersionSuffixPreservesCleanId() {
        assertEquals("openai/gpt-4", ModelDiscoveryService.stripVersionSuffix("openai/gpt-4"));
    }

    // --- detectThinkingSupport ---

    @Test
    public void detectThinkingSupportFromProviderParams() {
        var obj = JsonParser.parseString("""
                {"id": "some-model", "supported_parameters": ["reasoning", "temperature"]}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectThinkingSupport(obj);
        assertTrue(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectThinkingSupportFromProviderParamsNegative() {
        var obj = JsonParser.parseString("""
                {"id": "some-model", "supported_parameters": ["temperature", "top_p"]}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectThinkingSupport(obj);
        assertFalse(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectThinkingSupportFallbackO1() {
        var obj = JsonParser.parseString("""
                {"id": "openai/o1-preview"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectThinkingSupport(obj);
        assertTrue(result.confirmed());
        assertFalse(result.fromProvider());
    }

    @Test
    public void detectThinkingSupportFallbackDeepseekR1() {
        var obj = JsonParser.parseString("""
                {"id": "deepseek/deepseek-r1"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectThinkingSupport(obj);
        assertTrue(result.confirmed());
        assertFalse(result.fromProvider());
    }

    @Test
    public void detectThinkingSupportUnknownModel() {
        var obj = JsonParser.parseString("""
                {"id": "vendor/some-regular-model"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectThinkingSupport(obj);
        assertFalse(result.confirmed());
        assertFalse(result.fromProvider());
    }

    // --- detectVisionSupport ---

    @Test
    public void detectVisionFromOpenRouterModalities() {
        // OpenRouter's /api/v1/models puts input modalities under
        // architecture.input_modalities as an array of tokens.
        var obj = JsonParser.parseString("""
                {"id": "anthropic/claude-sonnet-4-6",
                 "architecture": {"input_modalities": ["text", "image"]}}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectVisionSupport(obj);
        assertTrue(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectVisionFromOpenRouterModalitiesNegative() {
        // Provider explicitly reports text-only input — confirmed absence.
        var obj = JsonParser.parseString("""
                {"id": "vendor/text-only-model",
                 "architecture": {"input_modalities": ["text"]}}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectVisionSupport(obj);
        assertFalse(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectVisionFromLegacyModalityString() {
        // OpenRouter's older shape: a single modality string like "text+image->text".
        var obj = JsonParser.parseString("""
                {"id": "vendor/legacy",
                 "architecture": {"modality": "text+image->text"}}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectVisionSupport(obj);
        assertTrue(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectVisionFromOllamaCapabilities() {
        // Ollama surfaces capabilities via /api/show; some /api/tags variants
        // merge them in. "vision" is the canonical token.
        var obj = JsonParser.parseString("""
                {"id": "llava:13b", "capabilities": ["completion", "vision"]}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectVisionSupport(obj);
        assertTrue(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectVisionFallbackGpt4o() {
        // No architecture, no capabilities — ID heuristic kicks in.
        var obj = JsonParser.parseString("""
                {"id": "openai/gpt-4o"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectVisionSupport(obj);
        assertTrue(result.confirmed());
        assertFalse(result.fromProvider());
    }

    @Test
    public void detectVisionFallbackClaudeSonnet() {
        var obj = JsonParser.parseString("""
                {"id": "anthropic/claude-sonnet-4-6"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectVisionSupport(obj);
        assertTrue(result.confirmed());
        assertFalse(result.fromProvider());
    }

    @Test
    public void detectVisionUnknownModel() {
        var obj = JsonParser.parseString("""
                {"id": "vendor/plain-text-model"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectVisionSupport(obj);
        assertFalse(result.confirmed());
        assertFalse(result.fromProvider());
    }

    // --- detectAudioSupport ---

    @Test
    public void detectAudioFromOpenRouterModalities() {
        var obj = JsonParser.parseString("""
                {"id": "openai/gpt-4o-audio-preview",
                 "architecture": {"input_modalities": ["text", "audio"]}}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAudioSupport(obj);
        assertTrue(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectAudioFallbackGpt4oAudio() {
        var obj = JsonParser.parseString("""
                {"id": "openai/gpt-4o-audio-preview"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAudioSupport(obj);
        assertTrue(result.confirmed());
        assertFalse(result.fromProvider());
    }

    @Test
    public void detectAudioFromOllamaCapabilities() {
        var obj = JsonParser.parseString("""
                {"id": "qwen2-audio:7b", "capabilities": ["completion", "audio"]}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAudioSupport(obj);
        assertTrue(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectAudioUnknownModel() {
        var obj = JsonParser.parseString("""
                {"id": "vendor/text-only"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAudioSupport(obj);
        assertFalse(result.confirmed());
        assertFalse(result.fromProvider());
    }

    // --- inferPrice ---

    @Test
    public void inferPriceWithValidPricing() {
        var obj = JsonParser.parseString("""
                {"pricing": {"prompt": "0.000003", "completion": "0.000015"}}
                """).getAsJsonObject();
        double price = ModelDiscoveryService.inferPrice(obj, "prompt");
        assertEquals(3.0, price, 0.001);
    }

    @Test
    public void inferPriceWithMissingField() {
        var obj = JsonParser.parseString("""
                {"pricing": {"prompt": "0.000003"}}
                """).getAsJsonObject();
        double price = ModelDiscoveryService.inferPrice(obj, "completion");
        assertEquals(-1, price, 0.001);
    }

    @Test
    public void inferPriceWithNoPricingObject() {
        var obj = JsonParser.parseString("""
                {"id": "some-model"}
                """).getAsJsonObject();
        double price = ModelDiscoveryService.inferPrice(obj, "prompt");
        assertEquals(-1, price, 0.001);
    }

    @Test
    public void inferPriceWithNullField() {
        var obj = JsonParser.parseString("""
                {"pricing": {"prompt": null}}
                """).getAsJsonObject();
        double price = ModelDiscoveryService.inferPrice(obj, "prompt");
        assertEquals(-1, price, 0.001);
    }

    // --- parseModels ---

    @Test
    public void parseModelsWithDataArray() {
        var json = JsonParser.parseString("""
                {"data": [
                    {"id": "model-1", "name": "Model One", "context_length": 128000},
                    {"id": "model-2", "name": "Model Two", "context_length": 32000}
                ]}
                """).getAsJsonObject();
        var models = ModelDiscoveryService.parseModels(json);
        assertEquals(2, models.size());
        assertEquals("model-1", models.get(0).get("id"));
        assertEquals("Model One", models.get(0).get("name"));
        assertEquals(128000, models.get(0).get("contextWindow"));
    }

    @Test
    public void parseModelsWithModelsArray() {
        var json = JsonParser.parseString("""
                {"models": [
                    {"id": "alt-model", "context_window": 8000}
                ]}
                """).getAsJsonObject();
        var models = ModelDiscoveryService.parseModels(json);
        assertEquals(1, models.size());
        assertEquals("alt-model", models.get(0).get("id"));
        assertEquals(8000, models.get(0).get("contextWindow"));
    }

    @Test
    public void parseModelsSkipsBlankIds() {
        var json = JsonParser.parseString("""
                {"data": [
                    {"id": "", "name": "No ID"},
                    {"id": "valid", "name": "Valid"}
                ]}
                """).getAsJsonObject();
        var models = ModelDiscoveryService.parseModels(json);
        assertEquals(1, models.size());
        assertEquals("valid", models.get(0).get("id"));
    }

    @Test
    public void parseModelsHandlesEmptyResponse() {
        var json = JsonParser.parseString("""
                {"data": []}
                """).getAsJsonObject();
        var models = ModelDiscoveryService.parseModels(json);
        assertTrue(models.isEmpty());
    }

    @Test
    public void parseModelsHandlesMissingDataAndModels() {
        var json = JsonParser.parseString("""
                {"status": "ok"}
                """).getAsJsonObject();
        var models = ModelDiscoveryService.parseModels(json);
        assertTrue(models.isEmpty());
    }

    @Test
    public void parseModelsInfersNameFromId() {
        var json = JsonParser.parseString("""
                {"data": [{"id": "vendor/model-name"}]}
                """).getAsJsonObject();
        var models = ModelDiscoveryService.parseModels(json);
        assertEquals(1, models.size());
        assertEquals("model-name", models.get(0).get("name"));
    }
}
