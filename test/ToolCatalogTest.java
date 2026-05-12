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
                stubTool("notes",   "Utilities", "Keep notes"),
                stubTool("grep",    "Files",     "Search files"),
                stubTool("curl",    "Web",       "Fetch a URL"),
                stubTool("sudo",    "System",    "Elevated shell commands")
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
    public void catalogExcludesGroupedToolsBecauseTheyOwnTheirOwnSection() {
        // JCLAW-281: MCP servers (and any other grouped tool source) render
        // in their own ## MCP Servers section via McpServerCatalog, so the
        // ## Tool Catalog is native-only.
        ToolRegistry.publish(List.of(
                stubTool("notes",   "Utilities", "Keep notes"),
                stubMcpTool("mcp_jira_get_issue", "jira", "Look up an issue")
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of());

        assertTrue(catalog.contains("notes"), "native tool appears");
        assertFalse(catalog.contains("mcp_jira_get_issue"), "grouped tool excluded from Tool Catalog");
    }

    @Test
    public void catalogSkipsDisabledTools() {
        ToolRegistry.publish(List.of(
                stubTool("allowed",  "Utilities", "Allowed tool"),
                stubTool("disabled", "Utilities", "Disabled tool")
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of("disabled"));

        assertTrue(catalog.contains("allowed"));
        assertFalse(catalog.contains("disabled"));
    }

    @Test
    public void catalogIsEmptyStringWhenOnlyGroupedToolsAreRegistered() {
        ToolRegistry.publish(List.of(
                stubMcpTool("mcp_jira_get_issue", "jira", "Look up an issue")
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
    public void groupedToolsAreFullyExcludedFromNativeCatalog() {
        // JCLAW-281: MCP-style grouped tools no longer collapse into a
        // wildcard row in this catalog — they render in their own ## MCP
        // Servers section via McpServerCatalog. Native tools render here
        // unchanged.
        ToolRegistry.publish(List.of(
                stubTool("filesystem", "Files", "Read and write files"),
                stubMcpTool("mcp_jira_get_issue",    "jira", "Look up an issue"),
                stubMcpTool("mcp_jira_create_issue", "jira", "Create an issue")
        ));

        var catalog = ToolCatalog.formatCatalogForPrompt(Set.of());

        assertTrue(catalog.contains("`filesystem`"), "native tool row preserved");
        assertFalse(catalog.contains("mcp_jira"),
                "grouped tools completely absent (their section is built by McpServerCatalog)");
    }

    private static ToolRegistry.Tool stubTool(String name, String category, String summary) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return summary; }
            @Override public String summary() { return summary; }
            @Override public String category() { return category; }
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
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, models.Agent agent) { return ""; }
        };
    }
}
