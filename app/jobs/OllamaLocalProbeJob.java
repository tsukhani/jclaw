package jobs;

import play.Logger;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.OllamaLocalProbe;
import services.Tx;

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
        // Read config in its own short tx so the JPA carrier is released
        // before the HTTP probe call — the response timeout is several
        // seconds and we don't want the boot-time tx held open across a
        // network round trip.
        var baseUrl = Tx.run(() -> ConfigService.get("provider.ollama-local.baseUrl"));
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        var r = OllamaLocalProbe.probe(baseUrl);
        if (r.available()) {
            Logger.info("ollama-local: reachable at %s — %d model%s available",
                    baseUrl, r.modelCount(), r.modelCount() == 1 ? "" : "s");
        } else if (r.connectionRefused()) {
            Logger.debug("ollama-local: %s", r.reason());
        } else {
            Logger.warn("ollama-local: %s. Agents bound to ollama-local will fail to send messages. "
                    + "Install Ollama with: brew install ollama (macOS), "
                    + "curl https://ollama.com/install.sh | sh (Linux), or download "
                    + "the Windows installer from ollama.com.",
                    r.reason());
        }
    }
}
