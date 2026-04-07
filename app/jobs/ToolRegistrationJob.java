package jobs;

import agents.ToolRegistry;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import tools.*;

@OnApplicationStart
public class ToolRegistrationJob extends Job<Void> {

    @Override
    public void doJob() {
        ToolRegistry.clear();
        ToolRegistry.register(new TaskTool());
        ToolRegistry.register(new CheckListTool());
        ToolRegistry.register(new FileSystemTools());
        ToolRegistry.register(new WebFetchTool());
        ToolRegistry.register(new WebSearchTool());
        ToolRegistry.register(new SkillsTool());
        if ("true".equals(play.Play.configuration.getProperty("jclaw.tools.playwright.enabled", "false"))) {
            ToolRegistry.register(new PlaywrightBrowserTool());
        }
        if ("true".equals(play.Play.configuration.getProperty("jclaw.tools.shell.enabled", "false"))) {
            ToolRegistry.register(new ShellExecTool());
        }
        ToolRegistry.publish();
        services.EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
