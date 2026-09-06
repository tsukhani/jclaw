package jobs;

import memory.JpaMemoryStore;
import memory.MemoryReembedService;
import memory.MemoryStore;
import memory.MemoryStoreFactory;
import memory.MemoryVectorSettings;
import models.Memory;
import org.jspecify.annotations.Nullable;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

/**
 * JCLAW-942: warn when {@code memory.recall.minCosine} sits above the scale the configured
 * embedding model actually produces, which silently reduces recall to its keyword leg.
 *
 * <p>The floor is compared against a cosine, and cosine distributions are a property of the
 * model and of whether queries carry an instruction prefix — not of the code. The shipped
 * default (0.60) was swept on nomic-embed-text-v1.5 embedding queries bare; the same corpus
 * under snowflake-arctic-embed with a query prefix tops out near 0.35, where that default
 * rejects every vector hit on every query. {@code luceneHybridSearch} then returns the FTS
 * leg alone and reports success, so the loss shows up as vaguely worse answers rather than
 * as an error — the failure this job exists to name.
 *
 * <p>The probe is a stored memory's own text used as a query. That is deliberately not a
 * degenerate query, which is what makes the result decidable: the floor is <em>designed</em>
 * to reject nonsense, so a low cosine on "hey" proves nothing, while a low cosine on text
 * that is verbatim in the corpus proves the floor is unreachable. It does not verify the
 * memory retrieves <em>itself</em> — only that something clears the bar — because a near
 * duplicate scoring higher is a healthy corpus, not a fault.
 *
 * <p>Advisory: it logs and returns. A floor that is merely too high still serves keyword
 * recall, so refusing to boot over it would turn degraded retrieval into an outage.
 */
@OnApplicationStart(async = true)
@NoTransaction
public class MemoryRecallFloorCheckJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        if (!MemoryVectorSettings.enabled()) return;
        // Vectors are mid-migration until the backfill marker is set; probing now would
        // measure the old model's embeddings against the new model's query.
        if (!MemoryReembedService.upToDate()) return;

        var probe = Tx.run(() -> Memory.find("order by id desc").<Memory>first());
        if (probe == null || probe.text == null || probe.text.isBlank()) return;
        String agentId = Tx.run(() -> String.valueOf(probe.agent.id));

        double floor = ConfigService.getDouble(
                JpaMemoryStore.KEY_RECALL_MIN_COSINE, JpaMemoryStore.DEFAULT_RECALL_MIN_COSINE);
        var problem = diagnose(MemoryStoreFactory.get(), agentId, probe.text, floor);
        if (problem != null) EventLogger.error("memory", problem);
    }

    /**
     * The operator-facing problem, or null when the configuration is sound. Separated from
     * {@link #doJob} so the decision is testable: the job itself is skipped in test mode,
     * and a guard against a silent failure that no test can reach is not a guard.
     *
     * <p>A NaN cosine means the vector leg could not run at all — disabled, no provider,
     * empty index — which is a different condition with different causes, so it is not
     * reported here rather than being reported as a floor problem.
     */
    public static @Nullable String diagnose(MemoryStore store, String agentId, String probeText, double floor) {
        double best = store.bestQueryCosine(agentId, probeText);
        if (Double.isNaN(best) || best >= floor) return null;
        return ("Semantic recall is disabled by configuration: the best cosine any stored memory "
                + "reaches for its own text is %.3f, below the %s floor of %.3f. Every query will "
                + "fall back to keyword-only recall. Re-sweep the floor for the current embedding "
                + "model and query prefix, or lower it below %.3f.")
                .formatted(best, JpaMemoryStore.KEY_RECALL_MIN_COSINE, floor, best);
    }
}
