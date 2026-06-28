package services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import play.Logger;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.Strings;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Model catalog discovery from LLM provider APIs. Handles fetching, parsing,
 * normalizing, and ranking models from OpenAI-compatible endpoints.
 *
 * <p>Extracted from ApiProvidersController to separate domain logic (JSON parsing,
 * inference heuristics, leaderboard ranking) from HTTP request handling.
 */
public class ModelDiscoveryService {

    private ModelDiscoveryService() {}

    private static final Pattern HREF_PATTERN = Pattern.compile(
            "href=\"/([a-zA-Z0-9][-a-zA-Z0-9._]*/[a-zA-Z0-9][-a-zA-Z0-9._:]*?)\"");
    private static final Pattern USAGE_PATTERN = Pattern.compile(
            "\"([a-zA-Z0-9_-]+/[a-zA-Z0-9._:-]+)\"\\s*:\\s*\\d");

    private static final int DISCOVER_TIMEOUT_SECONDS = 15;
    private static final int LEADERBOARD_TIMEOUT_SECONDS = 10;

    // --- Normalized model-map keys (returned to the frontend) ---
    private static final String KEY_ID = "id";
    private static final String KEY_NAME = "name";
    private static final String KEY_CONTEXT_WINDOW = "contextWindow";
    private static final String KEY_MAX_TOKENS = "maxTokens";
    private static final String KEY_SUPPORTS_THINKING = "supportsThinking";
    private static final String KEY_THINKING_DETECTED_FROM_PROVIDER = "thinkingDetectedFromProvider";
    private static final String KEY_ALWAYS_THINKS = "alwaysThinks";
    private static final String KEY_ALWAYS_THINKS_DETECTED_FROM_PROVIDER = "alwaysThinksDetectedFromProvider";
    private static final String KEY_SUPPORTS_VISION = "supportsVision";
    private static final String KEY_VISION_DETECTED_FROM_PROVIDER = "visionDetectedFromProvider";
    private static final String KEY_SUPPORTS_AUDIO = "supportsAudio";
    private static final String KEY_AUDIO_DETECTED_FROM_PROVIDER = "audioDetectedFromProvider";
    private static final String KEY_SUPPORTS_VIDEO = "supportsVideo";
    private static final String KEY_VIDEO_DETECTED_FROM_PROVIDER = "videoDetectedFromProvider";
    private static final String KEY_PROMPT_PRICE = "promptPrice";
    private static final String KEY_COMPLETION_PRICE = "completionPrice";
    private static final String KEY_CACHED_READ_PRICE = "cachedReadPrice";
    private static final String KEY_CACHE_WRITE_PRICE = "cacheWritePrice";
    private static final String KEY_IS_FREE = "isFree";
    private static final String KEY_LEADERBOARD_RANK = "leaderboardRank";

    // --- Provider JSON field names ---
    private static final String FIELD_MODELS = "models";
    private static final String FIELD_DATA = "data";
    private static final String FIELD_ARCHITECTURE = "architecture";
    private static final String FIELD_INSTRUCT_TYPE = "instruct_type";
    private static final String FIELD_CAPABILITIES = "capabilities";
    private static final String FIELD_SUPPORTED_PARAMETERS = "supported_parameters";
    private static final String FIELD_INPUT_MODALITIES = "input_modalities";
    private static final String FIELD_MODALITY = "modality";
    private static final String FIELD_CONTEXT_LENGTH = "context_length";
    private static final String FIELD_CONTEXT_WINDOW = "context_window";
    private static final String FIELD_TOP_PROVIDER = "top_provider";
    private static final String FIELD_MAX_COMPLETION_TOKENS = "max_completion_tokens";
    private static final String FIELD_MAX_TOKENS = "max_tokens";
    private static final String FIELD_MAX_CONTEXT_LENGTH = "max_context_length";
    private static final String FIELD_MODEL_INFO = "model_info";
    private static final String FIELD_PRICING = "pricing";
    private static final String FIELD_PROMPT = "prompt";
    private static final String FIELD_COMPLETION = "completion";
    // Together AI's /v1/models pricing keys — distinct from OpenRouter's
    // prompt/completion, and quoted in dollars-per-million (not per-token).
    private static final String FIELD_TOGETHER_INPUT = "input";
    private static final String FIELD_TOGETHER_OUTPUT = "output";
    private static final String FIELD_TOGETHER_CACHED_INPUT = "cached_input";
    private static final String TYPE_INPUT_CACHE_READ = "input_cache_read";

    // --- Value literals ---
    private static final String INSTRUCT_TYPE_DEEPSEEK_R1 = "deepseek-r1";

    /** Result of a model discovery call. */
    public sealed interface DiscoveryResult {
        record Ok(List<Map<String, Object>> models) implements DiscoveryResult {}
        record Error(int statusCode, String message) implements DiscoveryResult {}
    }

