package tools;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.ConfigService;
import services.EventLogger;
import utils.GsonHolder;
import utils.HttpFactories;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Web search tool supporting multiple providers: Exa, Brave, and Tavily.
 * Uses the first enabled provider with a configured API key, or a user-specified
 * provider. All provider config (enabled flag, base URL, API key) lives in the
 * Config DB under {@code search.{id}.*} and is seeded by {@code DefaultConfigJob}.
 * A provider is skipped when {@code search.{id}.enabled} is {@code false} or its
 * API key is blank.
 */
public class WebSearchTool implements ToolRegistry.Tool {

    private static final Gson gson = GsonHolder.INSTANCE;
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_NUM_RESULTS = 5;
    private static final int MAX_NUM_RESULTS = 10;
    private static final MediaType JSON_MEDIA_TYPE = MediaType.get("application/json");

    /** Chars of a provider error body echoed into the failure message/event. */
    private static final int ERROR_BODY_SNIPPET_CHARS = 200;
    /** Chars of a per-result content field kept as its snippet. */
    private static final int CONTENT_SNIPPET_MAX_CHARS = 500;

    // Action / tool-call vocabulary
    private static final String ACTION_SEARCH = "search";

    // JSON argument and provider request-body keys
    private static final String ARG_QUERY = "query";
    private static final String ARG_NUM_RESULTS = "numResults";
    private static final String FIELD_MAX_RESULTS = "max_results";

    // JSON response keys used by multiple providers
    private static final String KEY_RESULTS = "results";
    private static final String KEY_HIGHLIGHTS = "highlights";
    private static final String KEY_RESOURCES = "resources";
    private static final String KEY_ANSWER = "answer";

    // HTTP header / auth literals shared by Bearer-token providers
    private static final String HEADER_AUTHORIZATION = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private static final String NO_RESULTS_MESSAGE = "No results found.";

