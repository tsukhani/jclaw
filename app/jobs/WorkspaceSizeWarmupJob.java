package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.WorkspaceFiles;

/** JF-35: counts the workspace at boot, so the dashboard's first visit finds a size rather than waiting on the walk. */
@OnApplicationStart(async = true)
@NoTransaction
public class WorkspaceSizeWarmupJob extends Job<Void> {

    @Override
    public void doJob() {
        // The test server shares the checkout's workspace/, where a boot walk would only add IO under the suite.
        if (Play.runningInTestMode()) return;
        WorkspaceFiles.countWorkspaceSize();
    }
}
