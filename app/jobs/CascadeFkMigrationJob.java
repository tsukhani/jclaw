package jobs;

import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;

import java.sql.SQLException;

/**
 * JCLAW-135: run the ownership-FK cascade migration at
 * {@code @OnApplicationStart} so a pre-existing database whose foreign keys were
 * created without {@code ON DELETE CASCADE} is brought up to the cascade
 * contract JCLAW-542 relies on. Idempotent and near-instant when the FKs
 * already cascade (fresh installs, dev DB), so it's cheap to run on every boot.
 *
 * <p>All logic lives in {@link CascadeFkMigrator#ensureCascades()}; this class
 * is the thin boot hook that skips test mode and lets a test call the migrator
 * directly. {@code @NoTransaction} because the DDL must not run inside a JPA
 * transaction — the migrator manages its own raw connection and commits it
 * (mirroring {@link DbSchedulerSchemaInitJob} / {@link PgVectorSchemaInitJob}).
 */
@OnApplicationStart
@NoTransaction
public class CascadeFkMigrationJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        try {
            CascadeFkMigrator.ensureCascades();
        } catch (SQLException e) {
            EventLogger.error("system",
                    "Ownership FK cascade migration failed: " + e.getMessage());
            throw new IllegalStateException("Ownership FK cascade migration failed", e);
        }
    }
}
