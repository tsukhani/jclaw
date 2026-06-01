package jobs;

import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.TailscaleFunnel;

/**
 * Re-establish the Tailscale Funnel at boot when it's enabled (JCLAW-84).
 *
 * <p>{@code funnel --bg} config is daemon-persistent, but {@link ShutdownJob}
 * tears it down on graceful stop, so we re-create it here on the next start.
 * {@link TailscaleFunnel#reconcile()} is a no-op (no subprocess) when the
 * feature is disabled — the common case — so this is free for instances that
 * don't use Funnel. Skipped in test mode.
 */
@OnApplicationStart
public class TailscaleFunnelBootJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        TailscaleFunnel.reconcile();
    }
}
