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

    // --- detectAlwaysThinks ---
    // No provider exposes "thinking is mandatory" in metadata except
    // OpenRouter's architecture.instruct_type for the R1 family. Everywhere
    // else we rely on tight id-pattern matching.

    @Test
    public void detectAlwaysThinksFromOpenRouterInstructType() {
        // The single provider-surfaced signal we get for "always thinks."
        var obj = JsonParser.parseString("""
                {"id": "deepseek/deepseek-r1",
                 "architecture": {"instruct_type": "deepseek-r1"}}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAlwaysThinks(obj);
        assertTrue(result.confirmed());
        assertTrue(result.fromProvider());
    }

    @Test
    public void detectAlwaysThinksInstructTypeIsCaseInsensitive() {
        var obj = JsonParser.parseString("""
                {"id": "deepseek/deepseek-r1",
                 "architecture": {"instruct_type": "DeepSeek-R1"}}
                """).getAsJsonObject();
        assertTrue(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed());
    }

    @Test
    public void detectAlwaysThinksMatchesO1Family() {
        for (var id : java.util.List.of(
                "o1", "o1-mini", "o1-pro", "o1-preview",
                "openai/o1", "openai/o1-mini", "openai/o1-preview"
        )) {
            var obj = JsonParser.parseString("{\"id\": \"" + id + "\"}").getAsJsonObject();
            var result = ModelDiscoveryService.detectAlwaysThinks(obj);
            assertTrue(result.confirmed(), id + " should match");
            assertFalse(result.fromProvider(), id + " is name-pattern, not provider");
        }
    }

    @Test
    public void detectAlwaysThinksMatchesO3Family() {
        for (var id : java.util.List.of("o3", "o3-mini", "o3-pro", "openai/o3-mini")) {
            var obj = JsonParser.parseString("{\"id\": \"" + id + "\"}").getAsJsonObject();
            assertTrue(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed(),
                    id + " should match");
        }
    }

    @Test
    public void detectAlwaysThinksMatchesO4Mini() {
        var obj = JsonParser.parseString("{\"id\": \"openai/o4-mini\"}").getAsJsonObject();
        assertTrue(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed());
    }

    @Test
    public void detectAlwaysThinksMatchesDeepseekR1Variants() {
        for (var id : java.util.List.of(
                "deepseek-r1", "deepseek-ai/deepseek-r1",
                "deepseek-r1:latest", "deepseek-r1-distill-llama-70b"
        )) {
            var obj = JsonParser.parseString("{\"id\": \"" + id + "\"}").getAsJsonObject();
            assertTrue(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed(),
                    id + " should match");
        }
    }

    @Test
    public void detectAlwaysThinksMatchesQwq() {
        for (var id : java.util.List.of("qwq", "qwen/qwq-32b", "qwq:latest")) {
            var obj = JsonParser.parseString("{\"id\": \"" + id + "\"}").getAsJsonObject();
            assertTrue(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed(),
                    id + " should match");
        }
    }

    @Test
    public void detectAlwaysThinksRejectsClaudeOpus() {
        // Critical regression guard: "claude-opus-4-1" contains "o" and a
        // digit, but must NOT match the o-series pattern. The tight regex
        // requires the o-token to start the id-component.
        for (var id : java.util.List.of(
                "claude-opus-4-1", "anthropic/claude-opus-4-1",
                "claude-opus-4", "claude-haiku-4-1"
        )) {
            var obj = JsonParser.parseString("{\"id\": \"" + id + "\"}").getAsJsonObject();
            assertFalse(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed(),
                    id + " must not match");
        }
    }

    @Test
    public void detectAlwaysThinksRejectsGpt4o() {
        // gpt-4o contains "o" but is hybrid (and uses GPT-4o-style audio,
        // not o-series reasoning architecture).
        for (var id : java.util.List.of("gpt-4o", "gpt-4o-mini", "openai/gpt-4o")) {
            var obj = JsonParser.parseString("{\"id\": \"" + id + "\"}").getAsJsonObject();
            assertFalse(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed(),
                    id + " must not match");
        }
    }

    @Test
    public void detectAlwaysThinksRejectsHybridReasoners() {
        // Hybrid models that DO support thinking but can be turned off —
        // the toggle is real for these, so they must NOT be locked.
        for (var id : java.util.List.of(
                "openai/gpt-5", "openai/gpt-5-mini",
                "anthropic/claude-sonnet-4-7",
                "google/gemini-2.5-pro", "google/gemini-3-flash-preview",
                "qwen/qwen3-32b", "deepseek/deepseek-v3",
                "kimi-k2.5"
        )) {
            var obj = JsonParser.parseString("{\"id\": \"" + id + "\"}").getAsJsonObject();
            assertFalse(ModelDiscoveryService.detectAlwaysThinks(obj).confirmed(),
                    id + " is hybrid, must not match");
        }
    }

    @Test
    public void detectAlwaysThinksUnknownModel() {
        var obj = JsonParser.parseString("""
                {"id": "vendor/some-regular-model"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAlwaysThinks(obj);
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

    @Test
    public void detectAudioFallbackAudioPreviewSuffix() {
        // JCLAW-160: OpenAI's /v1/models endpoint returns plain entries
        // without modality metadata, so the id heuristic is the only
        // signal. The "-audio-preview" suffix is OpenAI's stable naming
        // convention for audio-capable models — match it generically so
        // future variants (gpt-4o-mini-audio-preview, gpt-5-audio-preview)
        // are flagged without per-version updates.
        var obj = JsonParser.parseString("""
                {"id": "gpt-4o-mini-audio-preview"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAudioSupport(obj);
        assertTrue(result.confirmed(),
                "audio-preview suffix must be detected even with -mini- in the middle");
        assertFalse(result.fromProvider());
    }

    @Test
    public void detectAudioPreviewMatchRequiresLeadingDash() {
        // JCLAW-160 review follow-up: the audio-preview match must be
        // anchored on a leading dash. A hypothetical model whose id
        // begins with "audio-preview" (no preceding dash) — e.g. an
        // unrelated vendor naming a classifier with that prefix — must
        // not false-positive. Pin the contract so a future maintainer
        // can't quietly drop the dash and re-broaden the match.
        var obj = JsonParser.parseString("""
                {"id": "audio-preview-classifier"}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectAudioSupport(obj);
        assertFalse(result.confirmed(),
                "non-dash-prefixed audio-preview substring must not trip the detector");
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

    // Suppressed S125 (commented-out code) — the doc comment below is real
    // documentation, but Sonar's heuristic misreads it as code because the
    // prose contains method-call syntax (.getAsJsonObject()) and type names
    // (JsonElement). Reword and you lose the explanation; suppress and keep
    // the explanation. Localized to this method so other commented-out
    // code in this file would still get flagged.
    @SuppressWarnings("java:S125")
    @Test
    public void parseModelsHandlesBareJsonArray() {
        // Together AI returns /v1/models as a bare array (no {data: ...}
        // wrapper). The OpenAI-compat discovery path used to throw
        // IllegalStateException on .getAsJsonObject() and surface as a
        // 502; parseModels now accepts JsonElement and routes both shapes.
        var json = JsonParser.parseString("""
                [
                    {"id": "moonshotai/Kimi-K2.5", "context_length": 262144},
                    {"id": "meta-llama/Llama-3.3-70B-Instruct-Turbo", "context_length": 131072}
                ]
                """);
        var models = ModelDiscoveryService.parseModels(json);
        assertEquals(2, models.size());
        assertEquals("moonshotai/Kimi-K2.5", models.get(0).get("id"));
        assertEquals(262144, models.get(0).get("contextWindow"));
        assertEquals("meta-llama/Llama-3.3-70B-Instruct-Turbo", models.get(1).get("id"));
    }

    @Test
    public void parseModelsHandlesNullInput() {
        // Defensive: parseModels never throws on null/JsonNull, just returns
        // empty so callers see "0 models" rather than crashing through a NPE.
        assertTrue(ModelDiscoveryService.parseModels(null).isEmpty());
        assertTrue(ModelDiscoveryService.parseModels(com.google.gson.JsonNull.INSTANCE).isEmpty());
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

    // ─── Ollama native discovery (JCLAW-118) ─────────────────────────

    @Test
    public void stripV1SuffixRemovesTrailingV1() {
        assertEquals("https://ollama.com", ModelDiscoveryService.stripV1Suffix("https://ollama.com/v1"));
        assertEquals("https://ollama.com", ModelDiscoveryService.stripV1Suffix("https://ollama.com/v1/"));
        assertEquals("http://localhost:11434", ModelDiscoveryService.stripV1Suffix("http://localhost:11434/v1"));
    }

    @Test
    public void stripV1SuffixLeavesUrlsWithoutV1Untouched() {
        assertEquals("https://ollama.com", ModelDiscoveryService.stripV1Suffix("https://ollama.com"));
        assertEquals("https://example.com/api", ModelDiscoveryService.stripV1Suffix("https://example.com/api"));
        assertEquals("", ModelDiscoveryService.stripV1Suffix(null));
    }

    @Test
    public void extractTagIdsPullsNamesFromModelsArray() {
        var json = JsonParser.parseString("""
                {"models":[
                  {"name":"kimi-k2.5","model":"kimi-k2.5"},
                  {"name":"gpt-oss:20b","model":"gpt-oss:20b"},
                  {"name":"glm-5"}
                ]}
                """).getAsJsonObject();
        var ids = ModelDiscoveryService.extractTagIds(json);
        assertEquals(3, ids.size());
        assertEquals("kimi-k2.5", ids.get(0));
        assertEquals("gpt-oss:20b", ids.get(1));
        assertEquals("glm-5", ids.get(2));
    }

    @Test
    public void extractTagIdsFallsBackToModelKey() {
        // Malformed entry with only "model" and not "name" — still usable.
        var json = JsonParser.parseString("""
                {"models":[{"model":"qwen3-next:80b"}]}
                """).getAsJsonObject();
        var ids = ModelDiscoveryService.extractTagIds(json);
        assertEquals(1, ids.size());
        assertEquals("qwen3-next:80b", ids.get(0));
    }

    @Test
    public void extractTagIdsReturnsEmptyWhenModelsKeyMissing() {
        var json = JsonParser.parseString("{}").getAsJsonObject();
        assertTrue(ModelDiscoveryService.extractTagIds(json).isEmpty());
    }

    @Test
    public void extractOllamaContextLengthScansForFamilyPrefixedKey() {
        // Real /api/show shape: family is "kimi-k2", context_length lives under "kimi-k2.context_length".
        var json = JsonParser.parseString("""
                {
                  "model_info": {
                    "general.architecture": "kimi-k2",
                    "general.parameter_count": 1042000000000,
                    "kimi-k2.context_length": 262144,
                    "kimi-k2.embedding_length": 2048
                  }
                }
                """).getAsJsonObject();
        assertEquals(262144, ModelDiscoveryService.extractOllamaContextLength(json));
    }

    @Test
    public void extractOllamaContextLengthHandlesDifferentFamilies() {
        // Make sure the scan isn't hardcoded to kimi — any family prefix works.
        var json = JsonParser.parseString("""
                {"model_info": {"glm.context_length": 202752}}
                """).getAsJsonObject();
        assertEquals(202752, ModelDiscoveryService.extractOllamaContextLength(json));
    }

    @Test
    public void extractOllamaContextLengthReturnsZeroWhenMissing() {
        var json = JsonParser.parseString("""
                {"model_info": {"general.architecture": "mystery"}}
                """).getAsJsonObject();
        assertEquals(0, ModelDiscoveryService.extractOllamaContextLength(json));
    }

    @Test
    public void extractOllamaContextLengthReturnsZeroWhenNoModelInfo() {
        assertEquals(0, ModelDiscoveryService.extractOllamaContextLength(JsonParser.parseString("{}").getAsJsonObject()));
    }

    @Test
    public void parseOllamaShowPopulatesContextAndCapabilities() {
        // Mirrors the live /api/show response for kimi-k2.5 as of 2026-04-22.
        var json = JsonParser.parseString("""
                {
                  "details": {"family": "kimi-k2", "parameter_size": "1042000000000"},
                  "model_info": {
                    "general.architecture": "kimi-k2",
                    "kimi-k2.context_length": 262144
                  },
                  "capabilities": ["vision", "thinking", "completion", "tools"]
                }
                """).getAsJsonObject();
        var model = ModelDiscoveryService.parseOllamaShow("kimi-k2.5", json);

        assertEquals("kimi-k2.5", model.get("id"));
        assertEquals("kimi-k2.5", model.get("name"));
        assertEquals(262144, model.get("contextWindow"));
        assertEquals(true, model.get("supportsThinking"));
        assertEquals(true, model.get("thinkingDetectedFromProvider"));
        assertEquals(true, model.get("supportsVision"));
        assertEquals(true, model.get("visionDetectedFromProvider"));
        assertEquals(false, model.get("supportsAudio"));
        assertEquals(true, model.get("audioDetectedFromProvider"));
    }

    @Test
    public void parseOllamaShowHandlesAbsentCapabilities() {
        // A model with no capabilities array — all flags fall back to
        // detector defaults (id-based heuristic, or absent).
        var json = JsonParser.parseString("""
                {"model_info": {"mystery.context_length": 32768}}
                """).getAsJsonObject();
        var model = ModelDiscoveryService.parseOllamaShow("mystery-model", json);
        assertEquals(32768, model.get("contextWindow"));
        assertEquals(false, model.get("supportsThinking"));
        assertEquals(false, model.get("thinkingDetectedFromProvider"));
    }

    @Test
    public void parseOllamaShowReportsUnknownContextAsZero() {
        var json = JsonParser.parseString("""
                {"capabilities": ["completion"]}
                """).getAsJsonObject();
        var model = ModelDiscoveryService.parseOllamaShow("unknown-model", json);
        assertEquals(0, model.get("contextWindow"));
    }

    @Test
    public void detectThinkingSupportPicksUpOllamaCapabilities() {
        // Regression guard: the Ollama capabilities path has to live
        // alongside the OpenRouter supported_parameters path without
        // stealing precedence.
        var json = JsonParser.parseString("""
                {"id": "glm-5", "capabilities": ["thinking", "completion"]}
                """).getAsJsonObject();
        var result = ModelDiscoveryService.detectThinkingSupport(json);
        assertTrue(result.confirmed(), "thinking should be detected from capabilities array");
        assertTrue(result.fromProvider(), "detection should be marked as provider-confirmed");
    }

    // --- JCLAW-183: Ollama embedding-only filter via parseOllamaShow ---

    @Test
    public void parseOllamaShowReturnsNullForEmbeddingOnlyModel() {
        // Capabilities array contains "embedding" but not "completion" —
        // the model can't serve chat. parseOllamaShow returns null so
        // discoverOllamaNative drops the entry.
        var json = JsonParser.parseString("""
                {
                  "model_info": {"nomic-bert.context_length": 2048},
                  "capabilities": ["embedding"]
                }
                """).getAsJsonObject();
        var model = ModelDiscoveryService.parseOllamaShow("nomic-embed-text:latest", json);
        assertNull(model, "embedding-only Ollama model should be filtered out");
    }

    @Test
    public void parseOllamaShowKeepsModelWithEmptyCapabilitiesArray() {
        // Empty capabilities array = no signal. Treat as unknown and let
        // the model through; downstream UI / id-heuristic decides what
        // capabilities to mark.
        var json = JsonParser.parseString("""
                {"model_info": {"family.context_length": 8192}, "capabilities": []}
                """).getAsJsonObject();
        var model = ModelDiscoveryService.parseOllamaShow("opaque-model", json);
        assertNotNull(model, "empty capabilities array must not trigger the filter");
        assertEquals(8192, model.get("contextWindow"));
    }

    // --- JCLAW-183: LM Studio native /api/v0/models parsing ---

    @Test
    public void parseLmStudioNativeResponseFiltersOutNonChatTypes() {
        // Mirrors the live /api/v0/models response shape. type field is
        // authoritative — keep llm and vlm, drop embeddings/tts/stt.
        var json = JsonParser.parseString("""
                {
                  "data": [
                    {"id": "zai-org/glm-4.7-flash", "type": "llm", "max_context_length": 202752},
                    {"id": "google/gemma-4-e4b", "type": "vlm", "max_context_length": 131072},
                    {"id": "openai/gpt-oss-20b", "type": "llm", "max_context_length": 131072},
                    {"id": "text-embedding-nomic-embed-text-v1.5", "type": "embeddings"},
                    {"id": "kokoro-tts", "type": "tts"},
                    {"id": "whisper-base", "type": "stt"}
                  ]
                }
                """).getAsJsonObject();

        var models = ModelDiscoveryService.parseLmStudioNativeResponse(json);

        assertEquals(3, models.size(), "should keep 3 chat-capable models, drop 3 non-chat");
        var ids = models.stream().map(m -> m.get("id").toString()).toList();
        assertTrue(ids.contains("zai-org/glm-4.7-flash"));
        assertTrue(ids.contains("google/gemma-4-e4b"));
        assertTrue(ids.contains("openai/gpt-oss-20b"));
        assertFalse(ids.contains("text-embedding-nomic-embed-text-v1.5"));
        assertFalse(ids.contains("kokoro-tts"));
        assertFalse(ids.contains("whisper-base"));
    }

    @Test
    public void parseLmStudioNativeResponseMarksVlmAsVisionFromProvider() {
        // type "vlm" is authoritative for vision support — both the boolean
        // flag and the from-provider marker must reflect it so the UI can
        // lock the vision checkbox without falling through to id heuristics.
        var json = JsonParser.parseString("""
                {"data": [{"id": "google/gemma-4-e4b", "type": "vlm", "max_context_length": 131072}]}
                """).getAsJsonObject();

        var models = ModelDiscoveryService.parseLmStudioNativeResponse(json);

        assertEquals(1, models.size());
        var m = models.get(0);
        assertEquals(true, m.get("supportsVision"));
        assertEquals(true, m.get("visionDetectedFromProvider"));
        assertEquals(131072, m.get("contextWindow"));
    }

    @Test
    public void parseLmStudioNativeResponseLeavesThinkingAndAudioToHeuristic() {
        // type "llm" tells us nothing about thinking or audio — leave
        // fromProvider=false so the existing id-based heuristic in
        // detectThinkingSupport / detectAudioSupport can still kick in
        // for known families (deepseek-r1, whisper, etc.) downstream.
        var json = JsonParser.parseString("""
                {"data": [{"id": "openai/gpt-oss-20b", "type": "llm"}]}
                """).getAsJsonObject();

        var models = ModelDiscoveryService.parseLmStudioNativeResponse(json);

        assertEquals(1, models.size());
        var m = models.get(0);
        assertEquals(false, m.get("supportsThinking"));
        assertEquals(false, m.get("thinkingDetectedFromProvider"));
        assertEquals(false, m.get("supportsAudio"));
        assertEquals(false, m.get("audioDetectedFromProvider"));
    }

    @Test
    public void parseLmStudioNativeResponseSkipsEntriesWithBlankId() {
        var json = JsonParser.parseString("""
                {"data": [{"id": "", "type": "llm"}, {"type": "llm"}, {"id": "valid", "type": "llm"}]}
                """).getAsJsonObject();
        var models = ModelDiscoveryService.parseLmStudioNativeResponse(json);
        assertEquals(1, models.size());
        assertEquals("valid", models.get(0).get("id"));
    }

    @Test
    public void parseLmStudioNativeResponseHandlesMissingDataArray() {
        var json = JsonParser.parseString("{}").getAsJsonObject();
        var models = ModelDiscoveryService.parseLmStudioNativeResponse(json);
        assertTrue(models.isEmpty());
    }
}
