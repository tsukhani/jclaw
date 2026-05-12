package jclaw.mcp.server;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.Paths;
import io.swagger.v3.oas.models.media.Content;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import io.swagger.v3.oas.models.responses.ApiResponses;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the OpenAPI -> MCP tool mapping in {@link ToolGenerator}.
 *
 * <p>Each test composes a synthetic {@link OpenAPI} object covering one
 * generator concern (operationId prefixing, scope filtering, SSE skip,
 * body flattening, parameter binding). Going via swagger-parser would
 * couple these tests to JClaw's actual spec — by hand-constructing the
 * tree we cover edge cases the live spec doesn't exercise.
 */
class ToolGeneratorTest {

    private static Config configWithScope(Config.Scope scope, String... excludes) {
        return new Config(URI.create("http://localhost:9000"), "jcl_test", scope, List.of(excludes));
    }

    @Test
    void prefixesOperationIdWithJclaw() {
        var spec = new OpenAPI().paths(new Paths().addPathItem(
                "/api/agents",
                new PathItem().get(new Operation().operationId("listAgents").summary("List agents"))));
        var tools = new ToolGenerator(configWithScope(Config.Scope.READ_ONLY)).generate(spec);
        assertEquals(1, tools.size());
        assertEquals("jclaw_listAgents", tools.get(0).name(),
                "Tool name should mirror operationId with jclaw_ prefix so MCP hosts can "
                        + "differentiate JClaw tools from any other server's");
    }

    @Test
    void fallsBackToPathSlugWhenOperationIdMissing() {
        var spec = new OpenAPI().paths(new Paths().addPathItem(
                "/api/agents/{id}/tools",
                new PathItem().get(new Operation().summary("List agent tools"))));
        var tools = new ToolGenerator(configWithScope(Config.Scope.READ_ONLY)).generate(spec);
        var name = tools.get(0).name();
        assertTrue(name.startsWith("jclaw_get_"), "expected verb-prefixed slug; got: " + name);
        assertTrue(name.contains("agents"), "slug should preserve path segments; got: " + name);
    }

    @Test
    void readOnlyScopeFiltersMutatingVerbs() {
        var spec = new OpenAPI().paths(new Paths()
                .addPathItem("/api/agents", new PathItem()
                        .get(new Operation().operationId("listAgents"))
                        .post(new Operation().operationId("createAgent"))
                        .put(new Operation().operationId("updateAgentsBulk"))));
        var tools = new ToolGenerator(configWithScope(Config.Scope.READ_ONLY)).generate(spec);
        var names = tools.stream().map(ToolDefinition::name).collect(Collectors.toList());
        // Acceptance criterion #5: read-only scope must NOT advertise
        // mutating verbs at all. Belt-and-suspenders alongside the
        // backend's auth-filter check.
        assertTrue(names.contains("jclaw_listAgents"));
        assertFalse(names.contains("jclaw_createAgent"),
                "POST should not be advertised under read-only scope; got: " + names);
        assertFalse(names.contains("jclaw_updateAgentsBulk"),
                "PUT should not be advertised under read-only scope; got: " + names);
    }

    @Test
    void fullScopeAdvertisesMutatingVerbs() {
        var spec = new OpenAPI().paths(new Paths()
                .addPathItem("/api/agents", new PathItem()
                        .get(new Operation().operationId("listAgents"))
                        .post(new Operation().operationId("createAgent"))));
        var tools = new ToolGenerator(configWithScope(Config.Scope.FULL)).generate(spec);
        assertEquals(2, tools.size());
    }

    @Test
    void streamingResponsesAreSkipped() {
        var sseResponse = new ApiResponse().content(
                new Content().addMediaType("text/event-stream", new MediaType()));
        var spec = new OpenAPI().paths(new Paths()
                .addPathItem("/api/events",
                        new PathItem().get(new Operation()
                                .operationId("streamEvents")
                                .responses(new ApiResponses().addApiResponse("200", sseResponse))))
                .addPathItem("/api/agents",
                        new PathItem().get(new Operation().operationId("listAgents"))));
        var tools = new ToolGenerator(configWithScope(Config.Scope.READ_ONLY)).generate(spec);
        var names = tools.stream().map(ToolDefinition::name).collect(Collectors.toList());
        // MCP v1 doesn't carry SSE. Tunnelling a stream through a tool
        // result would deliver every byte at stream-close, which is
        // worse for the agent than refusing the call.
        assertFalse(names.contains("jclaw_streamEvents"), "SSE op should be skipped; got: " + names);
        assertTrue(names.contains("jclaw_listAgents"));
    }

