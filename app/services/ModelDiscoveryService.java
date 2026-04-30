package services;

import com.google.gson.*;
import utils.Strings;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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

    /** Result of a model discovery call. */
    public sealed interface DiscoveryResult {
        record Ok(List<Map<String, Object>> models) implements DiscoveryResult {}
        record Error(int statusCode, String message) implements DiscoveryResult {}
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

    private static DiscoveryResult discoverOpenAiCompat(String providerName, String baseUrl, String apiKey) {
        try {
            var url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            var req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .get()
                    .build();
            var call = utils.HttpFactories.llmSingleShot().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var response = call.execute()) {
                statusCode = response.code();
                responseBody = response.body() != null ? response.body().string() : "";
            }

            if (statusCode != 200) {
                return new DiscoveryResult.Error(502, "Provider returned HTTP %d: %s".formatted(
                        statusCode, Strings.truncate(responseBody, 200)));
            }

            var body = JsonParser.parseString(responseBody).getAsJsonObject();
            var models = parseModels(body);

            // JCLAW-183 Tier 3: drop entries whose id matches a non-chat
            // pattern. Safe to apply universally — chat-model ids never
            // collide with the embedding/audio/image-gen prefixes the
            // filter checks for.
            models.removeIf(m -> EmbeddingModelFilter.isLikelyNonChat((String) m.get("id")));

            var leaderboardUrl = ConfigService.get("provider." + providerName + ".leaderboardUrl");
            var rankings = fetchLeaderboard(leaderboardUrl);
            if (!rankings.isEmpty()) {
                applyRankings(models, rankings);
            }

            models.sort((a, b) -> {
                var rankA = a.get("leaderboardRank");
                var rankB = b.get("leaderboardRank");
                if (rankA != null && rankB != null) return Integer.compare((int) rankA, (int) rankB);
                if (rankA != null) return -1;
                if (rankB != null) return 1;
                var nameA = a.get("name") != null ? a.get("name").toString() : a.get("id").toString();
                var nameB = b.get("name") != null ? b.get("name").toString() : b.get("id").toString();
                return nameA.compareToIgnoreCase(nameB);
            });

            return new DiscoveryResult.Ok(models);

        } catch (JsonSyntaxException e) {
            return new DiscoveryResult.Error(502, "Invalid JSON response from provider");
        } catch (Exception e) {
            return new DiscoveryResult.Error(502, "Failed to connect to provider: %s".formatted(e.getMessage()));
        }
    }

    // --- Model parsing ---

    public static List<Map<String, Object>> parseModels(JsonObject body) {
        var result = new ArrayList<Map<String, Object>>();
        JsonArray dataArray = null;

        if (body.has("data") && body.get("data").isJsonArray()) {
            dataArray = body.getAsJsonArray("data");
        } else if (body.has("models") && body.get("models").isJsonArray()) {
            dataArray = body.getAsJsonArray("models");
        }

        if (dataArray == null) return result;

        for (var el : dataArray) {
            if (!el.isJsonObject()) continue;
            var obj = el.getAsJsonObject();

            var model = new LinkedHashMap<String, Object>();
            model.put("id", getString(obj, "id", ""));
            model.put("name", inferName(obj));
            model.put("contextWindow", inferContextWindow(obj));
            model.put("maxTokens", inferMaxTokens(obj));
            var thinking = detectThinkingSupport(obj);
            model.put("supportsThinking", thinking.confirmed());
            model.put("thinkingDetectedFromProvider", thinking.fromProvider());
            var vision = detectVisionSupport(obj);
            model.put("supportsVision", vision.confirmed());
            model.put("visionDetectedFromProvider", vision.fromProvider());
            var audio = detectAudioSupport(obj);
            model.put("supportsAudio", audio.confirmed());
            model.put("audioDetectedFromProvider", audio.fromProvider());
            model.put("promptPrice", inferPrice(obj, "prompt"));
            model.put("completionPrice", inferPrice(obj, "completion"));
            model.put("cachedReadPrice", inferPrice(obj, "input_cache_read"));
            model.put("cacheWritePrice", inferPrice(obj, "input_cache_write"));
            model.put("isFree", inferIsFree(obj));

            if (model.get("id").toString().isBlank()) continue;

            result.add(model);
        }

        return result;
    }

    private static String inferName(JsonObject obj) {
        if (obj.has("name") && !obj.get("name").isJsonNull()) {
            return obj.get("name").getAsString();
        }
        var id = getString(obj, "id", "");
        if (id.contains("/")) {
            id = id.substring(id.lastIndexOf('/') + 1);
        }
        return id;
    }

    private static int inferContextWindow(JsonObject obj) {
        if (obj.has("context_length") && !obj.get("context_length").isJsonNull()) {
            return obj.get("context_length").getAsInt();
        }
        if (obj.has("context_window") && !obj.get("context_window").isJsonNull()) {
            return obj.get("context_window").getAsInt();
        }
        return 0;
    }

    private static int inferMaxTokens(JsonObject obj) {
        if (obj.has("top_provider") && obj.get("top_provider").isJsonObject()) {
            var tp = obj.getAsJsonObject("top_provider");
            if (tp.has("max_completion_tokens") && !tp.get("max_completion_tokens").isJsonNull()) {
                return tp.get("max_completion_tokens").getAsInt();
            }
        }
        if (obj.has("max_completion_tokens") && !obj.get("max_completion_tokens").isJsonNull()) {
            return obj.get("max_completion_tokens").getAsInt();
        }
        if (obj.has("max_tokens") && !obj.get("max_tokens").isJsonNull()) {
            return obj.get("max_tokens").getAsInt();
        }
        return 0;
    }

    public record ThinkingDetection(boolean confirmed, boolean fromProvider) {}

    public static ThinkingDetection detectThinkingSupport(JsonObject obj) {
        if (obj.has("supported_parameters") && obj.get("supported_parameters").isJsonArray()) {
            for (var param : obj.getAsJsonArray("supported_parameters")) {
                if (param.isJsonPrimitive()) {
                    var val = param.getAsString();
                    if ("reasoning".equals(val) || "reasoning_effort".equals(val)) {
                        return new ThinkingDetection(true, true);
                    }
                }
            }
            return new ThinkingDetection(false, true);
        }

        // Ollama /api/show exposes capabilities as a bare array (JCLAW-118).
        // Mirrors the same precedence in detectVisionSupport /
        // detectAudioSupport — if the provider reports the array at all, we
        // trust it and mark fromProvider=true.
        if (obj.has("capabilities") && obj.get("capabilities").isJsonArray()) {
            for (var c : obj.getAsJsonArray("capabilities")) {
                if (c.isJsonPrimitive() && "thinking".equalsIgnoreCase(c.getAsString())) {
                    return new ThinkingDetection(true, true);
                }
            }
            return new ThinkingDetection(false, true);
        }

        var id = getString(obj, "id", "").toLowerCase();
        if (id.contains("o1") || id.contains("o3") || id.contains("o4-mini")
                || id.contains("deepseek-r1") || id.contains("qwq")) {
            return new ThinkingDetection(true, false);
        }

        return new ThinkingDetection(false, false);
    }

    /**
     * Shared return shape for capability detectors. {@code confirmed} is the
     * best-effort final answer; {@code fromProvider} is true when the provider
     * explicitly reported enough metadata to decide (so the UI can lock the
     * corresponding checkbox), false when we fell back to an ID-based heuristic
     * or defaulted to absence.
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

        if (obj.has("capabilities") && obj.get("capabilities").isJsonArray()) {
            for (var c : obj.getAsJsonArray("capabilities")) {
                if (c.isJsonPrimitive() && "vision".equalsIgnoreCase(c.getAsString())) {
                    return new CapabilityDetection(true, true);
                }
            }
            return new CapabilityDetection(false, true);
        }

        var id = getString(obj, "id", "").toLowerCase();
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

        if (obj.has("capabilities") && obj.get("capabilities").isJsonArray()) {
            for (var c : obj.getAsJsonArray("capabilities")) {
                if (c.isJsonPrimitive() && "audio".equalsIgnoreCase(c.getAsString())) {
                    return new CapabilityDetection(true, true);
                }
            }
            return new CapabilityDetection(false, true);
        }

        var id = getString(obj, "id", "").toLowerCase();
        if (id.contains("gpt-4o-audio") || id.contains("gpt-5-audio")
                || id.contains("gemini-2.5-flash-audio") || id.contains("whisper")
                || id.contains("qwen2-audio") || id.contains("voxtral")) {
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
        if (!obj.has("architecture") || !obj.get("architecture").isJsonObject()) return null;
        var arch = obj.getAsJsonObject("architecture");

        if (arch.has("input_modalities") && arch.get("input_modalities").isJsonArray()) {
            for (var m : arch.getAsJsonArray("input_modalities")) {
                if (m.isJsonPrimitive() && modality.equalsIgnoreCase(m.getAsString())) {
                    return new CapabilityDetection(true, true);
                }
            }
            return new CapabilityDetection(false, true);
        }

        if (arch.has("modality") && arch.get("modality").isJsonPrimitive()) {
            var s = arch.get("modality").getAsString().toLowerCase();
            return new CapabilityDetection(s.contains(modality), true);
        }

        return null;
    }

    public static double inferPrice(JsonObject obj, String type) {
        if (obj.has("pricing") && obj.get("pricing").isJsonObject()) {
            var pricing = obj.getAsJsonObject("pricing");
            if (pricing.has(type) && !pricing.get(type).isJsonNull()) {
                try {
                    var perToken = Double.parseDouble(pricing.get(type).getAsString());
                    return perToken * 1_000_000;
                } catch (NumberFormatException _) {}
            }
        }
        return -1;
    }

    private static boolean inferIsFree(JsonObject obj) {
        if (obj.has("pricing") && obj.get("pricing").isJsonObject()) {
            var pricing = obj.getAsJsonObject("pricing");
            try {
                var prompt = pricing.has("prompt") && !pricing.get("prompt").isJsonNull()
                        ? Double.parseDouble(pricing.get("prompt").getAsString()) : -1;
                var completion = pricing.has("completion") && !pricing.get("completion").isJsonNull()
                        ? Double.parseDouble(pricing.get("completion").getAsString()) : -1;
                return prompt == 0 && completion == 0;
            } catch (NumberFormatException _) {}
        }
        return false;
    }

    // --- Leaderboard ---

    static List<String> fetchLeaderboard(String leaderboardUrl) {
        if (leaderboardUrl == null || leaderboardUrl.isBlank()) return List.of();

        try {
            var req = new okhttp3.Request.Builder()
                    .url(leaderboardUrl)
                    .header("Accept", "text/html,application/json")
                    .get()
                    .build();
            var call = utils.HttpFactories.general().newCall(req);
            call.timeout().timeout(LEADERBOARD_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            String body;
            try (var response = call.execute()) {
                if (response.code() != 200) return List.of();
                body = response.body() != null ? response.body().string() : "";
            }

            try {
                var json = JsonParser.parseString(body);
                if (json.isJsonArray()) {
                    return parseJsonLeaderboard(json.getAsJsonArray());
                }
                if (json.isJsonObject() && json.getAsJsonObject().has("data")) {
                    return parseJsonLeaderboard(json.getAsJsonObject().getAsJsonArray("data"));
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
            var id = getString(obj, "id", getString(obj, "slug", getString(obj, "name", "")));
            if (!id.isBlank()) result.add(id.toLowerCase());
        }
        return result;
    }

    static List<String> parseHtmlLeaderboard(String html) {
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();

        var hrefMatcher = HREF_PATTERN.matcher(html);
        while (hrefMatcher.find()) {
            var slug = hrefMatcher.group(1).toLowerCase();
            if (slug.startsWith("docs/") || slug.startsWith("api/") || slug.startsWith("_next/")
                    || slug.startsWith("images/") || slug.startsWith("settings/")) {
                continue;
            }
            var baseSlug = slug.contains(":") ? slug.substring(0, slug.indexOf(':')) : slug;
            if (seen.add(baseSlug)) result.add(baseSlug);
        }

        if (result.isEmpty()) {
            var usageMatcher = USAGE_PATTERN.matcher(html);
            while (usageMatcher.find()) {
                var slug = usageMatcher.group(1).toLowerCase();
                var baseSlug = slug.contains(":") ? slug.substring(0, slug.indexOf(':')) : slug;
                if (!baseSlug.startsWith("api/") && !baseSlug.startsWith("docs/")
                        && !baseSlug.startsWith("_next/")) {
                    if (seen.add(baseSlug)) result.add(baseSlug);
                }
            }
        }

        return result;
    }

    static void applyRankings(List<Map<String, Object>> models, List<String> rankings) {
        for (var model : models) {
            var modelId = model.get("id").toString().toLowerCase();
            var modelBase = stripVariant(modelId);
            int bestRank = Integer.MAX_VALUE;

            for (int i = 0; i < rankings.size(); i++) {
                var ranked = rankings.get(i);
                if (modelBase.equals(ranked) || modelId.equals(ranked)
                        || stripVersionSuffix(modelBase).equals(stripVersionSuffix(ranked))) {
                    if (i + 1 < bestRank) bestRank = i + 1;
                    break;
                }
            }

            if (bestRank < Integer.MAX_VALUE) {
                model.put("leaderboardRank", bestRank);
            }
        }
    }

    public static String stripVariant(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(0, idx) : id;
    }

    public static String stripVersionSuffix(String id) {
        return id.replaceAll("-\\d{6,}$", "").replaceAll("-\\d{3,4}$", "");
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
    static DiscoveryResult discoverLmStudioNative(String providerName, String baseUrl, String apiKey) {
        try {
            var nativeBase = stripV1Suffix(baseUrl);
            var url = nativeBase + "/api/v0/models";
            var req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .header("Accept", "application/json")
                    .get()
                    .build();
            var call = utils.HttpFactories.llmSingleShot().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var resp = call.execute()) {
                statusCode = resp.code();
                responseBody = resp.body() != null ? resp.body().string() : "";
            }
            if (statusCode != 200) return null;

            var body = JsonParser.parseString(responseBody).getAsJsonObject();
            var models = parseLmStudioNativeResponse(body);

            models.sort((a, b) -> {
                var nameA = a.get("name") != null ? a.get("name").toString() : a.get("id").toString();
                var nameB = b.get("name") != null ? b.get("name").toString() : b.get("id").toString();
                return nameA.compareToIgnoreCase(nameB);
            });

            return new DiscoveryResult.Ok(models);
        } catch (Exception e) {
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
        if (!body.has("data") || !body.get("data").isJsonArray()) return results;

        for (var el : body.getAsJsonArray("data")) {
            if (!el.isJsonObject()) continue;
            var entry = el.getAsJsonObject();

            var type = getString(entry, "type", "").toLowerCase();
            if (!"llm".equals(type) && !"vlm".equals(type)) continue;

            var id = getString(entry, "id", "");
            if (id.isBlank()) continue;

            var ctxWin = entry.has("max_context_length") && !entry.get("max_context_length").isJsonNull()
                    ? entry.get("max_context_length").getAsInt()
                    : 0;

            var model = new LinkedHashMap<String, Object>();
            model.put("id", id);
            model.put("name", id.contains("/") ? id.substring(id.lastIndexOf('/') + 1) : id);
            model.put("contextWindow", ctxWin);
            model.put("maxTokens", 0);

            // Vision is authoritative from the type field.
            boolean isVlm = "vlm".equals(type);
            model.put("supportsVision", isVlm);
            model.put("visionDetectedFromProvider", true);

            // Thinking and audio aren't enumerated in the native API.
            // Leave fromProvider=false so the existing id-based heuristic
            // picks up known thinking models (deepseek-r1, qwq, etc.)
            // without overriding a confirmed answer here.
            model.put("supportsThinking", false);
            model.put("thinkingDetectedFromProvider", false);
            model.put("supportsAudio", false);
            model.put("audioDetectedFromProvider", false);

            // Local models — no pricing data.
            model.put("promptPrice", -1.0);
            model.put("completionPrice", -1.0);
            model.put("cachedReadPrice", -1.0);
            model.put("cacheWritePrice", -1.0);
            model.put("isFree", false);

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
    static DiscoveryResult discoverOllamaNative(String providerName, String baseUrl, String apiKey) {
        try {
            var nativeBase = stripV1Suffix(baseUrl);
            var tagsReq = new okhttp3.Request.Builder()
                    .url(nativeBase + "/api/tags")
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .header("Accept", "application/json")
                    .get()
                    .build();
            var tagsCall = utils.HttpFactories.llmSingleShot().newCall(tagsReq);
            tagsCall.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int tagsStatus;
            String tagsResponseBody;
            try (var tagsResp = tagsCall.execute()) {
                tagsStatus = tagsResp.code();
                tagsResponseBody = tagsResp.body() != null ? tagsResp.body().string() : "";
            }
            if (tagsStatus != 200) {
                return new DiscoveryResult.Error(502,
                        "Provider returned HTTP %d from /api/tags: %s".formatted(
                                tagsStatus, Strings.truncate(tagsResponseBody, 200)));
            }
            var tagsBody = JsonParser.parseString(tagsResponseBody).getAsJsonObject();
            var modelIds = extractTagIds(tagsBody);
            if (modelIds.isEmpty()) return new DiscoveryResult.Ok(List.of());

            var showUrl = nativeBase + "/api/show";
            var results = new ArrayList<Map<String, Object>>(modelIds.size());
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var futures = modelIds.stream()
                        .map(id -> executor.submit(() -> fetchOllamaShow(providerName, showUrl, apiKey, id)))
                        .toList();
                for (int i = 0; i < futures.size(); i++) {
                    try {
                        var model = futures.get(i).get(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                        if (model != null) results.add(model);
                    } catch (Exception e) {
                        play.Logger.warn("Ollama /api/show failed for %s: %s",
                                modelIds.get(i), e.getMessage());
                    }
                }
            }
            if (results.isEmpty()) {
                // JCLAW-183: covers both "every /api/show call failed" and
                // "every model was filtered out as non-chat" (e.g. an Ollama
                // install with only nomic-embed-text pulled). Either way the
                // operator gets a clear "nothing chat-capable here" message.
                return new DiscoveryResult.Error(502,
                        "No chat-capable models discovered for provider " + providerName);
            }

            var leaderboardUrl = ConfigService.get("provider." + providerName + ".leaderboardUrl");
            var rankings = fetchLeaderboard(leaderboardUrl);
            if (!rankings.isEmpty()) applyRankings(results, rankings);

            results.sort((a, b) -> {
                var rankA = a.get("leaderboardRank");
                var rankB = b.get("leaderboardRank");
                if (rankA != null && rankB != null) return Integer.compare((int) rankA, (int) rankB);
                if (rankA != null) return -1;
                if (rankB != null) return 1;
                var nameA = a.get("name") != null ? a.get("name").toString() : a.get("id").toString();
                var nameB = b.get("name") != null ? b.get("name").toString() : b.get("id").toString();
                return nameA.compareToIgnoreCase(nameB);
            });

            return new DiscoveryResult.Ok(results);

        } catch (JsonSyntaxException e) {
            return new DiscoveryResult.Error(502, "Invalid JSON response from provider");
        } catch (Exception e) {
            return new DiscoveryResult.Error(502,
                    "Failed to connect to provider: %s".formatted(e.getMessage()));
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
        if (!body.has("models") || !body.get("models").isJsonArray()) return out;
        for (var el : body.getAsJsonArray("models")) {
            if (!el.isJsonObject()) continue;
            var o = el.getAsJsonObject();
            var id = getString(o, "name", getString(o, "model", ""));
            if (!id.isBlank()) out.add(id);
        }
        return out;
    }

    private static Map<String, Object> fetchOllamaShow(String providerName, String url, String apiKey, String id) {
        try {
            var body = "{\"name\":\"" + id.replace("\"", "\\\"") + "\"}";
            var jsonMediaType = okhttp3.MediaType.get("application/json");
            var req = new okhttp3.Request.Builder()
                    .url(url)
                    .header("Authorization", "Bearer " + (apiKey != null ? apiKey : ""))
                    .header("Accept", "application/json")
                    .post(okhttp3.RequestBody.create(body, jsonMediaType))
                    .build();
            var call = utils.HttpFactories.llmSingleShot().newCall(req);
            call.timeout().timeout(DISCOVER_TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var resp = call.execute()) {
                statusCode = resp.code();
                responseBody = resp.body() != null ? resp.body().string() : "";
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
    public static Map<String, Object> parseOllamaShow(String id, JsonObject show) {
        // JCLAW-183 Tier 1: drop embedding-only models. Ollama's
        // /api/show capabilities array distinguishes "completion"
        // (chat-capable) from "embedding" (vector-only). When the array
        // is present and non-empty but lacks "completion", the model
        // can't serve chat — return null so {@code discoverOllamaNative}
        // drops it from the discovery list. A model with no capabilities
        // array (older Ollama versions) is kept; the existing detectors
        // fall back to id-based heuristics.
        if (show.has("capabilities") && show.get("capabilities").isJsonArray()) {
            var caps = show.getAsJsonArray("capabilities");
            if (caps.size() > 0) {
                boolean hasCompletion = false;
                for (var c : caps) {
                    if (c.isJsonPrimitive() && "completion".equalsIgnoreCase(c.getAsString())) {
                        hasCompletion = true;
                        break;
                    }
                }
                if (!hasCompletion) return null;
            }
        }

        var model = new LinkedHashMap<String, Object>();
        model.put("id", id);
        model.put("name", id);
        model.put("contextWindow", extractOllamaContextLength(show));
        model.put("maxTokens", 0);

        var forDetect = new JsonObject();
        forDetect.addProperty("id", id);
        if (show.has("capabilities") && show.get("capabilities").isJsonArray()) {
            forDetect.add("capabilities", show.getAsJsonArray("capabilities"));
        }
        var thinking = detectThinkingSupport(forDetect);
        model.put("supportsThinking", thinking.confirmed());
        model.put("thinkingDetectedFromProvider", thinking.fromProvider());
        var vision = detectVisionSupport(forDetect);
        model.put("supportsVision", vision.confirmed());
        model.put("visionDetectedFromProvider", vision.fromProvider());
        var audio = detectAudioSupport(forDetect);
        model.put("supportsAudio", audio.confirmed());
        model.put("audioDetectedFromProvider", audio.fromProvider());

        // Ollama doesn't publish pricing via the API. -1 means "unset" —
        // the frontend skips these fields when saving.
        model.put("promptPrice", -1.0);
        model.put("completionPrice", -1.0);
        model.put("cachedReadPrice", -1.0);
        model.put("cacheWritePrice", -1.0);
        model.put("isFree", false);

        return model;
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
        if (show == null || !show.has("model_info") || !show.get("model_info").isJsonObject()) return 0;
        var mi = show.getAsJsonObject("model_info");
        for (var entry : mi.entrySet()) {
            if (entry.getKey().endsWith(".context_length") && entry.getValue().isJsonPrimitive()) {
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
