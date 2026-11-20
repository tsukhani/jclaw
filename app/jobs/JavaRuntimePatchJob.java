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
        // The suite runs from a checkout, and a test's environment seam must never reach a vendor's catalog from here.
        if (Play.runningInTestMode()) return;
        var env = JavaRuntimeService.environment();
        JavaRuntimePatchCheck.run(env, () -> JavaRuntimeService.check(env), JavaRuntimeService.CURRENT,
                JavaRuntimeService::download, Agent.MAIN_AGENT_NAME);
    }
}
