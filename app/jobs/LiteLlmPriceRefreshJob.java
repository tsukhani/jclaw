package jobs;

import play.Logger;
import play.jobs.Job;
import play.jobs.On;
import services.PricingRefreshService;

/**
 * Nightly refresh of model pricing data from LiteLLM's community manifest
 * (JCLAW-28 follow-up). Schedule fires at 03:00 UTC; the off-peak window
 * keeps the GitHub fetch out of operator-active hours and avoids any
 * possible thundering-herd effect on the LiteLLM repo.
 *
 * <p>The job itself is just a thin wrapper around
 * {@link PricingRefreshService#refresh()} — the service does the toggle
 * check, the fetch, and the apply. Splitting them keeps the service
 * testable without scheduling and lets the manual-trigger API endpoint
 * call the same code path.
 *
 * <p>Default behavior (when {@code pricing.refresh.enabled} is unset or
 * false) is for the service to short-circuit. The job still wakes up
 * nightly to check the toggle — cheap, no I/O — so flipping the toggle
 * on doesn't require a JVM restart to take effect on the next cycle.
 *
 * @see PricingRefreshService
 */
@On("0 0 3 * * ?")
public class LiteLlmPriceRefreshJob extends Job<Void> {

    @Override
    public void doJob() {
        var result = PricingRefreshService.refresh();
        if (result.skipped()) {
            // Toggle is off; nothing to log. The pre-toggle wakeup is
            // intentional — see the class doc — but it's noise to log
            // every night when the operator hasn't opted in.
            return;
        }
        if (!result.warnings().isEmpty()) {
            Logger.warn("LiteLLM nightly refresh completed with %d warning%s: %s",
                    result.warnings().size(),
                    result.warnings().size() == 1 ? "" : "s",
                    String.join("; ", result.warnings()));
        }
        Logger.info("LiteLLM nightly refresh: scanned %d provider%s, updated %d model%s",
                result.providersScanned(),
                result.providersScanned() == 1 ? "" : "s",
                result.modelsUpdated(),
                result.modelsUpdated() == 1 ? "" : "s");
    }
}
