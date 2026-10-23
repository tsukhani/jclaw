package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Every;
import play.jobs.OnApplicationStart;
import services.EmbeddingModelKeepAlive;

/**
 * Hold a locally-served embedding model in memory so no chat turn pays a model load.
 *
 * <p>Recurring rather than boot-only, which is where this differs from
 * {@link OllamaLocalProbeJob}: that probe reports configuration, which does not change while
 * the process runs, whereas residency lapses on its own timer and has to be renewed.
 * Renewing also means warmth survives the model server being restarted underneath a running
 * JClaw.
 *
 * <p>Half-hourly, not minutes: the pin is a directive rather than a touch, so the interval
 * only has to outlast the ways a pin is lost rather than beat an idle timer.
 *
 * <p>Renewed for Ollama too, though it survives more than one might expect — a pinned model
 * stayed resident through a 1.3 GB and a 9 GB model loading beside it. What it does not
 * survive is the model server exiting, which takes every pin with it and is ordinary
 * operation: an update, a reboot, a sleep/wake, a crash. Nothing else would re-pin, so
 * without renewal one Ollama restart silently returns every later idle gap to the 581 ms
 * reload, until JClaw itself is restarted.
 */
@OnApplicationStart(async = true)
@Every("30mn")
@NoTransaction
public class EmbeddingModelKeepAliveJob extends PeriodicJob {

    @Override
    public void doJob() {
        // Same reasoning as OllamaLocalProbeJob: tests point at mock servers, so the real
        // call buys no signal and adds boot latency plus a path for external flakiness.
        if (Play.runningInTestMode()) return;
        EmbeddingModelKeepAlive.pin();
    }
}
