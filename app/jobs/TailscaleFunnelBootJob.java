package jobs;

import okhttp3.Request;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.TailscaleFunnel;
import services.Tx;
import utils.HttpFactories;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Re-establish the Tailscale Funnel at boot when it's enabled (JCLAW-84).
 *
 * <p>{@code funnel --bg} config is daemon-persistent, but {@link ShutdownJob}
 * tears it down on graceful stop, so we re-create it here on the next start.
 * It runs off the boot thread (JF-16), and the fork starts an async job before the
 * server binds, so it first waits for the funnelled port to answer: a funnel opened
 * earlier forwards to a closed port. Free when the feature is disabled — the common
 * case — and skipped in test mode.
 */
@OnApplicationStart(async = true)
@NoTransaction
public class TailscaleFunnelBootJob extends Job<Void> {

    /** Counts from submission, which precedes the rest of the boot; on expiry the funnel opens anyway, as it did before JF-16. */
    private static final Duration LISTENER_BUDGET = Duration.ofMinutes(5);
    private static final Duration LISTENER_POLL = Duration.ofMillis(250);

    public enum Listener { ANSWERED, TIMED_OUT, STOPPING }

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        if (!Tx.run(TailscaleFunnel::isFunnelEnabled)) {
            return;
        }
        int port = Tx.run(TailscaleFunnel::configuredPort);
        switch (awaitListener(port, LISTENER_BUDGET, LISTENER_POLL, TailscaleFunnel::isStopping)) {
            case STOPPING -> {
                return;
            }
            case TIMED_OUT -> EventLogger.warn("tailscale", "Nothing answered on local port %d within %d minutes; enabling the funnel anyway"
                    .formatted(port, LISTENER_BUDGET.toMinutes()));
            case ANSWERED -> { }
        }
        TailscaleFunnel.reconcile();
    }

    /** Polls {@code 127.0.0.1:port}, where Funnel forwards, until anything answers HTTP, the budget runs out or JClaw stops. */
    public static Listener awaitListener(int port, Duration budget, Duration poll, BooleanSupplier stopping) {
        var request = new Request.Builder().url("http://127.0.0.1:" + port + "/api/status").build();
        long deadline = System.nanoTime() + budget.toNanos();
        while (!stopping.getAsBoolean()) {
            var call = HttpFactories.general().newCall(request);
            call.timeout().timeout(2, TimeUnit.SECONDS);
            try (var _ = call.execute()) {
                return Listener.ANSWERED;
            } catch (IOException _) {
                if (System.nanoTime() - deadline >= 0) {
                    return Listener.TIMED_OUT;
                }
            }
            try {
                Thread.sleep(poll);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                return Listener.STOPPING;
            }
        }
        return Listener.STOPPING;
    }
}
