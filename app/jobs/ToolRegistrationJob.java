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
        ToolRegistry.register(new SkillsTool());
        ToolRegistry.register(new SkillManagerTool());
        ToolRegistry.publish();
        services.EventLogger.info("system", "Registered %d tools".formatted(ToolRegistry.listTools().size()));
    }
}
