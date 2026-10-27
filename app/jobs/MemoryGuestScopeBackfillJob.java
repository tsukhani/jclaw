package jobs;

import models.Memory;
import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.Tx;

/**
 * JF-147: give each guest memory written before its chat was recorded the channel and peer of its
 * source conversation, once per boot, so it is recalled for that chat again rather than nowhere.
 *
 * <p>Idempotent, like {@link MemoryLineageRepairJob}: a filled row no longer matches, and a row
 * whose conversation is gone stays unfilled and visible nowhere.
 */
// After MemoryAuthorTypeColumnJob (priority 0): H2 refuses GUEST_TURN in a comparison against the ENUM column it widens.
@OnApplicationStart(priority = 1)
public class MemoryGuestScopeBackfillJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try {
            int filled = Tx.run(Memory::backfillGuestScope);
            if (filled > 0) {
                EventLogger.info("memory", null, null,
                        "Recorded the source chat of %d guest memory(ies)".formatted(filled));
            }
        } catch (Exception e) {
            // Unfilled rows are recalled nowhere, never more widely, so a failure must not stop the boot.
            EventLogger.warn("memory", null, null,
                    "Guest memory scope backfill failed: %s".formatted(e.getMessage()));
        }
    }
}
