package jobs;

import agents.SkillLoader;
import agents.ToolRegistry;
import models.AgentToolConfig;
import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
import tools.JClawApiTool;

import java.util.List;
import java.util.TreeSet;

/**
 * JF-293: once per install, removes every {@code jclaw_api} row of an agent other than main.
 *
 * <p>A row saved before JF-287 made the tool main's alone was ignored from then on, and the 1.0.13 notes told
 * operators the tool was gone for those agents. An enabled row switches the tool on again now that it is opt-in, so
 * a row from before would do it with no new action by the operator. The first start on this version removes them, and
 * the operator switches the tool on again under Tools on the agent's page.
 */
// Ahead of the default-priority jobs: they start the channels and the scheduler, whose turns read an agent's tools.
@OnApplicationStart(priority = -50)
public class JclawApiGrantResetJob extends Job<Void> {

    /** Written once the rows are gone, so a grant the operator makes afterwards survives every later start. */
    public static final String APPLIED_KEY = "tool.jclawApi.grantResetApplied";

    @Override
    public void doJob() {
        // Tests drive removeOnce directly; the boot pass would race their fixtures.
        if (Play.runningInTestMode()) return;
        removeOnce();
    }

    /** The step, a no-op once {@link #APPLIED_KEY} is written. The rows and the marker commit together. */
    public static void removeOnce() {
        if (ConfigService.get(APPLIED_KEY) != null) return;
        var switchedOff = Tx.run(() -> {
            var agents = removeRows();
            ConfigService.set(APPLIED_KEY, "true");
            // After the commit (JCLAW-1042): a read racing an earlier clear would cache the rows not yet deleted again.
            Tx.afterCommit(JclawApiGrantResetJob::clearToolCaches);
            return agents;
        });
        if (!switchedOff.isEmpty()) {
            EventLogger.info("system", ("Removed the jclaw_api grant of %d agent(s) (%s), saved before the tool became "
                    + "opt-in: switch it on again under Tools on the agent's page")
                    .formatted(switchedOff.size(), String.join(", ", switchedOff)));
        }
    }

    /** Whether the step removes {@code row}: a {@code jclaw_api} row, switched on or off, of any agent but main. */
    public static boolean removes(AgentToolConfig row) {
        return JClawApiTool.TOOL_NAME.equals(row.toolName) && !row.agent.isMain();
    }

    /** Deletes the rows and returns, for the log line, the agents whose row was switched on. */
    private static TreeSet<String> removeRows() {
        List<AgentToolConfig> rows = AgentToolConfig.find("toolName = ?1", JClawApiTool.TOOL_NAME).fetch();
        var switchedOff = new TreeSet<String>();
        for (var row : rows) {
            if (!removes(row)) continue;
            if (row.enabled) switchedOff.add(row.agent.name);
            row.delete();
        }
        return switchedOff;
    }

    private static void clearToolCaches() {
        ToolRegistry.clearDisabledToolsCache();
        // A skill that needs the tool leaves the agent's skill list with it, as on the operator's own toggle.
        SkillLoader.clearCache();
    }
}
