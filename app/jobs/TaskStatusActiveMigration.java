package jobs;

import play.db.jpa.JPA;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.ConfigService;
import services.EventLogger;
import services.Tx;

/**
 * One-shot migration that lifts existing recurring Tasks from
 * {@code Status.PENDING} to {@code Status.ACTIVE} after the v0.12.x
 * enum change. Recurring ({@code CRON} / {@code INTERVAL}) tasks were
 * stored as PENDING in their steady state; now they're stored as ACTIVE
 * to distinguish "waiting to fire" (one-shot) from "ongoing recurrence".
 *
 * <p>Runs once at boot under an idempotency marker in {@code ConfigService}
 * — re-running is a no-op (the SQL would update zero rows anyway since
 * the filter selects only PENDING rows). The marker is purely a logging
 * gate so we don't emit the "migration ran" line on every subsequent boot.
 *
 * <p>Removal: safe to delete once every operator install has booted on
 * v0.12.38+ at least once and the marker is set in their Config table.
 * No data depends on this class continuing to exist.
 */
@OnApplicationStart
public class TaskStatusActiveMigration extends Job<Void> {

    private static final String APPLIED_KEY = "migration.taskStatusActive.applied";

    @Override
    public void doJob() {
        if ("true".equals(ConfigService.get(APPLIED_KEY, null))) return;
        int updated = Tx.run(() -> JPA.em()
                .createNativeQuery(
                        "UPDATE task SET status = 'ACTIVE' "
                                + "WHERE status = 'PENDING' "
                                + "AND type IN ('CRON', 'INTERVAL')")
                .executeUpdate());
        ConfigService.set(APPLIED_KEY, "true");
        EventLogger.info("migration", null, null,
                "TaskStatusActiveMigration: %d recurring task(s) migrated PENDING → ACTIVE"
                        .formatted(updated));
    }
}
