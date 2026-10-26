package mcp;

import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import models.Agent;
import models.McpServer;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.Tx;
import utils.ApiResponses;
import utils.GsonHolder;
import utils.ToolErrorTemplates;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * JCLAW-281: server-level handle that exposes an entire MCP server as ONE
 * parameterized {@link ToolRegistry.Tool} to the LLM, instead of N per-action
 * wrappers. The function-calling schema sent to the model now contains a
 * single entry per connected server (name {@code mcp_<server>}, parameters
 * {@code {tool, args, describe, read}}); the per-action {@link McpToolAdapter} registrations
 * remain in the registry as the internal execution path but are hidden from
 * the function-calling defs via {@link ToolRegistry.Tool#isServerLevel()}.
 *
 * <p><b>Discovery is implicit.</b> When the model invokes {@code mcp_<server>}
 * with no {@code tool} argument, this returns the server's action list: with
 * each action's input schema while the list is small, otherwise as names and
 * one-line summaries, the schemas of chosen actions coming from a
 * {@code describe} call. The model builds its populated calls from those
 * schemas. When the model invokes with
 * a {@code tool} field, this delegates to the corresponding {@code McpToolAdapter}
 * (preserving its allowlist gate, audit trail, and error handling) so server-
 * level invocations and any legacy direct-action invocations share the same
 * execution path.
 *
 * <p>A server that declares the {@code resources} capability also lists its resources and
 * templates beside the actions, and {@code {"read": "<uri>"}} returns one resource's contents.
 *
 * <p>Removes the need for a dedicated {@code list_mcp_tools} discovery tool —
 * discovery is now part of every server's own surface, consistent across all
 * models regardless of whether they introspect the function-calling schema or
 * follow the system-prompt catalog.
 */
public final class McpServerTool implements ToolRegistry.Tool {

    private static final Gson GSON = GsonHolder.GSON;

    private static final String FIELD_DESCRIPTION = "description";
    private static final String ARG_TOOL = "tool";
    private static final String ARG_DESCRIBE = "describe";
    private static final String ARG_READ = "read";
    private static final String FIELD_RESOURCES_NOTE = "resourcesNote";
    private static final String CATEGORY_INVOKE = "MCP_TOOL_INVOKE";
    private static final String CATEGORY_RESOURCES = "MCP_RESOURCES";
    private static final String WEB_CHAT_ONLY = "web chat only, where their views can be shown";

    /** google-workspace lists 119 actions in 200 KB, 81% of it schemas: past this size the list is an index. */
    static final int FULL_CATALOG_MAX_CHARS = 8_000;
    private static final int SUMMARY_MAX_CHARS = 160;
    /**
     * A read's text, across all its contents, is cut past this rather than compressed: {@code ccr_retrieve}
     * would hand back the unbounded original. SerpAPI's largest engine resource is 48,761 characters.
     */
    public static final int READ_MAX_CHARS = 64_000;

    private final String serverName;

    public McpServerTool(String serverName) {
        this.serverName = serverName;
    }

    @Override
    public String name() {
        return McpServer.toolName(serverName, "");
    }

    @Override
    public String description() {
        var description = "MCP server `" + serverName + "`. "
                + "Call with no arguments (or `{}`) to list the server's actions. "
                + "A large server lists names and summaries only: call with "
                + "`{\"describe\": [\"<action>\", ...]}` for the input schemas of the actions you will use. "
                + "Call with `{\"tool\": \"<action>\", \"args\": {...}}` to "
                + "execute one action against the server.";
        if (!McpConnectionManager.offersResources(serverName)) return description;
        return description + " The list also shows the server's resources and resource templates: call with "
                + "`{\"read\": \"<uri>\"}` to read one, by a listed URI or a template filled in.";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> tool = Map.of(
                "type", "string",
                FIELD_DESCRIPTION, "The action to execute. Omit to list the server's actions.");
        Map<String, Object> args = Map.of(
                "type", "object",
                FIELD_DESCRIPTION, "Arguments for the action, matching its input schema.",
                "additionalProperties", true);
        Map<String, Object> describe = Map.of(
                "type", "array",
                "items", Map.of("type", "string"),
                FIELD_DESCRIPTION, "Action names whose input schemas to return, instead of executing one.");
        Map<String, Object> properties = McpConnectionManager.offersResources(serverName)
                ? Map.of(ARG_TOOL, tool, "args", args, ARG_DESCRIBE, describe, ARG_READ, Map.of(
                        "type", "string",
                        FIELD_DESCRIPTION, "A resource URI to read instead of executing an action: a listed "
                                + "resource's URI, or a resource template filled in."))
                : Map.of(ARG_TOOL, tool, "args", args, ARG_DESCRIBE, describe);
        return Map.of("type", "object", "properties", properties, "required", List.of());
    }

    /** Always {@code true} — this is the server-level handle that surfaces in
     *  the function-calling defs sent to the LLM. The per-action adapters
     *  registered alongside it return {@code false}. */
    @Override
    public boolean isServerLevel() { return true; }

    /** Share the per-server group so the admin UI's tool catalog continues
     *  to render one card per MCP server with its actions folded inside. */
    @Override
    public String group() { return serverName; }

    @Override
    public String category() { return "MCP"; }

    @Override
    public String icon() { return "plug"; }

    @Override
    public String summary() {
        return McpConnectionManager.offersResources(serverName)
                ? "MCP server `" + serverName + "`. Discovery, action dispatch and resource reads live behind this single handle."
                : "MCP server `" + serverName + "`. Discovery and action dispatch live behind this single handle.";
    }

    @Override
    public String shortDescription() {
        return "Atomic handle for the `" + serverName + "` MCP server. " + (McpConnectionManager.offersResources(serverName)
                ? "Empty-args invocations enumerate actions and resources; `tool` routes to the action, `read` reads a resource."
                : "Empty-args invocations enumerate; populated invocations route to the action.");
    }

    /** Surface every discovered action as an admin-UI sub-entry so the
     *  /tools page card still shows what's available without the LLM seeing
     *  N separate function-calling defs. */
    @Override
    public List<ToolAction> actions() {
        var defs = McpConnectionManager.tools(serverName);
        if (defs == null || defs.isEmpty()) return List.of();
        var out = new ArrayList<ToolAction>(defs.size());
        for (var def : defs) out.add(new ToolAction(def.name(), def.description()));
        return out;
    }

    /** Network-bound and stateless from this Tool's perspective; concurrent
     *  invocations within a single round (each with a different {@code tool})
     *  are safe to race. Per-action serialization, if any, is the MCP
     *  server's own concern. */
    @Override
    public boolean parallelSafe() { return true; }

    /** JCLAW-388: surface the server's {@code requiresApproval} flag as this
     *  handle's {@link ToolRegistry.Tool#dangerous() dangerous} state. This is
     *  the live LLM-facing entry for the server (per JCLAW-281 the per-action
     *  adapters are hidden from the function-calling defs), so the wire-format
     *  name {@link agents.DangerousActionGate} sees is {@code mcp_<server>} — resolving
     *  the flag here is what makes an approval-flagged server's calls raise the
     *  same approve/deny prompt as {@code exec}. The lookup reads the in-memory
     *  connection entry (no DB hit on the dispatch path). */
    @Override
    public boolean dangerous() {
        return McpConnectionManager.requiresApproval(serverName);
    }

    /** The action list and the described schemas are what the model builds its calls from; a read is what it asked for whole. */
    @Override
    public boolean verbatimResult(String argsJson) {
        try {
            var parsed = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            if (!parsed.isJsonObject()) return false;
            var args = parsed.getAsJsonObject();
            return readArgument(args) != null || !describedNames(args).isEmpty() || !namesAnAction(args);
        } catch (RuntimeException _) {
            return false;
        }
    }

    @Override
    public String execute(String argsJson, Agent agent) {
        return executeRich(argsJson, agent).text();
    }

    @Override
    public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
        // Asked once per call, and only when a view tool is in question.
        var webChat = McpViewTools.once(McpViewTools::webChatDispatchOrNot);
        JsonObject args;
        try {
            var parsed = JsonParser.parseString(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            args = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        } catch (RuntimeException e) {
            return ToolRegistry.ToolResult.text(
                    "Error parsing arguments for MCP server '" + serverName + "': " + e.getMessage());
        }

        var read = readArgument(args);
        if (read != null) return readResource(read, agent, args);

        var described = describedNames(args);
        if (!described.isEmpty()) return describeActions(described, webChat);

        // No `tool` → the action list; the model builds its populated calls from the schemas.
        if (!namesAnAction(args)) {
            // A whole envelope wrapped once more also has no outer `tool`, and the catalog
            // does not correct it: the model re-sends the same shape.
            var nested = nestedEnvelopeHint(args, webChat);
            return nested.isEmpty()
                    ? enumerateActions(webChat)
                    : ToolRegistry.ToolResult.text(
                            "MCP server '" + serverName + "' was called with no `tool`. " + nested.strip());
        }

        var actionName = args.get(ARG_TOOL).getAsString();
        var actionArgs = args.has("args") && args.get("args").isJsonObject()
                ? args.getAsJsonObject("args").toString()
                : "{}";

        // Delegate to the per-action adapter that already lives in the
        // registry. The adapter carries the allowlist gate + audit trail,
        // so server-level invocations get the same JCLAW-32 safety
        // guarantees as legacy direct-action invocations.
        // An action this turn is not offered, the view's own or a view tool off web chat, does not exist for the model.
        var adapter = ToolRegistry.lookupTool(McpServer.toolName(serverName, actionName));
        if (adapter != null && !offeredHere(adapter, webChat)) adapter = null;
        if (adapter == null) {
            // Server down, action misspelled, or tool list changed since the model last
            // enumerated. Names only (no schemas) so the model can self-correct next turn
            // without re-ingesting the full catalog, which on a large server pushes the prompt
            // tens of thousands of tokens and time-to-first-token into the minute range.
            return ToolRegistry.ToolResult.text(
                    "MCP server '" + serverName + "' has no action named '" + actionName + "'. "
                            + envelopeHint(actionName, args, webChat)
                            + "Available actions: " + enumerateActionNames(webChat)
                            + ". Call mcp_" + serverName + " with {\"describe\": [\"<action>\"]} "
                            + "for an action's input schema if needed.");
        }
        return adapter instanceof McpToolAdapter mcpAdapter
                ? mcpAdapter.executeRich(actionArgs, agent, webChat) : adapter.executeRich(actionArgs, agent);
    }

    /**
     * A bare action list does not correct a model that read {@code tool} as
     * "which server" and re-nested the real call under {@code args} — it
     * re-sends the same wrong shape until the round budget runs out.
     *
     * @return a corrective sentence ending in a space, empty for a plain
     *         unknown action (typo, stale tool list).
     */
    private String envelopeHint(String actionName, JsonObject args, BooleanSupplier webChat) {
        var nested = nestedEnvelopeHint(args, webChat);
        if (!nested.isEmpty()) return nested;
        if (actionName.equals(serverName) || actionName.equals(name())) {
            return "That is this server's own name, not an action — `tool` takes the action name. ";
        }
        return "";
    }

    /** The corrective sentence when {@code args.tool} names a registered action, else empty. */
    private String nestedEnvelopeHint(JsonObject args, BooleanSupplier webChat) {
        if (args.has("args") && args.get("args").isJsonObject()) {
            var inner = args.getAsJsonObject("args");
            if (inner.has("tool") && inner.get("tool").isJsonPrimitive()) {
                var nested = inner.get("tool").getAsString();
                var tool = ToolRegistry.lookupTool(McpServer.toolName(serverName, nested));
                if (tool != null && offeredHere(tool, webChat)) {
                    return "The envelope is nested one level too deep — '" + nested
                            + "' is the action. Call {\"tool\": \"" + nested
                            + "\", \"args\": {...}} with the action's own arguments at the top of `args`. ";
                }
            }
        }
        return "";
    }

    /** Judged by the adapter's own def, which is registered before the server's tool list reads as connected. */
    private boolean offeredHere(ToolRegistry.Tool tool, BooleanSupplier webChat) {
        return !(tool instanceof McpToolAdapter adapter)
                || !McpViewTools.offered(serverName, List.of(adapter.def()), webChat).isEmpty();
    }

    /** A connected server whose model-visible actions all link a view is not down: they are kept for web chat. */
    private boolean actionsKeptForWebChat() {
        return !forModel(McpConnectionManager.tools(serverName)).isEmpty();
    }

    /** This server's actions the model may see on the turn dispatching this call. */
    private List<McpToolDef> offeredActions(BooleanSupplier webChat) {
        return McpViewTools.offered(serverName, McpConnectionManager.toolsForTurn(serverName), webChat);
    }

    /** MCP Apps' visibility without {@code "model"} keeps an action for the view alone. */
    private static List<McpToolDef> forModel(List<McpToolDef> defs) {
        return defs.stream().filter(McpToolDef::visibleToModel).toList();
    }

    private static boolean namesAnAction(JsonObject args) {
        return args.has(ARG_TOOL) && !args.get(ARG_TOOL).isJsonNull() && !args.get(ARG_TOOL).getAsString().isBlank();
    }

    private static List<String> describedNames(JsonObject args) {
        var el = args.get(ARG_DESCRIBE);
        if (el == null || el.isJsonNull()) return List.of();
        // Models send one name as a bare string as often as a one-element list.
        Iterable<JsonElement> items = el.isJsonArray() ? el.getAsJsonArray() : List.of(el);
        var names = new ArrayList<String>();
        for (var item : items) {
            if (item.isJsonPrimitive() && !item.getAsString().isBlank()) names.add(item.getAsString());
        }
        return names;
    }

    /**
     * The {@code read} a call names, null when the call is no read: present and not null, nor blank beside
     * a named action, since a model filling every optional field sends {@code "read": null} or {@code ""}.
     * The one decision, for the handle and for the log line that names what it runs.
     */
    public static @Nullable JsonElement readArgument(JsonObject args) {
        var el = args.get(ARG_READ);
        if (el == null || el.isJsonNull()) return null;
        var blank = el.isJsonPrimitive() && el.getAsString().isBlank();
        return blank && namesAnAction(args) ? null : el;
    }

    private ToolRegistry.ToolResult enumerateActions(BooleanSupplier webChat) {
        var defs = offeredActions(webChat);
        var resources = McpConnectionManager.offersResources(serverName);
        if ((defs == null || defs.isEmpty()) && !resources) return noActionsOffered();
        if (!resources) return catalog(serverName, defs);
        ToolRegistry.ToolResult listed;
        try {
            listed = catalog(serverName, defs, McpConnectionManager.listResources(serverName));
        } catch (IOException | RuntimeException e) {
            EventLogger.warn(CATEGORY_RESOURCES, "MCP server '%s' could not list its resources: %s"
                    .formatted(serverName, ApiResponses.messageOf(e)));
            listed = catalogWithoutResources(serverName, defs);
        }
        if (!defs.isEmpty() || !actionsKeptForWebChat()) return listed;
        var payload = JsonParser.parseString(listed.text()).getAsJsonObject();
        payload.addProperty("actionsNote", "This server offers its actions in " + WEB_CHAT_ONLY + ".");
        var json = GSON.toJson(payload);
        return new ToolRegistry.ToolResult(json, json);
    }

    /** Validated, then refused without a request when the server is down, declared no resources, or granted this agent nothing. */
    private ToolRegistry.ToolResult readResource(JsonElement read, Agent agent, JsonObject args) {
        if (!read.isJsonPrimitive() || !read.getAsJsonPrimitive().isString() || read.getAsString().isBlank()) {
            return ToolRegistry.ToolResult.text("The `read` argument for MCP server '" + serverName
                    + "' must be a resource URI, as a non-blank string. Call " + name()
                    + " with {} to list its resources and templates.");
        }
        var uri = read.getAsString();
        if (McpResources.isViewPage(uri, null)) return viewPage(uri);
        if (McpConnectionManager.status(serverName) != McpServer.Status.CONNECTED) return notConnected();
        if (!McpConnectionManager.offersResources(serverName)) {
            return ToolRegistry.ToolResult.text("MCP server '" + serverName + "' offers no resources.");
        }
        boolean granted;
        try {
            // Grants are written per tool, so on a server with none the per-server switch alone decides.
            granted = McpConnectionManager.tools(serverName).isEmpty()
                    || Tx.run(() -> McpAllowlist.holdsAnyGrant(agent, serverName));
        } catch (RuntimeException e) {
            return ToolRegistry.ToolResult.text(ToolErrorTemplates.render(ToolErrorTemplates.mcpProtocolFailed(
                    serverName, ARG_READ, "allowlist check failed: " + ApiResponses.messageOf(e))));
        }
        auditRead(agent, uri, args, granted);
        if (!granted) {
            return ToolRegistry.ToolResult.text(ToolErrorTemplates.render(
                    ToolErrorTemplates.mcpNotAllowed(serverName, ARG_READ, agentName(agent))));
        }
        try {
            var contents = McpConnectionManager.readResource(serverName, uri);
            if (contents.stream().anyMatch(c -> McpResources.isViewPage(c.uri(), c.mimeType()))) return viewPage(uri);
            var rendered = renderRead(contents);
            return ToolRegistry.ToolResult.text(rendered.isEmpty()
                    ? "MCP server '" + serverName + "' returned no contents for '" + uri + "'."
                    : rendered);
        } catch (McpException.ResourceNotFound _) {
            return ToolRegistry.ToolResult.text("MCP server '" + serverName + "' has no resource '" + uri
                    + "'. Call " + name() + " with {} to list its resources and templates.");
        } catch (McpException.InputRequired questions) {
            return ToolRegistry.ToolResult.text(Objects.requireNonNullElse(questions.getMessage(), ""));
        } catch (IOException e) {
            return ToolRegistry.ToolResult.text(ToolErrorTemplates.render(
                    ToolErrorTemplates.mcpConnectionFailed(serverName, ARG_READ, ApiResponses.messageOf(e))));
        } catch (RuntimeException e) {
            return ToolRegistry.ToolResult.text(ToolErrorTemplates.render(
                    ToolErrorTemplates.mcpProtocolFailed(serverName, ARG_READ, ApiResponses.messageOf(e))));
        }
    }

    private ToolRegistry.ToolResult viewPage(String uri) {
        return ToolRegistry.ToolResult.text("'" + uri + "' on MCP server '" + serverName
                + "' is a view page, which the web chat draws; it is not a resource to read.");
    }

    /** The same audit trail an action call leaves; through {@link EventLogger}, which masks a credential in the URI. */
    private void auditRead(@Nullable Agent agent, String uri, JsonObject args, boolean allowed) {
        var agentId = agent != null && agent.id != null ? String.valueOf(agent.id) : null;
        EventLogger.record(allowed ? "INFO" : "WARN", CATEGORY_INVOKE, agentId, null,
                "%s read %s by '%s' [%s]".formatted(serverName, new JsonPrimitive(uri), agentName(agent),
                        allowed ? "allowed" : "denied"),
                args.toString());
    }

    private static String agentName(@Nullable Agent agent) {
        return agent != null && agent.name != null ? agent.name : "<unknown>";
    }

    private ToolRegistry.ToolResult describeActions(List<String> names, BooleanSupplier webChat) {
        var defs = offeredActions(webChat);
        return defs == null || defs.isEmpty() ? noActionsOffered() : describe(serverName, defs, names);
    }

    private ToolRegistry.ToolResult noActionsOffered() {
        if (actionsKeptForWebChat()) {
            return ToolRegistry.ToolResult.text("MCP server '" + serverName + "' offers its actions in " + WEB_CHAT_ONLY + ".");
        }
        if (!McpConnectionManager.tools(serverName).isEmpty()) {
            return ToolRegistry.ToolResult.text("MCP server '" + serverName + "' offers no actions the model may call.");
        }
        return notConnected();
    }

    private ToolRegistry.ToolResult notConnected() {
        return ToolRegistry.ToolResult.text(
                "MCP server '" + serverName + "' is not currently connected or advertises no actions.");
    }

    /** The action list: every input schema while that stays small, otherwise names and one-line summaries. */
    public static ToolRegistry.ToolResult catalog(String serverName, List<McpToolDef> defs) {
        return catalog(serverName, defs, _ -> {});
    }

    /** The action list, with the server's resources and templates beside it. */
    public static ToolRegistry.ToolResult catalog(String serverName, List<McpToolDef> defs,
                                                  McpResources.Listing listing) {
        return catalog(serverName, defs, payload -> {
            payload.put("resources", resourceEntries(listing.resources()));
            payload.put("resourceTemplates", templateEntries(listing.templates()));
            if (listing.more()) {
                payload.put(FIELD_RESOURCES_NOTE, "The server has more resources or resource templates than are "
                        + "listed here. A template filled in, or a URI you already know, reads directly with "
                        + "{\"read\": \"<uri>\"}.");
            }
        });
    }

    /** The action list when the server's resources could not be fetched: the actions stand, and one line says so. */
    public static ToolRegistry.ToolResult catalogWithoutResources(String serverName, List<McpToolDef> defs) {
        return catalog(serverName, defs, payload -> payload.put(FIELD_RESOURCES_NOTE,
                "The server's resources and resource templates could not be listed just now."));
    }

    /** Whole or an index by the actions alone, so resources never change how the actions are shown. */
    private static ToolRegistry.ToolResult catalog(String serverName, List<McpToolDef> defs,
                                                   Consumer<Map<String, Object>> resources) {
        var shown = forModel(defs);
        var payload = payload(serverName, null, entries(shown, true));
        if (GSON.toJson(payload).length() > FULL_CATALOG_MAX_CHARS) {
            payload = payload(serverName,
                    "Not listed. Call mcp_" + serverName + " with {\"describe\": [\"<action>\", ...]} for the input "
                            + "schemas of the actions you will use, before calling them.",
                    entries(shown, false));
        }
        resources.accept(payload);
        var json = GSON.toJson(payload);
        return new ToolRegistry.ToolResult(json, json);
    }

    /** An MCP Apps page is drawn by the web chat, never read by the model. */
    private static List<Map<String, Object>> resourceEntries(List<McpResources.Resource> resources) {
        var out = new ArrayList<Map<String, Object>>(resources.size());
        for (var r : resources) {
            if (McpResources.isViewPage(r.uri(), r.mimeType())) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("uri", r.uri());
            putIfPresent(entry, "name", r.name());
            putSummary(entry, r.description());
            putIfPresent(entry, "mimeType", r.mimeType());
            out.add(entry);
        }
        return out;
    }

    private static List<Map<String, Object>> templateEntries(List<McpResources.Template> templates) {
        var out = new ArrayList<Map<String, Object>>(templates.size());
        for (var t : templates) {
            if (McpResources.isViewPage(t.uriTemplate(), null)) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("uriTemplate", t.uriTemplate());
            putIfPresent(entry, "name", t.name());
            putSummary(entry, t.description());
            out.add(entry);
        }
        return out;
    }

    private static void putIfPresent(Map<String, Object> entry, String key, @Nullable String value) {
        if (value != null) entry.put(key, value);
    }

    /** A listing is verbatim, so a description is cut as an action summary is. */
    private static void putSummary(Map<String, Object> entry, @Nullable String description) {
        if (description != null) entry.put(FIELD_DESCRIPTION, summary(description));
    }

    /**
     * A read's contents as the model sees them: one text content alone, otherwise each under a
     * {@code --- <uri> (<mimeType>) ---} line, and a blob named by its type and size. Past
     * {@link #READ_MAX_CHARS} of that output the rest is cut, and a last line says how much was shown.
     */
    public static String renderRead(List<McpResources.Content> contents) {
        var alone = contents.size() == 1 && contents.getFirst().text() != null;
        var pieces = new ArrayList<String>();
        var atLineStart = true;
        for (var c : contents) {
            var mimeType = c.mimeType();
            if (!alone) {
                if (!atLineStart) pieces.add("\n");
                pieces.add("--- " + c.uri() + (mimeType != null ? " (" + mimeType + ")" : "") + " ---\n");
                atLineStart = true;
            }
            var text = c.text();
            var shownType = mimeType != null ? " " + mimeType : "";
            var body = text != null ? text : "[binary" + shownType + ", " + c.blobBytes() + " bytes]";
            pieces.add(body);
            if (!body.isEmpty()) atLineStart = body.endsWith("\n");
        }
        long total = 0;
        for (var piece : pieces) total += piece.length();
        if (total <= READ_MAX_CHARS) return String.join("", pieces);
        var out = new StringBuilder(READ_MAX_CHARS + 80);
        for (var piece : pieces) {
            var room = READ_MAX_CHARS - out.length();
            if (piece.length() > room) {
                out.append(piece, 0, cutAt(piece, room));
                break;
            }
            out.append(piece);
        }
        return out.append(String.format(Locale.ROOT, "\n[resource truncated: showed the first %,d of %,d characters]",
                out.length(), total)).toString();
    }

    /** Never between a surrogate pair: a lone half does not survive the JSON sent to the provider. */
    private static int cutAt(String text, int at) {
        return at > 0 && Character.isHighSurrogate(text.charAt(at - 1)) ? at - 1 : at;
    }

    /** The whole description and input schema of each named action; a name the server lacks is reported as unknown. */
    public static ToolRegistry.ToolResult describe(String serverName, List<McpToolDef> defs, List<String> names) {
        var visible = forModel(defs);
        var known = new ArrayList<McpToolDef>();
        var unknown = new ArrayList<String>();
        for (var name : names) {
            var def = visible.stream().filter(d -> d.name().equals(name)).findFirst().orElse(null);
            if (def == null) unknown.add(name);
            else known.add(def);
        }
        var payload = payload(serverName, null, entries(known, true));
        if (!unknown.isEmpty()) payload.put("unknown", unknown);
        var json = GSON.toJson(payload);
        return new ToolRegistry.ToolResult(json, json);
    }

    private static Map<String, Object> payload(String serverName, @Nullable String inputSchemas,
                                               List<Map<String, Object>> actions) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("server", serverName);
        if (inputSchemas != null) payload.put("inputSchemas", inputSchemas);
        payload.put("actions", actions);
        return payload;
    }

    private static List<Map<String, Object>> entries(List<McpToolDef> defs, boolean withSchema) {
        var out = new ArrayList<Map<String, Object>>(defs.size());
        for (var def : defs) {
            var description = def.description() == null ? "" : def.description();
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", def.name());
            entry.put(FIELD_DESCRIPTION, withSchema ? description : summary(description));
            if (withSchema) entry.put("inputSchema", def.parametersAsMap());
            out.add(entry);
        }
        return out;
    }

    /** A description's first line, cut at a sentence end where one falls inside the cap. */
    private static String summary(String description) {
        var line = description.strip();
        int newline = line.indexOf('\n');
        if (newline >= 0) line = line.substring(0, newline).strip();
        if (line.length() <= SUMMARY_MAX_CHARS) return line;
        int sentenceEnd = line.lastIndexOf(". ", SUMMARY_MAX_CHARS);
        return sentenceEnd > 0 ? line.substring(0, sentenceEnd + 1) : line.substring(0, SUMMARY_MAX_CHARS - 1) + "…";
    }

    /**
     * Compact name-only list for the unknown-action error path.
     * {@link #enumerateActions} can carry every {@code inputSchema}, which is
     * overkill for a typo-correction signal. Names alone let the model spot the right action; if it actually needs
     * a schema it can ask for that one with {@code describe}.
     */
    private String enumerateActionNames(BooleanSupplier webChat) {
        var defs = offeredActions(webChat);
        if (defs == null || defs.isEmpty()) {
            return actionsKeptForWebChat() ? "(none here: this server offers its actions in " + WEB_CHAT_ONLY + ")"
                    : "(none — server not connected or advertises no actions)";
        }
        var names = new ArrayList<String>(defs.size());
        for (var def : defs) names.add(def.name());
        return String.join(", ", names);
    }
}
