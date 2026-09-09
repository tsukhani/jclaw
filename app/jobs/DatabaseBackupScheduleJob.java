package jobs;

import play.jobs.Every;
import play.jobs.Job;
import services.database.DatabaseService;

/**
 * The optional daily backup (JCLAW-1165): {@code db.backup.schedule} names a time of day in
 * the operator's zone, and this fires the backup in the first minute at or after it. A
 * one-minute Play job rather than a db-scheduler task because the time is an editable config
 * value: a persisted schedule would need re-registering on every change, and a missed day
 * needs no catch-up — the next day's backup is the catch-up.
 */
@Every("1min")
public class DatabaseBackupScheduleJob extends Job<Void> {

    @Override
    public void doJob() {
        DatabaseService.runScheduledBackupIfDue();
    }
}
