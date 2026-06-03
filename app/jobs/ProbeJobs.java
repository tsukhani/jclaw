package jobs;

import play.Logger;
import services.ConfigService;
import services.Tx;

import java.util.function.Function;

/**
 * Shared boot-probe logic for the local-LLM provider probe jobs
 * ({@link LmStudioProbeJob}, {@link OllamaLocalProbeJob}). Both probe a
 * configured local provider once at boot and log the same three-way outcome —
 * INFO with a model count when reachable, DEBUG (silent under default logging)
 * when connection-refused so a fresh install without the provider running stays
 * quiet, and WARN with an install hint when reachable-but-broken. The jobs
 * differ only in their config key, label, install hint, and which probe service
 * they call, so that variation is threaded in as arguments.
 */
final class ProbeJobs {

    private ProbeJobs() { }

    /** Adapter over the per-provider {@code ProbeResult} records, which are
     *  structurally identical but distinct types. */
    record Outcome(boolean available, int modelCount, String reason, boolean connectionRefused) { }

    /**
     * Read {@code baseUrlConfigKey} in its own short tx (releasing the JPA
     * carrier before the network round-trip), probe it, and log the outcome.
     * A blank/absent base URL is a no-op. Caller is responsible for the
     * test-mode skip — the probe contributes zero signal under autotest.
     *
     * @param label          provider label used in every log line (e.g. {@code "lm-studio"})
     * @param baseUrlConfigKey Config key holding the provider base URL
     * @param installHint     the WARN-line suffix guiding the operator to install/start the provider
     * @param probe           probe function mapping a base URL to its {@link Outcome}
     */
    static void run(String label, String baseUrlConfigKey, String installHint,
                    Function<String, Outcome> probe) {
        var baseUrl = Tx.run(() -> ConfigService.get(baseUrlConfigKey));
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        var r = probe.apply(baseUrl);
        if (r.available()) {
            Logger.info("%s: reachable at %s — %d model%s available",
                    label, baseUrl, r.modelCount(), r.modelCount() == 1 ? "" : "s");
        } else if (r.connectionRefused()) {
            Logger.debug("%s: %s", label, r.reason());
        } else {
            Logger.warn("%s: %s. Agents bound to %s will fail to send messages. %s",
                    label, r.reason(), label, installHint);
        }
    }
}
