package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.LocalProviderProbeSupport;
import utils.Strings;

/**
 * Probe the configured self-hosted vLLM instance once at boot. Same shape as
 * {@link OllamaLocalProbeJob} / {@link LmStudioProbeJob} — INFO with the model count when reachable,
 * WARN with a start hint when reachable-but-broken, DEBUG (silent under default logging) on
 * connection-refused so a fresh install without vLLM running stays quiet. Reuses the shared
 * {@link LocalProviderProbeSupport#probeModels} {@code GET /models} check directly (no dedicated
 * cached-probe service — nothing reads a cached vLLM result; Settings → Video Interpretation
 * live-probes {@code /api/providers/vllm/reachable} on demand).
 */
@OnApplicationStart
@NoTransaction
public class VllmProbeJob extends Job<Void> {

    @Override
    public void doJob() {
        // Skip in test mode — same rationale as the other local-provider probe jobs: operator
        // guidance only, zero signal under autotest, and a path for external-state flakiness.
        if (Play.runningInTestMode()) return;
        ProbeJobs.run("vllm", "provider.vllm.baseUrl",
                "Start a vLLM OpenAI-compatible server (e.g. `vllm serve <model>`, default port 8000) "
                        + "and set provider.vllm.baseUrl to its /v1 URL.",
                baseUrl -> {
                    var r = LocalProviderProbeSupport.probeModels(Strings.trimTrailingSlash(baseUrl), "vllm");
                    return new ProbeJobs.Outcome(r.available(), r.modelCount(), r.reason(), r.connectionRefused());
                });
    }
}
