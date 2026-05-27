package jobs;

import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.search.MessageSearch;

/**
 * Initialise full-text search at {@code @OnApplicationStart}.
 * {@link MessageSearch#init} picks the {@code MessageSearchRepository}
 * implementation and runs its idempotent setup:
 *
 * <ul>
 *   <li><b>Direct Lucene 10 path (default)</b>: opens an FSDirectory
 *       under {@code data/jclaw-lucene/task_run_message/} via
 *       {@link services.search.LuceneIndexer#open}. On first boot —
 *       or any boot after the directory was wiped — backfills the
 *       index from every existing {@code TaskRunMessage} row. JPA
 *       lifecycle hooks on {@code TaskRunMessage} keep it in sync
 *       thereafter (no DB triggers involved).</li>
 *   <li><b>Postgres-native path</b>: opt-in via the
 *       {@code -Djclaw.search.postgres-native=true} system property
 *       when running against the Postgres dialect. Currently a
 *       logged no-op — the {@code search_vector tsvector} column +
 *       GIN index + update trigger DDL ships when an operator first
 *       deploys against that path.</li>
 * </ul>
 *
 * <p>Re-running on every boot costs microseconds when the index is
 * already in place — both backends' {@code init} short-circuit on
 * the second call.
 *
 * <h2>Test-mode skip</h2>
 * Pre-Lucene-10 the test-mode skip was necessary because H2's
 * {@code FullTextLucene} installed FT_* artifacts that clashed with
 * Play 1.x's {@code Fixtures.deleteDatabase}. The direct Lucene path
 * no longer has that interaction (it lives entirely on disk, outside
 * the JDBC metadata Fixtures iterates over), but we keep the skip:
 * autotest runs don't need an open FSDirectory locked against the
 * file, and skipping avoids cross-suite contention when parallel
 * worktrees run autotest.
 *
 * <p>Sibling to {@link DbSchedulerSchemaInitJob} — both are
 * {@code @OnApplicationStart} schema-readiness jobs. Play 1.x
 * doesn't strictly order siblings, but neither depends on the
 * other.
 */
@OnApplicationStart
public class FullTextSearchInitJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            // Skip in test mode — same rationale as
            // DbSchedulerBootstrapJob's test-mode skip.
            return;
        }
        try {
            MessageSearch.init();
        } catch (Exception e) {
            // Don't crash the JVM if FT init fails — searches will
            // return empty (the facade returns List.of() pre-init)
            // and operators can re-run init from the admin path
            // once the underlying problem is fixed. Logging the
            // failure surfaces it to the dashboard's Recent Activity
            // feed for operator-side triage.
            EventLogger.error("search", null, null,
                    "FullTextSearchInitJob: init failed (%s) — searches will return empty until repaired"
                            .formatted(e.getMessage()));
        }
    }
}
