package jobs;

import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.TaskRunReports;

/** Subscribes {@link TaskRunReports} to the bus, so a run started from a chat reports back there. */
@OnApplicationStart
public class TaskRunReportsJob extends Job<Void> {

    @Override
    public void doJob() {
        TaskRunReports.install();
    }
}
