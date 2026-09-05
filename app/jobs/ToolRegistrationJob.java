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
import tools.ConversationSearchTool;
import tools.ConversationSendTool;
import tools.DateTimeTool;
import tools.DiarizeAudioTool;
import tools.DocumentsTool;
import tools.FileSystemTools;
import tools.GenerateAudioTool;
import tools.GenerateImageTool;
import tools.GenerateVideoTool;
import tools.JClawApiTool;
import tools.LoadTestSleepTool;
import tools.MemoryTool;
import tools.MessageTool;
import tools.PlaywrightBrowserTool;
import tools.PrinterTool;
import tools.ShellExecTool;
import tools.SubagentSpawnTool;
import tools.SubagentYieldTool;
import tools.TaskTool;
import tools.UserGuideTool;
import tools.WebFetchTool;
import tools.WebScrapeTool;
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
        // JCLAW-876: speak a reply aloud as an audio attachment. Default-off per
        // agent — synthesis costs seconds and can trigger a sidecar model load.
        toolList.add(new GenerateAudioTool());
        // JCLAW-559: on-demand speaker diarization of an uploaded recording.
        // Default-on: local CPU only, and the tool description steers the
        // model away from invoking it on ordinary voice notes.
        toolList.add(new DiarizeAudioTool());
        // JCLAW-462: ccr_retrieve — fetch the full original of a content-
        // compressed tool result by its hash. Registered unconditionally
        // (per-agent disable still applies); only useful once content
        // compression is enabled and has left a retrieval marker.
        toolList.add(new CcrRetrieveTool());
        toolList.add(new UserGuideTool());
        toolList.add(new CheckListTool());
        // JCLAW-919: recall is why this exists — prompt assembly queries memory once per
        // turn, so a fact the opening message missed is otherwise unreachable. Its store
        // and forget actions are for explicit operator instructions only; capture remains
        // the automatic write path. On for main, opt-in for every other agent.
        toolList.add(new MemoryTool());
        toolList.add(new FileSystemTools());
        toolList.add(new DocumentsTool());
        toolList.add(new AppInstallTool()); // JCLAW-768: sandbox-safe hosted-app stage/validate/install
        toolList.add(new WebFetchTool());
        // JCLAW-1083: multi-page sibling of web_fetch — same extraction chain,
        // plus a frontier and a budget.
        toolList.add(new WebScrapeTool());
        toolList.add(new WebSearchTool());
        // JCLAW-911: printer — mDNS discovery + JVM-native printing (IPP, raw
        // socket, LPD). Registered unconditionally so it appears on the Tools
        // page; per-agent disable still applies, and printing is physical and
        // irreversible, so most agents should leave it off.
        toolList.add(new PrinterTool());
        // JCLAW-172: registered unconditionally — the old global `playwright.enabled` / `shell.enabled`
        // gates duplicated the per-agent enable on the Tools page (each agent's AgentToolConfig row decides).
        toolList.add(new PlaywrightBrowserTool());
        toolList.add(new ShellExecTool());
        // JCLAW-282: in-process JClaw API tool. Registered globally so the
        // Tools page shows it, but AgentService.create disables it for
        // every non-main agent so only main can actually invoke it
        // (defense in depth alongside the skill-not-installed gate).
        toolList.add(new JClawApiTool());
        // JCLAW-265: subagent_spawn — the synchronous primitive; recursion limits and the async path are JCLAW-266 / JCLAW-270.
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
        toolList.add(new ConversationSearchTool());
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
        // JCLAW-281: no list_mcp_tools — discovery is each server's own mcp_<server> call with empty args (McpConnectionManager.republishTools).
        if ("true".equals(ConfigService.get("provider.loadtest-mock.enabled"))) {
            toolList.add(new LoadTestSleepTool());
        }
        ToolRegistry.publish(toolList);
        EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
