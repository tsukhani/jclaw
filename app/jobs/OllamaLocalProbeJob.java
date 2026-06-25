package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.OllamaLocalProbe;

/**
 * JCLAW-178: probe the configured local Ollama instance once at boot. Logs
 * INFO with a model count when reachable, WARN with an install hint when
 * reachable-but-broken, and DEBUG (silent under default logging) when
 * connection-refused so a fresh install with no local Ollama doesn't surface
 * a spurious WARN line on every JVM start.
 *
 * <p>Run-once-at-boot is intentional: registration is config-driven, not
 * health-driven, so the probe is operator guidance — turning it into a
 * per-request cost would be all noise, no signal. A restart picks up new
 * state.
 */
@OnApplicationStart
@NoTransaction
public class OllamaLocalProbeJob extends Job<Void> {

    @Override
    public void doJob() {
        // Skip in test mode. The probe contributes zero signal there —
        // tests use 127.0.0.1 mock servers, not the developer's real
        // Ollama instance on :11434 — but the real HTTP call still
        // costs boot latency on every autotest run and creates a path
        // for external-state flakiness (Ollama busy/slow → slow boot).
        // Mirrors the test-mode skip in DbSchedulerBootstrapJob.
        if (Play.runningInTestMode()) return;
        ProbeJobs.run("ollama-local", "provider.ollama-local.baseUrl",
                "Install Ollama with: brew install ollama (macOS), "
                        + "curl https://ollama.com/install.sh | sh (Linux), or download "
                        + "the Windows installer from ollama.com.",
                baseUrl -> {
                    var r = OllamaLocalProbe.probe(baseUrl);
                    return new ProbeJobs.Outcome(r.available(), r.modelCount(), r.reason(), r.connectionRefused());
                });
    }
}
