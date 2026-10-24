import agents.CompressionPipeline;
import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import mcp.McpResources;
import mcp.McpServerTool;
import mcp.McpToolDef;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JCLAW-312: server-level handle coverage for {@link McpServerTool}.
 *
 * <p>{@link McpServerTool} is the single function-calling entry per connected
 * MCP server. It carries five behavioural surfaces:
 *
 * <ul>
 *   <li>Identity metadata ({@code name}, {@code description}, {@code group},
 *       {@code category}, {@code icon}, {@code isServerLevel}) derived from
 *       the server name passed at construction.</li>
 *   <li>Empty / blank / missing {@code tool} args → discovery catalog (which
 *       falls back to "not connected" when no entry exists for the server).</li>
 *   <li>Malformed args JSON → deterministic parse error envelope.</li>
 *   <li>Populated {@code tool} args with an unknown adapter →
 *       not-registered error envelope with the current catalog appended.</li>
 *   <li>Populated {@code tool} args with a registered adapter → delegation to
 *       that adapter's {@code executeRich}, returning its result verbatim.</li>
 * </ul>
 *
 * <p>The not-connected path is fully testable here — {@code McpConnectionManager}
 * returns an empty tools list for any server name without an active entry,
 * which is exactly what {@code enumerateActions} signals when no live MCP
 * connection exists. The connected-with-defs catalog path requires a running
 * stdio server fixture and is deferred to {@code McpConnectionManagerTest}.
 */
class McpServerToolTest extends UnitTest {

    @BeforeEach
    void saveRegistry() {
        // JCLAW-894: take the registry lock, because this class wipes a
        // process-global that concurrently-running classes read. The old snapshot
        // idiom (listTools() then publish(saved)) was also wrong in its own right:
        // listTools() returns the MERGED map, while publish() replaces only the
        // NATIVE one, so round-tripping promoted every MCP-discovered tool into the
        // native slot, where nothing would ever unpublish it.
        ToolRegistrySync.publishForTest(List.of());
    }

    @AfterEach
    void restoreRegistry() {
        ToolRegistrySync.release();
    }

    // ==================== identity metadata ====================

    @Test
    void nameMirrorsServerNameWithMcpPrefix() {
        var tool = new McpServerTool("github");
        assertEquals("mcp_github", tool.name(),
                "name shape matches the McpToolAdapter naming convention so the "
                        + "LLM can correlate the server-level handle with its actions");
    }

    @Test
    void descriptionExposesEmptyArgsContract() {
        // The description teaches the model that empty args = discovery.
        // Without that hint, models that don't introspect the schema would
        // skip enumeration and fail to find any actions.
        var d = new McpServerTool("github").description();
        assertTrue(d.contains("github"),
                "description must name the server so log-trawling stays clear");
        assertTrue(d.contains("`{}`") || d.contains("no arguments"),
                "empty-args path must be advertised: " + d);
        assertTrue(d.contains("\"tool\"") && d.contains("\"args\""),
                "populated-args shape must be advertised: " + d);
    }

