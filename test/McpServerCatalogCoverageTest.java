import agents.McpServerCatalog;
import agents.ToolRegistry;
import models.Agent;
import models.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JCLAW-315: cover the {@link McpServerCatalog} system-prompt manifest
 * builder. The class is a pure render over {@link ToolRegistry}'s
 * server-level handles plus optional {@link McpServer} metadata; we
 * exercise it by publishing synthetic handles into the registry and
 * asserting the rendered markdown shape.
 *
 * <p>Pattern borrowed from {@code McpDiscoveryTest}: snapshot the live
 * registry in {@code @BeforeEach}, restore in {@code @AfterEach} so the
 * test doesn't leak handles into sibling tests.
 */
class McpServerCatalogCoverageTest extends UnitTest {

    private List<ToolRegistry.Tool> originalTools;

    @BeforeEach
    void saveRegistry() {
        Fixtures.deleteDatabase();
        originalTools = ToolRegistry.listTools();
    }

    @AfterEach
    void restoreRegistry() {
        ToolRegistry.publish(originalTools);
    }

    @Test
    void emptyStringWhenNoServerLevelHandles() {
        // Publish only a native (group=null) tool and a per-action MCP
        // wrapper (group set, isServerLevel=false). Neither should appear
        // in the catalog — the empty return lets the system-prompt
        // assembler omit the whole "## MCP Servers" section.
        ToolRegistry.publish(List.of(
                stubTool("filesystem", null, false),
                stubTool("mcp_jira_get_issue", "jira", false)
        ));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());
        assertEquals("", out, "no server-level handles → empty string, not header-only");
    }

    @Test
    void singleServerEmitsMarkdownTable() {
        ToolRegistry.publish(List.of(stubMcpServerHandle("mcp_jira", "jira")));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());

        assertTrue(out.contains("| Server | Actions | Description |"),
                "must render table header: " + out);
        assertTrue(out.contains("|---|---|---|"),
                "must render table separator: " + out);
        assertTrue(out.contains("`mcp_jira`"),
                "must include the server-level handle name: " + out);
        // McpConnectionManager.tools() returns an empty list (not null)
        // when the server isn't connected, so the row renders "0 actions"
        // for unconnected servers. The "lazy, call to enumerate" branch
        // is only reachable via a null return that the current connection
        // manager doesn't produce — see the JCLAW-315 report for the
        // dead-code flag.
        assertTrue(out.contains("0 actions"),
                "unconnected server must render 0 actions: " + out);
    }

    @Test
    void multipleServersAllAppearInOrder() {
        // Publish two server-level handles; both should appear, in the
        // order they were registered (LinkedHashMap preserves insertion).
        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira", "jira"),
                stubMcpServerHandle("mcp_github", "github")
        ));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());
        int jiraIdx = out.indexOf("`mcp_jira`");
        int githubIdx = out.indexOf("`mcp_github`");
        assertTrue(jiraIdx >= 0 && githubIdx >= 0,
                "both servers must appear: " + out);
        assertTrue(jiraIdx < githubIdx,
                "registration order must be preserved (jira before github): " + out);
    }

    @Test
    void disabledServerExcludedFromCatalog() {
        // Operator-disabled per-(agent, server) row → the server-level
        // handle's NAME is in the disabledForAgent set, so the catalog
        // must skip it. The other server still renders.
        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira", "jira"),
                stubMcpServerHandle("mcp_github", "github")
        ));
        var disabled = Set.of("mcp_jira");
        var out = McpServerCatalog.formatCatalogForPrompt(disabled);

        assertFalse(out.contains("`mcp_jira`"),
                "disabled server must not appear: " + out);
        assertTrue(out.contains("`mcp_github`"),
                "non-disabled server must still appear: " + out);
    }

    @Test
    void disablingAllServersReturnsEmpty() {
        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira", "jira"),
                stubMcpServerHandle("mcp_github", "github")
        ));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.of("mcp_jira", "mcp_github"));
        assertEquals("", out, "every handle disabled → empty catalog");
    }

    @Test
    void handlesWithoutGroupAreSkipped() {
        // Defensive coverage: an isServerLevel=true tool that forgot to set
        // group() must NOT be folded into the catalog (no key to group on).
        ToolRegistry.publish(List.of(
                stubMcpServerHandleNoGroup("malformed_handle"),
                stubMcpServerHandle("mcp_jira", "jira")
        ));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());
        assertFalse(out.contains("malformed_handle"),
                "group()=null server-level handle must be skipped: " + out);
        assertTrue(out.contains("`mcp_jira`"));
    }

    @Test
    void serverHandleUsesSummaryWhenNoMcpServerRow() {
        // No McpServer.findByName row, no per-server connected client
        // (so transport is null). The describeServer branch falls through
        // to handle.summary() — confirm the summary text reaches the row.
        ToolRegistry.publish(List.of(stubMcpServerHandleWithSummary(
                "mcp_jira", "jira", "Jira issue tracker MCP server")));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());
        assertTrue(out.contains("Jira issue tracker MCP server"),
                "summary must surface as the description: " + out);
    }

    @Test
    void serverHandleFallsBackToInvocationHintWhenSummaryIsNull() {
        // Tool with no summary override → describeServer falls to
        // invocationHint, which embeds the handle name and the calling
        // convention.
        ToolRegistry.publish(List.of(stubMcpServerHandleNullSummary("mcp_jira", "jira")));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());
        assertTrue(out.contains("Call `mcp_jira`"),
                "fallback description must include the invocation hint: " + out);
        assertTrue(out.contains("enumerate available actions"),
                "invocation hint must explain the empty-args bootstrap: " + out);
    }

    @Test
    void mcpServerRowSurfacesTransportInDescription() {
        // Persist an McpServer row with transport=STDIO. When
        // formatCatalogForPrompt runs, the describeServer branch must
        // detect the row and emit "MCP server via stdio" in the row.
        var server = new McpServer();
        server.name = "jira";
        server.enabled = true;
        server.transport = McpServer.Transport.STDIO;
        server.configJson = "{\"command\":\"jira-mcp\"}";
        server.save();

        ToolRegistry.publish(List.of(stubMcpServerHandle("mcp_jira", "jira")));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());
        assertTrue(out.contains("MCP server via stdio"),
                "transport hint must render in lowercase: " + out);
    }

    @Test
    void batchLoadedRowsMatchedPerServer() {
        // JCLAW-408: the manifest now batch-loads all McpServer rows once and
        // looks each server up by name, instead of one findByName per row.
        // Verify the per-server matching is still correct: the server WITH a
        // persisted row renders its transport hint; the server WITHOUT one
        // falls back to its handle summary. A stale all-rows map must not
        // cross-contaminate descriptions.
        var jira = new McpServer();
        jira.name = "jira";
        jira.enabled = true;
        jira.transport = McpServer.Transport.STDIO;
        jira.configJson = "{\"command\":\"jira-mcp\"}";
        jira.save();

        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira", "jira"),
                stubMcpServerHandleWithSummary("mcp_github", "github", "GitHub MCP server")
        ));
        var out = McpServerCatalog.formatCatalogForPrompt(Set.<String>of());

        assertTrue(out.contains("MCP server via stdio"),
                "row-backed jira server must render transport hint: " + out);
        assertTrue(out.contains("GitHub MCP server"),
                "row-less github server must fall back to its summary: " + out);
    }

    @Test
    void serversForAgentReturnsEmptyWhenNoHandles() {
        ToolRegistry.publish(List.of(stubTool("filesystem", null, false)));
        assertEquals(List.of(), McpServerCatalog.serversForAgent(Set.<String>of()),
                "no server-level handles → empty list");
    }

    @Test
    void serversForAgentListsConnectedServersInOrder() {
        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira", "jira"),
                stubMcpServerHandle("mcp_github", "github")
        ));
        var servers = McpServerCatalog.serversForAgent(Set.<String>of());
        assertEquals(List.of("jira", "github"), servers,
                "serversForAgent must return groups in registration order");
    }

    @Test
    void serversForAgentExcludesDisabled() {
        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira", "jira"),
                stubMcpServerHandle("mcp_github", "github")
        ));
        var servers = McpServerCatalog.serversForAgent(Set.of("mcp_jira"));
        assertEquals(List.of("github"), servers,
                "disabled handle's group must be excluded");
    }

    @Test
    void serversForAgentDedupesGroups() {
        // Two server-level handles for the same group (shouldn't happen in
        // production, but the API contract is dedup on group). The list
        // must contain the group once.
        ToolRegistry.publish(List.of(
                stubMcpServerHandle("mcp_jira_a", "jira"),
                stubMcpServerHandle("mcp_jira_b", "jira")
        ));
        var servers = McpServerCatalog.serversForAgent(Set.<String>of());
        assertEquals(List.of("jira"), servers,
                "two handles for one group must dedupe: " + servers);
    }

    // ==================== helpers ====================

    private static ToolRegistry.Tool stubTool(String name, String group, boolean serverLevel) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " stub"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String group() { return group; }
            @Override public boolean isServerLevel() { return serverLevel; }
        };
    }

    private static ToolRegistry.Tool stubMcpServerHandle(String name, String group) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " handle"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String group() { return group; }
            @Override public boolean isServerLevel() { return true; }
        };
    }

    private static ToolRegistry.Tool stubMcpServerHandleNoGroup(String name) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " handle"; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public boolean isServerLevel() { return true; }
            // group() defaults to null
        };
    }

    private static ToolRegistry.Tool stubMcpServerHandleWithSummary(String name, String group, String summary) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " handle"; }
            @Override public String summary() { return summary; }
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String group() { return group; }
            @Override public boolean isServerLevel() { return true; }
        };
    }

    private static ToolRegistry.Tool stubMcpServerHandleNullSummary(String name, String group) {
        return new ToolRegistry.Tool() {
            @Override public String name() { return name; }
            @Override public String description() { return name + " handle"; }
            @Override public String summary() { return null; }  // force the fallback branch
            @Override public Map<String, Object> parameters() { return Map.of(); }
            @Override public String execute(String argsJson, Agent agent) { return ""; }
            @Override public String group() { return group; }
            @Override public boolean isServerLevel() { return true; }
        };
    }
}
