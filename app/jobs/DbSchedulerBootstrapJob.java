package jobs;

import com.github.kagkarlsson.scheduler.Scheduler;
import com.github.kagkarlsson.scheduler.SchedulerBuilder;
import com.github.kagkarlsson.scheduler.jdbc.DefaultJdbcCustomization;
import play.db.DB;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.TaskExecutionHandler;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * JCLAW-21: build the db-scheduler {@link Scheduler}, register
 * {@link TaskExecutionHandler}, and start polling.
 *
 * <p>Runs LAST among {@code @OnApplicationStart} jobs
 * ({@code priority = 100}; play1 1.13.27+ runs startup jobs in ascending
 * priority order). Starting the scheduler last guarantees that everything an
 * overdue boot-time fire might touch is already in place before any task can
 * fire: seeded config ({@link DefaultConfigJob}, priority -100), the built-in
 * tool registry ({@link ToolRegistrationJob}), and the {@code scheduled_tasks}
 * schema ({@link DbSchedulerSchemaInitJob}) all run at lower priorities. The
 * {@link DbSchedulerSchemaInitJob#ensureSchema} call below is retained as a
 * cheap defensive re-assert.
 *
 * <h2>Configuration</h2>
 * <ul>
 *   <li><b>DataSource</b>: Play's pool (read via {@link DB#datasource}).</li>
 *   <li><b>Executor</b>: {@link Executors#newVirtualThreadPerTaskExecutor()}
 *       — task fires are LLM-bound (tens of seconds wall-clock) and
 *       virtual threads unmount during blocking I/O, so a single fire
 *       in flight doesn't tie up a platform thread.</li>
 *   <li><b>Polling interval</b>: 2 seconds — the spec's choice;
 *       short enough that ad-hoc fires (e.g. {@code TaskSchedulingService.runNow})
 *       feel snappy, long enough to keep idle CPU at zero.</li>
 *   <li><b>Immediate execution</b>: enabled so {@code runNow} doesn't
 *       have to wait up to one polling interval.</li>
 *   <li><b>Failure handler</b>:
 *       {@link services.JClawFailureHandler} (transient-error
 *       classifier + backoff schedule) is wired into the
 *       {@code Tasks.custom(...)} registration in
 *       {@link services.TaskExecutionHandler#buildTask}.</li>
 * </ul>
 *
 * <h2>Migration of pre-cutover PENDING Tasks</h2>
 * Pre-cutover Tasks (created before this scheduler was wired in)
 * exist as PENDING rows with no corresponding {@code scheduled_tasks}
 * row. {@link BootConsistencyCheck} runs as a separate
 * {@code @OnApplicationStart} job and registers them with
 * {@link services.TaskSchedulingService} so they don't sit stranded.
 * The crash-recovery case (Task persisted but register() never
 * ran) is closed by the same sweep.
 *
 * <p>Static {@link #scheduler} reference exposed so {@link ShutdownJob}
 * can stop the same instance (via {@link #shutdownGracefully}), and so
 * {@link services.TaskSchedulingService} can read it for
 * {@code SchedulerClient.schedule}-style operations.
 */
@OnApplicationStart(priority = 100)
public class DbSchedulerBootstrapJob extends Job<Void> {

    /**
     * The running {@link Scheduler} instance. {@code volatile} because
     * boot runs on the startup thread while {@link ShutdownJob}'s
     * {@code @OnApplicationStop} callback (which fans out to
     * {@link #shutdownGracefully}) runs on a different thread.
     */
    // Publish-once-read-many handoff between the @OnApplicationStart
    // bootstrap and the @OnApplicationStop shutdown hook. S3077 targets
    // compound mutation on volatile non-primitives; a pure reference
    // handoff is the canonical valid use of volatile.
    @SuppressWarnings("java:S3077")
    private static volatile Scheduler scheduler;

    public static Scheduler scheduler() {
        return scheduler;
    }

    @Override
    @SuppressWarnings("java:S2696") // Static scheduler handoff is intentional: shutdown hook needs the same instance
    public void doJob() throws Exception {
        // Skip in test mode. Fixtures.deleteDatabase wipes the task table
        // between tests but cannot touch db-scheduler's scheduled_tasks
        // table (non-JPA, created by DbSchedulerSchemaInitJob). A live
        // scheduler polling at 2s intervals fires orphan rows pointing
        // at deleted Tasks, surfacing as NPEs in TaskExecutor and
        // cascading into MCP_SERVER row-lock timeouts on subsequent
        // Fixtures.deleteDatabase calls. Mirrors FullTextSearchInitJob's
        // test-mode skip.
        if (play.Play.runningInTestMode()) {
            EventLogger.info("task", null, null,
                    "DbSchedulerBootstrap: skipping scheduler start in test mode");
            return;
        }

        // Defense-in-depth: re-assert the scheduled_tasks DDL. Priority ordering
        // (this job runs last) already means DbSchedulerSchemaInitJob has run,
        // but the idempotency check is cheap and keeps the bootstrap robust if
        // the schema job is ever reordered or removed.
        DbSchedulerSchemaInitJob.ensureSchema();

        var datasource = DB.getDataSource();
        if (datasource == null) {
            EventLogger.error("task", null, null,
                    "DbSchedulerBootstrap: no DataSource available — db-scheduler will not start");
            return;
        }

        var jclawTask = TaskExecutionHandler.buildTask();

        SchedulerBuilder builder = Scheduler.create(datasource, List.of(jclawTask))
                .pollingInterval(Duration.ofSeconds(2))
                // JCLAW-258: shorten heartbeat from db-scheduler's default to
                // 30 s so a crash-interrupted fire surfaces faster. 30 s × 4
                // missed = 120 s before db-scheduler declares an execution
                // dead and re-fires it; LostTaskDetector flips the visible
                // Task.status to LOST at the 60 s mark (2 missed heartbeats),
                // so operator visibility consistently leads scheduler
                // recovery by roughly 60 s. Floor of missedHeartbeatsLimit
                // is 4 in db-scheduler 16.9 — pinning to the floor (rather
                // than 2 to mirror the visibility threshold) keeps the
                // safety margin db-scheduler's authors mandate for the
                // re-fire path.
                .heartbeatInterval(Duration.ofSeconds(30))
                .missedHeartbeatsLimit(4)
                .executorService(Executors.newVirtualThreadPerTaskExecutor())
                .enableImmediateExecution()
                // db-scheduler's JdbcRunner inspects connection.getAutoCommit()
                // to decide whether to commit. Play 1.x's Hibernate
                // configuration sets autoCommit=false on its Hikari pool
                // connections (because Hibernate manages JPA transactions).
                // When db-scheduler grabs a connection from that pool, it
                // sees autoCommit=false and — without this flag — assumes
                // "the transaction is externally managed" and silently
                // SKIPS the commit. The INSERT into scheduled_tasks runs
                // but is then rolled back when Hikari recycles the
                // connection, so client.schedule() appears to succeed but
                // no row lands. .commitWhenAutocommitDisabled(true) tells
                // db-scheduler to always commit/rollback explicitly even
                // when autoCommit is off — which is what we need since
                // db-scheduler is the EXTERNAL caller from JPA's view.
                .commitWhenAutocommitDisabled(true);

        // db-scheduler's AutodetectJdbcCustomization only switches on
        // MSSQL/Postgres/Oracle/MariaDB/MySQL; H2 falls through to the
        // default and a SILENCABLE warn fires each boot ("No
        // database-specific jdbc-overrides applied..."). Our
        // scheduled_tasks DDL uses TIMESTAMP WITH TIME ZONE (zone-aware),
        // which DefaultJdbcCustomization(persistTimestampInUTC=false)
        // handles correctly via setObject(OffsetDateTime). The warn's
        // suggested .alwaysPersistTimestampInUTC() would actually be
        // wrong here — that flips to a setTimestamp(..., UTC_Calendar)
        // path intended for zone-less TIMESTAMP columns.
        //
        // We only wire the explicit customization on H2 so that an
        // operator switching to Postgres via %prod.db.url still gets
        // PostgreSqlJdbcCustomization, which provides the single-statement
        // claim-and-fetch (UPDATE ... RETURNING with FOR UPDATE SKIP LOCKED).
        if (isH2(datasource)) {
            builder = builder.jdbcCustomization(new DefaultJdbcCustomization(false));
        }

        var built = builder.build();

        // JCLAW-411/413: built-in tools are guaranteed registered before this
        // point — ToolRegistrationJob runs at default priority (0), this job at
        // 100 — so an overdue boot-time fire no longer races an empty
        // ToolRegistry. (Pre-1.13.27 this job called ToolRegistrationJob.registerAll()
        // inline as a workaround for the lack of startup-job ordering.)
        TaskExecutionHandler.setSchedulerClient(built);
        scheduler = built;
        built.start();

        EventLogger.info("task", null, null,
                "db-scheduler started (polling 2s, virtual-thread executor, "
                + "registered task '%s')".formatted(TaskExecutionHandler.TASK_NAME));

        // Run the consistency sweep inline now that the scheduler is alive.
        // Even with startup-job priority ordering (1.13.27+), the sweep stays
        // inline because it needs the live SchedulerClient ('built') we just
        // created — not merely a guarantee of running after this job. (Pre-1.13.27
        // it was also the only way to dodge the unordered-sibling race that left
        // it logging "scheduler not bootstrapped; skipping sweep".)
        try {
            BootConsistencyCheck.sweep(built);
        } catch (Exception e) {
            // Don't crash the bootstrap if sweep throws — the scheduler
            // is already alive and TaskSchedulingService.register
            // calls from CRUD operations will keep the system live for
            // newly-created Tasks. Pre-existing PENDING orphans get
            // picked up on the next restart.
            EventLogger.warn("task", null, null,
                    "BootConsistencyCheck.sweep raised at startup: %s".formatted(e.getMessage()));
        }
    }

    /**
     * Graceful stop hook for {@link ShutdownJob}. Plugged into the
     * existing parallel-component shutdown list rather than a separate
     * {@code @OnApplicationStop} job so the existing 15-second
     * overall-timeout ceiling covers it and so two competing shutdown
     * windows aren't fighting for Play's 30s scheduler budget.
     *
     * <p>{@link Scheduler#stop} blocks until in-flight fires finish or
     * the executor times out (db-scheduler's internal default is 30s
     * which is past our ceiling, but the parallel-VT pattern in
     * {@code ShutdownJob} means our 15s overall-cap still bounds the
     * wall-clock — db-scheduler's stop runs in a sibling VT alongside
     * the other components and gets cut off by the latch await).
     */
    public static void shutdownGracefully() {
        Scheduler local = scheduler;
        if (local == null) return;
        try {
            local.stop();
        } catch (Exception e) {
            EventLogger.warn("task", null, null,
                    "db-scheduler stop raised: %s".formatted(e.getMessage()));
        } finally {
            scheduler = null;
        }
    }

    private static boolean isH2(javax.sql.DataSource datasource) {
        try (Connection conn = datasource.getConnection()) {
            return conn.getMetaData().getDatabaseProductName()
                    .toLowerCase().contains("h2");
        } catch (SQLException _) {
            // Conservative fallback: if we can't read metadata, let
            // AutodetectJdbcCustomization run (and emit its warn) rather
            // than mis-pin the customization.
            return false;
        }
    }
}
