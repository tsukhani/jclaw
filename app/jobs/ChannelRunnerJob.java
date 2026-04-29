package jobs;

import channels.TelegramPollingRunner;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;

/**
 * Boots the channel polling runners. Invoked once after Play finishes loading
 * plugins and the JPA schema; reconciliation at runtime (after admin config
 * saves) is driven inline from {@link controllers.ApiChannelsController#save}.
 *
 * <p>{@code @NoTransaction} skips the framework's outer JPA tx wrapper for
 * this job. {@link TelegramPollingRunner#reconcile()} manages its own
 * transactions via {@link services.Tx#run}, so the outer wrapper was
 * redundant — and its {@code EntityManager.close()} on the way out is the
 * call that races with H2's shutdown hook during a restart, surfacing the
 * cosmetic "Database is already closed" stacktrace. Skipping the wrapper
 * removes the race window without changing any actual DB behaviour.</p>
 */
@OnApplicationStart
@NoTransaction
public class ChannelRunnerJob extends Job<Void> {

    @Override
    public void doJob() {
        TelegramPollingRunner.reconcile();
    }
}
