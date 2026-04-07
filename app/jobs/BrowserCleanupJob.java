package jobs;

import play.jobs.Every;
import play.jobs.Job;
import tools.PlaywrightBrowserTool;

@Every("60s")
public class BrowserCleanupJob extends Job<Void> {

    @Override
    public void doJob() {
        PlaywrightBrowserTool.cleanupIdleSessions();
    }
}
