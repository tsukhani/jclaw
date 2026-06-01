package jobs;

import play.Play;
import play.jobs.Job;
import play.jobs.OnApplicationStop;
import services.search.LuceneIndexer;

/**
 * Close the Lucene index cleanly at JVM shutdown. Commits any
 * in-flight writes and releases the {@code write.lock} so the next
 * boot opens the directory without contention. Mirrors the
 * {@code @OnApplicationStop} hook in {@link ShutdownJob} that calls
 * {@code DbSchedulerBootstrapJob.shutdownGracefully} for db-scheduler.
 *
 * <p>Skipped in test mode for the same reason
 * {@link FullTextSearchInitJob} skips init there: the test JVM
 * never opens the FSDirectory, so there's nothing to close.
 */
@OnApplicationStop
public class FullTextSearchShutdownJob extends Job<Void> {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) {
            return;
        }
        // Mark shutdown before LuceneIndexer.close() logs "Lucene index closed":
        // this hook often runs before ShutdownJob, so it's the first chance to
        // flip EventLogger to file-only and keep shutdown logs off the doomed
        // batched DB flush. Idempotent with ShutdownJob's identical call.
        services.EventLogger.markShuttingDown();
        LuceneIndexer.close();
    }
}
