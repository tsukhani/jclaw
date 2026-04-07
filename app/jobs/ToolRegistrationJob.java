package jobs;

import agents.ToolRegistry;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import tools.*;

@OnApplicationStart
public class ToolRegistrationJob extends Job<Void> {

    @Override
    public void doJob() {
        registerAll();
    }

    /** Re-run tool registration. Safe to call at runtime — the volatile map swap in publish() is atomic for readers. */
    public static void registerAll() {
        ToolRegistry.clear();
        ToolRegistry.register(new TaskTool());
        ToolRegistry.register(new CheckListTool());
        ToolRegistry.register(new FileSystemTools());
        ToolRegistry.register(new WebFetchTool());
        ToolRegistry.register(new WebSearchTool());
        ToolRegistry.register(new SkillsTool());
        if (isToolEnabled("jclaw.tools.playwright.enabled")) {
            ToolRegistry.register(new PlaywrightBrowserTool());
        }
        if (isToolEnabled("jclaw.tools.shell.enabled")) {
            ToolRegistry.register(new ShellExecTool());
        }
        ToolRegistry.publish();
        services.EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }

    /** Check Config DB first (set by Settings UI), fall back to application.conf. */
    private static boolean isToolEnabled(String key) {
        var dbValue = services.ConfigService.get(key);
        if (dbValue != null) return "true".equals(dbValue);
        return "true".equals(play.Play.configuration.getProperty(key, "false"));
    }
}