    @Test
    void parametersSchemaExposesToolAndArgsFields() {
        var params = new McpServerTool("github").parameters();
        assertEquals("object", params.get("type"));
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) params.get("properties");
        @SuppressWarnings("unchecked")
        var toolField = (Map<String, Object>) props.get("tool");
        assertEquals("string", toolField.get("type"));
        @SuppressWarnings("unchecked")
        var argsField = (Map<String, Object>) props.get("args");
        assertEquals("object", argsField.get("type"));
        assertEquals(Boolean.TRUE, argsField.get("additionalProperties"),
                "args must allow arbitrary keys — the action's input schema "
                        + "varies per server, so this entry is intentionally open");
        // required:[] — both fields are optional. Empty args triggers discovery.
        assertTrue(params.get("required") instanceof List);
        @SuppressWarnings("unchecked")
        var required = (List<Object>) params.get("required");
        assertTrue(required.isEmpty(),
                "neither field required: empty invocation must reach the discovery path");
    }

    @Test
    void groupMatchesServerNameForAdminUiCardGrouping() {
        var tool = new McpServerTool("github");
        assertEquals("github", tool.group(),
                "group must equal serverName so per-server admin cards fold both "
                        + "the server-level handle and per-action adapters together");
    }

    @Test
    void isServerLevelTrueDistinguishesFromPerActionAdapters() {
        // The function-calling-def filter in ToolRegistry hides every tool
        // with group != null AND isServerLevel() == false. The handle must
        // set this true to survive the filter.
        assertTrue(new McpServerTool("github").isServerLevel());
    }

    @Test
    void categoryAndIconAreMcpDefaults() {
        var tool = new McpServerTool("github");
        assertEquals("MCP", tool.category(),
                "category bucket so the admin UI groups MCP cards together");
        assertEquals("plug", tool.icon(),
                "icon matches McpToolAdapter so per-server card stays visually consistent");
    }

    @Test
    void parallelSafeTrueForStatelessHttpHandle() {
        // Per Javadoc: network-bound and stateless from the handle's POV.
        // The actual MCP server may serialize internally; that's its concern.
        assertTrue(new McpServerTool("github").parallelSafe());
    }

    @Test
    void summaryAndShortDescriptionMentionServerName() {
        var tool = new McpServerTool("github");
        assertTrue(tool.summary().contains("github"));
        assertTrue(tool.shortDescription().contains("github"));
    }

    @Test
    void actionsListEmptyWhenNoLiveConnection() {
        // No connection → empty action list. The admin UI renders the card
        // without an actions disclosure rather than throwing.
        var tool = new McpServerTool("never-connected-" + System.nanoTime());
        List<ToolAction> actions = tool.actions();
        assertNotNull(actions);
        assertTrue(actions.isEmpty(),
                "no live connection ⇒ no advertised actions, but no NPE");
    }

    // ==================== enumerate (empty / blank / missing tool) ====================

    @Test
    void emptyArgsHitsEnumerateForDisconnectedServer() {
        var tool = new McpServerTool("ghost-server-" + System.nanoTime());
        var result = tool.executeRich("{}", null);
        assertNotNull(result);
        assertTrue(result.text().contains("not currently connected")
                        || result.text().contains("advertises no actions"),
                "no live entry ⇒ enumerate signals disconnect: " + result.text());
    }

    @Test
    void nullArgsTreatedAsEmptyObject() {
        // The streaming accumulator can hand the tool a null argsJson when the
        // model emits an empty tool call. McpServerTool normalises to "{}".
        var tool = new McpServerTool("ghost-server-" + System.nanoTime());
        var result = tool.executeRich(null, null);
        assertNotNull(result);
        assertTrue(result.text().contains("not currently connected")
                        || result.text().contains("advertises no actions"),
                "null args should route to enumerate, not crash: " + result.text());
    }

    @Test
    void blankArgsTreatedAsEmptyObject() {
        var tool = new McpServerTool("ghost-server-" + System.nanoTime());
        var result = tool.executeRich("   ", null);
        assertNotNull(result);
        assertTrue(result.text().contains("not currently connected")
                        || result.text().contains("advertises no actions"));
    }

    @Test
    void blankToolFieldHitsEnumerate() {
        var tool = new McpServerTool("ghost-server-" + System.nanoTime());
        // Blank string for the tool field — model-emitted edge case — must
        // route to discovery so the model can self-correct on the next turn
        // instead of receiving a "missing tool 'EMPTY'" error.
        var result = tool.executeRich("{\"tool\":\"\"}", null);
        assertTrue(result.text().contains("not currently connected")
                        || result.text().contains("advertises no actions"));
    }

    @Test
    void nullToolFieldHitsEnumerate() {
        var tool = new McpServerTool("ghost-server-" + System.nanoTime());
        var result = tool.executeRich("{\"tool\":null}", null);
        assertTrue(result.text().contains("not currently connected")
                        || result.text().contains("advertises no actions"));
    }

    // ==================== execute() text-only delegation ====================

    @Test
    void executeReturnsTextOfExecuteRich() {
        // The text-only variant must mirror executeRich().text() so tools that
        // only consume String results (legacy callers) see the same envelope.
        var tool = new McpServerTool("ghost-server-" + System.nanoTime());
        var text = tool.execute("{}", null);
        var rich = tool.executeRich("{}", null);
        assertEquals(rich.text(), text);
    }

    // ==================== malformed args ====================

    @Test
    void malformedJsonSurfacesParseError() {
        var tool = new McpServerTool("github");
        var result = tool.executeRich("{ this is not json", null);
        assertTrue(result.text().startsWith("Error parsing arguments"),
                "deterministic prefix lets the LLM recognize the failure mode: "
                        + result.text());
        assertTrue(result.text().contains("github"),
                "error names the server so multi-server agents can attribute it");
    }

    @Test
    void nonObjectArgsTreatedAsEmptyObject() {
        // Top-level array/string is valid JSON but not a JsonObject.
        // Production code falls through to the enumerate branch, treating the
        // input as if it were {}.
        var tool = new McpServerTool("ghost-server-" + System.nanoTime());
        var result = tool.executeRich("[1,2,3]", null);
        assertNotNull(result);
        // Either enumerate (currently expected) or error — both are
        // deterministic envelopes, never an exception escape.
        assertFalse(result.text().isBlank());
    }

    // ==================== delegation to registered adapter ====================

    @Test
    void populatedToolFieldDelegatesToRegisteredAdapter() {
        var serverName = "github";
        var actionName = "create_issue";
        var adapterName = "mcp_" + serverName + "_" + actionName;

        var adapter = recordingAdapter(adapterName);
        ToolRegistry.publish(List.of(adapter));

        var tool = new McpServerTool(serverName);
        var result = tool.executeRich(
                "{\"tool\":\"create_issue\",\"args\":{\"title\":\"Bug\"}}", null);

        assertEquals("FAKE-RESULT[create_issue]", result.text(),
                "the adapter's output must surface verbatim — no wrapping or "
                        + "envelope rewrites at the server-level layer");
        assertNotNull(adapter.lastArgs,
                "adapter must have been invoked");
        // The args field of the outer envelope is passed through verbatim as JSON.
        var parsed = JsonParser.parseString(adapter.lastArgs).getAsJsonObject();
        assertEquals("Bug", parsed.get("title").getAsString());
    }

    @Test
    void populatedToolFieldWithMissingArgsPassesEmptyObject() {
        // A model may emit just {tool:"x"} for parameter-less actions. The
        // adapter must receive "{}" so it can parse without an EOF error.
        var serverName = "github";
        var adapterName = "mcp_" + serverName + "_ping";
        var adapter = recordingAdapter(adapterName);
        ToolRegistry.publish(List.of(adapter));

        var tool = new McpServerTool(serverName);
        var result = tool.executeRich("{\"tool\":\"ping\"}", null);

        assertEquals("FAKE-RESULT[ping]", result.text());
        assertEquals("{}", adapter.lastArgs,
                "missing args field defaults to empty object so the adapter "
                        + "sees parseable JSON");
    }

    @Test
    void populatedToolFieldWithNonObjectArgsAlsoPassesEmptyObject() {
        // Defensive: args field present but not an object (string, array, null)
        // is silently coerced to {} per production code at lines 150–152.
        var serverName = "github";
        var adapterName = "mcp_" + serverName + "_ping";
        var adapter = recordingAdapter(adapterName);
        ToolRegistry.publish(List.of(adapter));

        var tool = new McpServerTool(serverName);
        var result = tool.executeRich(
                "{\"tool\":\"ping\",\"args\":\"unexpected-string\"}", null);
        assertEquals("FAKE-RESULT[ping]", result.text());
        assertEquals("{}", adapter.lastArgs);
    }

    // ==================== the action list: whole when small, an index when large ====================

    private static McpToolDef def(String name, String description, int schemaProperties) {
        var props = new JsonObject();
        for (int i = 0; i < schemaProperties; i++) {
            var p = new JsonObject();
            p.addProperty("type", "string");
            p.addProperty("description", "Property " + i + " of " + name + ", described at some length.");
            props.add("field_" + i, p);
        }
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", props);
        return new McpToolDef(name, description, schema);
    }

    private static List<McpToolDef> largeServer() {
        var defs = new ArrayList<McpToolDef>();
        for (int i = 0; i < 40; i++) {
            defs.add(def("action_" + i, "Does thing " + i + " to a file.\n\nArgs:\n    a long docstring follows", 6));
        }
        return defs;
    }

    @Test
    void aSmallServerListsEveryActionWithItsSchema() {
        var result = McpServerTool.catalog("tiny", List.of(def("ping", "Pings.", 1), def("pong", "Pongs.", 1)));
        var actions = JsonParser.parseString(result.text()).getAsJsonObject().getAsJsonArray("actions");
        assertEquals(2, actions.size());
        assertTrue(actions.get(0).getAsJsonObject().has("inputSchema"),
                "a list this small costs less than a second call would: " + result.text());
    }

    @Test
    void aLargeServerListsNamesAndSummariesAndPointsAtDescribe() {
        var defs = largeServer();
        var whole = 0;
        for (var d : defs) whole += d.inputSchema().toString().length();
        var result = McpServerTool.catalog("big", defs);
        var payload = JsonParser.parseString(result.text()).getAsJsonObject();
        var actions = payload.getAsJsonArray("actions");
        assertEquals(40, actions.size(), "every action is named, or the model cannot pick one");
        var first = actions.get(0).getAsJsonObject();
        assertEquals("action_0", first.get("name").getAsString());
        assertEquals("Does thing 0 to a file.", first.get("description").getAsString(),
                "the summary is the description's first line");
        assertFalse(result.text().contains("inputSchema\":{"), "no schema in the index: " + result.text());
        assertTrue(result.text().contains("\\\"describe\\\""), "the index says how to get a schema: " + result.text());
        assertTrue(result.text().length() < whole / 3,
                "the index is a fraction of the schemas it leaves out: " + result.text().length() + " vs " + whole);
    }

    @Test
    void describeReturnsTheSchemasOfTheNamedActionsOnly() {
        var result = McpServerTool.describe("big", largeServer(), List.of("action_3", "action_7", "nope"));
        var payload = JsonParser.parseString(result.text()).getAsJsonObject();
        var actions = payload.getAsJsonArray("actions");
        assertEquals(2, actions.size());
        var third = actions.get(0).getAsJsonObject();
        assertEquals("action_3", third.get("name").getAsString());
        assertTrue(third.getAsJsonObject("inputSchema").getAsJsonObject("properties").has("field_5"));
        assertTrue(third.get("description").getAsString().contains("Args:"), "describe carries the whole description");
        assertEquals("nope", payload.getAsJsonArray("unknown").get(0).getAsString(),
                "a name the server does not have is reported, not dropped");
    }

    @Test
    void describeWithNoConnectionSaysSo() {
        var result = new McpServerTool("absent-" + System.nanoTime())
                .executeRich("{\"describe\":[\"create_issue\"]}", null);
        assertTrue(result.text().contains("not currently connected"), result.text());
    }

    @Test
    void onlyDiscoveryCallsAreVerbatim() {
        var tool = new McpServerTool("github");
        assertTrue(tool.verbatimResult("{}"), "the action list");
        assertTrue(tool.verbatimResult("{\"describe\":[\"create_issue\"]}"), "the schemas of chosen actions");
        assertFalse(tool.verbatimResult("{\"tool\":\"create_issue\",\"args\":{}}"), "an action's own output");
        assertFalse(tool.verbatimResult("not json"), "unreadable arguments make no claim");
    }

    // ==================== resources: the listing ====================

    private static McpResources.Listing oneOfEach(boolean more) {
        return new McpResources.Listing(
                List.of(new McpResources.Resource("serpapi://engines", "serpapi-engines-index", null, "application/json")),
                List.of(new McpResources.Template("serpapi://engines/{engine}", "engine", "Parameters of one engine.")),
                more);
    }

    private static final String SEARCH_ACTION = "{\"name\":\"search\",\"description\":\"Searches.\","
            + "\"inputSchema\":{\"type\":\"object\",\"properties\":{}}}";

    @Test
    void aServerWithoutResourcesListsExactlyWhatItListedBefore() {
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "]}",
                McpServerTool.catalog("serpapi", List.of(def("search", "Searches.", 0))).text());
    }

    @Test
    void aResourceServerListsItsResourcesAndTemplatesBesideTheActions() {
        var result = McpServerTool.catalog("serpapi", List.of(def("search", "Searches.", 0)), oneOfEach(false));
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "],"
                + "\"resources\":[{\"uri\":\"serpapi://engines\",\"name\":\"serpapi-engines-index\","
                + "\"mimeType\":\"application/json\"}],"
                + "\"resourceTemplates\":[{\"uriTemplate\":\"serpapi://engines/{engine}\",\"name\":\"engine\","
                + "\"description\":\"Parameters of one engine.\"}]}", result.text());
        assertEquals(result.text(), result.structuredJson());
    }

    @Test
    void aServerWithMoreResourcesOrTemplatesThanListedSaysHowToReadTheRest() {
        var templates = new ArrayList<McpResources.Template>();
        for (int i = 0; i < 50; i++) templates.add(new McpResources.Template("x://t" + i + "/{id}", null, null));
        var payload = JsonParser.parseString(McpServerTool.catalog("serpapi", List.of(def("search", "Searches.", 0)),
                new McpResources.Listing(List.of(), templates, true)).text()).getAsJsonObject();
        assertEquals("The server has more resources or resource templates than are listed here. A template filled in,"
                + " or a URI you already know, reads directly with {\"read\": \"<uri>\"}.",
                payload.get("resourcesNote").getAsString());
        assertEquals(50, payload.getAsJsonArray("resourceTemplates").size());
    }

    @Test
    void aLongResourceOrTemplateDescriptionIsCutAsAnActionSummaryIs() {
        var long2000 = "a".repeat(2_000);
        var listing = new McpResources.Listing(List.of(new McpResources.Resource("x://r", null, long2000, null)),
                List.of(new McpResources.Template("x://t/{id}", null, long2000)), false);
        var cut = "a".repeat(159) + "…";
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "],"
                + "\"resources\":[{\"uri\":\"x://r\",\"description\":\"" + cut + "\"}],"
                + "\"resourceTemplates\":[{\"uriTemplate\":\"x://t/{id}\",\"description\":\"" + cut + "\"}]}",
                McpServerTool.catalog("serpapi", List.of(def("search", "Searches.", 0)), listing).text());
        assertEquals(160, cut.length());
    }

    @Test
    void aFailedResourceFetchKeepsTheActionsAndAddsOneNote() {
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "],"
                        + "\"resourcesNote\":\"The server's resources and resource templates could not be listed just now.\"}",
                McpServerTool.catalogWithoutResources("serpapi", List.of(def("search", "Searches.", 0))).text());
    }

    // ==================== MCP Apps: what the model is kept from ====================

    private static McpToolDef viewTool(String name, List<String> visibility) {
        var schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.add("properties", new JsonObject());
        return new McpToolDef(name, "Searches.", schema, "ui://serpapi/table.html", visibility);
    }

    @Test
    void aViewToolTheModelMayCallIsListedAsAPlainActionIsAndAnAppOnlyOneIsNot() {
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "]}",
                McpServerTool.catalog("serpapi", List.of(viewTool("search", List.of("model", "app")),
                        viewTool("refresh", List.of("app")), viewTool("hidden", List.of()))).text());
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "]}",
                McpServerTool.catalog("serpapi", List.of(viewTool("search", List.of("model")))).text());
    }

    @Test
    void describeReportsAnAppOnlyActionAsUnknown() {
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "],\"unknown\":[\"refresh\"]}",
                McpServerTool.describe("serpapi", List.of(viewTool("search", List.of("model", "app")),
                        viewTool("refresh", List.of("app"))), List.of("refresh", "search")).text());
    }

    @Test
    void theModelsTemplateListingLeavesOutViewPageTemplates() {
        var listing = new McpResources.Listing(List.of(), List.of(
                new McpResources.Template("ui://serpapi/{engine}.html", "views", null),
                new McpResources.Template("serpapi://engines/{engine}", "engine", null)), false);
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "],\"resources\":[],"
                + "\"resourceTemplates\":[{\"uriTemplate\":\"serpapi://engines/{engine}\",\"name\":\"engine\"}]}",
                McpServerTool.catalog("serpapi", List.of(def("search", "Searches.", 0)), listing).text());
    }

    @Test
    void anUpperCaseUiSchemeIsAViewPageInTheListingAndTheTemplates() {
        var listing = new McpResources.Listing(List.of(new McpResources.Resource("UI://prefab/x.html", null, null, "text/html")),
                List.of(new McpResources.Template("Ui://prefab/{id}.html", null, null)), false);
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "],\"resources\":[],\"resourceTemplates\":[]}",
                McpServerTool.catalog("serpapi", List.of(def("search", "Searches.", 0)), listing).text());
    }

    @Test
    void aViewToolIsRefusedOffWebChatFromItsAdaptersOwnDefWhileTheServerStillConnects() {
        var called = new java.util.concurrent.atomic.AtomicInteger();
        var server = "connecting-" + System.nanoTime();
        var adapter = new mcp.McpToolAdapter(server, viewTool("search_table", List.of("model", "app")), (s, t, a) -> {
            called.incrementAndGet();
            return new mcp.CallToolResult("ran", false);
        });
        ToolRegistry.publish(List.of(adapter));
        var result = new McpServerTool(server).executeRich("{\"tool\":\"search_table\",\"args\":{}}", null);
        assertEquals("MCP server '" + server + "' has no action named 'search_table'. Available actions: (none — server not"
                + " connected or advertises no actions). Call mcp_" + server + " with {\"describe\": [\"<action>\"]} for an"
                + " action's input schema if needed.", result.text());
        assertEquals(0, called.get());
    }

    @Test
    void theModelsResourceListingLeavesOutViewPagesHoweverTheirTypeIsWritten() {
        var listing = new McpResources.Listing(List.of(
                new McpResources.Resource("serpapi://engines", "serpapi-engines-index", null, "application/json"),
                new McpResources.Resource("ui://prefab/table.html", "Prefab Renderer (search_table)", null,
                        "text/html;profile=mcp-app"),
                new McpResources.Resource("ui://prefab/other.html", null, null, "Text/HTML; profile=MCP-App"),
                new McpResources.Resource("x://page.html", null, null, "text/html")), List.of(), false);
        assertEquals("{\"server\":\"serpapi\",\"actions\":[" + SEARCH_ACTION + "],"
                + "\"resources\":[{\"uri\":\"serpapi://engines\",\"name\":\"serpapi-engines-index\","
                + "\"mimeType\":\"application/json\"},{\"uri\":\"x://page.html\",\"mimeType\":\"text/html\"}],"
                + "\"resourceTemplates\":[]}",
                McpServerTool.catalog("serpapi", List.of(def("search", "Searches.", 0)), listing).text());
    }

    @Test
    void resourcesNeverTurnASmallActionListIntoAnIndexNorALargeOneWhole() {
        var many = new ArrayList<McpResources.Resource>();
        for (int i = 0; i < 50; i++) {
            many.add(new McpResources.Resource("x://resource/" + i, "resource " + i, "A resource described at length.", null));
        }
        var listing = new McpResources.Listing(many, List.of(), false);
        var small = JsonParser.parseString(
                McpServerTool.catalog("tiny", List.of(def("ping", "Pings.", 1)), listing).text()).getAsJsonObject();
        assertEquals(Set.of("server", "actions", "resources", "resourceTemplates"), small.keySet());
        assertEquals(Set.of("name", "description", "inputSchema"),
                small.getAsJsonArray("actions").get(0).getAsJsonObject().keySet(), "a small server keeps its schemas");
        var large = JsonParser.parseString(McpServerTool.catalog("big", largeServer(), listing).text()).getAsJsonObject();
        assertEquals(Set.of("server", "inputSchemas", "actions", "resources", "resourceTemplates"), large.keySet(),
                "a large server is still an index");
        assertEquals(Set.of("name", "description"), large.getAsJsonArray("actions").get(0).getAsJsonObject().keySet());
        assertEquals(50, large.getAsJsonArray("resources").size());
    }

    // ==================== resources: a read's contents ====================

    private static McpResources.Content text(String uri, String mimeType, String text) {
        return new McpResources.Content(uri, mimeType, text, 0);
    }

    @Test
    void oneTextContentIsTheTextAlone() {
        assertEquals("{\"params\":32}", McpServerTool.renderRead(
                List.of(text("serpapi://engines/google_flights", "application/json", "{\"params\":32}"))));
    }

    @Test
    void aReadOfExactlyTheCapIsWholeWithNoTruncationLine() {
        var whole = "a".repeat(McpServerTool.READ_MAX_CHARS);
        assertEquals(64_000, McpServerTool.READ_MAX_CHARS);
        assertEquals(whole, McpServerTool.renderRead(List.of(text("x://log", "text/plain", whole))));
    }

    @Test
    void aReadOneCharacterOverTheCapIsCutAndSaysSo() {
        assertEquals("a".repeat(64_000) + "\n[resource truncated: showed the first 64,000 of 64,001 characters]",
                McpServerTool.renderRead(List.of(text("x://log", "text/plain", "a".repeat(64_001)))));
    }

    @Test
    void theCapCountsEveryRenderedCharacterHeadersIncluded() {
        // 14 + 32,000 + 1 + 14 + 31,971 = 64,000 rendered.
        var exact = "--- x://1 ---\n" + "a".repeat(32_000) + "\n--- x://2 ---\n" + "b".repeat(31_971);
        assertEquals(exact, McpServerTool.renderRead(List.of(text("x://1", null, "a".repeat(32_000)),
                text("x://2", null, "b".repeat(31_971)))), "64,000 across two contents is still whole");
        assertEquals(exact + "\n[resource truncated: showed the first 64,000 of 64,001 characters]",
                McpServerTool.renderRead(List.of(text("x://1", null, "a".repeat(32_000)),
                        text("x://2", null, "b".repeat(31_972)))));
        var over = McpServerTool.renderRead(List.of(text("x://1", null, "a".repeat(30_000)),
                text("x://2", "text/plain", "b".repeat(30_000)), text("x://3", null, "c".repeat(30_000)),
                new McpResources.Content("x://4", "image/png", null, 10)));
        assertEquals("--- x://1 ---\n" + "a".repeat(30_000) + "\n--- x://2 (text/plain) ---\n" + "b".repeat(30_000)
                + "\n--- x://3 ---\n" + "c".repeat(3_943)
                + "\n[resource truncated: showed the first 64,000 of 90,112 characters]", over,
                "the cut ends the read, so nothing after it is shown");
    }

    @Test
    void thousandsOfBlobContentsStayWithinTheCap() {
        var contents = new ArrayList<McpResources.Content>();
        var whole = new StringBuilder();
        for (int i = 0; i < 5_000; i++) {
            contents.add(new McpResources.Content("x://%04d".formatted(i), "image/png", null, 8));
            if (i > 0) whole.append('\n');
            whole.append("--- x://%04d (image/png) ---\n[binary image/png, 8 bytes]".formatted(i));
        }
        assertEquals(284_999, whole.length());
        assertEquals(whole.substring(0, 64_000) + "\n[resource truncated: showed the first 64,000 of 284,999 characters]",
                McpServerTool.renderRead(contents));
    }

    @Test
    void theCutNeverSplitsASurrogatePairAndSaysHowMuchItShowed() {
        var text = "a".repeat(63_999) + "😀" + "b";
        assertEquals("a".repeat(63_999) + "\n[resource truncated: showed the first 63,999 of 64,002 characters]",
                McpServerTool.renderRead(List.of(text("x://emoji", "text/plain", text))));
    }

    @Test
    void anEmptyReadRendersAsNothing() {
        assertEquals("", McpServerTool.renderRead(List.of()));
        assertEquals("", McpServerTool.renderRead(List.of(text("x://e", "text/plain", ""))));
    }

    @Test
    void aBlobIsNamedByItsTypeAndSizeAndEachContentOfManyGetsAHeader() {
        assertEquals("--- x://logo.png (image/png) ---\n[binary image/png, 8 bytes]",
                McpServerTool.renderRead(List.of(new McpResources.Content("x://logo.png", "image/png", null, 8))));
        assertEquals("--- dir://a.txt (text/plain) ---\nalpha\n--- dir://b ---\nbeta\n--- dir://c ---\n[binary, 3 bytes]",
                McpServerTool.renderRead(List.of(text("dir://a.txt", "text/plain", "alpha"),
                        text("dir://b", null, "beta\n"), new McpResources.Content("dir://c", null, null, 3))));
    }

    // ==================== resources: the read form ====================

    @Test
    void aReadWithABlankOrNonStringUriIsRefusedNamingRead() {
        var tool = new McpServerTool("github");
        for (var args : List.of("{\"read\":\"\"}", "{\"read\":\"   \"}", "{\"read\":5}", "{\"read\":[\"x://a\"]}",
                "{\"read\":{}}", "{\"tool\":\"\",\"read\":\"\"}")) {
            assertEquals("The `read` argument for MCP server 'github' must be a resource URI, as a non-blank string."
                    + " Call mcp_github with {} to list its resources and templates.", tool.executeRich(args, null).text(), args);
        }
    }

    @Test
    void aBlankReadBesideANamedActionIsIgnoredAndTheActionRuns() {
        var adapter = recordingAdapter("mcp_github_create_issue");
        ToolRegistry.publish(List.of(adapter));
        var tool = new McpServerTool("github");
        var args = "{\"tool\":\"create_issue\",\"read\":\"\",\"args\":{\"title\":\"Bug\"}}";
        assertEquals("FAKE-RESULT[create_issue]", tool.executeRich(args, null).text());
        assertEquals("{\"title\":\"Bug\"}", adapter.lastArgs);
        assertFalse(tool.verbatimResult(args), "an action's own output, as without the blank read");
    }

    @Test
    void aNullReadIsNoReadAtAll() {
        var name = "ghost-server-" + System.nanoTime();
        var tool = new McpServerTool(name);
        assertEquals("MCP server '" + name + "' is not currently connected or advertises no actions.",
                tool.executeRich("{\"read\":null}", null).text(), "a listing, as {} would be");
        assertFalse(tool.verbatimResult("{\"tool\":\"create_issue\",\"read\":null}"));
    }

    @Test
    void aReadOnADisconnectedServerSaysItIsNotConnected() {
        var name = "ghost-server-" + System.nanoTime();
        assertEquals("MCP server '" + name + "' is not currently connected or advertises no actions.",
                new McpServerTool(name).executeRich("{\"read\":\"x://a\"}", null).text());
    }

    @Test
    void aServerThatOffersNoResourcesAdvertisesNoReadForm() {
        var name = "ghost-server-" + System.nanoTime();
        var tool = new McpServerTool(name);
        assertEquals("MCP server `" + name + "`. Call with no arguments (or `{}`) to list the server's actions. A large"
                + " server lists names and summaries only: call with `{\"describe\": [\"<action>\", ...]}` for the"
                + " input schemas of the actions you will use. Call with `{\"tool\": \"<action>\", \"args\": {...}}`"
                + " to execute one action against the server.", tool.description());
        @SuppressWarnings("unchecked")
        var props = (Map<String, Object>) tool.parameters().get("properties");
        assertEquals(Set.of("tool", "args", "describe"), props.keySet());
    }

    @Test
    void aReadIsVerbatimEvenBesideAnAction() {
        var tool = new McpServerTool("serpapi");
        assertTrue(tool.verbatimResult("{\"read\":\"serpapi://engines/google_flights\"}"));
        assertTrue(tool.verbatimResult("{\"tool\":\"search\",\"read\":\"serpapi://engines\"}"), "the read branch runs first");
    }

    @Test
    void aReadIsLeftWholeByCompressionOnALaterRoundWhileAnActionsOutputIsNot() {
        ToolRegistry.publish(List.of(new McpServerTool("serpapi")));
        var json = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) json.append(',');
            json.append("{\"name\":\"param_").append(i).append("\",\"description\":\"An engine parameter, described.\"}");
        }
        var big = json.append(']').toString();
        var read = ChatMessage.toolResult("c1", "mcp_serpapi", big);
        var action = ChatMessage.toolResult("c2", "mcp_serpapi", big);
        var messages = List.of(
                toolCall("c1", "mcp_serpapi", "{\"read\":\"serpapi://engines/google_flights\"}"), read,
                toolCall("c2", "mcp_serpapi", "{\"tool\":\"search\",\"args\":{}}"), action,
                ChatMessage.assistant("done"));
        var out = CompressionPipeline.compressMessages(messages, "gpt-4o", 250, 6_000);
        assertSame(read, out.get(1), "a read stays whole on every round");
        assertNotSame(action, out.get(3), "the same server's action output is compressed, so the pipeline ran");
    }

    private static ChatMessage toolCall(String id, String tool, String args) {
        return ChatMessage.assistant("", List.of(new ToolCall(id, "function", new FunctionCall(tool, args))));
    }

    // ==================== unknown action with no live connection ====================

    @Test
    void unknownActionWithDisconnectedServerNamesTheActionAndServer() {
        // Unknown-action error path: when no adapter is registered AND no live
        // connection advertises the action, the response must (a) name the
        // unknown action, (b) name the server, and (c) point the model at
        // empty-args discovery for the full schemas. The compact name list
        // collapses to a "(none — server not connected ...)" sentinel here,
        // since the fake serverName has no advertised actions.
        //
        // The error body must NOT include action input schemas — that
        // bloated the next LLM turn's prompt by tens of thousands of tokens
        // on schema-heavy servers like google-workspace, dragging
        // time-to-first-token into the minute range.
        var serverName = "absent-" + System.nanoTime();
        var tool = new McpServerTool(serverName);
        var result = tool.executeRich(
                "{\"tool\":\"create_issue\",\"args\":{}}", null);
        assertTrue(result.text().contains("no action named 'create_issue'"),
                "missing-adapter message lets the model recognize stale tool names: "
                        + result.text());
        assertTrue(result.text().contains(serverName),
                "server name surfaces so multi-server agents can attribute the failure");
        assertTrue(result.text().contains("Available actions:"),
                "compact name list header replaces the old schema-dump catalog: "
                        + result.text());
        assertFalse(result.text().contains("inputSchema"),
                "input schemas must NOT appear in the unknown-action error: "
                        + result.text());
    }

    // ==================== envelope-misuse hints ====================

    @Test
    void nestedEnvelopeNamesTheRealActionAndTheCorrectedShape() {
        // The exact payload a weaker model emitted against google-workspace-mcp:
        // server name in the `tool` slot, real call re-nested under `args`. The
        // bare 119-name list left it looping the same shape until the round
        // budget ran out, so the hint has to name the mistake, not just deny it.
        var serverName = "google-workspace-mcp";
        var adapter = recordingAdapter("mcp_" + serverName + "_search_gmail_messages");
        ToolRegistry.publish(List.of(adapter));

        var result = new McpServerTool(serverName).executeRich(
                "{\"tool\":\"google-workspace-mcp\",\"args\":{\"tool\":\"search_gmail_messages\","
                        + "\"tool_args\":{\"q\":\"is:unread\"}}}", null);

        assertTrue(result.text().contains("nested one level too deep"),
                "hint must name the nesting, not just deny the action: " + result.text());
        assertTrue(result.text().contains("search_gmail_messages"),
                "hint must surface the action the model already supplied: " + result.text());
        assertNull(adapter.lastArgs,
                "the hint corrects the model — it must not silently dispatch a "
                        + "malformed envelope, which would mask the defect");
    }

    @Test
    void nestedEnvelopeWithNoOuterToolGetsTheHintInsteadOfTheCatalog() {
        // A whole envelope wrapped once more has no outer `tool`, so it read as a discovery
        // call: one model got the full catalog back three times before it sent the flat shape.
        var serverName = "academy";
        var adapter = recordingAdapter("mcp_" + serverName + "_list_popular_courses");
        ToolRegistry.publish(List.of(adapter));

        var result = new McpServerTool(serverName).executeRich(
                "{\"args\":{\"args\":{},\"tool\":\"list_popular_courses\"}}", null);

        assertTrue(result.text().contains("nested one level too deep"), result.text());
        assertTrue(result.text().contains("{\"tool\": \"list_popular_courses\""),
                "the hint quotes the corrected call: " + result.text());
        assertNull(adapter.lastArgs, "still a hint, never a silent dispatch");
    }

    @Test
    void aDiscoveryCallWithAnUnregisteredInnerToolStillEnumerates() {
        var result = new McpServerTool("absent-" + System.nanoTime()).executeRich(
                "{\"args\":{\"tool\":\"not_a_real_action\"}}", null);
        assertFalse(result.text().contains("nested one level too deep"), result.text());
        assertTrue(result.text().contains("not currently connected"),
                "anything else with no outer `tool` is still a discovery call: " + result.text());
    }

    @Test
    void serverNameInTheActionSlotIsCalledOutAsSuch() {
        var serverName = "github";
        var result = new McpServerTool(serverName).executeRich(
                "{\"tool\":\"github\",\"args\":{}}", null);
        assertTrue(result.text().contains("server's own name"),
                "the server name is the least visible wrong answer in a long "
                        + "action list, so it gets named explicitly: " + result.text());
    }

    @Test
    void prefixedHandleNameInTheActionSlotIsCalledOutAsSuch() {
        // Models also echo the function-calling name itself, `mcp_<server>`.
        var result = new McpServerTool("github").executeRich(
                "{\"tool\":\"mcp_github\",\"args\":{}}", null);
        assertTrue(result.text().contains("server's own name"), result.text());
    }

    @Test
    void plainUnknownActionGetsNoEnvelopeHint() {
        // A typo or a stale tool name is not an envelope mistake; adding a hint
        // there would teach the model to restructure a call that was well-formed.
        var result = new McpServerTool("absent-" + System.nanoTime()).executeRich(
                "{\"tool\":\"create_issue\",\"args\":{}}", null);
        assertFalse(result.text().contains("nested one level too deep"), result.text());
        assertFalse(result.text().contains("server's own name"), result.text());
    }

    @Test
    void unregisteredInnerToolValueIsNotMistakenForNesting() {
        // `args.tool` only means nesting when it resolves to a real action —
        // otherwise an action that legitimately takes a `tool` argument would
        // be told to restructure a correct call.
        var result = new McpServerTool("github").executeRich(
                "{\"tool\":\"github\",\"args\":{\"tool\":\"not_a_real_action\"}}", null);
        assertFalse(result.text().contains("nested one level too deep"), result.text());
        assertTrue(result.text().contains("server's own name"), result.text());
    }

    // ==================== helpers ====================

    /** Test double that records the last argsJson it received and returns a
     *  fixed marker. Used to verify {@link McpServerTool}'s delegation path
     *  without spinning up a real MCP server. */
    private static final class RecordingAdapter implements ToolRegistry.Tool {
        /** Full adapter name, e.g. {@code mcp_github_create_issue}. */
        private final String name;
        /** Action portion after the {@code mcp_<server>_} prefix; used as a
         *  marker in the result string so multi-adapter tests can distinguish
         *  which adapter answered. */
        private final String marker;
        volatile String lastArgs;

        RecordingAdapter(String name, String marker) {
            this.name = name;
            this.marker = marker;
        }

        @Override public String name() { return name; }
        @Override public String description() { return name + " (test adapter)"; }
        @Override public Map<String, Object> parameters() { return Map.of(); }

        @Override
        public String execute(String argsJson, Agent agent) {
            return executeRich(argsJson, agent).text();
        }

        @Override
        public ToolRegistry.ToolResult executeRich(String argsJson, Agent agent) {
            this.lastArgs = argsJson;
            return ToolRegistry.ToolResult.text("FAKE-RESULT[" + marker + "]");
        }
    }

    /** Build a recording adapter for {@code mcp_<server>_<action>} so the
     *  marker reflects the action name regardless of how many underscores it
     *  contains (e.g. {@code create_issue}). */
    private static RecordingAdapter recordingAdapter(String adapterName) {
        // adapterName shape is mcp_<server>_<action>. Strip the first two
        // underscore-separated segments to recover the action name (which
        // may itself contain underscores).
        var firstUnderscore = adapterName.indexOf('_');
        var secondUnderscore = adapterName.indexOf('_', firstUnderscore + 1);
        var action = secondUnderscore >= 0
                ? adapterName.substring(secondUnderscore + 1)
                : adapterName;
        return new RecordingAdapter(adapterName, action);
    }
}
