package jobs;

import play.jobs.Job;
import services.EventLogger;
import utils.JvmShutdown;

/**
 * The base of every job Play runs on a schedule ({@code @Every} or {@code @On}). Between SIGTERM and
 * Play stopping its jobs, H2's exit hook has already closed the database (see conf/application.conf),
 * so a tick then is skipped, and one the shutdown cut short is one INFO line, not two ERROR stacks (JF-23).
 */
public abstract class PeriodicJob extends Job<Void> {

    /** Skips the whole invocation, so Play's filters never open a transaction on the closed database. */
    @Override
    public boolean init() {
        return !stopping() && super.init();
    }

    @Override
    public void onException(Throwable e) {
        if (!stopping()) {
            super.onException(e);
            return;
        }
        EventLogger.info("shutdown", "%s: tick cut short by the shutdown: %s"
                .formatted(getClass().getSimpleName(), EventLogger.rootCause(e)));
    }

    /** Whether the JVM is shutting down; a test overrides it, since it cannot stop its own JVM. */
    protected boolean stopping() {
        return JvmShutdown.inProgress();
    }
}
