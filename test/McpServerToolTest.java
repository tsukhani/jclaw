import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonParser;
import mcp.McpServerTool;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;

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

    /** Snapshot of the live registry so each test runs against a clean slate. */
    private List<ToolRegistry.Tool> savedTools;

    @BeforeEach
    void saveRegistry() {
        savedTools = ToolRegistry.listTools();
        // Wipe the registry so adapter lookups during these tests can't be
        // contaminated by tools other suites have registered.
        ToolRegistry.publish(List.of());
    }

    @AfterEach
    void restoreRegistry() {
        ToolRegistry.publish(savedTools);
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

    // ==================== unknown action with no live connection ====================

    @Test
    void unknownActionWithDisconnectedServerSignalsBothFailures() {
        // No adapter registered for the action AND no live connection ⇒ both
        // diagnostic phrases should appear: the "no action named" error AND
        // the catalog's not-connected line (since the catalog body is
        // appended after the error). Together they tell the model exactly
        // what to do next: re-enumerate via empty args.
        var serverName = "absent-" + System.nanoTime();
        var tool = new McpServerTool(serverName);
        var result = tool.executeRich(
                "{\"tool\":\"create_issue\",\"args\":{}}", null);
        assertTrue(result.text().contains("no action named 'create_issue'"),
                "missing-adapter message lets the model recognize stale tool names: "
                        + result.text());
        assertTrue(result.text().contains(serverName),
                "server name surfaces in both halves of the diagnostic");
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
