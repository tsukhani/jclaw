package jobs;

import agents.ToolRegistry;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.EventLogger;
import tools.AppInstallTool;
import tools.CcrRetrieveTool;
import tools.CheckListTool;
import tools.ConversationHistoryTool;
import tools.ConversationListTool;
import tools.ConversationSendTool;
import tools.DateTimeTool;
import tools.DiarizeAudioTool;
import tools.DocumentsTool;
import tools.FileSystemTools;
import tools.GenerateImageTool;
import tools.GenerateVideoTool;
import tools.JClawApiTool;
import tools.LoadTestSleepTool;
import tools.MessageTool;
import tools.PlaywrightBrowserTool;
import tools.ShellExecTool;
import tools.SubagentSpawnTool;
import tools.SubagentYieldTool;
import tools.TaskTool;
import tools.UserGuideTool;
import tools.WebFetchTool;
import tools.WebSearchTool;

import java.util.ArrayList;

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
        var toolList = new ArrayList<ToolRegistry.Tool>();
        toolList.add(new TaskTool());
        toolList.add(new DateTimeTool());
        toolList.add(new GenerateImageTool()); // JCLAW-228: default-off per agent (opt-in)
        toolList.add(new GenerateVideoTool()); // JCLAW-235: async video gen; default-off per agent (opt-in)
        // JCLAW-559: on-demand speaker diarization of an uploaded recording.
        // Default-on: local CPU only, and the tool description steers the
        // model away from invoking it on ordinary voice notes.
        toolList.add(new DiarizeAudioTool());
        // JCLAW-462: ccr_retrieve — fetch the full original of a content-
        // compressed tool result by its hash. Registered unconditionally
        // (per-agent disable still applies); only useful once content
        // compression is enabled and has left a retrieval marker.
        toolList.add(new CcrRetrieveTool());
        // JClaw user-guide lookup: answers chat questions about JClaw's own
        // features/usage by searching the bundled docs/user-guide/ markdown.
        toolList.add(new UserGuideTool());
        toolList.add(new CheckListTool());
        toolList.add(new FileSystemTools());
        toolList.add(new DocumentsTool());
        toolList.add(new AppInstallTool()); // JCLAW-768: sandbox-safe hosted-app stage/validate/install
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
        // JCLAW-265: subagent_spawn. Recursion limits and async path land
        // later (JCLAW-266, JCLAW-270) — this is the synchronous primitive.
        toolList.add(new SubagentSpawnTool());
        // JCLAW-273: subagent_yield. Companion tool to async spawn —
        // flips SubagentRun.yielded so the announce VT posts a USER-role
        // resume Message and re-invokes AgentRunner.run on the parent
        // conversation when the child terminates.
        toolList.add(new SubagentYieldTool());
        // JCLAW-274: conversation_history. Read a subagent run's child
        // conversation transcript (role, content, tool calls/results,
        // timestamps). Parent-owned access only.
        toolList.add(new ConversationHistoryTool());
        // JCLAW-326: conversation_send. Bidirectional parent↔child message
        // delivery. Parent→child appends a USER message on the child's
        // conversation; child→parent appends back to the parent's
        // conversation. Fire-and-forget; does not block either side.
        toolList.add(new ConversationSendTool());
        // JCLAW-326: conversation_list. Paginated, parent-scoped list of this
        // agent's SubagentRun rows with status / label-glob / agentId
        // filters.
        toolList.add(new ConversationListTool());
        // JCLAW-327: message. Push a text message to an external chat
        // channel (Telegram / Slack / WhatsApp) mid-turn. Defaults channel
        // and target from the calling agent's active conversation, so
        // subagents spawned inside a channel-bound thread can reply
        // upstream without hardcoding credentials.
        toolList.add(new MessageTool());
        // JCLAW-281: list_mcp_tools is gone. Discovery is folded into each
        // MCP server's own surface — the model calls mcp_<server> with
        // empty args to enumerate that server's actions, registered by
        // McpConnectionManager.republishTools.
        if ("true".equals(ConfigService.get("provider.loadtest-mock.enabled"))) {
            toolList.add(new LoadTestSleepTool());
        }
        ToolRegistry.publish(toolList);
        EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
