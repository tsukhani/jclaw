package jclaw.mcp.server;

import io.swagger.parser.OpenAPIParser;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.parser.core.models.ParseOptions;
import okhttp3.Request;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;

/**
 * Fetch and parse JClaw's OpenAPI spec.
 *
 * <p>Two steps: HTTP GET the spec from {@code /@api/openapi.json}
 * (with bearer auth, in case the operator has gated the endpoint),
 * then hand the body to {@code swagger-parser}. The bearer header
 * matters because JCLAW-282 anticipates a follow-up where {@code /@api/*}
 * moves behind {@code AuthCheck} — see the security follow-up comment
 * in {@code conf/application.conf}.
 *
 * <p>Resolve-refs is on so {@link ToolGenerator} sees inlined schemas
 * — {@code copyPrimitiveSchema} doesn't follow {@code $ref}, and the
 * spec produced by Play's OpenApiPlugin tends to use refs heavily for
 * component schemas.
 */
public final class OpenApiCatalog {

    private static final Logger log = LoggerFactory.getLogger(OpenApiCatalog.class);

    private final JClawHttp http;

    public OpenApiCatalog(JClawHttp http) {
        this.http = http;
    }

    /** Hit {@code /@api/openapi.json} and parse the result. Throws
     *  {@link IOException} on transport failure with the URL embedded
     *  in the message; throws {@link IllegalStateException} when the
     *  body is non-empty but unparseable, so the operator's logs hint
     *  at "spec is malformed" rather than "couldn't reach JClaw". */
    public OpenAPI fetch() throws IOException {
        var url = resolveOpenApiUrl(http.config().baseUrl());
        log.info("Fetching OpenAPI spec from {}", url);

        var req = new Request.Builder()
                .url(url.toString())
                .header("Authorization", "Bearer " + http.config().bearerToken())
                .header("Accept", "application/json")
                .build();

        try (var resp = http.client().newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new IOException("OpenAPI spec fetch failed (%d) from %s".formatted(
                        resp.code(), url));
            }
            var body = resp.body().string();
            if (body.isBlank()) {
                throw new IOException("OpenAPI spec at %s returned an empty body".formatted(url));
            }
            return parseSpec(body, url);
        }
    }

    static OpenAPI parseSpec(String body, URI sourceForErrorMessage) {
        var parseOptions = new ParseOptions();
        parseOptions.setResolve(true);
        parseOptions.setResolveFully(true);
        var result = new OpenAPIParser().readContents(body, null, parseOptions);
        if (result.getOpenAPI() == null) {
            var messages = result.getMessages() == null
                    ? "(no parser messages)" : String.join("; ", result.getMessages());
            throw new IllegalStateException(
                    "Could not parse OpenAPI spec from %s: %s".formatted(
                            sourceForErrorMessage, messages));
        }
        return result.getOpenAPI();
    }

    /** {@code baseUrl} points at the JClaw API root (often
     *  {@code http://localhost:9000}); the spec lives at
     *  {@code /@api/openapi.json}. Handles a {@code baseUrl} with or
     *  without a trailing slash. */
    static URI resolveOpenApiUrl(URI baseUrl) {
        var asString = baseUrl.toString();
        if (asString.endsWith("/")) asString = asString.substring(0, asString.length() - 1);
        return URI.create(asString + "/@api/openapi.json");
    }
}
