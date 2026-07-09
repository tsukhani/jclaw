package services;

import models.Notification;

/**
 * JCLAW-153: entity-lookup accessor for Notification rows so controllers route
 * their finder calls through the service layer instead of reaching into raw
 * {@code Notification.findById(...)}. Thin passthrough that relies on the
 * caller's ambient JPA transaction — no {@link Tx} wrapper — matching
 * {@link AgentService#findById}.
 */
public final class NotificationService {

    private NotificationService() {}

    public static Notification findById(Long id) {
        return Notification.findById(id);
    }
}
