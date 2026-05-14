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
        // JCLAW-282: in-process JClaw API tool. Registered globally so the
        // Tools page shows it, but AgentService.create disables it for
        // every non-main agent so only main can actually invoke it
        // (defense in depth alongside the skill-not-installed gate).
        toolList.add(new JClawApiTool());
        // JCLAW-265: spawn_subagent. Recursion limits and async path land
        // later (JCLAW-266, JCLAW-270) — this is the synchronous primitive.
        toolList.add(new SpawnSubagentTool());
        // JCLAW-273: yield_to_subagent. Companion tool to async spawn —
        // flips SubagentRun.yielded so the announce VT posts a USER-role
        // resume Message and re-invokes AgentRunner.run on the parent
        // conversation when the child terminates.
        toolList.add(new YieldToSubagentTool());
        // JCLAW-274: sessions_history. Read a subagent run's child
        // conversation transcript (role, content, tool calls/results,
        // timestamps). Parent-owned access only.
        toolList.add(new SessionsHistoryTool());
        // JCLAW-281: list_mcp_tools is gone. Discovery is folded into each
        // MCP server's own surface — the model calls mcp_<server> with
        // empty args to enumerate that server's actions, registered by
        // McpConnectionManager.republishTools.
        if ("true".equals(services.ConfigService.get("provider.loadtest-mock.enabled"))) {
            toolList.add(new LoadTestSleepTool());
        }
        ToolRegistry.publish(toolList);
        services.EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