    @Test
    void excludePatternMatchesPathSubstring() {
        var spec = new OpenAPI().paths(new Paths()
                .addPathItem("/api/agents", new PathItem().get(new Operation().operationId("listAgents")))
                .addPathItem("/api/metrics/loadtest",
                        new PathItem().get(new Operation().operationId("loadtestStatus"))));
        var tools = new ToolGenerator(configWithScope(Config.Scope.READ_ONLY, "/api/metrics/loadtest")).generate(spec);
        var names = tools.stream().map(ToolDefinition::name).collect(Collectors.toList());
        assertFalse(names.contains("jclaw_loadtestStatus"),
                "excluded path should be filtered; got: " + names);
        assertTrue(names.contains("jclaw_listAgents"));
    }

    @Test
    void pathParametersAreBoundAndMarkedRequired() {
        var op = new Operation().operationId("getAgent")
                .addParametersItem(new Parameter().name("id").in("path").schema(new StringSchema()));
        var spec = new OpenAPI().paths(new Paths().addPathItem("/api/agents/{id}",
                new PathItem().get(op)));
        var tools = new ToolGenerator(configWithScope(Config.Scope.READ_ONLY)).generate(spec);
        assertEquals(1, tools.size());
        var t = tools.get(0);
        assertEquals(1, t.parameters().size());
        var p = t.parameters().get(0);
        assertEquals("id", p.name());
        assertEquals(ToolDefinition.Location.PATH, p.location());
        // Path params are intrinsically required — the URL doesn't
        // resolve without them, and OpenAPI specs sometimes omit the
        // required=true flag on path params.
        assertTrue(p.required());
        assertTrue(t.inputSchema().getAsJsonObject("properties").has("id"));
    }

    @Test
    void requestBodySchemaIsFlattenedIntoInputs() {
        var bodySchema = new Schema<Object>().type("object")
                .addProperty("name", new StringSchema())
                .addProperty("scope", new StringSchema())
                .required(List.of("name"));
        var op = new Operation().operationId("mintToken")
                .requestBody(new RequestBody().content(
                        new Content().addMediaType("application/json", new MediaType().schema(bodySchema))));
        var spec = new OpenAPI().paths(new Paths().addPathItem("/api/api-tokens",
                new PathItem().post(op)));
        var tools = new ToolGenerator(configWithScope(Config.Scope.FULL)).generate(spec);
        var t = tools.get(0);
        // Both body fields surface as top-level inputs, not under a
        // nested {body: ...} envelope — that's the shape MCP agents
        // expect (per the Anthropic Cookbook examples).
        var props = t.inputSchema().getAsJsonObject("properties");
        assertTrue(props.has("name"));
        assertTrue(props.has("scope"));
        var required = t.inputSchema().getAsJsonArray("required");
        // 'name' is required on the body; 'scope' isn't. Required must
        // round-trip into the MCP schema so the host validates locally.
        assertTrue(required.toString().contains("\"name\""));
        assertFalse(required.toString().contains("\"scope\""));
    }

    @Test
    void duplicateOperationIdsAreDisambiguated() {
        // Two operations with the same operationId is a spec bug; MCP
        // requires unique tool names. We suffix-disambiguate rather
        // than drop so every operation stays callable.
        var spec = new OpenAPI().paths(new Paths()
                .addPathItem("/api/a", new PathItem().get(new Operation().operationId("dup")))
                .addPathItem("/api/b", new PathItem().get(new Operation().operationId("dup"))));
        var tools = new ToolGenerator(configWithScope(Config.Scope.READ_ONLY)).generate(spec);
        var names = tools.stream().map(ToolDefinition::name).collect(Collectors.toList());
        assertEquals(2, tools.size());
        assertTrue(names.contains("jclaw_dup"));
        assertTrue(names.contains("jclaw_dup_2"),
                "duplicate operationId should be suffix-disambiguated; got: " + names);
    }
}
