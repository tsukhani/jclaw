package jclaw.mcp.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.PathItem;
import io.swagger.v3.oas.models.media.MediaType;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.parameters.Parameter;
import io.swagger.v3.oas.models.parameters.RequestBody;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Walk an {@link OpenAPI} tree and emit one {@link ToolDefinition} per
 * non-streaming operation that the operator's {@link Config#scope}
 * permits.
 *
 * <p><b>Naming.</b> {@code operationId} prefixed with {@code jclaw_} when
 * present (it is for every operation in JClaw's spec since JCLAW-277);
 * a verb-and-path slug otherwise so a legacy unannotated route still
 * yields a unique, stable tool name.
 *
 * <p><b>Filtering.</b> Three reasons to skip an operation:
 * <ol>
 *   <li><b>Streaming response.</b> Any 200 response that advertises
 *       {@code text/event-stream} as a content type — MCP v1 doesn't
 *       carry SSE, and tunnelling streamed bytes through a tool result
 *       would deliver them all at once after stream close, which is
 *       worse than refusing.</li>
 *   <li><b>Scope mismatch.</b> Read-only scope advertises only GET
 *       operations (JCLAW-282 acceptance criterion #5).</li>
 *   <li><b>Operator exclusion.</b> Any of the {@code --exclude}
 *       patterns matches the operationId or path as a substring.</li>
 * </ol>
 *
 * <p>The skip log line at {@code info} level names the operation and
 * the reason so operators can audit the catalog without re-reading the
 * spec.
 */
public final class ToolGenerator {

    private static final Logger log = LoggerFactory.getLogger(ToolGenerator.class);
    private static final String TOOL_NAME_PREFIX = "jclaw_";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String SSE_CONTENT_TYPE = "text/event-stream";

    private final Config config;

    public ToolGenerator(Config config) {
        this.config = config;
    }

    /** Build the tool catalog. Order matches the OpenAPI spec's path
     *  iteration order so {@code tools/list} returns a stable response
     *  for a given spec — important for caching at the MCP-client layer. */
    public List<ToolDefinition> generate(OpenAPI spec) {
        var tools = new ArrayList<ToolDefinition>();
        Objects.requireNonNull(spec, "OpenAPI spec");
        var paths = spec.getPaths();
        if (paths == null || paths.isEmpty()) {
            log.warn("OpenAPI spec contains no paths — no tools will be advertised");
            return tools;
        }

        var seen = new java.util.HashSet<String>();
        for (var pathEntry : paths.entrySet()) {
            var path = pathEntry.getKey();
            var item = pathEntry.getValue();
            for (var op : operationsOf(item)) {
                var tool = tryBuildTool(op.method, path, op.operation, seen);
                if (tool != null) tools.add(tool);
            }
        }
        log.info("Generated {} MCP tool(s) from {} OpenAPI path(s); scope={}",
                tools.size(), paths.size(), config.scope());
        return tools;
    }

    private record HttpOp(String method, Operation operation) {}

    /** {@link PathItem} stores operations under per-verb getters rather
     *  than a map, so this method just enumerates them. Keeping the
     *  order consistent across runs is helpful for caching. */
    private static List<HttpOp> operationsOf(PathItem item) {
        var list = new ArrayList<HttpOp>();
        if (item.getGet() != null) list.add(new HttpOp("GET", item.getGet()));
        if (item.getPost() != null) list.add(new HttpOp("POST", item.getPost()));
        if (item.getPut() != null) list.add(new HttpOp("PUT", item.getPut()));
        if (item.getDelete() != null) list.add(new HttpOp("DELETE", item.getDelete()));
        if (item.getPatch() != null) list.add(new HttpOp("PATCH", item.getPatch()));
        return list;
    }

    private ToolDefinition tryBuildTool(String method, String path, Operation op,
                                       java.util.Set<String> seenNames) {
        var name = toolName(method, path, op);

        if (isStreaming(op)) {
            log.info("Skipping {} {} ({}): streams via {} which MCP v1 doesn't carry",
                    method, path, name, SSE_CONTENT_TYPE);
            return null;
        }
        if (config.scope() == Config.Scope.READ_ONLY && !"GET".equals(method)) {
            log.info("Skipping {} {} ({}): scope=READ_ONLY excludes mutating verbs",
                    method, path, name);
            return null;
        }
        for (var pattern : config.excludes()) {
            if (path.contains(pattern) || (op.getOperationId() != null
                    && op.getOperationId().contains(pattern))) {
                log.info("Skipping {} {} ({}): matched --exclude={}",
                        method, path, name, pattern);
                return null;
            }
        }
        if (!seenNames.add(name)) {
            // Same operationId on two different paths is a spec bug, but
            // MCP requires unique tool names — we'd rather suffix-disambiguate
            // than silently drop. The "_2", "_3" suffixes preserve callability.
            var disambiguated = name;
            var i = 2;
            while (!seenNames.add(disambiguated)) {
                disambiguated = name + "_" + (i++);
            }
            log.warn("Duplicate tool name {} for {} {}; disambiguated to {}",
                    name, method, path, disambiguated);
            name = disambiguated;
        }

        var bindings = new ArrayList<ToolDefinition.ParameterBinding>();
        var schemaProperties = new LinkedHashMap<String, JsonObject>();
        var requiredFields = new ArrayList<String>();

        // Path + query + header parameters from operation.parameters[].
        if (op.getParameters() != null) {
            for (var param : op.getParameters()) {
                var binding = bindParam(param);
                if (binding == null) continue;
                bindings.add(binding);
                schemaProperties.put(binding.name(), schemaForParam(param));
                if (binding.required()) requiredFields.add(binding.name());
            }
        }

        // Request body — flatten {application/json} schema properties
        // into the top-level input schema so tool callers see a single
        // flat argument object rather than a nested {body: {…}} envelope.
        // The flattening is what Anthropic Cookbook's MCP examples model
        // and what most agents expect.
        var hasJsonBody = false;
        var body = op.getRequestBody();
        if (body != null) {
            var schema = jsonSchemaFromBody(body);
            if (schema != null) {
                hasJsonBody = true;
                mergeBodySchema(schema, schemaProperties, requiredFields, bindings);
            }
        }

        var inputSchema = new JsonObject();
        inputSchema.addProperty("type", "object");
        var propsObj = new JsonObject();
        schemaProperties.forEach(propsObj::add);
        inputSchema.add("properties", propsObj);
        if (!requiredFields.isEmpty()) {
            var arr = new JsonArray();
            requiredFields.forEach(arr::add);
            inputSchema.add("required", arr);
        }
        inputSchema.addProperty("additionalProperties", false);

        return new ToolDefinition(
                name,
                description(op),
                inputSchema,
                method,
                path,
                List.copyOf(bindings),
                hasJsonBody);
    }

    private static String toolName(String method, String path, Operation op) {
        var raw = op.getOperationId();
        if (raw != null && !raw.isBlank()) {
            return TOOL_NAME_PREFIX + raw;
        }
        // Fallback: <verb>_<slug-of-path>. Slug drops curly braces and
        // converts non-alphanumeric to underscores.
        var slug = path.replaceAll("[{}]", "")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return TOOL_NAME_PREFIX + method.toLowerCase(Locale.ROOT) + "_" + slug;
    }

    private static String description(Operation op) {
        var summary = op.getSummary();
        var desc = op.getDescription();
        if (summary != null && desc != null && !summary.isBlank() && !desc.isBlank()) {
            return summary + "\n\n" + desc;
        }
        if (desc != null && !desc.isBlank()) return desc;
        if (summary != null && !summary.isBlank()) return summary;
        // Last-resort: a non-empty description is required by some MCP
        // hosts. Give the host SOMETHING actionable rather than blank.
        return "JClaw API operation (no description in OpenAPI spec).";
    }

    private static boolean isStreaming(Operation op) {
        var responses = op.getResponses();
        if (responses == null) return false;
        for (ApiResponse resp : responses.values()) {
            var content = resp.getContent();
            if (content == null) continue;
            for (var contentType : content.keySet()) {
                if (SSE_CONTENT_TYPE.equalsIgnoreCase(contentType)) return true;
            }
        }
        return false;
    }

    private static ToolDefinition.ParameterBinding bindParam(Parameter param) {
        if (param == null || param.getName() == null) return null;
        var in = param.getIn();
        if (in == null) return null;
        var loc = switch (in.toLowerCase(Locale.ROOT)) {
            case "path" -> ToolDefinition.Location.PATH;
            case "query" -> ToolDefinition.Location.QUERY;
            // Header + cookie parameters are intentionally not supported
            // — the bearer header is the only inbound header the MCP
            // server controls; passing arbitrary header inputs through
            // an agent-callable tool is a footgun.
            default -> null;
        };
        if (loc == null) return null;
        var required = Boolean.TRUE.equals(param.getRequired()) || loc == ToolDefinition.Location.PATH;
        return new ToolDefinition.ParameterBinding(param.getName(), loc, required);
    }

    private static JsonObject schemaForParam(Parameter param) {
        var schema = param.getSchema();
        var json = new JsonObject();
        if (schema == null) {
            json.addProperty("type", "string");
        } else {
            copyPrimitiveSchema(schema, json);
        }
        if (param.getDescription() != null) {
            json.addProperty("description", param.getDescription());
        }
        return json;
    }

    /** Pull the JSON schema out of the {@code application/json} content
     *  of a request body. Returns {@code null} when the operation's
     *  body isn't JSON (e.g. multipart upload) — those operations
     *  reach {@link ToolInvoker} without body marshaling and 415 from
     *  the backend if invoked. */
    private static Schema<?> jsonSchemaFromBody(RequestBody body) {
        var content = body.getContent();
        if (content == null) return null;
        MediaType media = content.get(JSON_CONTENT_TYPE);
        if (media == null) return null;
        return media.getSchema();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void mergeBodySchema(Schema schema,
                                        Map<String, JsonObject> properties,
                                        List<String> required,
                                        List<ToolDefinition.ParameterBinding> bindings) {
        // Top-level object schemas flatten directly. Non-object bodies
        // are wrapped in a single {body: ...} argument so the tool
        // remains callable. This handles e.g. raw-string or array
        // request bodies even if no JClaw endpoint uses one today.
        Map<String, Schema> props = schema.getProperties();
        List<String> requiredKeys = schema.getRequired();
        if (props == null || props.isEmpty()) {
            var bodyJson = new JsonObject();
            copyPrimitiveSchema(schema, bodyJson);
            properties.put("body", bodyJson);
            bindings.add(new ToolDefinition.ParameterBinding("body",
                    ToolDefinition.Location.BODY, true));
            required.add("body");
            return;
        }
        for (var entry : props.entrySet()) {
            var propName = entry.getKey();
            var propSchema = entry.getValue();
            var propJson = new JsonObject();
            copyPrimitiveSchema(propSchema, propJson);
            properties.put(propName, propJson);
            bindings.add(new ToolDefinition.ParameterBinding(propName,
                    ToolDefinition.Location.BODY,
                    requiredKeys != null && requiredKeys.contains(propName)));
            if (requiredKeys != null && requiredKeys.contains(propName)) {
                required.add(propName);
            }
        }
    }

    /** Shallow-copy the JSON-Schema-relevant fields. We don't try to
     *  resolve {@code $ref} or follow {@code allOf} composition — the
     *  swagger-parser resolver has already inlined refs from the same
     *  spec by the time we get here, and JClaw's spec doesn't use
     *  composition (every response/request schema is a flat POJO from
     *  a Java record). If that assumption breaks, we'd see the missing
     *  fields in the tools/list output and add a deeper walk. */
    @SuppressWarnings("rawtypes")
    private static void copyPrimitiveSchema(Schema schema, JsonObject out) {
        if (schema == null) {
            out.addProperty("type", "string");
            return;
        }
        var type = schema.getType();
        if (type != null) out.addProperty("type", type);
        else out.addProperty("type", "object");
        if (schema.getFormat() != null) out.addProperty("format", schema.getFormat());
        if (schema.getDescription() != null) out.addProperty("description", schema.getDescription());
        if (schema.getEnum() != null && !schema.getEnum().isEmpty()) {
            var arr = new JsonArray();
            for (var v : schema.getEnum()) {
                if (v == null) arr.add((String) null);
                else arr.add(v.toString());
            }
            out.add("enum", arr);
        }
        if ("array".equals(type) && schema.getItems() != null) {
            var items = new JsonObject();
            copyPrimitiveSchema(schema.getItems(), items);
            out.add("items", items);
        }
    }
}
