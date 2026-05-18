package jobs;

import com.github.kagkarlsson.scheduler.Scheduler;
import play.db.DB;
import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.TaskExecutionHandler;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * JCLAW-21: build the db-scheduler {@link Scheduler}, register
 * {@link TaskExecutionHandler}, and start polling.
 *
 * <p>Runs on {@code @OnApplicationStart} but is ordered after
 * {@link DbSchedulerSchemaInitJob} only by virtue of explicitly
 * calling {@link DbSchedulerSchemaInitJob#ensureSchema} as its first
 * step — Play 1.x doesn't guarantee startup-job ordering, so we
 * make the dependency explicit instead of relying on declaration
 * order.
 *
 * <h3>Configuration</h3>
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
 * <h3>Migration of pre-cutover PENDING Tasks</h3>
 * Pre-cutover Tasks (created before this scheduler was wired in)
 * exist as PENDING rows with no corresponding {@code scheduled_tasks}
 * row. {@link BootConsistencyCheck} runs as a separate
 * {@code @OnApplicationStart} job and registers them with
 * {@link services.TaskSchedulingService} so they don't sit stranded.
 * The crash-recovery case (Task persisted but register() never
 * ran) is closed by the same sweep.
 *
 * <p>Static {@link #scheduler} reference exposed so
 * {@link DbSchedulerShutdownJob} can stop the same instance, and
 * future commits' {@code TaskSchedulingService} can read it for
 * {@code SchedulerClient.schedule}-style operations.
 */
@OnApplicationStart
public class DbSchedulerBootstrapJob extends Job<Void> {

    /**
     * The running {@link Scheduler} instance. {@code volatile} because
     * boot runs on the startup thread while
     * {@link DbSchedulerShutdownJob}'s {@code @OnApplicationStop}
     * callback runs on a different thread.
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
        // Defense-in-depth: re-run the DDL idempotency check. The schema-init
        // job runs in the same @OnApplicationStart phase but the framework
        // doesn't strictly order siblings, so we re-assert the schema rather
        // than depend on it.
        DbSchedulerSchemaInitJob.ensureSchema();

        var datasource = DB.datasource;
        if (datasource == null) {
            EventLogger.error("task", null, null,
                    "DbSchedulerBootstrap: no DataSource available — db-scheduler will not start");
            return;
        }

        var jclawTask = TaskExecutionHandler.buildTask();

        var built = Scheduler.create(datasource, List.of(jclawTask))
                .pollingInterval(Duration.ofSeconds(2))
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
                .commitWhenAutocommitDisabled(true)
                .build();

        TaskExecutionHandler.setSchedulerClient(built);
        scheduler = built;
        built.start();

        EventLogger.info("task", null, null,
                "db-scheduler started (polling 2s, virtual-thread executor, "
                + "registered task '%s')".formatted(TaskExecutionHandler.TASK_NAME));

        // Run the consistency sweep inline now that the scheduler is
        // alive. Pre-fix this was a standalone @OnApplicationStart
        // job, but Play 1.x doesn't order startup jobs deterministically
        // — on some restarts BootConsistencyCheck fired BEFORE this
        // bootstrap and logged "scheduler not bootstrapped; skipping
        // sweep", stranding existing PENDING Tasks. Calling sweep
        // here makes the ordering explicit and removes that race.
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
}
