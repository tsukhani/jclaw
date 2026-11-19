package jobs;

import models.Agent;
import play.Play;
import play.db.jpa.NoTransaction;
import play.jobs.Every;
import play.jobs.OnApplicationStart;
import services.JavaRuntimePatchCheck;
import services.JavaRuntimeService;

/** Once a day, {@link JavaRuntimePatchCheck}: a newer patch of JClaw's own Java is staged, any other is announced. */
// @Every alone first fires a full interval after boot, so a 24h period never elapses on an instance
// restarted more often than daily.
@OnApplicationStart(async = true)
@Every("24h")
@NoTransaction
public class JavaRuntimePatchJob extends PeriodicJob {

    @Override
    public void doJob() {
        // The suite runs from a checkout, and a test's environment seam must never reach Azul from here.
        if (Play.runningInTestMode()) return;
        JavaRuntimePatchCheck.run(JavaRuntimeService.environment(), () -> JavaRuntimeService.latest(true),
                JavaRuntimeService.CURRENT, JavaRuntimeService::download, Agent.MAIN_AGENT_NAME);
    }
}
