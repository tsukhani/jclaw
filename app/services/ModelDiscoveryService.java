package services;

import com.google.gson.*;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;
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
     */
    public static DiscoveryResult discover(String providerName, String baseUrl, String apiKey) {
        try {
            var url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(DISCOVER_TIMEOUT_SECONDS))
                    .GET()
                    .build();

            var response = utils.HttpClients.GENERAL.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return new DiscoveryResult.Error(502, "Provider returned HTTP %d: %s".formatted(
                        response.statusCode(), truncate(response.body(), 200)));
            }

            var body = JsonParser.parseString(response.body()).getAsJsonObject();
            var models = parseModels(body);

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
            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(leaderboardUrl))
                    .header("Accept", "text/html,application/json")
                    .timeout(Duration.ofSeconds(LEADERBOARD_TIMEOUT_SECONDS))
                    .GET()
                    .build();

            var response = utils.HttpClients.GENERAL.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();

            var body = response.body();

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

    static String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
