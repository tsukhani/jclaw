package jobs;

import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.search.MessageSearch;

/**
 * JCLAW-21: initialise full-text search at {@code @OnApplicationStart}.
 * {@link MessageSearch#init} picks the dialect-appropriate
 * {@code MessageSearchRepository} and runs its idempotent setup:
 *
 * <ul>
 *   <li><b>H2 path</b>: calls {@code FullTextLucene.init} (creates
 *       the {@code FT_*} stored-procedure aliases if missing) and
 *       {@code FullTextLucene.createIndex} (installs INSERT /
 *       UPDATE / DELETE triggers on {@code task_run_message}). The
 *       trigger creation includes a one-shot
 *       {@code SELECT * FROM table} sweep so existing rows from
 *       before the index existed get indexed immediately.</li>
 *   <li><b>Postgres path</b>: currently a logged no-op — the
 *       {@code search_vector tsvector} column + GIN index + update
 *       trigger DDL ships when an operator first deploys against
 *       Postgres.</li>
 * </ul>
 *
 * <p>Re-running on every boot costs microseconds when the schema
 * is already in place (both backends' {@code init} are idempotent).
 *
 * <h3>Test-mode skip</h3>
 * Play 1.x's {@code Fixtures.deleteDatabase} iterates every table
 * the JDBC metadata exposes and runs an unqualified
 * {@code DELETE FROM &lt;name&gt;}. FullTextLucene creates an
 * {@code INDEXES} table in the {@code FT} schema; the deleteDatabase
 * call hits {@code DELETE FROM INDEXES} (schema-less) and fails when
 * {@code INDEXES} isn't in the default schema search path —
 * cascading across every test class that calls
 * {@code Fixtures.deleteDatabase} in {@code @BeforeEach}.
 *
 * <p>Skipping init in test mode keeps the bulk of the test suite
 * green; tests that need full-text (the H2 repo tests) run their
 * own setup/teardown via {@code FullTextLucene.dropAll} so they
 * don't leak FT artifacts across class boundaries.
 *
 * <p>Sibling to {@link DbSchedulerSchemaInitJob} — both are
 * {@code @OnApplicationStart} schema-readiness jobs. Play 1.x
 * doesn't strictly order siblings, but neither depends on the
 * other: db-scheduler's {@code scheduled_tasks} table is separate
 * from {@code task_run_message} where the FT index lives.
 */
@OnApplicationStart
public class FullTextSearchInitJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            // Don't create FT artifacts in test mode — see class
            // javadoc for the Fixtures.deleteDatabase clash.
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
