package jobs;

import channels.SlackSocketModeRunner;
import channels.TelegramPollingRunner;
import channels.WhatsAppCobaltRunner;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import utils.VirtualThreads;

import java.util.List;

/**
 * Boots the channel polling runners. Invoked once after Play finishes loading
 * plugins and the JPA schema; reconciliation at runtime (after admin config
 * saves) is driven inline from {@link controllers.ApiChannelsController#save}.
 * The three run at once, off the boot thread (JF-277): waiting for their network
 * round trips held the port closed for 1.1 s on an instance with Slack and two
 * Telegram bots, and nothing before the port opens needs them, since the channels
 * send through each provider's API rather than the receiving connection. A start
 * that loses the race to a stop starts nothing ({@code reconcileUnless}).
 *
 * <p>{@code @NoTransaction} skips the framework's outer JPA tx wrapper for
 * this job. {@link TelegramPollingRunner#reconcile()} manages its own
 * transactions via {@link services.Tx#run}, so the outer wrapper was
 * redundant — and its {@code EntityManager.close()} on the way out is the
 * call that races with H2's shutdown hook during a restart, surfacing the
 * cosmetic "Database is already closed" stacktrace. Skipping the wrapper
 * removes the race window without changing any actual DB behavior.</p>
 */
@OnApplicationStart(async = true)
@NoTransaction
public class ChannelRunnerJob extends Job<Void> {

    @Override
    public void doJob() {
        VirtualThreads.runAll("channel-start", List.of(TelegramPollingRunner::reconcile,
                SlackSocketModeRunner::reconcile, WhatsAppCobaltRunner::reconcile));
    }
}
