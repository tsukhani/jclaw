package jclaw.mcp.server;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end ToolInvoker coverage against an in-process MockWebServer.
 *
 * <p>Three things to nail down:
 * <ul>
 *   <li>The bearer header is on every outbound request — JCLAW-282
 *       acceptance criterion #7 names "bearer-header passthrough" as
 *       a required test surface.</li>
 *   <li>Path placeholders, query parameters, and JSON-body fields each
 *       land in the right slot.</li>
 *   <li>Non-2xx responses surface as {@code isError: true} rather than
 *       throwing — the JSON-RPC layer must keep dispatching after a
 *       tool fails.</li>
 * </ul>
 */
class ToolInvokerTest {

    private MockWebServer server;
    private ToolInvoker invoker;

    @BeforeEach
    void setup() throws Exception {
        server = new MockWebServer();
        server.start();
        var config = new Config(
                URI.create(server.url("/").toString().replaceAll("/$", "")),
                "jcl_test_bearer",
                Config.Scope.FULL,
                List.of());
        invoker = new ToolInvoker(new JClawHttp(config, new OkHttpClient()));
    }

    @AfterEach
    void teardown() throws Exception {
        server.close();
    }

    @Test
    void attachesBearerHeaderToOutboundRequest() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("[]").build());

        invoker.invoke(simpleGetTool(), new JsonObject());

        var req = server.takeRequest();
        // Backend auth-filter check is what enforces authorization; the
        // MCP server's contract is "every outbound request carries the
        // operator's token unchanged".
        assertEquals("Bearer jcl_test_bearer", req.getHeaders().get("Authorization"));
        assertEquals("application/json", req.getHeaders().get("Accept"));
    }

    @Test
    void substitutesPathParameters() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"id\":42}").build());

        var args = new JsonObject();
        args.addProperty("id", "42");
        invoker.invoke(getAgentTool(), args);

        var req = server.takeRequest();
        // {id} placeholder must be substituted in the path itself,
        // NOT appended as a query param — that's a different endpoint
        // shape on the Play side.
        assertEquals("/api/agents/42", req.getUrl().encodedPath());
    }

    @Test
    void appendsQueryParameters() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("[]").build());

        var tool = new ToolDefinition(
                "jclaw_search",
                "Search",
                new JsonObject(),
                "GET",
                "/api/search",
                List.of(new ToolDefinition.ParameterBinding("q",
                        ToolDefinition.Location.QUERY, true)),
                false);
        var args = new JsonObject();
        args.addProperty("q", "hello world");
        invoker.invoke(tool, args);

        var req = server.takeRequest();
        // OkHttp URL-encodes the space; the assertion accommodates both
        // %20 and + encodings since query string conventions vary.
        var encoded = req.getUrl().encodedQuery();
        assertNotNull(encoded);
        assertTrue(encoded.equals("q=hello%20world") || encoded.equals("q=hello+world"),
                "query string should carry the encoded value; got: " + encoded);
    }

    @Test
    void sendsJsonBodyForPostOperations() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200).body("{\"id\":99}").build());

        var tool = new ToolDefinition(
                "jclaw_mintToken",
                "Mint a token",
                new JsonObject(),
                "POST",
                "/api/api-tokens",
                List.of(
                        new ToolDefinition.ParameterBinding("name",
                                ToolDefinition.Location.BODY, true),
                        new ToolDefinition.ParameterBinding("scope",
                                ToolDefinition.Location.BODY, false)),
                true);
        var args = new JsonObject();
        args.addProperty("name", "test");
        args.addProperty("scope", "FULL");
        invoker.invoke(tool, args);

        var req = server.takeRequest();
        var bodyJson = JsonParser.parseString(req.getBody().utf8()).getAsJsonObject();
        // Both bound fields must land in the body, top-level, NOT
        // wrapped in {body: {…}} — see ToolGenerator's flattening.
        assertEquals("test", bodyJson.get("name").getAsString());
        assertEquals("FULL", bodyJson.get("scope").getAsString());
    }

    @Test
    void returnsIsErrorOnNon2xx() throws Exception {
        server.enqueue(new MockResponse.Builder().code(403)
                .body("{\"error\":\"forbidden\",\"code\":\"token_read_only\"}").build());

        var result = invoker.invoke(simpleGetTool(), new JsonObject());

        // The MCP spec routes tool-level failures via isError on the
        // result rather than via JSON-RPC errors — that lets agents
        // distinguish "the tool you called failed" from "the MCP host
        // is broken" without special-casing.
        assertTrue(result.has("isError") && result.get("isError").getAsBoolean(),
                "non-2xx response should surface as isError; got: " + result);
        var text = result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
        assertTrue(text.contains("HTTP 403"), "result text should embed status code; got: " + text);
        assertTrue(text.contains("token_read_only"),
                "result text should echo backend error code so the agent can act on it; got: " + text);
    }

    @Test
    void missingRequiredPathParamProducesIsError() {
        var args = new JsonObject(); // no 'id'
        var result = invoker.invoke(getAgentTool(), args);
        assertTrue(result.has("isError") && result.get("isError").getAsBoolean());
        var text = result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
        assertTrue(text.contains("id"),
                "error text should name the missing parameter; got: " + text);
    }

    @Test
    void prettyPrintsJsonResponseForAgentReadability() throws Exception {
        server.enqueue(new MockResponse.Builder().code(200)
                .body("{\"a\":1,\"b\":[2,3]}").build());

        var result = invoker.invoke(simpleGetTool(), new JsonObject());
        var text = result.getAsJsonArray("content").get(0).getAsJsonObject()
                .get("text").getAsString();
        // The pretty-printed shape has each property on its own line;
        // agents read this prose-style, so unindented JSON is harder
        // to parse field-by-field.
        assertTrue(text.contains("\"a\": 1"), "expected pretty-printed JSON; got: " + text);
    }

    private static ToolDefinition simpleGetTool() {
        return new ToolDefinition(
                "jclaw_listAgents",
                "List agents",
                new JsonObject(),
                "GET",
                "/api/agents",
                List.of(),
                false);
    }

    private static ToolDefinition getAgentTool() {
        return new ToolDefinition(
                "jclaw_getAgent",
                "Get agent by id",
                new JsonObject(),
                "GET",
                "/api/agents/{id}",
                List.of(new ToolDefinition.ParameterBinding("id",
                        ToolDefinition.Location.PATH, true)),
                false);
    }
}