    /**
     * JCLAW-170: structured per-result shape the UI renders as clickable chips.
     *
     * @param title      result title shown as the chip's main label
     * @param url        clickable destination
     * @param snippet    short prose excerpt from the result page
     * @param faviconUrl resolved through the DuckDuckGo favicon service
     *                   (handles every host uniformly without an API key);
     *                   {@code null} when the host cannot be parsed
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
    public String icon() { return ACTION_SEARCH; }

    @Override
    public String shortDescription() {
        return "Search the web using Exa, Brave, Tavily, or Perplexity for current results.";
    }

    @Override
    public List<ToolAction> actions() {
        return List.of(
                new ToolAction(ACTION_SEARCH, "Execute a web search; provider is auto-selected or can be specified explicitly")
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
                SchemaKeys.TYPE, SchemaKeys.OBJECT,
                SchemaKeys.PROPERTIES, Map.of(
                        ARG_QUERY, Map.of(SchemaKeys.TYPE, SchemaKeys.STRING, SchemaKeys.DESCRIPTION, "The search query"),
                        ARG_NUM_RESULTS, Map.of(SchemaKeys.TYPE, SchemaKeys.INTEGER,
                                SchemaKeys.DESCRIPTION, "Number of results to return (default: 5, max: 10)")
                ),
                SchemaKeys.REQUIRED, List.of(ARG_QUERY)
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
        payload.put(KEY_RESULTS, outcome.results());
        return new ToolRegistry.ToolResult(outcome.text(), gson.toJson(payload));
    }

    private SearchOutcome runFromArgs(String argsJson, Agent agent) {
        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var query = args.get(ARG_QUERY).getAsString();
        var numResults = args.has(ARG_NUM_RESULTS)
                ? Math.min(args.get(ARG_NUM_RESULTS).getAsInt(), MAX_NUM_RESULTS) : DEFAULT_NUM_RESULTS;
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
            EventLogger.warn(ACTION_SEARCH, agent != null ? agent.name : null, null,
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
        var priorities = new HashMap<String, Integer>();
        for (var e : candidates) {
            priorities.put(e.provider().id(),
                    parseInt(ConfigService.get("search." + e.provider().id() + ".priority", "99"), 99));
        }
        candidates.sort(Comparator.comparingInt(e -> priorities.get(e.provider().id())));
        return candidates;
    }

    private SearchOutcome doSearch(SearchProvider provider, String apiKey, String query, int numResults, Agent agent) {
        try {
            EventLogger.info(ACTION_SEARCH, agent != null ? agent.name : null, null,
                    "Searching via %s: \"%s\" (numResults=%d)".formatted(provider.displayName(), query, numResults));

            var request = provider.buildRequest(apiKey, query, numResults);
            var call = HttpFactories.general().newCall(request);
            call.timeout().timeout(TIMEOUT_SECONDS, TimeUnit.SECONDS);

            int statusCode;
            String responseBody;
            try (var response = call.execute()) {
                statusCode = response.code();
                responseBody = response.body().string();
            }

            if (statusCode != 200) {
                EventLogger.error(ACTION_SEARCH, agent != null ? agent.name : null, null,
                        "%s API returned HTTP %d: %s".formatted(provider.displayName(), statusCode,
                                responseBody.substring(0, Math.min(responseBody.length(), ERROR_BODY_SNIPPET_CHARS))));
                return SearchOutcome.error("Error: %s API returned HTTP %d: %s".formatted(
                        provider.displayName(), statusCode,
                        responseBody.substring(0, Math.min(responseBody.length(), ERROR_BODY_SNIPPET_CHARS))));
            }

            var results = provider.parseResults(responseBody);
            var markdown = provider.formatResults(responseBody, results);
            EventLogger.info(ACTION_SEARCH, agent != null ? agent.name : null, null,
                    "%s returned %d chars for \"%s\"".formatted(provider.displayName(), markdown.length(), query));

            return new SearchOutcome(markdown, results, provider.displayName(), true);
        } catch (Exception e) {
            EventLogger.error(ACTION_SEARCH, agent != null ? agent.name : null, null,
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
        if (results.isEmpty()) return NO_RESULTS_MESSAGE;
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
        return content.length() > CONTENT_SNIPPET_MAX_CHARS
                ? content.substring(0, CONTENT_SNIPPET_MAX_CHARS) + "..." : content;
    }

    private static JsonArray topLevelArray(String responseJson, String key) {
        var json = JsonParser.parseString(responseJson).getAsJsonObject();
        // Providers — notably Ollama's /api/web_search — return the array key
        // present but JSON-null for a query that found nothing. has(key) is
        // true for a null value, so a bare getAsJsonArray(key) throws
        // "JsonNull cannot be cast to JsonArray". Type-check first: a missing,
        // null, or non-array value all mean "no results".
        if (!json.has(key)) return null;
        var el = json.get(key);
        if (!el.isJsonArray()) return null;
        var arr = el.getAsJsonArray();
        return arr.isEmpty() ? null : arr;
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
            var body = new HashMap<String, Object>();
            body.put(ARG_QUERY, query);
            body.put(ARG_NUM_RESULTS, numResults);
            body.put("contents", Map.of(KEY_HIGHLIGHTS, Map.of("maxCharacters", 4000)));
            return new Request.Builder()
                    .url(baseUrl())
                    .header("x-api-key", apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, KEY_RESULTS);
            if (arr == null) return List.of();
            return parseResultArray(arr, "url", ExaProvider::joinedHighlights);
        }

        @Override
        public String formatResults(String responseJson, List<SearchResult> parsed) {
            // Exa's markdown shape historically emitted one line per highlight,
            // not a joined snippet. Preserve that so existing LLM prompts see
            // the same structure.
            if (parsed.isEmpty()) return NO_RESULTS_MESSAGE;
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            var arr = json.getAsJsonArray(KEY_RESULTS);
            var sb = new StringBuilder("Found %d results (via Exa):\n\n".formatted(parsed.size()));
            for (int i = 0; i < arr.size(); i++) {
                var r = arr.get(i).getAsJsonObject();
                var result = parsed.get(i);
                sb.append("### %d. %s\n".formatted(i + 1, result.title()));
                sb.append("URL: %s\n".formatted(result.url()));
                if (r.has(KEY_HIGHLIGHTS) && r.getAsJsonArray(KEY_HIGHLIGHTS).size() > 0) {
                    for (var h : r.getAsJsonArray(KEY_HIGHLIGHTS)) {
                        sb.append("> %s\n".formatted(h.getAsString().strip()));
                    }
                }
                sb.append("\n");
            }
            return sb.toString().strip();
        }

        private static String joinedHighlights(JsonObject result) {
            if (!result.has(KEY_HIGHLIGHTS) || result.getAsJsonArray(KEY_HIGHLIGHTS).isEmpty()) return null;
            var sb = new StringBuilder();
            for (var h : result.getAsJsonArray(KEY_HIGHLIGHTS)) {
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
            var encodedQuery = URLEncoder.encode(query, StandardCharsets.UTF_8);
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
            if (!json.has("web") || !json.getAsJsonObject("web").has(KEY_RESULTS)
                    || json.getAsJsonObject("web").getAsJsonArray(KEY_RESULTS).isEmpty()) {
                return List.of();
            }
            var arr = json.getAsJsonObject("web").getAsJsonArray(KEY_RESULTS);
            return parseResultArray(arr, "url", r -> nullableString(r, SchemaKeys.DESCRIPTION));
        }
    }

    // --- Tavily ---

    static class TavilyProvider implements SearchProvider {
        @Override public String id() { return "tavily"; }
        @Override public String displayName() { return "Tavily"; }
        @Override public String defaultBaseUrl() { return "https://api.tavily.com/search"; }

        @Override
        public Request buildRequest(String apiKey, String query, int numResults) {
            var body = new HashMap<String, Object>();
            body.put(ARG_QUERY, query);
            body.put(FIELD_MAX_RESULTS, numResults);
            body.put("search_depth", "basic");
            body.put("include_answer", false);
            return new Request.Builder()
                    .url(baseUrl())
                    .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, KEY_RESULTS);
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
            var body = new HashMap<String, Object>();
            body.put(ARG_QUERY, query);
            body.put(FIELD_MAX_RESULTS, numResults);
            var recency = ConfigService.get("search.perplexity.recencyFilter", DEFAULT_RECENCY_FILTER);
            if (recency != null && !recency.isBlank() && !"none".equalsIgnoreCase(recency)) {
                body.put("search_recency_filter", recency);
            }
            return new Request.Builder()
                    .url(baseUrl())
                    .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, KEY_RESULTS);
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
            var body = new HashMap<String, Object>();
            body.put(ARG_QUERY, query);
            body.put(FIELD_MAX_RESULTS, Math.min(numResults, 10));
            return new Request.Builder()
                    .url(baseUrl())
                    .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var arr = topLevelArray(responseJson, KEY_RESULTS);
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
            var body = Map.of(ARG_QUERY, query);
            return new Request.Builder()
                    .url(baseUrl())
                    .header(HEADER_AUTHORIZATION, BEARER_PREFIX + apiKey)
                    .post(RequestBody.create(gson.toJson(body).getBytes(StandardCharsets.UTF_8), JSON_MEDIA_TYPE))
                    .build();
        }

        @Override
        public List<SearchResult> parseResults(String responseJson) {
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("data") || json.get("data").isJsonNull()) return List.of();
            var data = json.getAsJsonObject("data");
            if (!data.has(KEY_RESOURCES) || data.getAsJsonArray(KEY_RESOURCES).isEmpty()) return List.of();
            var arr = data.getAsJsonArray(KEY_RESOURCES);
            return parseResultArray(arr, "link", r -> nullableString(r, "snippet"));
        }

        @Override
        public String formatResults(String responseJson, List<SearchResult> parsed) {
            // Felo emits an answer summary alongside its resources; prepend it
            // so the LLM sees the same two-part shape as before JCLAW-170.
            var json = JsonParser.parseString(responseJson).getAsJsonObject();
            if (!json.has("data") || json.get("data").isJsonNull()) return NO_RESULTS_MESSAGE;
            var data = json.getAsJsonObject("data");
            var sb = new StringBuilder();
            if (data.has(KEY_ANSWER) && !data.get(KEY_ANSWER).isJsonNull()) {
                var answer = data.get(KEY_ANSWER).getAsString().strip();
                if (!answer.isEmpty()) sb.append("**Felo summary:** ").append(answer).append("\n\n");
            }
            if (parsed.isEmpty()) return sb.isEmpty() ? NO_RESULTS_MESSAGE : sb.append(NO_RESULTS_MESSAGE).toString().strip();
            sb.append(renderMarkdown(parsed, "Felo"));
            return sb.toString().strip();
        }
    }
}
