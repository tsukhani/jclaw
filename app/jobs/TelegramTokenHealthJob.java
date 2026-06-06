package jobs;

import channels.TelegramPollingRunner;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Every;
import play.jobs.Job;

/**
 * JCLAW-436: periodically probe every enabled POLLING binding's bot token
 * against Telegram and disable any the server permanently rejects (getMe
 * 401/403/404 → invalid/revoked token). Replaces the old inert liveness
 * watchdog; the actual work lives in
 * {@link TelegramPollingRunner#probeTokenHealth()}.
 *
 * <p>{@code @NoTransaction}: the probe manages its own transactions via
 * {@link services.Tx#run}, so the framework's outer JPA wrapper is redundant
 * (same rationale as {@link ChannelRunnerJob}).
 *
 * <p>Skipped in test mode — the probe dials {@code api.telegram.org} through
 * getMe, which contributes zero signal under autotest and is a path for
 * external-state flakiness (same rationale as {@link LmStudioProbeJob} /
 * {@link OllamaLocalProbeJob}). The probe's own logic is exercised directly via
 * {@code TelegramPollingRunnerTestHooks.runTokenHealthProbe}.
 */
@Every("5min")
@NoTransaction
public class TelegramTokenHealthJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        TelegramPollingRunner.probeTokenHealth();
    }
}
