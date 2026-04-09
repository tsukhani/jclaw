package jobs;

import play.jobs.Job;
import play.jobs.OnApplicationStop;
import tools.PlaywrightBrowserTool;

@OnApplicationStop
public class ShutdownJob extends Job<Void> {

    @Override
    public void doJob() {
        PlaywrightBrowserTool.closeAllSessions();
    }
}
