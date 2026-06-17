package jobs;

import play.jobs.Job;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.LoggerLevelService;

/**
 * Replay persisted per-logger level overrides at startup.
 *
 * <p>Runs at {@code @OnApplicationStart}, after Play's log4j init has loaded the
 * {@code log4j2.xml} / {@code application.conf} configuration. Re-applying the
 * operator's {@code logging.level.*} Config rows here is what makes the
 * overrides both survive a restart and win over the file configuration — they
 * are applied last. See {@link LoggerLevelService}.
 */
@OnApplicationStart
public class LoggerLevelInitJob extends Job<Void> {

    @Override
    public void doJob() {
        try {
            LoggerLevelService.applyAllFromConfig();
        } catch (Exception e) {
            EventLogger.warn("logging",
                    "Failed to apply log-level overrides at startup: " + e.getMessage());
        }
    }
}
