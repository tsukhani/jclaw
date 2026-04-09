package controllers;

import com.google.gson.*;
import llm.LlmTypes.ModelInfo;
import play.mvc.Controller;
import play.mvc.With;
import services.ConfigService;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

/**
 * Provider management endpoints — model discovery from provider APIs.
 */
@With(AuthCheck.class)
public class ApiProvidersController extends Controller {

    private static final Gson gson = new Gson();

    /**
     * POST /api/providers/{name}/discover-models
     * Fetches the model catalog from the provider's /models endpoint.
     * Returns normalized model info including auto-detected capabilities.
     */
    public static void discoverModels(String name) {
        var baseUrl = ConfigService.get("provider." + name + ".baseUrl");
        var apiKey = ConfigService.get("provider." + name + ".apiKey");

        if (baseUrl == null || baseUrl.isBlank()) {
            error(400, "Provider '%s' has no base URL configured".formatted(name));
        }
        if (apiKey == null || apiKey.isBlank()) {
            error(400, "Provider '%s' has no API key configured".formatted(name));
        }

        try {
            var url = baseUrl.endsWith("/") ? baseUrl + "models" : baseUrl + "/models";
            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(15))
                    .GET()
                    .build();

            var response = utils.HttpClients.GENERAL.send(httpReq, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                error(502, "Provider returned HTTP %d: %s".formatted(
                        response.statusCode(), truncate(response.body(), 200)));
            }

            var body = JsonParser.parseString(response.body()).getAsJsonObject();
            var models = parseModels(body, name);

            // Fetch leaderboard rankings if configured
            var leaderboardUrl = ConfigService.get("provider." + name + ".leaderboardUrl");
            var rankings = fetchLeaderboard(leaderboardUrl);
            if (!rankings.isEmpty()) {
                applyRankings(models, rankings);
            }

            // Sort: ranked models first (by rank), then unranked by name
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

            renderJSON(gson.toJson(Map.of("models", models, "count", models.size())));

        } catch (JsonSyntaxException e) {
            error(502, "Invalid JSON response from provider");
        } catch (play.exceptions.PlayException e) {
            throw e; // Re-throw Play's error() calls
        } catch (Exception e) {
            error(502, "Failed to connect to provider: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Parse models from the provider response. Handles different provider formats:
     * - OpenRouter: rich metadata with supported_parameters
     * - OpenAI: minimal (id, created, owned_by)
     * - Ollama: OpenAI-compatible wrapper
     */
    private static List<Map<String, Object>> parseModels(JsonObject body, String providerName) {
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
            model.put("promptPrice", inferPrice(obj, "prompt"));
            model.put("completionPrice", inferPrice(obj, "completion"));
            model.put("isFree", inferIsFree(obj));

            // Skip models with empty IDs
            if (model.get("id").toString().isBlank()) continue;

            result.add(model);
        }

        return result;
    }

    private static String inferName(JsonObject obj) {
        // Try "name" first, then derive from "id"
        if (obj.has("name") && !obj.get("name").isJsonNull()) {
            return obj.get("name").getAsString();
        }
        var id = getString(obj, "id", "");
        // Strip provider prefix for cleaner display (e.g., "openai/gpt-4" -> "GPT-4")
        if (id.contains("/")) {
            id = id.substring(id.lastIndexOf('/') + 1);
        }
        return id;
    }

    private static int inferContextWindow(JsonObject obj) {
        // OpenRouter: context_length
        if (obj.has("context_length") && !obj.get("context_length").isJsonNull()) {
            return obj.get("context_length").getAsInt();
        }
        // Some providers use "context_window"
        if (obj.has("context_window") && !obj.get("context_window").isJsonNull()) {
            return obj.get("context_window").getAsInt();
        }
        return 0;
    }

    private static int inferMaxTokens(JsonObject obj) {
        // OpenRouter: top_provider.max_completion_tokens
        if (obj.has("top_provider") && obj.get("top_provider").isJsonObject()) {
            var tp = obj.getAsJsonObject("top_provider");
            if (tp.has("max_completion_tokens") && !tp.get("max_completion_tokens").isJsonNull()) {
                return tp.get("max_completion_tokens").getAsInt();
            }
        }
        // Some providers expose this directly
        if (obj.has("max_completion_tokens") && !obj.get("max_completion_tokens").isJsonNull()) {
            return obj.get("max_completion_tokens").getAsInt();
        }
        if (obj.has("max_tokens") && !obj.get("max_tokens").isJsonNull()) {
            return obj.get("max_tokens").getAsInt();
        }
        return 0;
    }

    private record ThinkingDetection(boolean confirmed, boolean fromProvider) {}

    private static ThinkingDetection detectThinkingSupport(JsonObject obj) {
        // OpenRouter: check supported_parameters for "reasoning"
        if (obj.has("supported_parameters") && obj.get("supported_parameters").isJsonArray()) {
            for (var param : obj.getAsJsonArray("supported_parameters")) {
                if (param.isJsonPrimitive()) {
                    var val = param.getAsString();
                    if ("reasoning".equals(val) || "reasoning_effort".equals(val)) {
                        return new ThinkingDetection(true, true);
                    }
                }
            }
            // Provider gave us supported_parameters but "reasoning" wasn't in it — confirmed off
            return new ThinkingDetection(false, true);
        }

        // Fallback: check model ID patterns for known thinking models (not provider-confirmed)
        var id = getString(obj, "id", "").toLowerCase();
        if (id.contains("o1") || id.contains("o3") || id.contains("o4-mini")
                || id.contains("deepseek-r1") || id.contains("qwq")) {
            return new ThinkingDetection(true, false);
        }

        return new ThinkingDetection(false, false);
    }

    /**
     * Extract price per token from pricing object.
     * OpenRouter format: { pricing: { prompt: "0.000001", completion: "0.000002" } }
     * Returns price per million tokens as a double, or -1 if unavailable.
     */
    private static double inferPrice(JsonObject obj, String type) {
        if (obj.has("pricing") && obj.get("pricing").isJsonObject()) {
            var pricing = obj.getAsJsonObject("pricing");
            if (pricing.has(type) && !pricing.get(type).isJsonNull()) {
                try {
                    var perToken = Double.parseDouble(pricing.get(type).getAsString());
                    return perToken * 1_000_000; // Convert to per-million-tokens
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

    /**
     * Fetch a leaderboard page and extract an ordered list of model identifiers.
     * Returns a list of model slug/name fragments in ranked order (index 0 = #1).
     * Best-effort: returns empty list on any failure.
     */
    private static List<String> fetchLeaderboard(String leaderboardUrl) {
        if (leaderboardUrl == null || leaderboardUrl.isBlank()) return List.of();

        try {
            var httpReq = HttpRequest.newBuilder()
                    .uri(URI.create(leaderboardUrl))
                    .header("Accept", "text/html,application/json")
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

            var response = utils.HttpClients.GENERAL.send(httpReq, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return List.of();

            var body = response.body();

            // Try JSON first (if the URL returns a JSON array of models)
            try {
                var json = JsonParser.parseString(body);
                if (json.isJsonArray()) {
                    return parseJsonLeaderboard(json.getAsJsonArray());
                }
                if (json.isJsonObject() && json.getAsJsonObject().has("data")) {
                    return parseJsonLeaderboard(json.getAsJsonObject().getAsJsonArray("data"));
                }
            } catch (JsonSyntaxException _) {
                // Not JSON — parse as HTML
            }

            return parseHtmlLeaderboard(body);

        } catch (Exception e) {
            services.EventLogger.warn("provider", "Leaderboard fetch failed: %s".formatted(e.getMessage()));
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

    /**
     * Extract model names from an HTML leaderboard page.
     *
     * For OpenRouter's rankings page, the leaderboard is rendered as an ordered list of links
     * like href="/provider/model-name". We extract these links in document order, which matches
     * the displayed ranking order (#1 first, #2 second, etc.).
     *
     * The page also contains chart data with "provider/model":tokenCount patterns, but these
     * represent historical weekly data, not the current leaderboard — so we avoid those.
     */
    private static List<String> parseHtmlLeaderboard(String html) {
        var result = new ArrayList<String>();
        var seen = new HashSet<String>();

        // Strategy 1: Extract href="/provider/model" links — these are the rendered leaderboard
        // entries in document order. Match "provider/model" but exclude known non-model paths.
        var hrefPattern = java.util.regex.Pattern.compile(
                "href=\"/([a-zA-Z0-9][-a-zA-Z0-9._]*/[a-zA-Z0-9][-a-zA-Z0-9._:]*?)\"");
        var hrefMatcher = hrefPattern.matcher(html);
        while (hrefMatcher.find()) {
            var slug = hrefMatcher.group(1).toLowerCase();
            // Exclude non-model paths
            if (slug.startsWith("docs/") || slug.startsWith("api/") || slug.startsWith("_next/")
                    || slug.startsWith("images/") || slug.startsWith("settings/")) {
                continue;
            }
            // Strip :variant suffixes for deduplication
            var baseSlug = slug.contains(":") ? slug.substring(0, slug.indexOf(':')) : slug;
            if (seen.add(baseSlug)) result.add(baseSlug);
        }

        // Strategy 2: Fallback — extract "provider/model":number patterns from embedded JSON
        if (result.isEmpty()) {
            var usagePattern = java.util.regex.Pattern.compile(
                    "\"([a-zA-Z0-9_-]+/[a-zA-Z0-9._:-]+)\"\\s*:\\s*\\d");
            var usageMatcher = usagePattern.matcher(html);
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

    /**
     * Apply leaderboard rankings to discovered models.
     * Matches by model ID with fuzzy matching for variant suffixes (:free, :thinking, etc.)
     * and version suffixes (-20250219, -0528, etc.)
     */
    private static void applyRankings(List<Map<String, Object>> models, List<String> rankings) {
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

    /** Strip :variant suffix (e.g., ":free", ":thinking") from a model ID. */
    private static String stripVariant(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 ? id.substring(0, idx) : id;
    }

    /** Strip date/version suffixes like -20250219, -0528, -001 from the end of a model ID. */
    private static String stripVersionSuffix(String id) {
        return id.replaceAll("-\\d{6,}$", "").replaceAll("-\\d{3,4}$", "");
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }
}
