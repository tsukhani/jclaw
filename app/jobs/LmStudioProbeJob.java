package jobs;

import play.Logger;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.LmStudioProbe;
import services.Tx;

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
        var baseUrl = Tx.run(() -> ConfigService.get("provider.lm-studio.baseUrl"));
        if (baseUrl == null || baseUrl.isBlank()) {
            return;
        }
        var r = LmStudioProbe.probe(baseUrl);
        if (r.available()) {
            Logger.info("lm-studio: reachable at %s — %d model%s available",
                    baseUrl, r.modelCount(), r.modelCount() == 1 ? "" : "s");
        } else if (r.connectionRefused()) {
            Logger.debug("lm-studio: %s", r.reason());
        } else {
            Logger.warn("lm-studio: %s. Agents bound to lm-studio will fail to send messages. "
                    + "Download LM Studio from https://lmstudio.ai, load a model in the My Models "
                    + "tab, then start the local server from the Server tab (default port 1234).",
                    r.reason());
        }
    }
}
