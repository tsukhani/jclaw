package jobs;

import memory.PgVectorProvisioner;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Job;
import play.jobs.OnApplicationStart;

/**
 * JCLAW-528: provision the pgvector schema at {@code @OnApplicationStart} when
 * vector memory is enabled, so a missing extension or privilege surfaces in the
 * boot log — not at the first memory write, which may be hours later.
 *
 * <p>All the real logic (Postgres-only guard, idempotent DDL, loud failure
 * logging) lives in {@link PgVectorProvisioner#ensureProvisioned()}.
 * {@link memory.JpaMemoryStore} re-runs the same idempotent step when it is
 * constructed, so nothing depends on this job's ordering relative to other
 * {@code @OnApplicationStart} siblings — the job exists purely for early
 * operator feedback (the {@link DbSchedulerSchemaInitJob} /
 * {@link DbSchedulerBootstrapJob} pattern).
 *
 * <p>{@code @NoTransaction} because DDL should not run inside a JPA
 * transaction; the provisioner manages its own raw connection. Skipped in test
 * mode: dev/test run on H2 where the step is a no-op by design (the Lucene
 * HNSW backend serves vectors there), and the two-arg test constructor of
 * {@code JpaMemoryStore} bypasses provisioning deliberately.
 */
@OnApplicationStart
@NoTransaction
public class PgVectorSchemaInitJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        if (!"true".equals(Play.configuration.getProperty("memory.jpa.vector.enabled", "false"))) {
            return;
        }
        PgVectorProvisioner.ensureProvisioned();
    }
}
