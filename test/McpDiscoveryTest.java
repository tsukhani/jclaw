import agents.ToolRegistry;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JCLAW-281: verifies the function-calling filter contract for MCP servers.
 *
 * <ul>
 *   <li>{@link ToolRegistry#getToolDefsForAgent(Agent, Set)} hides every
 *       per-action MCP wrapper (group set, not server-level) from the
 *       function-calling defs unconditionally — the server-level handle
 *       is the sole face the LLM sees. The {@code discoveredMcpServers}
 *       argument no longer drives filtering; it's retained for binary
 *       compatibility with tests and callers.</li>
 *   <li>Server-level MCP handles ({@code isServerLevel()=true}) ship
 *       every turn so the model can self-bootstrap by calling with
 *       empty args — discovery is built into the server-level surface
 *       itself, no separate {@code list_mcp_tools} tool needed.</li>
 * </ul>
 *
 * <p>Pre-JCLAW-281 this file also covered {@code McpDiscovery}'s parser
 * for prior {@code list_mcp_tools} calls in conversation history; that
 * class is gone now, so the parser tests went with it.
 */
class McpDiscoveryTest extends UnitTest {

    private List<ToolRegistry.Tool> originalTools;

    @BeforeEach
    void saveRegistry() {
        originalTools = ToolRegistry.listTools();
    }

    @AfterEach
    void restoreRegistry() {
        ToolRegistry.publish(originalTools);
    }

    @Test
    void perActionMcpWrappersAreHiddenFromFunctionCallingDefs() {
        var nativeTool = stubTool("filesystem", null);
        var perActionWrapper = stubMcpTool("mcp_jira_get_issue", "jira");
        var serverHandle = stubMcpServerHandle("mcp_jira", "jira");
        ToolRegistry.publish(List.of(nativeTool, perActionWrapper, serverHandle));

        var defs = ToolRegistry.getToolDefsForAgent((Agent) null, Set.<String>of());
        var names = defs.stream().map(d -> d.function().name()).toList();
        assertTrue(names.contains("filesystem"), "native tool present");
        assertTrue(names.contains("mcp_jira"), "server-level handle present");
        assertFalse(names.contains("mcp_jira_get_issue"),
                "per-action wrapper hidden from function-calling defs: " + names);
    }

    @Test
    void serverLevelHandleAlwaysSurfacesRegardlessOfDiscoveredSet() {
        var serverHandle = stubMcpServerHandle("mcp_jira", "jira");
        ToolRegistry.publish(List.of(serverHandle));

        // Empty discovered set: server-level handle still emitted (the parameterized
        // entry is what the model uses to discover; no lazy gate any more).
        var emptyNames = ToolRegistry.getToolDefsForAgent((Agent) null, Set.<String>of())
                .stream().map(d -> d.function().name()).toList();
        assertTrue(emptyNames.contains("mcp_jira"),
                "server-level handle ships even with empty discovered set: " + emptyNames);

        // Pre-populated discovered set: same result. discoveredMcpServers no
        // longer drives filtering in JCLAW-281.
        var preDiscoveredNames = ToolRegistry.getToolDefsForAgent((Agent) null, Set.of("jira"))
                .stream().map(d -> d.function().name()).toList();
        assertTrue(preDiscoveredNames.contains("mcp_jira"));
    }

    @Test
    void multipleServerHandlesAllSurfacePerActionWrappersStayHidden() {
        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira", "jira"),
                stubMcpTool("mcp_jira_get_issue", "jira"),
                stubMcpServerHandle("mcp_github", "github"),
                stubMcpTool("mcp_github_create_issue", "github")
        ));

        var names = ToolRegistry.getToolDefsForAgent((Agent) null, Set.<String>of())
                .stream().map(d -> d.function().name()).toList();
        assertTrue(names.contains("mcp_jira"), "jira server-level handle present");
        assertTrue(names.contains("mcp_github"), "github server-level handle present");
        assertFalse(names.contains("mcp_jira_get_issue"), "jira per-action wrapper hidden");
        assertFalse(names.contains("mcp_github_create_issue"), "github per-action wrapper hidden");
    }

    // ==================== helpers ====================

    private static ToolRegistry.Tool stubTool(String name, String group) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " stub"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String group() { return group; }
        };
    }

    private static ToolRegistry.Tool stubMcpTool(String name, String server) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " stub"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String category() { return "MCP"; }
            @Override public String group() { return server; }
        };
    }

    /** JCLAW-281: stub of the server-level handle ({@code McpServerTool} shape). */
    private static ToolRegistry.Tool stubMcpServerHandle(String name, String server) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " server handle stub"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String category() { return "MCP"; }
            @Override public String group() { return server; }
            @Override public boolean isServerLevel() { return true; }
        };
    }
}
