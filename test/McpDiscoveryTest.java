import agents.ToolRegistry;
import com.google.gson.JsonObject;
import mcp.McpDiscovery;
import models.Agent;
import models.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Verifies the lazy MCP discovery contract:
 *
 * <ul>
 *   <li>{@link McpDiscovery#discoveredServers(List)} extracts the
 *       {@code server} argument from prior {@code list_mcp_tools} tool
 *       calls in conversation history.</li>
 *   <li>{@link ToolRegistry#getToolDefsForAgent(Agent, Set)} hides MCP
 *       tool schemas until the model has discovered their server.</li>
 *   <li>The discovery tool itself ({@code list_mcp_tools}) ships every
 *       turn so the model can self-bootstrap.</li>
 * </ul>
 */
public class McpDiscoveryTest extends UnitTest {

    private List<ToolRegistry.Tool> originalTools;

    @BeforeEach
    public void saveRegistry() {
        originalTools = ToolRegistry.listTools();
    }

    @AfterEach
    public void restoreRegistry() {
        ToolRegistry.publish(originalTools);
    }

    // ==================== McpDiscovery — pure parsing ====================

    @Test
    public void discoveredServersIsEmptyForBlankHistory() {
        assertTrue(McpDiscovery.discoveredServers(List.<Message>of()).isEmpty());
        assertTrue(McpDiscovery.discoveredServers((List<Message>) null).isEmpty());
    }

    @Test
    public void discoveredServersIgnoresUserAndToolRoles() {
        var msgs = new ArrayList<Message>();
        msgs.add(messageWithToolCalls("user", "[]"));
        msgs.add(messageWithToolCalls("tool", listMcpToolsCall("jira")));
        assertTrue(McpDiscovery.discoveredServers(msgs).isEmpty());
    }

    @Test
    public void discoveredServersExtractsServerNameFromAssistantToolCalls() {
        var msgs = new ArrayList<Message>();
        msgs.add(messageWithToolCalls("assistant", listMcpToolsCall("jira-confluence")));
        var found = McpDiscovery.discoveredServers(msgs);
        assertEquals(1, found.size());
        assertTrue(found.contains("jira-confluence"));
    }

    @Test
    public void discoveredServersAccumulatesAcrossMultipleCalls() {
        var msgs = new ArrayList<Message>();
        msgs.add(messageWithToolCalls("assistant", listMcpToolsCall("jira-confluence")));
        msgs.add(messageWithToolCalls("user", null));
        msgs.add(messageWithToolCalls("assistant", listMcpToolsCall("github")));
        var found = McpDiscovery.discoveredServers(msgs);
        assertEquals(2, found.size());
        assertTrue(found.contains("jira-confluence"));
        assertTrue(found.contains("github"));
    }

    @Test
    public void discoveredServersIgnoresOtherToolNames() {
        var msgs = new ArrayList<Message>();
        msgs.add(messageWithToolCalls("assistant", toolCallJson("filesystem", "{\"path\":\"a\"}")));
        msgs.add(messageWithToolCalls("assistant", toolCallJson("web_search", "{\"q\":\"x\"}")));
        assertTrue(McpDiscovery.discoveredServers(msgs).isEmpty());
    }

    @Test
    public void discoveredServersToleratesMalformedJson() {
        var msgs = new ArrayList<Message>();
        msgs.add(messageWithToolCalls("assistant", "not json"));
        msgs.add(messageWithToolCalls("assistant", "[]"));
        msgs.add(messageWithToolCalls("assistant", listMcpToolsCall("jira")));
        var found = McpDiscovery.discoveredServers(msgs);
        assertEquals(1, found.size());
        assertTrue(found.contains("jira"));
    }

    // ==================== ToolRegistry lazy gate ====================

    @Test
    public void mcpToolsAreHiddenUntilDiscovered() {
        var nativeTool = stubTool("filesystem", null);
        var mcpTool = stubMcpTool("mcp_jira_get_issue", "jira");
        var discovery = stubTool("list_mcp_tools", null);
        ToolRegistry.publish(List.of(nativeTool, mcpTool, discovery));

        // Empty discovered set → MCP tool excluded; native + discovery present.
        var defs = ToolRegistry.getToolDefsForAgent((Agent) null, Set.<String>of());
        var names = defs.stream().map(d -> d.function().name()).toList();
        assertTrue(names.contains("filesystem"), "native tool present");
        assertTrue(names.contains("list_mcp_tools"), "discovery tool present");
        assertFalse(names.contains("mcp_jira_get_issue"), "undiscovered MCP tool hidden");
    }

    @Test
    public void mcpToolsAppearAfterDiscovery() {
        var nativeTool = stubTool("filesystem", null);
        var mcpTool = stubMcpTool("mcp_jira_get_issue", "jira");
        var discovery = stubTool("list_mcp_tools", null);
        ToolRegistry.publish(List.of(nativeTool, mcpTool, discovery));

        var defs = ToolRegistry.getToolDefsForAgent((Agent) null, Set.of("jira"));
        var names = defs.stream().map(d -> d.function().name()).toList();
        assertTrue(names.contains("mcp_jira_get_issue"),
                "post-discovery MCP tool surfaced: " + names);
    }

    @Test
    public void unrelatedServerDiscoveryDoesNotUnlockOthers() {
        ToolRegistry.publish(List.of(
                stubMcpTool("mcp_jira_get_issue", "jira"),
                stubMcpTool("mcp_github_create_issue", "github")
        ));

        var names = ToolRegistry.getToolDefsForAgent((Agent) null, Set.of("jira"))
                .stream().map(d -> d.function().name()).toList();
        assertTrue(names.contains("mcp_jira_get_issue"));
        assertFalse(names.contains("mcp_github_create_issue"),
                "github tool stays hidden when only jira was discovered");
    }

    // ==================== helpers ====================

    private static Message messageWithToolCalls(String role, String toolCallsJson) {
        var m = new Message();
        m.role = role;
        m.toolCalls = toolCallsJson;
        return m;
    }

    private static String listMcpToolsCall(String server) {
        return toolCallJson("list_mcp_tools", "{\"server\":\"" + server + "\"}");
    }

    private static String toolCallJson(String name, String argsJson) {
        // OpenAI shape: arguments is itself a JSON-encoded string.
        var fn = new JsonObject();
        fn.addProperty("name", name);
        fn.addProperty("arguments", argsJson);
        var call = new JsonObject();
        call.addProperty("id", "call_" + name);
        call.add("function", fn);
        var arr = new com.google.gson.JsonArray();
        arr.add(call);
        return arr.toString();
    }

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
}
