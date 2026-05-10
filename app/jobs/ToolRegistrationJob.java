package jobs;

import agents.ToolRegistry;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import tools.*;

// All DB-touching calls below (ConfigService.get, EventLogger.info) wrap
// their own work in Tx.run, so no outer JPA tx is needed. @NoTransaction
// keeps the cleanup-time EntityManager.close() out of the shutdown race.
@OnApplicationStart
@NoTransaction
public class ToolRegistrationJob extends Job<Void> {

    @Override
    public void doJob() {
        registerAll();
    }

    /** Re-run tool registration. Thread-safe: builds a local list and publishes atomically. */
    public static void registerAll() {
        var toolList = new java.util.ArrayList<ToolRegistry.Tool>();
        toolList.add(new TaskTool());
        toolList.add(new DateTimeTool());
        toolList.add(new CheckListTool());
        toolList.add(new FileSystemTools());
        toolList.add(new DocumentsTool());
        toolList.add(new WebFetchTool());
        toolList.add(new WebSearchTool());
        // JCLAW-172: PlaywrightBrowserTool and ShellExecTool used to be gated
        // on global `playwright.enabled` / `shell.enabled` config keys, but
        // that duplicated the per-agent enable that already lives on the
        // Tools page (each agent's AgentToolConfig row decides binding).
        // Both register unconditionally now; per-agent disable still hides
        // them from any agent that doesn't want them.
        toolList.add(new PlaywrightBrowserTool());
        toolList.add(new ShellExecTool());
        // Discovery entrypoint for MCP tools — always registered so the
        // model can issue list_mcp_tools(server) even before any MCP
        // server has connected. After a successful call, the server's
        // tool schemas become callable for the rest of the conversation
        // (gated by ToolRegistry.getToolDefsForAgent + McpDiscovery).
        toolList.add(new ListMcpToolsTool());
        if ("true".equals(services.ConfigService.get("provider.loadtest-mock.enabled"))) {
            toolList.add(new LoadTestSleepTool());
        }
        ToolRegistry.publish(toolList);
        services.EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
