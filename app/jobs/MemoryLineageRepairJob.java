package jobs;

import models.Memory;
import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.Tx;

/**
 * JCLAW-529: null supersession pointers whose target row is gone, once per boot.
 *
 * <p>{@code Memory.supersededById} is a bare column with no foreign key — only
 * {@code agent_id} carries one — so before {@link Memory#deleteWithLineage()} every
 * delete could leave retired rows pointing at an id that no longer exists. A live store
 * had 265 of them, each a lineage chain that cannot be walked to its surviving
 * descendant. {@code deleteWithLineage} stops new ones; this repairs the ones already
 * written.
 *
 * <p>Recall filters on {@code supersededAt}, and its cluster expansion follows the pointer
 * only to live rows, so a dangling one hides nothing from it: this fixes an audit question
 * rather than a retrieval one, and is safe to run at any time.
 *
 * <p>Idempotent, and cheap once it has run: the {@code WHERE} matches nothing on a
 * repaired store, so the steady-state cost is one indexed-scan UPDATE per boot. Left in
 * place rather than deleted after one release for the same reason
 * {@link CascadeFkMigrationJob} is — a database restored from an older backup, or a
 * deployment that skipped a version, arrives needing it again.
 */
@OnApplicationStart
public class MemoryLineageRepairJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try {
            int repaired = Tx.run(Memory::clearDanglingSupersessionPointers);
            if (repaired > 0) {
                EventLogger.info("memory", null, null,
                        "Cleared %d supersession pointer(s) whose target row no longer exists"
                                .formatted(repaired));
            }
        } catch (Exception e) {
            // A cosmetic lineage repair must never keep the application from starting.
            EventLogger.warn("memory", null, null,
                    "Supersession pointer repair failed: %s".formatted(e.getMessage()));
        }
    }
}
