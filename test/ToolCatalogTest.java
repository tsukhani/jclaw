import org.junit.jupiter.api.*;
import play.test.*;
import agents.ToolCatalog;
import agents.ToolRegistry;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies JCLAW-71 behaviour: the system-prompt Tool Catalog groups tools by
 * category in canonical order (System → Files → Web → Utilities), excludes
 * system tools, and respects the per-agent disabled set.
 */
public class ToolCatalogTest extends UnitTest {

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
    public void catalogGroupsToolsByCanonicalCategoryOrder() {
        ToolRegistry.publish(List.of(
                stubTool("notes",   "Utilities", "Keep notes",              false),
                stubTool("grep",    "Files",     "Search files",            false),
                stubTool("curl",    "Web",       "Fetch a URL",             false),
                stubTool("sudo",    "System",    "Elevated shell commands", false)
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of());

        assertTrue(catalog.contains("### System"), "System heading present");
        assertTrue(catalog.contains("### Files"), "Files heading present");
        assertTrue(catalog.contains("### Web"), "Web heading present");
        assertTrue(catalog.contains("### Utilities"), "Utilities heading present");

        // Canonical ordering: System before Files before Web before Utilities.
        var systemIdx = catalog.indexOf("### System");
        var filesIdx = catalog.indexOf("### Files");
        var webIdx = catalog.indexOf("### Web");
        var utilsIdx = catalog.indexOf("### Utilities");
        assertTrue(systemIdx < filesIdx, "System precedes Files");
        assertTrue(filesIdx < webIdx, "Files precedes Web");
        assertTrue(webIdx < utilsIdx, "Web precedes Utilities");

        // Each tool lands under its own category with a purpose column.
        assertTrue(catalog.contains("| `sudo` | Elevated shell commands |"), "sudo listed in its row");
        assertTrue(catalog.contains("| `grep` | Search files |"), "grep listed in its row");
    }

    @Test
    public void catalogExcludesSystemTools() {
        ToolRegistry.publish(List.of(
                stubTool("notes",        "Utilities", "Keep notes",      false),
                stubTool("introspector", "Utilities", "Internal lookup", true)
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of());

        assertTrue(catalog.contains("notes"), "non-system tool appears");
        assertFalse(catalog.contains("introspector"), "isSystem()=true tool is filtered out");
    }

    @Test
    public void catalogSkipsDisabledTools() {
        ToolRegistry.publish(List.of(
                stubTool("allowed",  "Utilities", "Allowed tool",  false),
                stubTool("disabled", "Utilities", "Disabled tool", false)
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of("disabled"));

        assertTrue(catalog.contains("allowed"));
        assertFalse(catalog.contains("disabled"));
    }

    @Test
    public void catalogIsEmptyStringWhenNothingToShow() {
        ToolRegistry.publish(List.of(
                stubTool("only-system", "Utilities", "Internal tool", true)
        ));
        assertEquals("", ToolCatalog.formatCatalogForPrompt(Set.of()));
    }

    @Test
    public void canonicalCategoryOrderMatchesFrontendTaxonomy() {
        // Keep this list in sync with frontend/composables/useToolMeta.ts:TOOL_CATEGORIES.
        // JCLAW-72 collapses the dual source of truth; until then this guards drift.
        // "MCP" added by JCLAW-33 — every McpToolAdapter reports that category.
        assertEquals(List.of("System", "Files", "Web", "Utilities", "MCP"),
                ToolCatalog.CANONICAL_CATEGORY_ORDER);
    }

    @Test
    public void defaultCategoryOnToolInterfaceIsUtilities() {
        var anonymous = new ToolRegistry.Tool() {
            @Override public String name() { return "anon"; }
            @Override public String description() { return "no override"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, models.Agent agent) { return ""; }
        };
        assertEquals("Utilities", anonymous.category());
    }

    @Test
    public void mcpToolsCollapseToOneRowPerServer() {
        // 3 tools from one MCP server. Each one would normally render as its
        // own row; the catalog should fold them into a single server row.
        ToolRegistry.publish(List.of(
                stubMcpTool("mcp_jira_get_issue",    "jira", "Look up an issue"),
                stubMcpTool("mcp_jira_create_issue", "jira", "Create an issue"),
                stubMcpTool("mcp_jira_search",       "jira", "Search via JQL")
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of());

        // One row per server (not per tool): the wildcard placeholder shows
        // up exactly once and the individual tool names do NOT appear in the
        // catalog body.
        assertTrue(catalog.contains("`mcp_jira_*`"), "server-level wildcard row present");
        assertTrue(catalog.contains("3 tools advertised"), "tool count surfaced");
        assertTrue(catalog.contains("list_mcp_tools"), "discovery hint present");
        assertFalse(catalog.contains("get_issue"), "individual tool name suppressed: " + catalog);
        assertFalse(catalog.contains("create_issue"), "individual tool name suppressed: " + catalog);
        assertFalse(catalog.contains("Search via JQL"), "individual tool description suppressed");
    }

    @Test
    public void mcpAndNativeToolsCoexistCleanly() {
        // A native tool keeps its own row; an MCP server with two tools folds.
        ToolRegistry.publish(List.of(
                stubTool("filesystem", "Files", "Read and write files", false),
                stubMcpTool("mcp_jira_get_issue",    "jira", "Look up an issue"),
                stubMcpTool("mcp_jira_create_issue", "jira", "Create an issue")
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of());

        assertTrue(catalog.contains("`filesystem`"), "native tool row preserved");
        assertTrue(catalog.contains("`mcp_jira_*`"), "MCP server row present");
        assertTrue(catalog.contains("2 tools advertised"));
    }

    private static ToolRegistry.Tool stubTool(String name, String category, String summary, boolean system) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return summary; }
            @Override public String summary() { return summary; }
            @Override public String category() { return category; }
            @Override public boolean isSystem() { return system; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, models.Agent agent) { return ""; }
        };
    }

    private static ToolRegistry.Tool stubMcpTool(String name, String server, String summary) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return summary; }
            @Override public String summary() { return summary; }
            @Override public String category() { return "MCP"; }
            @Override public String group() { return server; }
            @Override public boolean isSystem() { return false; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, models.Agent agent) { return ""; }
        };
    }
}