    // Provider model catalogs change on the order of days, so the read-heavy
    // page-load paths (e.g. the Settings video-model dropdown, ~1.3s/call) cache
    // discovery for 10 minutes rather than hitting the provider on every load.
    // Keyed by name+baseUrl+apiKey so a config change re-discovers immediately;
    // only Ok results are cached, so an error/misconfig retries on the next call.
    // The explicit POST /discover-models refresh keeps calling uncached discover().
    private static final Cache<String, DiscoveryResult> DISCOVER_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofMinutes(10))
            .maximumSize(32)
            .build();

    /**
     * Cached variant of {@link #discover} for page-load reads (see DISCOVER_CACHE).
     * Use this on GET paths that re-discover on every render; keep {@link #discover}
     * for explicit refreshes that must hit the provider live.
     */
    public static DiscoveryResult discoverCached(String providerName, String baseUrl, String apiKey) {
        var key = providerName + "|" + baseUrl + "|" + (apiKey == null ? "" : apiKey);
        var cached = DISCOVER_CACHE.getIfPresent(key);
        if (cached != null) return cached;
        var fresh = discover(providerName, baseUrl, apiKey);
        if (fresh instanceof DiscoveryResult.Ok) DISCOVER_CACHE.put(key, fresh);
        return fresh;
    }

    /**
     * Fetch the model catalog from a provider's /models endpoint, apply leaderboard
     * rankings if configured, and return normalized model info sorted by rank.
     *
     * <p>Dispatches on provider name and exposes the JCLAW-183 tiered filter:
     *
     * <ul>
     *   <li><b>Tier 1, Ollama</b> (any name containing {@code "ollama"}):
     *       native {@code /api/tags} + {@code /api/show} pair (JCLAW-118).
     *       Real {@code context_length} comes back, and the
     *       {@code capabilities} array distinguishes chat-capable models
     *       from embedding-only ones — {@link #parseOllamaShow} drops
     *       entries whose capabilities lack {@code "completion"}.</li>
     *   <li><b>Tier 1, LM Studio</b> (any name containing {@code "lm-studio"}):
     *       prefer the native {@code /api/v0/models} endpoint via
     *       {@link #discoverLmStudioNative}, which exposes a {@code type}
     *       field per model ({@code "llm"}, {@code "vlm"}, {@code "embeddings"},
     *       {@code "tts"}, {@code "stt"}). Only {@code "llm"} and
     *       {@code "vlm"} pass through. Falls back to the OpenAI-compat
     *       path if the native endpoint is missing (older LM Studio
     *       versions ship before {@code /api/v0}).</li>
     *   <li><b>Tier 2 / Tier 3, everything else</b>: OpenAI-compatible
     *       {@code /v1/models} endpoint. OpenRouter returns rich metadata
     *       (catalog is empirically chat-only, filter is a no-op);
     *       plain providers (OpenAI, Groq, vanilla OpenAI-compat) get the
     *       Tier 3 ID heuristic from {@link EmbeddingModelFilter}.</li>
     * </ul>
     */
    public static DiscoveryResult discover(String providerName, String baseUrl, String apiKey) {
        var lower = providerName == null ? "" : providerName.toLowerCase();
        if (lower.contains("ollama")) {
            return discoverOllamaNative(providerName, baseUrl, apiKey);
        }
        if (lower.contains("lm-studio")) {
            var lmStudioResult = discoverLmStudioNative(providerName, baseUrl, apiKey);
            // null = native endpoint missing or returned non-200; fall through
            // so the caller still gets a result via the OpenAI-compat path,
            // applying the ID heuristic in lieu of the type field.
            if (lmStudioResult != null) return lmStudioResult;
        }
        return discoverOpenAiCompat(providerName, baseUrl, apiKey);
    }

    @SuppressWarnings("java:S1193") // Catches Exception broadly; instanceof InterruptedException restores interrupt status defensively
    private static DiscoveryResult discoverOpenAiCompat(String providerName, String baseUrl, String apiKey) {
        try {
            var url = baseUrl.endsWith("/") ? baseUrl + FIELD_MODELS : baseUrl + "/" + FIELD_MODELS;
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + apiKey)
                    .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                    .get()
                    .build();
            var call = HttpFactories.llmSingleShot().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var response = call.execute()) {
                statusCode = response.code();
                responseBody = response.body().string();
            }

            if (statusCode != 200) {
                Logger.warn("[discover/%s] upstream returned HTTP %d: %s",
                        providerName, statusCode, Strings.truncate(responseBody, 500));
                return new DiscoveryResult.Error(502, "Provider returned HTTP %d: %s".formatted(
                        statusCode, Strings.truncate(responseBody, 200)));
            }

            // Together returns a bare JSON array `[{id, ...}, ...]` here,
            // not OpenAI's wrapped `{data: [...]}` shape; parseModels
            // accepts either via JsonElement detection.
            var body = JsonParser.parseString(responseBody);
            var models = parseModels(body);

            // JCLAW-183 Tier 3: drop entries whose id matches a non-chat
            // pattern. Safe to apply universally — chat-model ids never
            // collide with the embedding/audio/image-gen prefixes the
            // filter checks for.
            models.removeIf(m -> EmbeddingModelFilter.isLikelyNonChat((String) m.get(KEY_ID)));

            applyLeaderboardAndSort(providerName, models);

            return new DiscoveryResult.Ok(models);

        } catch (JsonSyntaxException e) {
            Logger.warn("[discover/%s] invalid JSON: %s", providerName, e.getMessage());
            return new DiscoveryResult.Error(502, "Invalid JSON response from provider");
        } catch (Exception e) {
            Logger.warn("[discover/%s] connect/parse failed: %s", providerName, e.getMessage());
            return new DiscoveryResult.Error(502, "Failed to connect to provider: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Fetch the configured leaderboard for {@code providerName} (if any),
     * apply rankings to {@code models}, then sort by {@code leaderboardRank}
     * with name fallback. Shared by the OpenAI-compat and Ollama-native
     * discovery paths.
     */
    private static void applyLeaderboardAndSort(String providerName, List<Map<String, Object>> models) {
        var leaderboardUrl = ConfigService.get("provider." + providerName + ".leaderboardUrl");
        var rankings = fetchLeaderboard(leaderboardUrl);
        if (!rankings.isEmpty()) {
            applyRankings(models, rankings);
        }
        models.sort(ModelDiscoveryService::compareByRankThenName);
    }

    /**
     * Comparator: rank-bearing entries first (ascending rank), then everything
     * else by name (id fallback), case-insensitive.
     */
    private static int compareByRankThenName(Map<String, Object> a, Map<String, Object> b) {
        var rankA = a.get(KEY_LEADERBOARD_RANK);
        var rankB = b.get(KEY_LEADERBOARD_RANK);
        if (rankA != null && rankB != null) return Integer.compare((int) rankA, (int) rankB);
        if (rankA != null) return -1;
        if (rankB != null) return 1;
        var nameA = a.get(KEY_NAME) != null ? a.get(KEY_NAME).toString() : a.get(KEY_ID).toString();
        var nameB = b.get(KEY_NAME) != null ? b.get(KEY_NAME).toString() : b.get(KEY_ID).toString();
        return nameA.compareToIgnoreCase(nameB);
    }

    // --- Model parsing ---

    public static List<Map<String, Object>> parseModels(JsonElement body) {
        var result = new ArrayList<Map<String, Object>>();
        JsonArray dataArray = null;

        // Three response shapes seen in the wild:
        //   1. Bare array `[{id, ...}, ...]` — Together AI
        //   2. {data: [...]}                — OpenAI, OpenRouter, most OpenAI-compats
        //   3. {models: [...]}              — Ollama-shaped responses
        // Anything else returns an empty list (graceful degradation: caller
        // sees "0 models" rather than a 502).
        if (body == null || body.isJsonNull()) return result;
        if (body.isJsonArray()) {
            dataArray = body.getAsJsonArray();
        } else if (body.isJsonObject()) {
            var obj = body.getAsJsonObject();
            if (obj.has(FIELD_DATA) && obj.get(FIELD_DATA).isJsonArray()) {
                dataArray = obj.getAsJsonArray(FIELD_DATA);
            } else if (obj.has(FIELD_MODELS) && obj.get(FIELD_MODELS).isJsonArray()) {
                dataArray = obj.getAsJsonArray(FIELD_MODELS);
            }
        }

        if (dataArray == null) return result;

        for (var el : dataArray) {
            if (!el.isJsonObject()) continue;
            var obj = el.getAsJsonObject();

            var model = new LinkedHashMap<String, Object>();
            model.put(KEY_ID, getString(obj, KEY_ID, ""));
            model.put(KEY_NAME, inferName(obj));
            model.put(KEY_CONTEXT_WINDOW, inferContextWindow(obj));
            model.put(KEY_MAX_TOKENS, inferMaxTokens(obj));
            var thinking = detectThinkingSupport(obj);
            model.put(KEY_SUPPORTS_THINKING, thinking.confirmed());
            model.put(KEY_THINKING_DETECTED_FROM_PROVIDER, thinking.fromProvider());
            var alwaysThinks = detectAlwaysThinks(obj);
            model.put(KEY_ALWAYS_THINKS, alwaysThinks.confirmed());
            model.put(KEY_ALWAYS_THINKS_DETECTED_FROM_PROVIDER, alwaysThinks.fromProvider());
            var vision = detectVisionSupport(obj);
            model.put(KEY_SUPPORTS_VISION, vision.confirmed());
            model.put(KEY_VISION_DETECTED_FROM_PROVIDER, vision.fromProvider());
            var audio = detectAudioSupport(obj);
            model.put(KEY_SUPPORTS_AUDIO, audio.confirmed());
            model.put(KEY_AUDIO_DETECTED_FROM_PROVIDER, audio.fromProvider());
            var video = detectVideoSupport(obj);
            model.put(KEY_SUPPORTS_VIDEO, video.confirmed());
            model.put(KEY_VIDEO_DETECTED_FROM_PROVIDER, video.fromProvider());
            model.put(KEY_PROMPT_PRICE, inferPrice(obj, FIELD_PROMPT));
            model.put(KEY_COMPLETION_PRICE, inferPrice(obj, FIELD_COMPLETION));
            model.put(KEY_CACHED_READ_PRICE, inferPrice(obj, TYPE_INPUT_CACHE_READ));
            model.put(KEY_CACHE_WRITE_PRICE, inferPrice(obj, "input_cache_write"));
            model.put(KEY_IS_FREE, inferIsFree(obj));

            if (model.get(KEY_ID).toString().isBlank()) continue;

            result.add(model);
        }

        return result;
    }

    private static String inferName(JsonObject obj) {
        if (obj.has(KEY_NAME) && !obj.get(KEY_NAME).isJsonNull()) {
            return obj.get(KEY_NAME).getAsString();
        }
        var id = getString(obj, KEY_ID, "");
        if (id.contains("/")) {
            id = id.substring(id.lastIndexOf('/') + 1);
        }
        return id;
    }

    private static int inferContextWindow(JsonObject obj) {
        if (obj.has(FIELD_CONTEXT_LENGTH) && !obj.get(FIELD_CONTEXT_LENGTH).isJsonNull()) {
            return obj.get(FIELD_CONTEXT_LENGTH).getAsInt();
        }
        if (obj.has(FIELD_CONTEXT_WINDOW) && !obj.get(FIELD_CONTEXT_WINDOW).isJsonNull()) {
            return obj.get(FIELD_CONTEXT_WINDOW).getAsInt();
        }
        return 0;
    }

    private static int inferMaxTokens(JsonObject obj) {
        if (obj.has(FIELD_TOP_PROVIDER) && obj.get(FIELD_TOP_PROVIDER).isJsonObject()) {
            var tp = obj.getAsJsonObject(FIELD_TOP_PROVIDER);
            if (tp.has(FIELD_MAX_COMPLETION_TOKENS) && !tp.get(FIELD_MAX_COMPLETION_TOKENS).isJsonNull()) {
                return tp.get(FIELD_MAX_COMPLETION_TOKENS).getAsInt();
            }
        }
        if (obj.has(FIELD_MAX_COMPLETION_TOKENS) && !obj.get(FIELD_MAX_COMPLETION_TOKENS).isJsonNull()) {
            return obj.get(FIELD_MAX_COMPLETION_TOKENS).getAsInt();
        }
        if (obj.has(FIELD_MAX_TOKENS) && !obj.get(FIELD_MAX_TOKENS).isJsonNull()) {
            return obj.get(FIELD_MAX_TOKENS).getAsInt();
        }
        return 0;
    }

    /**
     * Detect "always thinks" pure reasoning models — those whose architecture
     * has no non-thinking mode (OpenAI o-series, DeepSeek-R1 family, Qwen QwQ).
     * The provider API accepts a "reasoning off" value but the model thinks
     * anyway, so the UI surfaces these with a locked-on toggle.
     *
     * <p>No major provider exposes this as a metadata field — neither
     * {@code supported_parameters} (OpenRouter) nor {@code capabilities}
     * (Ollama) distinguishes reasoning-required from reasoning-optional.
     * The single programmatic signal we have is OpenRouter's
     * {@code architecture.instruct_type: "deepseek-r1"}, which uniquely
     * identifies the R1 family. Everywhere else we fall back to tight
     * id-pattern matching against the well-known reasoning-only families.
     *
     * <p>Patterns are deliberately tighter than {@link #detectThinkingSupport}'s
     * fallback (which uses bare {@code id.contains("o1")} and would
     * false-positive on {@code "claude-opus-4-1"}, etc.). This detector
     * requires the o-series id-component to start at the beginning of the
     * id or after a {@code /} provider prefix, and matches only the suffixes
     * the OpenAI catalog actually ships ({@code -mini}, {@code -pro},
     * {@code -preview}).
     */
    public static CapabilityDetection detectAlwaysThinks(JsonObject obj) {
        // OpenRouter publishes architecture.instruct_type — the only
        // provider-surfaced signal we get for "always thinks." For the
        // R1 family it's exactly "deepseek-r1"; treat as confirmed.
        if (obj.has(FIELD_ARCHITECTURE) && obj.get(FIELD_ARCHITECTURE).isJsonObject()) {
            var arch = obj.getAsJsonObject(FIELD_ARCHITECTURE);
            if (arch.has(FIELD_INSTRUCT_TYPE) && arch.get(FIELD_INSTRUCT_TYPE).isJsonPrimitive()) {
                var instructType = arch.get(FIELD_INSTRUCT_TYPE).getAsString().toLowerCase();
                if (INSTRUCT_TYPE_DEEPSEEK_R1.equals(instructType)) {
                    return new CapabilityDetection(true, true);
                }
            }
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (matchesReasoningOnlyFamily(id)) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * Match known reasoning-only model id patterns. Tight on purpose — see
     * {@link #detectAlwaysThinks} doc for why we don't use the broader
     * {@link #detectThinkingSupport} fallback patterns. New families ship
     * roughly twice a year and require an update here.
     */
    private static boolean matchesReasoningOnlyFamily(String id) {
        // OpenAI o-series. Allows optional provider prefix (openai/) and
        // optional Ollama-style :tag suffix. Suffixes are limited to the
        // -mini / -pro / -preview variants the catalog actually ships.
        if (id.matches("^(?:[^/]+/)?o[1-9](?:-(?:mini|pro|preview))?(?::.+)?$")) return true;
        // DeepSeek-R1 family — explicit hyphen so we don't match bare "r1"
        // tokens in unrelated names. Covers distill variants too.
        if (id.contains(INSTRUCT_TYPE_DEEPSEEK_R1)) return true;
        // Qwen QwQ (Question with Question). No non-pure-reasoner variant
        // currently ships under this family name.
        return id.contains("qwq");
    }

    public record ThinkingDetection(boolean confirmed, boolean fromProvider) {}

    public static ThinkingDetection detectThinkingSupport(JsonObject obj) {
        if (obj.has(FIELD_SUPPORTED_PARAMETERS) && obj.get(FIELD_SUPPORTED_PARAMETERS).isJsonArray()) {
            var hit = arrayContainsAny(
                    obj.getAsJsonArray(FIELD_SUPPORTED_PARAMETERS),
                    false,
                    "reasoning", "reasoning_effort");
            return new ThinkingDetection(hit, true);
        }

        // Ollama /api/show exposes capabilities as a bare array (JCLAW-118).
        // Mirrors the same precedence in detectVisionSupport /
        // detectAudioSupport — if the provider reports the array at all, we
        // trust it and mark fromProvider=true.
        if (obj.has(FIELD_CAPABILITIES) && obj.get(FIELD_CAPABILITIES).isJsonArray()) {
            var hit = arrayContainsAny(obj.getAsJsonArray(FIELD_CAPABILITIES), true, "thinking");
            return new ThinkingDetection(hit, true);
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (id.contains("o1") || id.contains("o3") || id.contains("o4-mini")
                || id.contains(INSTRUCT_TYPE_DEEPSEEK_R1) || id.contains("qwq")) {
            return new ThinkingDetection(true, false);
        }

        return new ThinkingDetection(false, false);
    }

    /**
     * Return {@code true} if any JSON-primitive element of {@code array} equals
     * one of the {@code needles}. {@code caseInsensitive} toggles between
     * {@link String#equals} and {@link String#equalsIgnoreCase}. Used by the
     * capability detectors to scan provider-reported arrays
     * ({@code supported_parameters}, {@code capabilities}).
     */
    private static boolean arrayContainsAny(JsonArray array, boolean caseInsensitive, String... needles) {
        for (var el : array) {
            if (!el.isJsonPrimitive()) continue;
            var val = el.getAsString();
            for (var needle : needles) {
                if (caseInsensitive ? needle.equalsIgnoreCase(val) : needle.equals(val)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Shared return shape for capability detectors.
     *
     * @param confirmed    best-effort final answer for the capability
     * @param fromProvider true when the provider explicitly reported enough
     *                     metadata to decide (so the UI can lock the
     *                     corresponding checkbox); false when we fell back
     *                     to an ID-based heuristic or defaulted to absence
     */
    public record CapabilityDetection(boolean confirmed, boolean fromProvider) {}

    /**
     * Detect vision (image input) support. Primary signal is OpenRouter's
     * {@code architecture.input_modalities} array (e.g. {@code ["text","image"]});
     * secondary is Ollama's {@code capabilities} array from {@code /api/show}
     * merged into the /models payload. Falls back to model-ID heuristics for
     * well-known vision-capable families when the provider reports nothing.
     */
    public static CapabilityDetection detectVisionSupport(JsonObject obj) {
        var provider = extractModalities(obj, "image");
        if (provider != null) return provider;

        if (obj.has(FIELD_CAPABILITIES) && obj.get(FIELD_CAPABILITIES).isJsonArray()) {
            var hit = arrayContainsAny(obj.getAsJsonArray(FIELD_CAPABILITIES), true, "vision");
            return new CapabilityDetection(hit, true);
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (id.contains("gpt-4o") || id.contains("gpt-4.1") || id.contains("gpt-4-vision")
                || id.contains("gpt-5") || id.contains("claude-3") || id.contains("claude-opus")
                || id.contains("claude-sonnet") || id.contains("claude-haiku")
                || id.contains("gemini") || id.contains("llava") || id.contains("-vl")
                || id.contains("qwen2-vl") || id.contains("qwen3-vl") || id.contains("pixtral")
                || id.contains("internvl")) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * Detect audio input support. Same precedence as vision: OpenRouter
     * modality arrays → Ollama capabilities → model-ID heuristic. Audio is
     * still rare across providers so the heuristic list is small.
     */
    public static CapabilityDetection detectAudioSupport(JsonObject obj) {
        var provider = extractModalities(obj, "audio");
        if (provider != null) return provider;

        if (obj.has(FIELD_CAPABILITIES) && obj.get(FIELD_CAPABILITIES).isJsonArray()) {
            var hit = arrayContainsAny(obj.getAsJsonArray(FIELD_CAPABILITIES), true, "audio");
            return new CapabilityDetection(hit, true);
        }

        var id = getString(obj, KEY_ID, "").toLowerCase();
        // JCLAW-160: "-audio-preview" is OpenAI's stable suffix for audio-capable
        // models on /v1/models. Match the suffix anchored on a leading dash so
        // future variants (gpt-4o-mini-audio-preview, gpt-5-audio-preview, …)
        // are flagged without per-version updates, while a hypothetical
        // mid-word match like "non-audio-preview-test" cannot trip the
        // detector. The gpt-4o-audio / gpt-5-audio lines stay in place to
        // catch GA snapshots whose ids drop the -preview suffix.
        if (id.contains("gpt-4o-audio") || id.contains("gpt-5-audio")
                || id.contains("-audio-preview")
                || id.contains("gemini-2.5-flash-audio") || id.contains("whisper")
                || id.contains("qwen2-audio") || id.contains("voxtral")) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * JCLAW-217: detect native video input support. Same precedence as
     * vision/audio: provider modality arrays (OpenRouter advertises {@code video}
     * in {@code architecture.input_modalities} for Qwen-VL routes) then a
     * model-ID heuristic for the Qwen-VL family. Provider-aware by construction:
     * only the OpenAI-compatible discovery path (OpenRouter, and a vLLM-backed
     * custom provider) reaches this; the Ollama and LM Studio native discovery
     * paths never call it, so a Qwen-VL model served there stays
     * {@code supportsVideo=false} and routes to the multi-image
     * fallback — those backends are image-only (see JCLAW-208). Gemini is not
     * included: we are not using Gemini.
     */
    public static CapabilityDetection detectVideoSupport(JsonObject obj) {
        var provider = extractModalities(obj, "video");
        if (provider != null) return provider;

        var id = getString(obj, KEY_ID, "").toLowerCase();
        if (id.contains("qwen2.5-vl") || id.contains("qwen3-vl") || id.contains("qwen-vl")
                || id.contains("qwen2.5-omni") || id.contains("qwen3-omni")) {
            return new CapabilityDetection(true, false);
        }

        return new CapabilityDetection(false, false);
    }

    /**
     * Scan OpenRouter's {@code architecture.input_modalities} (preferred) and
     * legacy {@code architecture.modality} string for a given modality token.
     * Returns non-null only when the provider explicitly reports modality
     * metadata — caller should fall through to other signals otherwise.
     */
    private static CapabilityDetection extractModalities(JsonObject obj, String modality) {
        if (!obj.has(FIELD_ARCHITECTURE) || !obj.get(FIELD_ARCHITECTURE).isJsonObject()) return null;
        var arch = obj.getAsJsonObject(FIELD_ARCHITECTURE);

        if (arch.has(FIELD_INPUT_MODALITIES) && arch.get(FIELD_INPUT_MODALITIES).isJsonArray()) {
            var hit = arrayContainsAny(arch.getAsJsonArray(FIELD_INPUT_MODALITIES), true, modality);
            return new CapabilityDetection(hit, true);
        }

        if (arch.has(FIELD_MODALITY) && arch.get(FIELD_MODALITY).isJsonPrimitive()) {
            var s = arch.get(FIELD_MODALITY).getAsString().toLowerCase();
            return new CapabilityDetection(s.contains(modality), true);
        }

        return null;
    }

    /**
     * Extract a per-million-token price for a JClaw price {@code type}
     * ({@code "prompt"}, {@code "completion"}, {@code "input_cache_read"},
     * {@code "input_cache_write"}) from a model's {@code pricing} object,
     * handling both provider conventions seen on {@code /v1/models}:
     *
     * <ul>
     *   <li><b>OpenRouter:</b> {@code pricing.{prompt,completion,...}} quoted
     *       in dollars <i>per token</i> — multiplied by 1e6 to reach JClaw's
     *       per-million convention.</li>
     *   <li><b>Together AI:</b> {@code pricing.{input,output,cached_input}}
     *       quoted in dollars <i>per million tokens</i> already — used as-is.
     *       These were previously dropped (returned {@code -1}) because only
     *       the OpenRouter keys were read, which is why Together models
     *       discovered unpriced.</li>
     * </ul>
     *
     * <p>The key shape is the discriminator: among JClaw's supported providers
     * only Together uses {@code input}/{@code output}, and it quotes
     * per-million, so reading those keys without the 1e6 scale is correct.
     * Returns {@code -1} ("unknown") when neither shape carries the field.
     */
    public static double inferPrice(JsonObject obj, String type) {
        if (!obj.has(FIELD_PRICING) || !obj.get(FIELD_PRICING).isJsonObject()) return -1;
        var pricing = obj.getAsJsonObject(FIELD_PRICING);

        // OpenRouter shape: dollars per token → scale to per-million.
        var perToken = readPriceField(pricing, type);
        if (perToken >= 0) return perToken * 1_000_000;

        // Together shape: dollars per million already → no scaling.
        var perMillion = readPriceField(pricing, togetherPricingKey(type));
        if (perMillion >= 0) return perMillion;

        return -1;
    }

    /**
     * Read a numeric price field from a {@code pricing} object. Accepts both
     * string-encoded (OpenRouter) and bare-number (Together) JSON values via
     * {@code getAsString}. Returns {@code -1} when the key is null/absent or
     * unparseable; a {@code NaN} value parses without throwing but fails the
     * {@code >= 0} guard in {@link #inferPrice}, so it too resolves to -1.
     */
    private static double readPriceField(JsonObject pricing, String key) {
        if (key == null || !pricing.has(key) || pricing.get(key).isJsonNull()) return -1;
        try {
            return Double.parseDouble(pricing.get(key).getAsString());
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    /**
     * Map a JClaw price {@code type} to the equivalent Together AI pricing
     * key. Returns {@code null} for types Together doesn't expose (it has no
     * cache-write price), which {@link #readPriceField} treats as absent.
     */
    private static String togetherPricingKey(String type) {
        return switch (type) {
            case FIELD_PROMPT -> FIELD_TOGETHER_INPUT;
            case FIELD_COMPLETION -> FIELD_TOGETHER_OUTPUT;
            case TYPE_INPUT_CACHE_READ -> FIELD_TOGETHER_CACHED_INPUT;
            default -> null;
        };
    }

    /**
     * A model is "free" when both its input and output prices are explicitly
     * zero. Delegates to {@link #inferPrice} so both the OpenRouter and
     * Together pricing shapes are recognized; an unpriced model (either price
     * unknown / {@code -1}) is not free.
     */
    private static boolean inferIsFree(JsonObject obj) {
        return inferPrice(obj, FIELD_PROMPT) == 0 && inferPrice(obj, FIELD_COMPLETION) == 0;
    }

    // --- Leaderboard ---

    @SuppressWarnings("java:S1141") // Inner try isolates JSON-parse fallback to HTML; refactor would require returning a sentinel from a helper
    static List<String> fetchLeaderboard(String leaderboardUrl) {
        if (leaderboardUrl == null || leaderboardUrl.isBlank()) return List.of();

        try {
            var req = new Request.Builder()
                    .url(leaderboardUrl)
                    .header(HttpKeys.ACCEPT, "text/html,application/json")
                    .get()
                    .build();
            var call = HttpFactories.general().newCall(req);
            call.timeout().timeout(LEADERBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String body;
            try (var response = call.execute()) {
                if (response.code() != 200) return List.of();
                body = response.body().string();
            }

            try {
                var json = JsonParser.parseString(body);
                if (json.isJsonArray()) {
                    return parseJsonLeaderboard(json.getAsJsonArray());
                }
                if (json.isJsonObject() && json.getAsJsonObject().has(FIELD_DATA)) {
                    return parseJsonLeaderboard(json.getAsJsonObject().getAsJsonArray(FIELD_DATA));
                }
            } catch (JsonSyntaxException _) {}

            return parseHtmlLeaderboard(body);

        } catch (Exception e) {
            EventLogger.warn("provider", "Leaderboard fetch failed: %s".formatted(e.getMessage()));
            return List.of();
        }
    }

    private static List<String> parseJsonLeaderboard(JsonArray array) {
        var result = new ArrayList<String>();
        for (var el : array) {
            if (!el.isJsonObject()) continue;
            var obj = el.getAsJsonObject();
            var id = getString(obj, KEY_ID, getString(obj, "slug", getString(obj, KEY_NAME, "")));
            if (!id.isBlank()) result.add(id.toLowerCase());
        }
        return result;
    }

    private static final String[] HREF_PREFIX_BLOCKLIST =
            {"docs/", "api/", "_next/", "images/", "settings/"};
    private static final String[] USAGE_PREFIX_BLOCKLIST = {"api/", "docs/", "_next/"};

    static List<String> parseHtmlLeaderboard(String html) {
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();

        scanLeaderboardMatches(HREF_PATTERN.matcher(html), HREF_PREFIX_BLOCKLIST, seen, result);
        if (result.isEmpty()) {
            scanLeaderboardMatches(USAGE_PATTERN.matcher(html), USAGE_PREFIX_BLOCKLIST, seen, result);
        }

        return result;
    }

    /**
     * Walk a leaderboard regex matcher, lower-case + strip-variant each slug,
     * filter out blocklisted prefixes, and append deduped survivors to
     * {@code out}. Shared by the {@code href} and {@code usage} passes — the
     * only difference is the blocklist set.
     */
    private static void scanLeaderboardMatches(
            Matcher matcher,
            String[] prefixBlocklist,
            HashSet<String> seen,
            ArrayList<String> out) {
        while (matcher.find()) {
            var slug = matcher.group(1).toLowerCase();
            var baseSlug = stripVariant(slug);
            if (hasAnyPrefix(baseSlug, prefixBlocklist)) continue;
            if (seen.add(baseSlug)) out.add(baseSlug);
        }
    }

    private static boolean hasAnyPrefix(String s, String[] prefixes) {
        for (var p : prefixes) {
            if (s.startsWith(p)) return true;
        }
        return false;
    }

    static void applyRankings(List<Map<String, Object>> models, List<String> rankings) {
        // stripVersionSuffix(ranked) is invariant across the inner loop's
        // model iterations, so precompute it once rather than per model×ranking.
        var strippedRankings = rankings.stream().map(ModelDiscoveryService::stripVersionSuffix).toList();
        for (var model : models) {
            var modelId = model.get(KEY_ID).toString().toLowerCase();
            var modelBase = stripVariant(modelId);
            var modelBaseStripped = stripVersionSuffix(modelBase);
            int bestRank = Integer.MAX_VALUE;

            for (int i = 0; i < rankings.size(); i++) {
                var ranked = rankings.get(i);
                if (modelBase.equals(ranked) || modelId.equals(ranked)
                        || modelBaseStripped.equals(strippedRankings.get(i))) {
                    if (i + 1 < bestRank) bestRank = i + 1;
                    break;
                }
            }

            if (bestRank < Integer.MAX_VALUE) {
                model.put(KEY_LEADERBOARD_RANK, bestRank);
            }
        }
    }

    public static String stripVariant(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(0, idx) : id;
    }

    /**
     * Strip a date or numeric-version suffix from a model id. Handles three
     * shapes seen in real provider catalogs:
     * <ul>
     *   <li>{@code -YYYY-MM-DD} — OpenAI's dated checkpoint format
     *       ({@code gpt-4o-2024-08-06})</li>
     *   <li>{@code -YYYYMMDD} or longer — Anthropic's contiguous-date format
     *       ({@code claude-3-5-sonnet-20241022})</li>
     *   <li>{@code -NNNN} or {@code -NNN} — short numeric version pins
     *       ({@code gpt-4-0125})</li>
     * </ul>
     *
     * <p>Order matters: the dash-separated date pattern must run first
     * because the trailing day component would otherwise match the short
     * suffix regex and leave the year/month dangling.
     */
    public static String stripVersionSuffix(String id) {
        return id
                .replaceAll("-\\d{4}-\\d{2}-\\d{2}$", "")
                .replaceAll("-\\d{6,}$", "")
                .replaceAll("-\\d{3,4}$", "");
    }

    // ─── LM Studio native discovery (JCLAW-183) ──────────────────────

    /**
     * LM Studio Tier 1 path. Hits the native {@code /api/v0/models}
     * endpoint, which returns a {@code type} field per model — one of
     * {@code "llm"}, {@code "vlm"} (vision-language), {@code "embeddings"},
     * {@code "tts"} (text-to-speech), {@code "stt"} (speech-to-text).
     * Keeps {@code "llm"} and {@code "vlm"}; drops the other three so
     * an operator can't accidentally bind a chat agent to an embedding
     * or audio model.
     *
     * <p>Returns {@code null} on any failure (404 from older LM Studio
     * versions that predate the native endpoint, malformed JSON,
     * connection refused) so the caller can fall back to the standard
     * OpenAI-compat path with the Tier 3 id heuristic.
     */
    @SuppressWarnings("java:S1172") // providerName kept for signature parity with other discover* variants
    static DiscoveryResult discoverLmStudioNative(String providerName, String baseUrl, String apiKey) {
        try {
            var nativeBase = stripV1Suffix(baseUrl);
            var url = nativeBase + "/api/v0/models";
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + (apiKey != null ? apiKey : ""))
                    .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                    .get()
                    .build();
            var call = HttpFactories.llmSingleShot().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var resp = call.execute()) {
                statusCode = resp.code();
                responseBody = resp.body().string();
            }
            if (statusCode != 200) return null;

            var body = JsonParser.parseString(responseBody).getAsJsonObject();
            var models = parseLmStudioNativeResponse(body);

            models.sort(ModelDiscoveryService::compareByRankThenName);

            return new DiscoveryResult.Ok(models);
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Parse a {@code /api/v0/models} response body into the same Map shape
     * other discovery paths produce. Filters on the {@code type} field —
     * keeps {@code "llm"} and {@code "vlm"}, drops everything else
     * ({@code "embeddings"}, {@code "tts"}, {@code "stt"}, plus any
     * future type LM Studio adds that we haven't accounted for).
     *
     * <p>Vision support is inferred from {@code type == "vlm"} and
     * marked {@code visionDetectedFromProvider=true} since the type
     * field is authoritative. Thinking and audio are left
     * detector-defaulted because the native API doesn't enumerate them
     * — id-based heuristics in the standard detectors fill in.
     */
    public static List<Map<String, Object>> parseLmStudioNativeResponse(JsonObject body) {
        var results = new ArrayList<Map<String, Object>>();
        if (!body.has(FIELD_DATA) || !body.get(FIELD_DATA).isJsonArray()) return results;

        for (var el : body.getAsJsonArray(FIELD_DATA)) {
            if (!el.isJsonObject()) continue;
            var entry = el.getAsJsonObject();

            var type = getString(entry, "type", "").toLowerCase();
            if (!"llm".equals(type) && !"vlm".equals(type)) continue;

            var id = getString(entry, KEY_ID, "");
            if (id.isBlank()) continue;

            var ctxWin = entry.has(FIELD_MAX_CONTEXT_LENGTH) && !entry.get(FIELD_MAX_CONTEXT_LENGTH).isJsonNull()
                    ? entry.get(FIELD_MAX_CONTEXT_LENGTH).getAsInt()
                    : 0;

            var model = new LinkedHashMap<String, Object>();
            model.put(KEY_ID, id);
            model.put(KEY_NAME, id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id);
            model.put(KEY_CONTEXT_WINDOW, ctxWin);
            model.put(KEY_MAX_TOKENS, 0);

            // Vision is authoritative from the type field.
            boolean isVlm = "vlm".equals(type);
            model.put(KEY_SUPPORTS_VISION, isVlm);
            model.put(KEY_VISION_DETECTED_FROM_PROVIDER, true);

            // Thinking and audio aren't enumerated in the native API.
            // Leave fromProvider=false so the existing id-based heuristic
            // picks up known thinking models (deepseek-r1, qwq, etc.)
            // without overriding a confirmed answer here.
            model.put(KEY_SUPPORTS_THINKING, false);
            model.put(KEY_THINKING_DETECTED_FROM_PROVIDER, false);
            // alwaysThinks runs the id-pattern detector regardless: locally
            // run R1 / QwQ models are still pure reasoners.
            var lmIdOnly = new JsonObject();
            lmIdOnly.addProperty(KEY_ID, id);
            var lmAlwaysThinks = detectAlwaysThinks(lmIdOnly);
            model.put(KEY_ALWAYS_THINKS, lmAlwaysThinks.confirmed());
            model.put(KEY_ALWAYS_THINKS_DETECTED_FROM_PROVIDER, lmAlwaysThinks.fromProvider());
            model.put(KEY_SUPPORTS_AUDIO, false);
            model.put(KEY_AUDIO_DETECTED_FROM_PROVIDER, false);

            // Local models — no pricing data.
            model.put(KEY_PROMPT_PRICE, -1.0);
            model.put(KEY_COMPLETION_PRICE, -1.0);
            model.put(KEY_CACHED_READ_PRICE, -1.0);
            model.put(KEY_CACHE_WRITE_PRICE, -1.0);
            model.put(KEY_IS_FREE, false);

            results.add(model);
        }

        return results;
    }

    // ─── Ollama native discovery (JCLAW-118) ─────────────────────────

    /**
     * Native-Ollama discovery path. Uses the richer {@code /api/tags} +
     * {@code /api/show} pair to extract {@code context_length},
     * {@code capabilities}, and architecture metadata that the
     * OpenAI-compatible {@code /v1/models} stub omits entirely for
     * Ollama Cloud. Fans out {@code /api/show} calls concurrently on
     * virtual threads so a provider with dozens of models discovers in
     * one round-trip's worth of wall time rather than N.
     */
    @SuppressWarnings("java:S1193") // Catches Exception broadly; instanceof InterruptedException restores interrupt status defensively
    static DiscoveryResult discoverOllamaNative(String providerName, String baseUrl, String apiKey) {
        try {
            var nativeBase = stripV1Suffix(baseUrl);
            var tagsResult = fetchOllamaTags(nativeBase, apiKey);
            if (tagsResult.error() != null) return tagsResult.error();
            var modelIds = tagsResult.modelIds();
            if (modelIds.isEmpty()) return new DiscoveryResult.Ok(List.of());

            var results = fanOutOllamaShow(nativeBase + "/api/show", apiKey, modelIds);
            if (results.isEmpty()) {
                // JCLAW-183: covers both "every /api/show call failed" and
                // "every model was filtered out as non-chat" (e.g. an Ollama
                // install with only nomic-embed-text pulled). Either way the
                // operator gets a clear "nothing chat-capable here" message.
                return new DiscoveryResult.Error(502,
                        "No chat-capable models discovered for provider " + providerName);
            }

            applyLeaderboardAndSort(providerName, results);

            return new DiscoveryResult.Ok(results);

        } catch (JsonSyntaxException _) {
            return new DiscoveryResult.Error(502, "Invalid JSON response from provider");
        } catch (Exception e) {
            // Defensive interrupt-status restore: the broad catch is unavoidable (provider
            // calls can surface InterruptedException wrapped or unwrapped), so the
            // instanceof check is the simplest way to honor cooperative cancellation.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return new DiscoveryResult.Error(502,
                    "Failed to connect to provider: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Internal carrier for the /api/tags step: either an {@code error} (non-200
     * upstream) or a {@code modelIds} list. Exactly one is non-null.
     */
    private record TagsResult(DiscoveryResult.Error error, List<String> modelIds) {}

    /**
     * GET {@code <nativeBase>/api/tags} and extract the model id list. On
     * non-200, returns a {@link TagsResult} carrying a populated {@link DiscoveryResult.Error}.
     */
    private static TagsResult fetchOllamaTags(String nativeBase, String apiKey) throws IOException {
        var tagsReq = new Request.Builder()
                .url(nativeBase + "/api/tags")
                .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + (apiKey != null ? apiKey : ""))
                .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                .get()
                .build();
        var tagsCall = HttpFactories.llmSingleShot().newCall(tagsReq);
        tagsCall.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

        int tagsStatus;
        String tagsResponseBody;
        try (var tagsResp = tagsCall.execute()) {
            tagsStatus = tagsResp.code();
            tagsResponseBody = tagsResp.body().string();
        }
        if (tagsStatus != 200) {
            return new TagsResult(new DiscoveryResult.Error(502,
                    "Provider returned HTTP %d from /api/tags: %s".formatted(
                            tagsStatus, Strings.truncate(tagsResponseBody, 200))),
                    null);
        }
        var tagsBody = JsonParser.parseString(tagsResponseBody).getAsJsonObject();
        return new TagsResult(null, extractTagIds(tagsBody));
    }

    /**
     * Fan out {@code /api/show} calls on virtual threads, one per model id.
     * Per-future timeouts and parse failures are logged and skipped — the
     * caller only sees the survivors. Models filtered out by
     * {@link #parseOllamaShow} (no {@code "completion"} capability) are also
     * absent from the return.
     */
    private static List<Map<String, Object>> fanOutOllamaShow(
            String showUrl, String apiKey, List<String> modelIds) {
        var results = new ArrayList<Map<String, Object>>(modelIds.size());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = modelIds.stream()
                    .map(id -> executor.submit(() -> fetchOllamaShow(showUrl, apiKey, id)))
                    .toList();
            for (int i = 0; i < futures.size(); i++) {
                collectShowResult(futures.get(i), modelIds.get(i), results);
            }
        }
        return results;
    }

    /**
     * Await a single {@code /api/show} future. Logs per-model failures (including
     * timeouts) without aborting the broader discovery; restores the interrupt
     * status on {@link InterruptedException} for cooperative cancellation.
     */
    private static void collectShowResult(
            Future<Map<String, Object>> future,
            String modelId,
            List<Map<String, Object>> out) {
        try {
            var model = future.get(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (model != null) out.add(model);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
            Logger.warn("Ollama /api/show interrupted for %s", modelId);
        } catch (Exception e) {
            Logger.warn("Ollama /api/show failed for %s: %s", modelId, e.getMessage());
        }
    }

    /**
     * Strip a trailing {@code /v1} (with or without trailing slash) from
     * a provider base URL. Ollama providers store
     * {@code https://ollama.com/v1} for the OpenAI-compat inference
     * path; the native discovery endpoints live at the bare host.
     */
    public static String stripV1Suffix(String baseUrl) {
        if (baseUrl == null) return "";
        var s = baseUrl;
        if (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.endsWith("/v1")) s = s.substring(0, s.length() - 3);
        return s;
    }

    /**
     * Extract the list of model ids from a {@code /api/tags} response.
     * The list lives under {@code models[].name} (the full tagged id
     * like {@code "gpt-oss:20b"}); falls back to {@code models[].model}
     * when {@code name} is missing.
     */
    public static List<String> extractTagIds(JsonObject body) {
        var out = new ArrayList<String>();
        if (!body.has(FIELD_MODELS) || !body.get(FIELD_MODELS).isJsonArray()) return out;
        for (var el : body.getAsJsonArray(FIELD_MODELS)) {
            if (!el.isJsonObject()) continue;
            var o = el.getAsJsonObject();
            var id = getString(o, KEY_NAME, getString(o, "model", ""));
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    @SuppressWarnings("java:S1168") // null means "drop this model from discovery"; empty map would be misread as a successful but empty result
    private static Map<String, Object> fetchOllamaShow(String url, String apiKey, String id) {
        try {
            var body = "{\"name\":\"" + id.replace("\"", "\\\"") + "\"}";
            var jsonMediaType = MediaType.get(HttpKeys.APPLICATION_JSON);
            var req = new Request.Builder()
                    .url(url)
                    .header(HttpKeys.AUTHORIZATION, HttpKeys.BEARER_PREFIX + (apiKey != null ? apiKey : ""))
                    .header(HttpKeys.ACCEPT, HttpKeys.APPLICATION_JSON)
                    .post(RequestBody.create(body, jsonMediaType))
                    .build();
            var call = HttpFactories.llmSingleShot().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var resp = call.execute()) {
                statusCode = resp.code();
                responseBody = resp.body().string();
            }
            if (statusCode != 200) return null;
            return parseOllamaShow(id, JsonParser.parseString(responseBody).getAsJsonObject());
        } catch (Exception _) {
            return null;
        }
    }

    /**
     * Parse a single {@code /api/show} response into the same Map shape
     * {@link #parseModels} produces. The context length is namespaced by
     * model family ({@code "kimi-k2.context_length"},
     * {@code "glm.context_length"}, {@code "qwen3.context_length"}, …),
     * so we scan for any key ending in {@code .context_length} rather
     * than hardcoding the family list. Capability flags are fed through
     * the standard detectors with the {@code capabilities} array
     * surfaced in a minimal synthetic object, so the Ollama path shares
     * the OpenRouter/Anthropic logic.
     */
    @SuppressWarnings("java:S1168") // null means "drop this model from discovery"; empty map would be misread as a successful but empty result
    public static Map<String, Object> parseOllamaShow(String id, JsonObject show) {
        // JCLAW-183 Tier 1: drop embedding-only models. Ollama's
        // /api/show capabilities array distinguishes "completion"
        // (chat-capable) from "embedding" (vector-only). When the array
        // is present and non-empty but lacks "completion", the model
        // can't serve chat — return null so {@code discoverOllamaNative}
        // drops it from the discovery list. A model with no capabilities
        // array (older Ollama versions) is kept; the existing detectors
        // fall back to id-based heuristics.
        if (!ollamaCapabilitiesAllowChat(show)) return null;

        var model = new LinkedHashMap<String, Object>();
        model.put(KEY_ID, id);
        model.put(KEY_NAME, id);
        model.put(KEY_CONTEXT_WINDOW, extractOllamaContextLength(show));
        model.put(KEY_MAX_TOKENS, 0);

        var forDetect = new JsonObject();
        forDetect.addProperty(KEY_ID, id);
        if (show.has(FIELD_CAPABILITIES) && show.get(FIELD_CAPABILITIES).isJsonArray()) {
            forDetect.add(FIELD_CAPABILITIES, show.getAsJsonArray(FIELD_CAPABILITIES));
        }
        var thinking = detectThinkingSupport(forDetect);
        model.put(KEY_SUPPORTS_THINKING, thinking.confirmed());
        model.put(KEY_THINKING_DETECTED_FROM_PROVIDER, thinking.fromProvider());
        var alwaysThinks = detectAlwaysThinks(forDetect);
        model.put(KEY_ALWAYS_THINKS, alwaysThinks.confirmed());
        model.put(KEY_ALWAYS_THINKS_DETECTED_FROM_PROVIDER, alwaysThinks.fromProvider());
        var vision = detectVisionSupport(forDetect);
        model.put(KEY_SUPPORTS_VISION, vision.confirmed());
        model.put(KEY_VISION_DETECTED_FROM_PROVIDER, vision.fromProvider());
        var audio = detectAudioSupport(forDetect);
        model.put(KEY_SUPPORTS_AUDIO, audio.confirmed());
        model.put(KEY_AUDIO_DETECTED_FROM_PROVIDER, audio.fromProvider());

        // Ollama doesn't publish pricing via the API. -1 means "unset" —
        // the frontend skips these fields when saving.
        model.put(KEY_PROMPT_PRICE, -1.0);
        model.put(KEY_COMPLETION_PRICE, -1.0);
        model.put(KEY_CACHED_READ_PRICE, -1.0);
        model.put(KEY_CACHE_WRITE_PRICE, -1.0);
        model.put(KEY_IS_FREE, false);

        return model;
    }

    /**
     * Return {@code false} when {@code show.capabilities} is a non-empty array
     * that lacks {@code "completion"} — i.e. the model is embedding-only and
     * not chat-capable. Returns {@code true} when capabilities are absent,
     * empty, or contain {@code "completion"}.
     */
    private static boolean ollamaCapabilitiesAllowChat(JsonObject show) {
        if (!show.has(FIELD_CAPABILITIES) || !show.get(FIELD_CAPABILITIES).isJsonArray()) return true;
        var caps = show.getAsJsonArray(FIELD_CAPABILITIES);
        if (caps.isEmpty()) return true;
        return arrayContainsAny(caps, true, FIELD_COMPLETION);
    }

    /**
     * Pick the first {@code *.context_length} entry under
     * {@code model_info} and return its integer value. Returns {@code 0}
     * when the response lacks a context-length entry — the frontend then
     * surfaces "unknown" and asks the user to fill it in. Malformed
     * numeric values are skipped; extraction continues so one
     * misbehaving family key doesn't mask a correct one.
     */
    public static int extractOllamaContextLength(JsonObject show) {
        if (show == null || !show.has(FIELD_MODEL_INFO) || !show.get(FIELD_MODEL_INFO).isJsonObject()) return 0;
        var mi = show.getAsJsonObject(FIELD_MODEL_INFO);
        var suffix = "." + FIELD_CONTEXT_LENGTH;
        for (var entry : mi.entrySet()) {
            if (entry.getKey().endsWith(suffix) && entry.getValue().isJsonPrimitive()) {
                try {
                    return entry.getValue().getAsInt();
                } catch (NumberFormatException _) {
                    // keep scanning in case a later family key is well-formed
                }
            }
        }
        return 0;
    }

    static String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }
}
