package tools;

import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import models.Agent;
import services.ConfigService;
import utils.HttpClients;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Web search tool using the Exa REST API (https://exa.ai).
 * API key is stored in the Config table as "exa.apiKey" and managed via Settings.
 */
public class WebSearchTool implements ToolRegistry.Tool {

    private static final Gson gson = new Gson();
    private static final String EXA_API_URL = "https://api.exa.ai/search";
    private static final int TIMEOUT_SECONDS = 30;
    private static final int DEFAULT_NUM_RESULTS = 5;
    private static final int MAX_HIGHLIGHT_CHARS = 4000;

    @Override
    public String name() { return "web_search"; }

    @Override
    public String description() {
        return """
                Search the web for current information using the Exa search API. \
                Returns relevant results with titles, URLs, and content highlights. \
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
                        "category", Map.of("type", "string",
                                "enum", List.of("general", "news", "research paper", "company", "personal site"),
                                "description", "Filter results by category (default: general)")
                ),
                "required", List.of("query")
        );
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        var apiKey = ConfigService.get("exa.apiKey");
        if (apiKey == null || apiKey.isBlank()) {
            return "Error: Exa API key not configured. Add 'exa.apiKey' in Settings.";
        }

        var args = JsonParser.parseString(argsJson).getAsJsonObject();
        var query = args.get("query").getAsString();
        var numResults = args.has("numResults")
                ? Math.min(args.get("numResults").getAsInt(), 10) : DEFAULT_NUM_RESULTS;
        var category = args.has("category") ? args.get("category").getAsString() : null;

        try {
            var requestBody = buildRequestBody(query, numResults, category);
            var request = HttpRequest.newBuilder()
                    .uri(URI.create(EXA_API_URL))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            var response = HttpClients.LLM.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                return "Error: Exa API returned HTTP %d: %s".formatted(
                        response.statusCode(), response.body().substring(0, Math.min(response.body().length(), 200)));
            }

            return formatResults(response.body());
        } catch (Exception e) {
            return "Error searching the web: %s".formatted(e.getMessage());
        }
    }

    private String buildRequestBody(String query, int numResults, String category) {
        var body = new java.util.HashMap<String, Object>();
        body.put("query", query);
        body.put("numResults", numResults);
        body.put("contents", Map.of(
                "highlights", Map.of("maxCharacters", MAX_HIGHLIGHT_CHARS)
        ));
        if (category != null && !"general".equals(category)) {
            body.put("category", category);
        }
        return gson.toJson(body);
    }

    private String formatResults(String responseJson) {
        var json = JsonParser.parseString(responseJson).getAsJsonObject();

        if (!json.has("results") || json.getAsJsonArray("results").isEmpty()) {
            return "No results found.";
        }

        var results = json.getAsJsonArray("results");
        var sb = new StringBuilder();
        sb.append("Found %d results:\n\n".formatted(results.size()));

        for (int i = 0; i < results.size(); i++) {
            var result = results.get(i).getAsJsonObject();
            var title = result.has("title") ? result.get("title").getAsString() : "Untitled";
            var url = result.has("url") ? result.get("url").getAsString() : "";

            sb.append("### %d. %s\n".formatted(i + 1, title));
            sb.append("URL: %s\n".formatted(url));

            if (result.has("highlights") && result.getAsJsonArray("highlights").size() > 0) {
                var highlights = result.getAsJsonArray("highlights");
                for (var h : highlights) {
                    sb.append("> %s\n".formatted(h.getAsString().strip()));
                }
            }
            sb.append("\n");
        }

        return sb.toString().strip();
    }
}
