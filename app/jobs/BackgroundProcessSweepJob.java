package jobs;

import models.Agent;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Every;
import services.BackgroundProcesses;
import services.Tx;

import java.util.ArrayList;

/** Stops background processes past their lifetime and bounds their output files (JCLAW-1565). */
@Every("10mn")
@NoTransaction
public class BackgroundProcessSweepJob extends PeriodicJob {

    @Override
    public void doJob() {
        if (Play.runningInTestMode()) return;
        // A subagent works in its root agent's workspace, so the root agents cover every workspace.
        var roots = Tx.run(() -> {
            var found = new ArrayList<Agent>();
            for (Object agent : Agent.find("parentAgent is null").fetch()) found.add((Agent) agent);
            return found;
        });
        BackgroundProcesses.sweep(roots);
    }
}
