package jobs;

import models.Agent;
import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.AgentService;
import services.WorkspaceFiles;

/**
 * Adds the owner's Name line to every existing agent's USER.md, and the step that asks for it to
 * its BOOTSTRAP.md, where either is missing. Templates are written only when a workspace is
 * created, so workspaces older than the Name line would otherwise never gain it.
 */
@OnApplicationStart
public class WorkspaceOwnerNameJob extends Job<Void> {

    @Override
    public void doJob() {
        // Tests drive WorkspaceFiles.addOwnerNamePrompts directly against workspace-test.
        if (Play.runningInTestMode()) return;
        for (Agent agent : AgentService.listAll()) WorkspaceFiles.addOwnerNamePrompts(agent.name);
    }
}
