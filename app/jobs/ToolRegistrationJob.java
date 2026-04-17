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
        if ("true".equals(services.ConfigService.get("playwright.enabled"))) {
            toolList.add(new PlaywrightBrowserTool());
        }
        if ("true".equals(services.ConfigService.get("shell.enabled"))) {
            toolList.add(new ShellExecTool());
        }
        if ("true".equals(services.ConfigService.get("provider.loadtest-mock.enabled"))) {
            toolList.add(new LoadTestSleepTool());
        }
        ToolRegistry.publish(toolList);
        services.EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
