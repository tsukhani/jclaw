package jclaw.mcp.server;

import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sanity tests for the spec-fetch URL composition and parser
 * robustness on small hand-rolled specs. The live-spec path is
 * exercised end-to-end by the README's setup smoke test rather than
 * here — pinning to a snapshot of JClaw's real spec would be a
 * test-drift trap when controllers add or rename operations.
 */
class OpenApiCatalogTest {

    @Test
    void resolveOpenApiUrlAppendsSpecPath() {
        // The OpenAPI plugin's published endpoint is /@api/openapi.json.
        // Honour both trailing-slash and no-trailing-slash base URLs so
        // the operator's config doesn't have to be exact.
        assertEquals("http://localhost:9000/@api/openapi.json",
                OpenApiCatalog.resolveOpenApiUrl(URI.create("http://localhost:9000")).toString());
        assertEquals("http://localhost:9000/@api/openapi.json",
                OpenApiCatalog.resolveOpenApiUrl(URI.create("http://localhost:9000/")).toString());
    }

    @Test
    void parseSpecSucceedsOnMinimalJson() {
        var json = """
                {
                  "openapi": "3.0.3",
                  "info": {"title": "Test", "version": "1.0"},
                  "paths": {
                    "/api/agents": {
                      "get": {"operationId": "listAgents", "summary": "List"}
                    }
                  }
                }
                """;
        var spec = OpenApiCatalog.parseSpec(json, URI.create("memory:test"));
        assertNotNull(spec);
        assertEquals(1, spec.getPaths().size());
        assertNotNull(spec.getPaths().get("/api/agents").getGet());
    }

    @Test
    void parseSpecFailsOnUnparseableJson() {
        var thrown = assertThrows(IllegalStateException.class,
                () -> OpenApiCatalog.parseSpec("not-json-at-all", URI.create("memory:example")));
        // Error message must name the URL we were fetching so the
        // operator can grep their config for it.
        assertTrue(thrown.getMessage().contains("memory:example"),
                "error should include the source URI; got: " + thrown.getMessage());
    }
}
