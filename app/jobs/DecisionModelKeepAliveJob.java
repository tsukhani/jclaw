package jobs;

import llm.routing.RouterClassifier;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Every;
import play.jobs.Job;
import play.jobs.OnApplicationStart;

/**
 * Keep the router's Ollama classifier model loaded (JCLAW-1338), and unload the decision models it
 * no longer uses (JCLAW-1339). Recurring for the reason {@link EmbeddingModelKeepAliveJob} is: an
 * Ollama restart drops every pin, and the classifier's own call, cut off at its timeout, cannot
 * always finish a cold load.
 */
@OnApplicationStart(async = true)
@Every("30mn")
@NoTransaction
public class DecisionModelKeepAliveJob extends Job<Void> {

    @Override
    public void doJob() {
        // Tests point at canned transports; a real call buys no signal and adds external flakiness.
        if (Play.runningInTestMode()) return;
        RouterClassifier.settleOllamaModelsInBackground();
    }
}
