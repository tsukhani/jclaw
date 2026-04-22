package jobs;

import channels.TelegramPollingRunner;
import channels.TelegramStreamingSink;
import controllers.ApiChatController;
import play.jobs.Job;
import play.jobs.OnApplicationStop;
import tools.PlaywrightBrowserTool;

@OnApplicationStop
public class ShutdownJob extends Job<Void> {

    @Override
    public void doJob() {
        TaskPollerJob.shutdownGracefully();
        PlaywrightBrowserTool.closeAllSessions();
        TelegramPollingRunner.stop();
        TelegramStreamingSink.shutdown();
        ApiChatController.shutdown();
    }
}
