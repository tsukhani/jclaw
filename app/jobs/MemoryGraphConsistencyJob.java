package jobs;

import memory.graph.GraphLifecycle;
import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

/**
 * Repairs the memory graph once per boot: an interrupted swap, a directory whose agent is
 * gone, Evidence whose memory was deleted or superseded while no commit hook ran.
 */
@OnApplicationStart
public class MemoryGraphConsistencyJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try {
            var result = GraphLifecycle.reconcile();
            if (result.recovered() + result.orphansDeleted() + result.recordsRemoved() > 0) {
                EventLogger.info("memory", null, null,
                        "Memory graph reconciled across %d agent(s): %d swap(s) recovered, %d orphaned graph(s) deleted, %d record(s) withdrawn"
                                .formatted(result.agents(), result.recovered(), result.orphansDeleted(),
                                        result.recordsRemoved()));
            }
        } catch (Exception e) {
            // A graph repair must never keep the application from starting.
            EventLogger.warn("memory", null, null,
                    "Memory graph consistency pass failed: %s".formatted(e.getMessage()));
        }
    }
}
