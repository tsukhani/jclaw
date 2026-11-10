package services;

import play.Logger;
import play.Play;
import play.PlayPlugin;
import utils.AotTraining;
import utils.RestartNarrator;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

/**
 * Takes the port over from the restart narrator just before Play binds (JF-273). Ordered after JobsPlugin, whose start
 * jobs are the last work before the bind, so the narrator answers through the whole boot. Framework and JDK types only:
 * a {@code conf/play.plugins} plugin compiles before the application, and a wide reference graph breaks that pass.
 */
public class RestartHandoverPlugin extends PlayPlugin {

    private static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(20);

    @Override
    public void afterApplicationStart() {
        // A training run boots on spare ports while the narrator holds the real one.
        if (Play.runningInTestMode() || AotTraining.thisJvm()) return;
        var logs = Play.applicationPath.toPath().resolve("logs");
        long started = System.nanoTime();
        try {
            switch (RestartNarrator.awaitHandover(logs.resolve("restart-narrator.pid"),
                    logs.resolve(RestartNarrator.HANDOVER_FILE), BUDGET_NANOS)) {
                case TAKEN -> Logger.info("Took the port over from the restart narrator in %d ms",
                        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
                case TIMED_OUT -> Logger.warn("jclaw.sh did not hand over the port within %d s; binding anyway",
                        TimeUnit.NANOSECONDS.toSeconds(BUDGET_NANOS));
                case NONE -> { }
            }
        } catch (IOException e) {
            Logger.warn("Could not ask jclaw.sh for the port: %s", e.getMessage());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
