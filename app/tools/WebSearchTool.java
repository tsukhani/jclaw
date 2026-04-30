package tools;

import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import services.EventLogger;
import utils.HttpFactories;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static final com.google.gson.Gson gson = utils.GsonHolder.INSTANCE;
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_NUM_RESULTS = 5;
    private static final int MAX_NUM_RESULTS = 10;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    /**
     * JCLAW-170: structured per-result shape the UI renders as clickable chips.
     * {@code faviconUrl} resolves through the DuckDuckGo favicon service, which
     * handles every host uniformly and does not require an API key. {@code null}
     * when the host cannot be parsed.
     */
    public record SearchResult(String title, String url, String snippet, String faviconUrl) {}

    /** Outcome of a provider call — carries both the LLM-visible markdown and
     *  the structured list the UI uses for JCLAW-170 result chips. */
    private record SearchOutcome(String text, List<SearchResult> results, String providerDisplayName, boolean ok) {
        static SearchOutcome error(String msg) { return new SearchOutcome(msg, null, null, false); }
    }

    private static final List<SearchProvider> PROVIDERS = List.of(
            new ExaProvider(),
            new BraveProvider(),
            new TavilyProvider(),
            new PerplexityProvider(),
            new OllamaProvider(),
            new FeloProvider()
    );

    @Override
    public String name() { return "web_search"; }

    @Override
    public String category() { return "Web"; }

    @Override
    public String icon() { return "search"; }

    @Override
    public String shortDescription() {
        return "Search the web using Exa, Brave, Tavily, or Perplexity for current results.";
    }

    @Override
    public java.util.List<agents.ToolAction> actions() {
        return java.util.List.of(
                new agents.ToolAction("search", "Execute a web search; provider is auto-selected or can be specified explicitly")
        );
    }

    @Override
    public String description() {
        return """
                Search the web for current information. \
                Returns relevant results with titles, URLs, and content snippets. \
                Use this to find up-to-date information, research topics, or answer questions about recent events.""";
    }

    @Override
    public Map<String, Object> parameters() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "The search query"),
                        "numResults", Map.of("type", "integer",
                                "description", "Number of results to return (default: 5, max: 10)")
                ),
                "required", List.of("query")
        );
    }

    /** Stateless search — each call is an independent HTTP request. Safe to
     *  run multiple queries in parallel. */
    @Override public boolean parallelSafe() { return true; }

    @Override
    public String execute(String argsJson, Agent agent) {
        return runFromArgs(argsJson, agent).text();
    }

    /**
     * JCLAW-170: rich variant. Emits both the LLM-visible markdown and a
     * structured JSON payload carrying the per-result title/url/snippet plus
     * a pre-resolved favicon URL, so the chat UI can render clickable result
     * chips instead of just rendering the markdown as plain text.
     */
    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        var outcome = runFromArgs(argsJson, agent);
        if (!outcome.ok() || outcome.results() == null || outcome.results().isEmpty()) {
            return ToolRegistry.ToolResult.text(outcome.text());
        }
        var payload = new LinkedHashMap<String, Object>();
        payload.put("provider", outcome.providerDisplayName());
        payload.put("results", outcome.results());
        return new ToolRegistry.ToolResult(outcome.text(), gson.toJson(payload));
    }

    private SearchOutcome runFromArgs(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var query = args.get("query").getAsString();
        var numResults = args.has("numResults")
                ? Math.min(args.get("numResults").getAsInt(), MAX_NUM_RESULTS) : DEFAULT_NUM_RESULTS;
        // Always use the configured provider priority order — the LLM should not
        // pick a provider. The 'provider' parameter was removed from the schema;
        // any stale tool-call that still passes it is silently ignored.
        return executeWithFallback(query, numResults, agent);
    }

    /**
     * Automatic provider selection: try each enabled provider in priority order,
     * falling back to the next on failure (HTTP error or exception).
     */
    private SearchOutcome executeWithFallback(String query, int numResults, Agent agent) {
        var candidates = resolveProvidersByPriority();
        if (candidates.isEmpty()) {
            return SearchOutcome.error("Error: No search provider configured. Enable one of Exa, Brave, Tavily, Perplexity, or Ollama and add its API key in Settings.");
        }

        SearchOutcome lastError = null;
        for (var entry : candidates) {
            var outcome = doSearch(entry.provider(), entry.apiKey(), query, numResults, agent);
            if (outcome.ok()) return outcome;
            lastError = outcome;
            EventLogger.warn("search", agent != null ? agent.name : null, null,
                    "%s failed, trying next provider. Error: %s".formatted(
                            entry.provider().displayName(), outcome.text()));
        }
        return lastError != null ? lastError : SearchOutcome.error("Error: No search provider available.");
    }

    private record ProviderEntry(SearchProvider provider, String apiKey) {}

    /** Return enabled providers with valid API keys, sorted by their configured priority. */
    private List<ProviderEntry> resolveProvidersByPriority() {
        var candidates = new ArrayList<ProviderEntry>();
        for (var p : PROVIDERS) {
            if (!p.isEnabled()) continue;
            var key = ConfigService.get(p.apiKeyKey());
            if (key != null && !key.isBlank()) {
                candidates.add(new ProviderEntry(p, key));
            }
        }
        // Pre-compute priorities to avoid ConfigService lookups inside the sort comparator
        var priorities = new java.util.HashMap<String, Integer>();
        for (var e : candidates) {
            priorities.put(e.provider().id(),
                    parseInt(ConfigService.get("search." + e.provider().id() + ".priority", "99"), 99));
        }
        candidates.sort(java.util.Comparator.comparingInt(e -> priorities.get(e.provider().id())));
        return candidates;
    }

    private SearchOutcome doSearch(SearchProvider provider, String apiKey, String query, int numResults, Agent agent) {
        try {
            EventLogger.info("search", agent != null ? agent.name : null, null,
                    "Searching via %s: \"%s\" (numResults=%d)".formatted(provider.displayName(), query, numResults));

            var request = provider.buildRequest(apiKey, query, numResults);
            var call = HttpFactories.general().newCall(request);
            call.timeout().timeout(TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var response = call.execute()) {
                statusCode = response.code();
                responseBody = response.body() != null ? response.body().string() : "";
            }

            if (statusCode != 200) {
                EventLogger.error("search", agent != null ? agent.name : null, null,
                        "%s API returned HTTP %d: %s".formatted(provider.displayName(), statusCode,
                                responseBody.substring(0, Math.min(responseBody.length(), 200))));
                return SearchOutcome.error("Error: %s API returned HTTP %d: %s".formatted(
                        provider.displayName(), statusCode,
                        responseBody.substring(0, Math.min(responseBody.length(), 200))));
            }

            var results = provider.parseResults(responseBody);
            var markdown = provider.formatResults(responseBody, results);
            EventLogger.info("search", agent != null ? agent.name : null, null,
                    "%s returned %d chars for \"%s\"".formatted(provider.displayName(), markdown.length(), query));

            return new SearchOutcome(markdown, results, provider.displayName(), true);
        } catch (Exception e) {
            EventLogger.error("search", agent != null ? agent.name : null, null,
                    "%s search failed: %s".formatted(provider.displayName(), e.getMessage()));
            return SearchOutcome.error("Error: searching with %s: %s".formatted(provider.displayName(), e.getMessage()));
        }
    }

    private static int parseInt(String s, int fallback) {
        try { return Integer.parseInt(s); } catch (NumberFormatException _) { return fallback; }
    }

    /**
     * JCLAW-170: resolve a result URL to a favicon URL via DuckDuckGo's public
     * icon service. Returns {@code null} when the URL can't be parsed or has
     * no host — the UI falls back to a generic globe icon on null.
     */
    static String faviconUrlFor(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            var host = URI.create(url).getHost();
            if (host == null || host.isBlank()) return null;
            return "https://icons.duckduckgo.com/ip3/" + host + ".ico";
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    /** Shared markdown renderer used by every provider so the LLM-visible
     *  shape stays identical before and after JCLAW-170's structured-result
     *  refactor. */
    private static String renderMarkdown(List<SearchResult> results, String providerDisplayName) {
        if (results.isEmpty()) return "No results found.";
        var sb = new StringBuilder("Found %d results (via %s):\n\n".formatted(results.size(), providerDisplayName));
        for (int i = 0; i < results.size(); i++) {
            appendMarkdownResult(sb, i + 1, results.get(i));
            sb.append("\n");
        }
        return sb.toString().strip();
    }

    private static void appendMarkdownResult(StringBuilder sb, int number, SearchResult result) {
        sb.append("### %d. %s\n".formatted(number, result.title() == null ? "Untitled" : result.title()));
        sb.append("URL: %s\n".formatted(result.url() == null ? "" : result.url()));
        if (result.snippet() != null && !result.snippet().isBlank()) {
            sb.append("> %s\n".formatted(result.snippet().strip()));
        }
    }

    @FunctionalInterface
    private interface SnippetReader {
        String snippet(JsonObject result);
    }

    private static List<SearchResult> parseResultArray(JsonArray arr, String urlKey, SnippetReader snippetReader) {
        var out = new ArrayList<SearchResult>(arr.size());
        for (var el : arr) {
            var r = el.getAsJsonObject();
            var title = stringOrDefault(r, "title", "Untitled");
            var url = stringOrDefault(r, urlKey, "");
            out.add(new SearchResult(title, url, snippetReader.snippet(r), faviconUrlFor(url)));
        }
        return out;
    }

    private static String stringOrDefault(JsonObject obj, String key, String fallback) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : fallback;
    }

    private static String nullableString(JsonObject obj, String key) {
        return obj.has(key) && !obj.get(key).isJsonNull() ? obj.get(key).getAsString() : null;
    }

    private static String trimmedContentSnippet(JsonObject obj) {
        var content = nullableString(obj, "content");
        if (content == null) return null;
        content = content.strip();
        return content.length() > 500 ? content.substring(0, 500) + "..." : content;
    }

    private static JsonArray topLevelArray(String responseJson, String key) {
        var json = JsonParser.parseString(responseJson).getAsJsonObject();
        return json.has(key) && !json.getAsJsonArray(key).isEmpty() ? json.getAsJsonArray(key) : null;
    }

    // --- Provider interface ---

    interface SearchProvider {
        String id();
        String displayName();
        String defaultBaseUrl();
        Request buildRequest(String apiKey, String query, int numResults);

        /** JCLAW-170: parse the provider's raw response into the structured
         *  per-result shape used both by the UI (chips with favicons) and by
         *  the shared markdown renderer above. Returns an empty list when
         *  the response has no results. */
        List<SearchResult> parseResults(String responseJson);

        /** Render the markdown text handed to the LLM. Default delegates to
         *  the shared renderer so every provider produces the same shape the
         *  pre-refactor callers saw. Felo overrides to prepend its
         *  answer-summary prefix. */
        default String formatResults(String responseJson, List<SearchResult> parsed) {
            return renderMarkdown(parsed, displayName());
        }

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
        public Request buildRequest(String apiKey, String query, int numResults) {
            var body = new java.util.HashMap<String, Object>();
            body.put("query", query);
            body.put("numResults", numResults);
            body.put("contents", Map.of("highlights", Map.of("maxCharacters", 4000)));
            return new Request.Builder()
                    .url(baseUrl())
                    .header("x-api-key", apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, "results");
            if (arr == null) return List.of();
            return parseResultArray(arr, "url", ExaProvider::joinedHighlights);
        }

        @Override
        public String formatResults(String responseJson, List<SearchResult> parsed) {
            // Exa's markdown shape historically emitted one line per highlight,
            // not a joined snippet. Preserve that so existing LLM prompts see
            // the same structure.
            if (parsed.isEmpty()) return "No results found.";
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            var arr = json.getAsJsonArray("results");
            var sb = new StringBuilder("Found %d results (via Exa):\n\n".formatted(parsed.size()));
            for (int i = 0; i < arr.size(); i++) {
                var r = arr.get(i).getAsJsonObject();
                var result = parsed.get(i);
                sb.append("### %d. %s\n".formatted(i + 1, result.title()));
                sb.append("URL: %s\n".formatted(result.url()));
                if (r.has("highlights") && r.getAsJsonArray("highlights").size() > 0) {
                    for (var h : r.getAsJsonArray("highlights")) {
                        sb.append("> %s\n".formatted(h.getAsString().strip()));
                    }
                }
                sb.append("\n");
            }
            return sb.toString().strip();
        }

        private static String joinedHighlights(JsonObject result) {
            if (!result.has("highlights") || result.getAsJsonArray("highlights").isEmpty()) return null;
            var sb = new StringBuilder();
            for (var h : result.getAsJsonArray("highlights")) {
                if (!sb.isEmpty()) sb.append(' ');
                sb.append(h.getAsString().strip());
            }
            return sb.toString();
        }
    }

    // --- Brave ---

    static class BraveProvider implements SearchProvider {
        @Override public String id() { return "brave"; }
        @Override public String displayName() { return "Brave"; }
        @Override public String defaultBaseUrl() { return "https://api.search.brave.com/res/v1/web/search"; }

        @Override
        public Request buildRequest(String apiKey, String query, int numResults) {
            var encodedQuery = java.net.URLEncoder.encode(query, StandardCharsets.UTF_8);
            var url = "%s?q=%s&count=%d".formatted(baseUrl(), encodedQuery, numResults);
            return new Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("X-Subscription-Token", apiKey)
                    .get()
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("web") || !json.getAsJsonObject("web").has("results")
                    || json.getAsJsonObject("web").getAsJsonArray("results").isEmpty()) {
                return List.of();
            }
            var arr = json.getAsJsonObject("web").getAsJsonArray("results");
            return parseResultArray(arr, "url", r -> nullableString(r, "description"));
        }
    }

    // --- Tavily ---

    static class TavilyProvider implements SearchProvider {
        @Override public String id() { return "tavily"; }
        @Override public String displayName() { return "Tavily"; }
        @Override public String defaultBaseUrl() { return "https://api.tavily.com/search"; }

        @Override
        public Request buildRequest(String apiKey, String query, int numResults) {
            var body = new java.util.HashMap<String, Object>();
            body.put("query", query);
            body.put("max_results", numResults);
            body.put("search_depth", "basic");
            body.put("include_answer", false);
            return new Request.Builder()
                    .url(baseUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, "results");
            return arr == null ? List.of() : parseResultArray(arr, "url", WebSearchTool::trimmedContentSnippet);
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
        public Request buildRequest(String apiKey, String query, int numResults) {
            var body = new java.util.HashMap<String, Object>();
            body.put("query", query);
            body.put("max_results", numResults);
            var recency = ConfigService.get("search.perplexity.recencyFilter", DEFAULT_RECENCY_FILTER);
            if (recency != null && !recency.isBlank() && !"none".equalsIgnoreCase(recency)) {
                body.put("search_recency_filter", recency);
            }
            return new Request.Builder()
                    .url(baseUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, "results");
            return arr == null ? List.of() : parseResultArray(arr, "url", r -> nullableString(r, "snippet"));
        }
    }

    // --- Ollama ---

    static class OllamaProvider implements SearchProvider {
        @Override public String id() { return "ollama"; }
        @Override public String displayName() { return "Ollama"; }
        @Override public String defaultBaseUrl() { return "https://ollama.com/api/web_search"; }

        @Override
        public Request buildRequest(String apiKey, String query, int numResults) {
            var body = new java.util.HashMap<String, Object>();
            body.put("query", query);
            body.put("max_results", Math.min(numResults, 10));
            return new Request.Builder()
                    .url(baseUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, "results");
            return arr == null ? List.of() : parseResultArray(arr, "url", WebSearchTool::trimmedContentSnippet);
        }
    }

    // --- Felo ---

    static class FeloProvider implements SearchProvider {
        @Override public String id() { return "felo"; }
        @Override public String displayName() { return "Felo"; }
        @Override public String defaultBaseUrl() { return "https://openapi.felo.ai/v2/chat"; }

        @Override
        public Request buildRequest(String apiKey, String query, int numResults) {
            var body = Map.of("query", query);
            return new Request.Builder()
                    .url(baseUrl())
                    .header("Authorization", "Bearer " + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("data") || json.get("data").isJsonNull()) return List.of();
            var data = json.getAsJsonObject("data");
            if (!data.has("resources") || data.getAsJsonArray("resources").isEmpty()) return List.of();
            var arr = data.getAsJsonArray("resources");
            return parseResultArray(arr, "link", r -> nullableString(r, "snippet"));
        }

        @Override
        public String formatResults(String responseJson, List<SearchResult> parsed) {
            // Felo emits an answer summary alongside its resources; prepend it
            // so the LLM sees the same two-part shape as before JCLAW-170.
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("data") || json.get("data").isJsonNull()) return "No results found.";
            var data = json.getAsJsonObject("data");
            var sb = new StringBuilder();
            if (data.has("answer") && !data.get("answer").isJsonNull()) {
                var answer = data.get("answer").getAsString().strip();
                if (!answer.isEmpty()) sb.append("**Felo summary:** ").append(answer).append("\n\n");
            }
            if (parsed.isEmpty()) return sb.isEmpty() ? "No results found." : sb.append("No results found.").toString().strip();
            sb.append(renderMarkdown(parsed, "Felo"));
            return sb.toString().strip();
        }
    }
}
