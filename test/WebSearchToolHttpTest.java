import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import tools.WebSearchTool;

/**
 * JCLAW-312: HTTP-level coverage for {@link WebSearchTool}.
 *
 * <p>Complements {@code WebSearchToolTest} (which covers the config-gating
 * code paths) by exercising the live HTTP round-trip against a
 * {@link MockWebServer}. The mock receives whatever the tool's provider
 * adapter emits — request shape, auth header, query encoding — and replies
 * with provider-shaped JSON the parser must round-trip into the LLM-visible
 * markdown.
 *
 * <p>Each test runs against a single provider with its {@code baseUrl}
 * overridden to point at the local mock; all other providers are forced
 * disabled and de-keyed so the auto-selection candidate list contains
 * exactly one entry. This pins {@code doSearch} to the provider under test
 * regardless of priority-comparator behaviour.
 *
 * <p>The deferred ACs from JCLAW-312 (SSRF guard on result fetching,
 * rate-limit retry/backoff) reflect features that don't exist in the
 * production class — {@code WebSearchTool} fetches the search-API
 * endpoint only and falls back to the next provider on error, with no
 * per-result follow-up and no retry loop. Tests for those would need a
 * production-side change first.
 */
class WebSearchToolHttpTest extends UnitTest {

    private static final String[] ALL_KEYS = {
            "search.exa.enabled", "search.exa.apiKey", "search.exa.baseUrl",
            "search.brave.enabled", "search.brave.apiKey", "search.brave.baseUrl",
            "search.brave.priority",
            "search.tavily.enabled", "search.tavily.apiKey", "search.tavily.baseUrl",
            "search.tavily.priority",
            "search.perplexity.enabled", "search.perplexity.apiKey", "search.perplexity.baseUrl",
            "search.ollama.enabled", "search.ollama.apiKey", "search.ollama.baseUrl",
            "search.felo.enabled", "search.felo.apiKey", "search.felo.baseUrl",
    };

