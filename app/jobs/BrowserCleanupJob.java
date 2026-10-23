package jobs;

import play.jobs.Every;
import tools.PlaywrightBrowserTool;

@Every("60s")
public class BrowserCleanupJob extends PeriodicJob {

    @Override
    public void doJob() {
        PlaywrightBrowserTool.cleanupIdleSessions();
    }
}
