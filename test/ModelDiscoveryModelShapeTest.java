import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ModelDiscoveryService;

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
            "promptPrice", "completionPrice", "cachedReadPrice", "cacheWritePrice",
            "isFree");

    @Test
    void parseModelsEmitsFullKeySetIncludingVideo() {
        var json = JsonParser.parseString("""
                {"data":[{"id":"vendor/model","context_length":128000}]}
                """).getAsJsonObject();
        var model = ModelDiscoveryService.parseModels(json).get(0);
        assertEquals(EXPECTED_KEYS, model.keySet());
    }

    @Test
    void parseLmStudioNativeResponseEmitsFullKeySetIncludingVideo() {
        var json = JsonParser.parseString("""
                {"data":[{"id":"qwen3-32b","type":"llm","max_context_length":131072}]}
                """).getAsJsonObject();
        var model = ModelDiscoveryService.parseLmStudioNativeResponse(json).get(0);
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
        var model = ModelDiscoveryService.parseOllamaShow("glm-5", json);
        assertEquals(EXPECTED_KEYS, model.keySet());
        assertEquals(false, model.get("supportsVideo"));
        assertEquals(false, model.get("videoDetectedFromProvider"));
    }
}
