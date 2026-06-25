package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.LmStudioProbe;

/**
 * JCLAW-182: probe the configured LM Studio instance once at boot. Same shape
 * as {@link OllamaLocalProbeJob} — INFO with the model count when reachable,
 * WARN with an install hint when reachable-but-broken, and DEBUG (silent
 * under default logging) when connection-refused so a fresh install without
 * LM Studio running stays quiet.
 */
@OnApplicationStart
@NoTransaction
public class LmStudioProbeJob extends Job<Void> {

    @Override
    public void doJob() {
        // Skip in test mode — same rationale as OllamaLocalProbeJob: the
        // probe is operator guidance, contributes zero signal under
        // autotest (which points providers at 127.0.0.1 mock servers),
        // and creates a path for external-state flakiness.
        if (Play.runningInTestMode()) return;
        ProbeJobs.run("lm-studio", "provider.lm-studio.baseUrl",
                "Download LM Studio from https://lmstudio.ai, load a model in the My Models "
                        + "tab, then start the local server from the Server tab (default port 1234).",
                baseUrl -> {
                    var r = LmStudioProbe.probe(baseUrl);
                    return new ProbeJobs.Outcome(r.available(), r.modelCount(), r.reason(), r.connectionRefused());
                });
    }
}
