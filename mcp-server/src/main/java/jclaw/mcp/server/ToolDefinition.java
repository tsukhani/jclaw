package jclaw.mcp.server;

import com.google.gson.JsonObject;

import java.util.List;

/**
 * One MCP tool, derived from one OpenAPI operation.
 *
 * <p>The MCP {@code tools/list} reply marshals just {@link #name},
 * {@link #description}, and {@link #inputSchema}; the rest of the fields
 * power {@link ToolInvoker} on a subsequent {@code tools/call} —
 * specifically, the HTTP request shape (method + path template +
 * parameter location for every input).
 *
 * <p>Why one record instead of separating "advertised shape" from
 * "callable shape": the two halves are derived from the same OpenAPI
 * source and consumed together (every {@code tools/call} matches one
 * advertised tool). A split would duplicate the operationId-as-key on
 * both sides and invite drift.
 */
public record ToolDefinition(
        String name,
        String description,
        JsonObject inputSchema,
        String httpMethod,
        String pathTemplate,
        List<ParameterBinding> parameters,
        boolean hasJsonBody) {

    /** Where an input argument lives in the outbound HTTP request. */
    public enum Location { PATH, QUERY, BODY }

    /** One input -> one HTTP-request slot. {@link #name} matches a
     *  key on the {@code arguments} object sent in the MCP tools/call;
     *  {@link #location} dictates how to spend it (URL-encode + put in
     *  path, append to query string, or include in JSON body). */
    public record ParameterBinding(String name, Location location, boolean required) {}
}