    private MockWebServer server;
    private WebSearchTool tool;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        tool = new WebSearchTool();
        // Hard-disable every provider; individual tests re-enable + key the
        // one they need. Clearing the cache between mutations keeps each test
        // hermetic since ConfigService's 60s TTL would otherwise mask flips.
        for (var k : ALL_KEYS) ConfigService.set(k, "");
        ConfigService.set("search.exa.enabled", "false");
        ConfigService.set("search.brave.enabled", "false");
        ConfigService.set("search.tavily.enabled", "false");
        ConfigService.set("search.perplexity.enabled", "false");
        ConfigService.set("search.ollama.enabled", "false");
        ConfigService.set("search.felo.enabled", "false");
        ConfigService.clearCache();
    }

    @AfterEach
    void tearDown() throws Exception {
        for (var k : ALL_KEYS) ConfigService.delete(k);
        ConfigService.clearCache();
        server.close();
    }

    // ==================== Brave: happy path ====================

    @Test
    void braveHappyPathReturnsMarkdownWithResultsAndAuthHeader() throws Exception {
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                        {"web":{"results":[
                          {"title":"OkHttp 5 release notes",
                           "url":"https://example.test/okhttp-5",
                           "description":"OkHttp 5.x ships virtual-thread-clean SSE."},
                          {"title":"Brave search docs",
                           "url":"https://example.test/brave",
                           "description":"REST API for web search."}
                        ]}}""")
                .build());

        var result = tool.execute("{\"query\":\"okhttp 5 sse\"}", null);
        assertFalse(result.startsWith("Error:"),
                "happy path must not emit an error envelope: " + result);
        assertTrue(result.contains("OkHttp 5 release notes"),
                "result title must surface in markdown: " + result);
        assertTrue(result.contains("https://example.test/okhttp-5"));
        assertTrue(result.contains("OkHttp 5.x ships virtual-thread-clean SSE."));
        assertTrue(result.contains("via Brave"),
                "provider attribution must be present so the LLM knows the source: "
                        + result);

        var req = server.takeRequest();
        assertEquals("GET", req.getMethod(),
                "Brave uses GET with query in the URL, not POST");
        assertEquals("test-brave-key", req.getHeaders().get("X-Subscription-Token"),
                "Brave authenticates via the X-Subscription-Token header");
    }

    @Test
    void braveQueryUrlEncodedIntoCount() throws Exception {
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                        {"web":{"results":[
                          {"title":"x","url":"https://example.test/x","description":"y"}
                        ]}}""")
                .build());

        tool.execute("{\"query\":\"hello world\",\"numResults\":3}", null);

        var req = server.takeRequest();
        var url = req.getUrl().toString();
        assertTrue(url.contains("q=hello+world") || url.contains("q=hello%20world"),
                "query must be URL-encoded into q= parameter: " + url);
        assertTrue(url.contains("count=3"),
                "numResults must surface as count= parameter: " + url);
    }

    @Test
    void numResultsClampedToTen() throws Exception {
        // MAX_NUM_RESULTS is 10; a request for 50 must clamp before hitting the wire.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"web\":{\"results\":[]}}")
                .build());

        tool.execute("{\"query\":\"x\",\"numResults\":50}", null);

        var req = server.takeRequest();
        assertTrue(req.getUrl().toString().contains("count=10"),
                "numResults>10 must clamp to MAX_NUM_RESULTS: " + req.getUrl());
    }

    // ==================== Brave: empty results ====================

    @Test
    void braveEmptyResultsYieldsNoResultsFoundEnvelope() throws Exception {
        // Per renderMarkdown: parsed.isEmpty() ⇒ "No results found."
        // The model needs this deterministic string so it can decide whether
        // to retry with a different query rather than parsing absence from
        // an empty markdown block.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"web\":{\"results\":[]}}")
                .build());

        var result = tool.execute("{\"query\":\"zero matches\"}", null);
        assertEquals("No results found.", result,
                "empty-result envelope must be deterministic and free of NPEs");
    }

    @Test
    void braveMissingWebKeyAlsoYieldsNoResultsFound() throws Exception {
        // Defensive: a Brave-shaped response with no `web` key at all (e.g.
        // a stripped error response that still 200s) must not NPE.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build());

        var result = tool.execute("{\"query\":\"x\"}", null);
        assertEquals("No results found.", result);
    }

    // ==================== Brave: error envelopes ====================

    @Test
    void brave401SurfacesAsToolError() throws Exception {
        // Auth failure must NOT throw — the agent loop catches exceptions
        // and emits a generic envelope. Surfacing as a tool-error string
        // lets the model recognize the auth class and stop retrying.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(401)
                .body("{\"error\":\"unauthorized\"}")
                .build());

        var result = tool.execute("{\"query\":\"x\"}", null);
        assertTrue(result.startsWith("Error:"),
                "401 must surface as deterministic Error: envelope, not an exception escape");
        assertTrue(result.contains("HTTP 401"),
                "status code must surface so logs/users can attribute the failure: " + result);
        assertTrue(result.contains("Brave"),
                "provider name surfaces in the error: " + result);
    }

    @Test
    void brave403SurfacesAsToolError() throws Exception {
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(403)
                .body("{\"error\":\"forbidden\"}")
                .build());
        var result = tool.execute("{\"query\":\"x\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("HTTP 403"));
    }

    @Test
    void brave429SurfacesAsToolError() throws Exception {
        // Rate-limit: no retry/backoff in current production code; the
        // provider-fallback loop walks to the next enabled provider. With
        // Brave as the only candidate, the rate-limit becomes the final
        // error envelope returned to the agent.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(429)
                .body("{\"error\":\"rate limited\"}")
                .build());
        var result = tool.execute("{\"query\":\"x\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("HTTP 429"),
                "rate-limit code surfaces in the envelope: " + result);
    }

    @Test
    void brave500ResponseBodyTruncatedAtTwoHundredChars() throws Exception {
        // A pathological 5xx with a huge body shouldn't drag a multi-KB
        // payload into the LLM context. doSearch truncates to 200 chars.
        enableBraveAtMock();
        var hugeBody = "X".repeat(2_000);
        server.enqueue(new MockResponse.Builder()
                .code(500)
                .body(hugeBody)
                .build());
        var result = tool.execute("{\"query\":\"x\"}", null);
        assertTrue(result.startsWith("Error:"));
        // The substring rendered should NOT exceed 200 X's.
        var xs = result.replaceAll("[^X]", "");
        assertTrue(xs.length() <= 200,
                "response body must be truncated to 200 chars; saw " + xs.length());
    }

    // ==================== Tavily: snippet truncation ====================

    @Test
    void tavilyLongSnippetTruncatedAtFiveHundredCharsWithEllipsis() throws Exception {
        // Tavily's `content` field goes through trimmedContentSnippet, which
        // caps at 500 chars + "...". This is the snippet cap the JIRA AC
        // names — verifying the long-body branch fires.
        //
        // The marker char must not appear anywhere else in the rendered
        // markdown — title, the "URL:" prefix (contains 'L'), provider name,
        // host portion of the url all count. Tilde (~) is safely outside
        // every renderer-emitted literal so the marker count equals the
        // snippet's truncated length exactly.
        enableTavilyAtMock();
        var longBody = "~".repeat(900);
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                        {"results":[{"title":"long","url":"https://example.test/l",
                          "content":"%s"}]}""".formatted(longBody))
                .build());

        var result = tool.execute("{\"query\":\"x\"}", null);
        assertFalse(result.startsWith("Error:"));
        assertTrue(result.contains("..."),
                "long snippet must be truncated with ellipsis marker: tail="
                        + result.substring(Math.max(0, result.length() - 80)));
        // The tilde-run in output must be exactly 500 (markers before the ellipsis).
        var run = result.replaceAll("[^~]", "");
        assertEquals(500, run.length(),
                "snippet content must cap at exactly 500 chars before the ellipsis");
    }

    @Test
    void tavilyShortSnippetPassedThroughVerbatim() throws Exception {
        enableTavilyAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                        {"results":[{"title":"t","url":"https://example.test/t",
                          "content":"a concise snippet"}]}""")
                .build());

        var result = tool.execute("{\"query\":\"x\"}", null);
        assertTrue(result.contains("a concise snippet"),
                "short snippet passes through unchanged: " + result);
        assertFalse(result.contains("..."),
                "no truncation marker for short snippets");
    }

    @Test
    void tavilyAuthHeaderIsBearerToken() throws Exception {
        enableTavilyAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"results\":[]}")
                .build());

        tool.execute("{\"query\":\"x\"}", null);

        var req = server.takeRequest();
        assertEquals("POST", req.getMethod(),
                "Tavily uses POST with JSON body");
        assertEquals("Bearer test-tavily-key",
                req.getHeaders().get("Authorization"),
                "Tavily authenticates via Authorization: Bearer ...");
    }

    // ==================== executeRich: structured payload ====================

    @Test
    void executeRichEmitsStructuredJsonAlongsideMarkdown() throws Exception {
        // JCLAW-170: the rich variant returns both LLM-visible markdown AND
        // a structured JSON payload the UI uses to render clickable chips.
        // Verify the JSON shape: provider name + results list with title/url/
        // snippet/faviconUrl per entry.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                        {"web":{"results":[
                          {"title":"Cat","url":"https://example.test/cat",
                           "description":"feline"}
                        ]}}""")
                .build());

        ToolRegistry.ToolResult rich = tool.executeRich(
                "{\"query\":\"cat\"}", null);
        assertNotNull(rich.structuredJson(),
                "non-empty results must produce a structured JSON payload");

        JsonObject payload = JsonParser.parseString(rich.structuredJson()).getAsJsonObject();
        assertEquals("Brave", payload.get("provider").getAsString());
        var arr = payload.getAsJsonArray("results");
        assertEquals(1, arr.size());
        var first = arr.get(0).getAsJsonObject();
        assertEquals("Cat", first.get("title").getAsString());
        assertEquals("https://example.test/cat", first.get("url").getAsString());
        assertEquals("feline", first.get("snippet").getAsString());
        // faviconUrl is derived from the host via DuckDuckGo's icon service.
        assertTrue(first.get("faviconUrl").getAsString().contains("example.test"),
                "faviconUrl must include the result host: " + first);
    }

    @Test
    void executeRichOnEmptyResultsHasNullStructuredJson() throws Exception {
        // When parsed.isEmpty() the executeRich branch falls through to the
        // text-only path so the UI doesn't render a chip strip for "no
        // results". The structured payload must therefore be null.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"web\":{\"results\":[]}}")
                .build());

        ToolRegistry.ToolResult rich = tool.executeRich("{\"query\":\"x\"}", null);
        assertNull(rich.structuredJson(),
                "empty results ⇒ no structured payload: structured=" + rich.structuredJson());
        assertEquals("No results found.", rich.text());
    }

    @Test
    void executeRichOnErrorPathHasNullStructuredJson() throws Exception {
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder().code(401).body("nope").build());

        ToolRegistry.ToolResult rich = tool.executeRich("{\"query\":\"x\"}", null);
        assertNull(rich.structuredJson(),
                "error path must not leak a structured payload");
        assertTrue(rich.text().startsWith("Error:"));
    }

    // ==================== favicon URL helper ====================

    @Test
    void faviconUrlGeneratedForResultHosts() throws Exception {
        // The shape is https://icons.duckduckgo.com/ip3/<host>.ico — verified
        // indirectly through the structured payload of a single-result fetch.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                        {"web":{"results":[
                          {"title":"x","url":"https://news.example.test/path/page",
                           "description":"d"}
                        ]}}""")
                .build());

        var rich = tool.executeRich("{\"query\":\"x\"}", null);
        var payload = JsonParser.parseString(rich.structuredJson()).getAsJsonObject();
        var first = payload.getAsJsonArray("results").get(0).getAsJsonObject();
        assertEquals(
                "https://icons.duckduckgo.com/ip3/news.example.test.ico",
                first.get("faviconUrl").getAsString(),
                "favicon URL is DuckDuckGo's ip3 endpoint with the result host");
    }

    // ==================== fallback to next provider ====================

    @Test
    void firstProviderFailureFallsBackToSecond() throws Exception {
        // Two providers enabled and keyed; the first (Brave, default priority
        // higher than Tavily? — see priorities). Whichever runs first must
        // fail-over to the other when it returns non-200.
        //
        // We use a single MockWebServer for both — the second enqueued
        // response handles the retry. Each provider sees a distinct path,
        // but for this test we don't care about path matching: the dispatcher
        // pops in FIFO order.
        ConfigService.set("search.brave.enabled", "true");
        ConfigService.set("search.brave.apiKey", "test-brave-key");
        ConfigService.set("search.brave.baseUrl", server.url("/brave").toString());
        ConfigService.set("search.brave.priority", "1");

        ConfigService.set("search.tavily.enabled", "true");
        ConfigService.set("search.tavily.apiKey", "test-tavily-key");
        ConfigService.set("search.tavily.baseUrl", server.url("/tavily").toString());
        ConfigService.set("search.tavily.priority", "2");

        ConfigService.clearCache();

        // First call: Brave returns 500 → falls back. Second call: Tavily 200.
        server.enqueue(new MockResponse.Builder().code(500).body("brave down").build());
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("""
                        {"results":[{"title":"fallback","url":"https://example.test/fb",
                          "content":"served by tavily"}]}""")
                .build());

        var result = tool.execute("{\"query\":\"x\"}", null);
        assertFalse(result.startsWith("Error:"),
                "fallback should have succeeded: " + result);
        assertTrue(result.contains("fallback"),
                "Tavily's result title must appear after Brave's 500: " + result);
        assertTrue(result.contains("via Tavily"),
                "provider attribution must reflect the fallback provider: " + result);
        assertEquals(2, server.getRequestCount(),
                "both providers must have been dialled (first failed, second won)");
    }

    // ==================== query template / interpolation ====================

    @Test
    void queryStringPassedThroughToProviderVerbatim() throws Exception {
        // WebSearchTool doesn't template the query against agent context —
        // whatever the model emits in argsJson reaches the provider as-is.
        // Verify that exotic chars (quotes, unicode, plus signs) survive
        // URL-encoding round-trip.
        enableBraveAtMock();
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body("{\"web\":{\"results\":[]}}")
                .build());

        tool.execute("{\"query\":\"C++ unicode \\u00e9 plus\"}", null);

        var req = server.takeRequest();
        var url = req.getUrl().toString();
        // C++ → "C%2B%2B" (plus encoded), é → "%C3%A9" (UTF-8 e-acute).
        assertTrue(url.contains("C%2B%2B"),
                "plus signs must be percent-encoded so providers see literal +: " + url);
        assertTrue(url.contains("%C3%A9"),
                "non-ASCII must be UTF-8 percent-encoded: " + url);
    }

    // ==================== no-provider edge ====================

    @Test
    void noProviderConfiguredReturnsDeterministicErrorEnvelope() {
        // All providers were disabled in setUp(); auto-select finds zero
        // candidates and emits the diagnostic the operator needs to see
        // in the agent's tool-output panel.
        var result = tool.execute("{\"query\":\"x\"}", null);
        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("No search provider"),
                "error must guide the operator to Settings: " + result);
    }

    // ==================== schema sanity ====================

    @Test
    void parametersSchemaDeclaresQueryRequired() {
        // The LLM's function-calling parser uses required[] to know which
        // fields it must populate. query is the only required arg.
        var params = tool.parameters();
        @SuppressWarnings("unchecked")
        var required = (java.util.List<String>) params.get("required");
        assertTrue(required.contains("query"),
                "query is the only required field; numResults is optional");
        assertEquals(1, required.size());
    }

    @Test
    void parallelSafeIsTrueForStatelessHttp() {
        // Per Javadoc: each call is an independent HTTP request, so the
        // AgentRunner is free to race multiple web_search calls within one
        // round on separate virtual threads.
        assertTrue(tool.parallelSafe());
    }

    @Test
    void nameAndCategoryStable() {
        assertEquals("web_search", tool.name());
        assertEquals("Web", tool.category());
        assertEquals("search", tool.icon());
    }

    // ==================== helpers ====================

    private void enableBraveAtMock() {
        ConfigService.set("search.brave.enabled", "true");
        ConfigService.set("search.brave.apiKey", "test-brave-key");
        ConfigService.set("search.brave.baseUrl", server.url("/brave").toString());
        ConfigService.clearCache();
    }

    private void enableTavilyAtMock() {
        ConfigService.set("search.tavily.enabled", "true");
        ConfigService.set("search.tavily.apiKey", "test-tavily-key");
        ConfigService.set("search.tavily.baseUrl", server.url("/tavily").toString());
        ConfigService.clearCache();
    }
}
