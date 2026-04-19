package jobs;

import channels.TelegramPollingRunner;
import play.jobs.Job;
import play.jobs.OnApplicationStart;

/**
 * Boots the channel polling runners. Invoked once after Play finishes loading
 * plugins and the JPA schema; reconciliation at runtime (after admin config
 * saves) is driven inline from {@link controllers.ApiChannelsController#save}.
 */
@OnApplicationStart
public class ChannelRunnerJob extends Job<Void> {

    @Override
    public void doJob() {
        TelegramPollingRunner.reconcile();
    }
}
