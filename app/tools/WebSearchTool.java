package tools;

import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import services.ConfigService;
import services.EventLogger;
import utils.HttpClients;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Web search tool supporting multiple providers: Exa, Brave, and Tavily.
 * Uses the first enabled provider with a configured API key, or a user-specified
 * provider. All provider config (enabled flag, base URL, API key) lives in the
 * Config DB under {@code search.{id}.*} and is seeded by {@code DefaultConfigJob}.
 * A provider is skipped when {@code search.{id}.enabled} is {@code false} or its
 * API key is blank.
 */
public class WebSearchTool implements ToolRegistry.Tool {

    private static final Gson gson = new Gson();
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_NUM_RESULTS = 5;
    private static final int MAX_NUM_RESULTS = 10;

    private static final List<SearchProvider> PROVIDERS = List.of(
            new ExaProvider(),
            new BraveProvider(),
            new TavilyProvider(),
            new PerplexityProvider()
    );

    @Override
    public String name() { return "web_search"; }

    @Override
    public String description() {
        return """
                Search the web for current information. \
                Returns relevant results with titles, URLs, and content snippets. \
                Supports Exa, Brave, Tavily, and Perplexity search providers (uses first configured, or specify with 'provider' parameter). \
                Use this to find up-to-date information, research topics, or answer questions about recent events.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "The search query"),
                        "numResults", Map.of("type", "integer",
                                "description", "Number of results to return (default: 5, max: 10)"),
                        "provider", Map.of("type", "string",
                                "enum", List.of("exa", "brave", "tavily", "perplexity"),
                                "description", "Search provider to use (default: first configured)")
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var query = args.get("query").getAsString();
        var numResults = args.has("numResults")
                ? Math.min(args.get("numResults").getAsInt(), MAX_NUM_RESULTS) : DEFAULT_NUM_RESULTS;
        var preferredProvider = args.has("provider") ? args.get("provider").getAsString() : null;

        // Resolve provider
        SearchProvider provider = null;
        String apiKey = null;

        if (preferredProvider != null) {
            SearchProvider match = null;
            for (var p : PROVIDERS) {
                if (p.id().equals(preferredProvider)) { match = p; break; }
            }
            if (match == null) {
                return "Error: Unknown search provider '%s'. Supported: exa, brave, tavily, perplexity.".formatted(preferredProvider);
            }
            if (!match.isEnabled()) {
                return "Error: %s is disabled. Enable it in Settings.".formatted(match.displayName());
            }
            apiKey = ConfigService.get(match.apiKeyKey());
            if (apiKey == null || apiKey.isBlank()) {
                return "Error: %s API key not configured. Add '%s' in Settings.".formatted(
                        match.displayName(), match.apiKeyKey());
            }
            provider = match;
        } else {
            // Use first enabled provider with a configured key
            for (var p : PROVIDERS) {
                if (!p.isEnabled()) continue;
                var key = ConfigService.get(p.apiKeyKey());
                if (key != null && !key.isBlank()) {
                    provider = p;
                    apiKey = key;
                    break;
                }
            }
            if (provider == null) {
                return "Error: No search provider configured. Enable one of Exa, Brave, Tavily, or Perplexity and add its API key in Settings.";
            }
        }

        try {
            EventLogger.info("search", agent != null ? agent.name : null, null,
                    "Searching via %s: \"%s\" (numResults=%d)".formatted(provider.displayName(), query, numResults));

            var request = provider.buildRequest(apiKey, query, numResults);
            var response = HttpClients.LLM.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                EventLogger.error("search", agent != null ? agent.name : null, null,
                        "%s API returned HTTP %d: %s".formatted(provider.displayName(), response.statusCode(),
                                response.body().substring(0, Math.min(response.body().length(), 200))));
                return "Error: %s API returned HTTP %d: %s".formatted(
                        provider.displayName(), response.statusCode(),
                        response.body().substring(0, Math.min(response.body().length(), 200)));
            }

            var formatted = provider.formatResults(response.body());
            EventLogger.info("search", agent != null ? agent.name : null, null,
                    "%s returned %d chars for \"%s\"".formatted(provider.displayName(), formatted.length(), query));

            return formatted;
        } catch (Exception e) {
            EventLogger.error("search", agent != null ? agent.name : null, null,
                    "%s search failed: %s".formatted(provider.displayName(), e.getMessage()));
            return "Error searching with %s: %s".formatted(provider.displayName(), e.getMessage());
        }
    }

    // --- Provider interface ---

    interface SearchProvider {
        String id();
        String displayName();
        String defaultBaseUrl();
        HttpRequest buildRequest(String apiKey, String query, int numResults);
        String formatResults(String responseJson);

        default String apiKeyKey() { return "search." + id() + ".apiKey"; }
        default String baseUrlKey() { return "search." + id() + ".baseUrl"; }
        default String enabledKey() { return "search." + id() + ".enabled"; }

        default boolean isEnabled() {
            return "true".equalsIgnoreCase(ConfigService.get(enabledKey(), "true"));
        }

        default String baseUrl() {
            return ConfigService.get(baseUrlKey(), defaultBaseUrl());
        }
    }

    // --- Exa ---

    static class ExaProvider implements SearchProvider {
        @Override public String id() { return "exa"; }
        @Override public String displayName() { return "Exa"; }
        @Override public String defaultBaseUrl() { return "https://api.exa.ai/search"; }

        @Override
        public HttpRequest buildRequest(String apiKey, String query, int numResults) {
            var body = new java.util.HashMap<String, Object>();
            body.put("query", query);
            body.put("numResults", numResults);
            body.put("contents", Map.of("highlights", Map.of("maxCharacters", 4000)));
            return HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl()))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
        }

        @Override
        public String formatResults(String responseJson) {
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("results") || json.getAsJsonArray("results").isEmpty()) {
                return "No results found.";
            }
            var results = json.getAsJsonArray("results");
            var sb = new StringBuilder("Found %d results (via Exa):\n\n".formatted(results.size()));
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i).getAsJsonObject();
                sb.append("### %d. %s\n".formatted(i + 1,
                        r.has("title") ? r.get("title").getAsString() : "Untitled"));
                sb.append("URL: %s\n".formatted(r.has("url") ? r.get("url").getAsString() : ""));
                if (r.has("highlights") && r.getAsJsonArray("highlights").size() > 0) {
                    for (var h : r.getAsJsonArray("highlights")) {
                        sb.append("> %s\n".formatted(h.getAsString().strip()));
                    }
                }
                sb.append("\n");
            }
            return sb.toString().strip();
        }
    }

    // --- Brave ---

    static class BraveProvider implements SearchProvider {
        @Override public String id() { return "brave"; }
        @Override public String displayName() { return "Brave"; }
        @Override public String defaultBaseUrl() { return "https://api.search.brave.com/res/v1/web/search"; }

        @Override
        public HttpRequest buildRequest(String apiKey, String query, int numResults) {
            var encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            var url = "%s?q=%s&count=%d".formatted(baseUrl(), encodedQuery, numResults);
            return HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .GET()
                    .build();
        }

        @Override
        public String formatResults(String responseJson) {
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("web") || !json.getAsJsonObject("web").has("results")
                    || json.getAsJsonObject("web").getAsJsonArray("results").isEmpty()) {
                return "No results found.";
            }
            var results = json.getAsJsonObject("web").getAsJsonArray("results");
            var sb = new StringBuilder("Found %d results (via Brave):\n\n".formatted(results.size()));
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i).getAsJsonObject();
                sb.append("### %d. %s\n".formatted(i + 1,
                        r.has("title") ? r.get("title").getAsString() : "Untitled"));
                sb.append("URL: %s\n".formatted(r.has("url") ? r.get("url").getAsString() : ""));
                if (r.has("description") && !r.get("description").isJsonNull()) {
                    sb.append("> %s\n".formatted(r.get("description").getAsString().strip()));
                }
                sb.append("\n");
            }
            return sb.toString().strip();
        }
    }

    // --- Tavily ---

    static class TavilyProvider implements SearchProvider {
        @Override public String id() { return "tavily"; }
        @Override public String displayName() { return "Tavily"; }
        @Override public String defaultBaseUrl() { return "https://api.tavily.com/search"; }

        @Override
        public HttpRequest buildRequest(String apiKey, String query, int numResults) {
            var body = new java.util.HashMap<String, Object>();
            body.put("query", query);
            body.put("max_results", numResults);
            body.put("search_depth", "basic");
            body.put("include_answer", false);
            return HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
        }

        @Override
        public String formatResults(String responseJson) {
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("results") || json.getAsJsonArray("results").isEmpty()) {
                return "No results found.";
            }
            var results = json.getAsJsonArray("results");
            var sb = new StringBuilder("Found %d results (via Tavily):\n\n".formatted(results.size()));
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i).getAsJsonObject();
                sb.append("### %d. %s\n".formatted(i + 1,
                        r.has("title") ? r.get("title").getAsString() : "Untitled"));
                sb.append("URL: %s\n".formatted(r.has("url") ? r.get("url").getAsString() : ""));
                if (r.has("content") && !r.get("content").isJsonNull()) {
                    var content = r.get("content").getAsString().strip();
                    if (content.length() > 500) content = content.substring(0, 500) + "...";
                    sb.append("> %s\n".formatted(content));
                }
                sb.append("\n");
            }
            return sb.toString().strip();
        }
    }

    // --- Perplexity ---

    static class PerplexityProvider implements SearchProvider {
        /**
         * Default recency filter sent on every Perplexity /search request. Valid
         * values are {@code hour|day|week|month|year}. Server-side filtering is
         * the only reliable fix for "latest X" queries — the LLM will not add
         * year/month keywords just because the system prompt says today's date,
         * and stale snippets convince it to echo stale dates in its answer.
         * Overridable per-install via {@code search.perplexity.recencyFilter}.
         */
        private static final String DEFAULT_RECENCY_FILTER = "month";

        @Override public String id() { return "perplexity"; }
        @Override public String displayName() { return "Perplexity"; }
        @Override public String defaultBaseUrl() { return "https://api.perplexity.ai/search"; }

        @Override
        public HttpRequest buildRequest(String apiKey, String query, int numResults) {
            var body = new java.util.HashMap<String, Object>();
            body.put("query", query);
            body.put("max_results", numResults);
            var recency = ConfigService.get("search.perplexity.recencyFilter", DEFAULT_RECENCY_FILTER);
            if (recency != null && !recency.isBlank() && !"none".equalsIgnoreCase(recency)) {
                body.put("search_recency_filter", recency);
            }
            return HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl()))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(gson.toJson(body), StandardCharsets.UTF_8))
                    .build();
        }

        @Override
        public String formatResults(String responseJson) {
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("results") || json.getAsJsonArray("results").isEmpty()) {
                return "No results found.";
            }
            var results = json.getAsJsonArray("results");
            var sb = new StringBuilder("Found %d results (via Perplexity):\n\n".formatted(results.size()));
            for (int i = 0; i < results.size(); i++) {
                var r = results.get(i).getAsJsonObject();
                sb.append("### %d. %s\n".formatted(i + 1,
                        r.has("title") ? r.get("title").getAsString() : "Untitled"));
                sb.append("URL: %s\n".formatted(r.has("url") ? r.get("url").getAsString() : ""));
                if (r.has("snippet") && !r.get("snippet").isJsonNull()) {
                    sb.append("> %s\n".formatted(r.get("snippet").getAsString().strip()));
                }
                sb.append("\n");
            }
            return sb.toString().strip();
        }
    }
}
