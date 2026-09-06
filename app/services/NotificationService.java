package services;

import models.Notification;
import org.jspecify.annotations.Nullable;

/**
 * JCLAW-153: entity-lookup accessor for Notification rows so controllers route
 * their finder calls through the service layer instead of reaching into raw
 * {@code Notification.findById(...)}. Thin passthrough that relies on the
 * caller's ambient JPA transaction — no {@link Tx} wrapper — matching
 * {@link AgentService#findById}.
 */
public final class NotificationService {

    private NotificationService() {}

    /** Play's {@code Model.findById} returns null for a missing row, so this can too
     *  (JCLAW-1160). */
    public static @Nullable Notification findById(Long id) {
        return Notification.findById(id);
    }
}
