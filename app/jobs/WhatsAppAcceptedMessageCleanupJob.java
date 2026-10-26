package jobs;

import channels.WhatsAppChannel;
import models.WhatsAppAcceptedMessage;
import play.jobs.Every;
import play.jobs.OnApplicationStart;
import services.EventLogger;
import services.Tx;
import utils.AppClock;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * JCLAW-1520: prunes {@link WhatsAppAcceptedMessage} rows past
 * {@code whatsapp.acceptedMessages.retentionDays}. A row must outlive Meta's redelivery window
 * ({@link WhatsAppChannel#REPLAY_WINDOW_SECONDS}): a pruned id whose message is still inside it
 * would be dispatched again, so a configured value below {@link #MIN_RETENTION_DAYS} is raised to it.
 */
// JCLAW-1067: @Every alone first fires a full interval after boot, so a 24h period
// never elapses on an instance restarted more often than daily.
@OnApplicationStart(async = true)
@Every("24h")
public class WhatsAppAcceptedMessageCleanupJob extends PeriodicJob {

    private static final String EVENT_CATEGORY = "WHATSAPP_CLEANUP";
    private static final String CONFIG_KEY = "whatsapp.acceptedMessages.retentionDays";

    /** One day past the replay window, so no id is pruned while its message could still be accepted. */
    public static final int MIN_RETENTION_DAYS =
            (int) Duration.ofSeconds(WhatsAppChannel.REPLAY_WINDOW_SECONDS).toDays() + 1;

    /** Default retention window when the config key is absent. */
    public static final int DEFAULT_RETENTION_DAYS = MIN_RETENTION_DAYS;

    private static final int MAX_RETENTION_DAYS = 3650;

    @Override
    public void doJob() {
        var retentionDays = resolveRetentionDays();
        if (retentionDays == RetentionDays.DISABLED) return;

        var cutoff = AppClock.now().minus(retentionDays, ChronoUnit.DAYS);
        int deleted = Tx.run(() -> WhatsAppAcceptedMessage.delete("acceptedAt < ?1", cutoff));
        if (deleted > 0) {
            EventLogger.info(EVENT_CATEGORY, null, null,
                    "Deleted %d accepted WhatsApp message id(s) older than %d day(s) (cutoff=%s)"
                            .formatted(deleted, retentionDays, cutoff));
        }
    }

    /**
     * Read {@code whatsapp.acceptedMessages.retentionDays} through {@link RetentionDays}; a value
     * from 1 to {@link #MIN_RETENTION_DAYS} - 1 is raised to {@link #MIN_RETENTION_DAYS} with a warn.
     * Public for direct test access.
     */
    public static int resolveRetentionDays() {
        var days = RetentionDays.fromConfig(CONFIG_KEY, DEFAULT_RETENTION_DAYS, MAX_RETENTION_DAYS, EVENT_CATEGORY);
        if (days == RetentionDays.DISABLED || days >= MIN_RETENTION_DAYS) return days;
        EventLogger.warn(EVENT_CATEGORY, "%s is %d, inside the %d-day redelivery window; using %d"
                .formatted(CONFIG_KEY, days, MIN_RETENTION_DAYS - 1, MIN_RETENTION_DAYS));
        return MIN_RETENTION_DAYS;
    }
}
