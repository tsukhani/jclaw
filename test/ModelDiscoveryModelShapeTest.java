import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.discovery.ModelCatalogParser;

import java.util.Set;

/**
 * JCLAW-827: the three discovery parsers (parseModels, parseLmStudioNativeResponse,
 * parseOllamaShow) used to build their normalized model maps independently, so the
 * LM Studio and Ollama paths had silently dropped the {@code supportsVideo} /
 * {@code videoDetectedFromProvider} keys the OpenAI-compat path emits. They now all
 * project through the shared {@code ModelInfo} shape; these tests pin that every
 * parser emits the same key set, so the paths can't diverge again.
 */
class ModelDiscoveryModelShapeTest extends UnitTest {

    // The full normalized key set every parser must emit.
    private static final Set<String> EXPECTED_KEYS = Set.of(
            "id", "name", "contextWindow", "maxTokens",
            "supportsThinking", "thinkingDetectedFromProvider",
            "alwaysThinks", "alwaysThinksDetectedFromProvider",
            "supportsVision", "visionDetectedFromProvider",
            "supportsAudio", "audioDetectedFromProvider",
            "supportsVideo", "videoDetectedFromProvider",
            "supportsTools", "toolsDetectedFromProvider",
            "promptPrice", "completionPrice", "cachedReadPrice", "cacheWritePrice",
            "isFree");

    @Test
    void parseModelsEmitsFullKeySetIncludingVideo() {
        var json = JsonParser.parseString("""
                {"data":[{"id":"vendor/model","context_length":128000}]}
                """).getAsJsonObject();
        var model = ModelCatalogParser.parseModels(json).get(0);
        assertEquals(EXPECTED_KEYS, model.keySet());
    }

    @Test
    void parseLmStudioNativeResponseEmitsFullKeySetIncludingVideo() {
        var json = JsonParser.parseString("""
                {"data":[{"id":"qwen3-32b","type":"llm","max_context_length":131072}]}
                """).getAsJsonObject();
        var model = ModelCatalogParser.parseLmStudioNativeResponse(json).get(0);
        assertEquals(EXPECTED_KEYS, model.keySet());
        // The previously-dropped video keys are present and default to false.
        assertEquals(false, model.get("supportsVideo"));
        assertEquals(false, model.get("videoDetectedFromProvider"));
    }

    @Test
    void parseOllamaShowEmitsFullKeySetIncludingVideo() {
        var json = JsonParser.parseString("""
                {"model_info":{"glm.context_length":131072},"capabilities":["completion"]}
                """).getAsJsonObject();
        var model = ModelCatalogParser.parseOllamaShow("glm-5", json);
        assertEquals(EXPECTED_KEYS, model.keySet());
        assertEquals(false, model.get("supportsVideo"));
        assertEquals(false, model.get("videoDetectedFromProvider"));
    }

    // ── JCLAW-1074: tool-calling capability ──────────────────────────────

    @Test
    void ollamaCapabilitiesWithoutToolsMarkTheModelToolIncapable() {
        // The dolphin3:8b shape: chat-capable, no tools. Sending a tools array
        // to this model is the 400 that made chat-only models unusable.
        var json = JsonParser.parseString("""
                {"model_info":{"llama.context_length":131072},"capabilities":["completion"]}
                """).getAsJsonObject();
        var model = ModelCatalogParser.parseOllamaShow("dolphin3:8b", json);
        assertEquals(false, model.get("supportsTools"));
        assertEquals(true, model.get("toolsDetectedFromProvider"),
                "a capability list that omits tools is an authoritative no");
    }

    @Test
    void ollamaCapabilitiesWithToolsMarkTheModelToolCapable() {
        var json = JsonParser.parseString("""
                {"model_info":{"qwen3.context_length":40960},"capabilities":["completion","tools"]}
                """).getAsJsonObject();
        var model = ModelCatalogParser.parseOllamaShow("qwen3:8b", json);
        assertEquals(true, model.get("supportsTools"));
        assertEquals(true, model.get("toolsDetectedFromProvider"));
    }

    @Test
    void aProviderThatReportsNoCapabilitiesLeavesToolsEnabled() {
        // Unknown means supported — the inverse of every other detector. Guessing
        // "no tools" here would disarm every agent on providers that publish no
        // capability list, which is most of them.
        var json = JsonParser.parseString("""
                {"data":[{"id":"some-cloud-model"}]}
                """).getAsJsonObject();
        var model = ModelCatalogParser.parseModels(json).get(0);
        assertEquals(true, model.get("supportsTools"));
        assertEquals(false, model.get("toolsDetectedFromProvider"),
                "defaulted, not reported — the UI must not lock this one");
    }
}
