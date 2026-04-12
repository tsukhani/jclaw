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
        ToolRegistry.register(new DateTimeTool());
        ToolRegistry.register(new CheckListTool());
        ToolRegistry.register(new FileSystemTools());
        ToolRegistry.register(new DocumentsTool());
        ToolRegistry.register(new WebFetchTool());
        ToolRegistry.register(new WebSearchTool());
        ToolRegistry.register(new SkillsTool());
        if ("true".equals(services.ConfigService.get("playwright.enabled"))) {
            ToolRegistry.register(new PlaywrightBrowserTool());
        }
        if ("true".equals(services.ConfigService.get("shell.enabled"))) {
            ToolRegistry.register(new ShellExecTool());
        }
        ToolRegistry.publish();
        services.EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
