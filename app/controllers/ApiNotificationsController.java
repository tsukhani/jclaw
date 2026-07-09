package controllers;

import com.google.gson.Gson;
import io.swagger.v3.oas.annotations.Operation;
import models.Notification;
import play.mvc.Controller;
import play.mvc.With;
import services.EventLogger;
import services.NotificationService;

import java.time.Instant;
import java.util.List;

import static utils.GsonHolder.INSTANCE;

/**
 * Notifications API — surface for the global {@code NotificationBar}
 * toast component (mounted in {@code layouts/default.vue}) and the
 * {@code /reminders} page's "Past reminders" tab.
 *
 * <p>Notifications are written by {@link services.ReminderDispatcher}
 * when a reminder task fires on the {@code web} channel. They persist
 * forever (operator's choice — reminders are by definition things the
 * user wants to keep around) and are removed only by an explicit
 * {@link #delete} call from the UI's dismiss button. {@link #ack} marks
 * a row read without removing it, so the past-reminders tab can still
 * show acknowledged entries.
 *
 * <p>Single-operator Personal Edition: the one authenticated admin sees every
 * notification — there is no per-user scoping because there is only one user.
 */
@With(AuthCheck.class)
public class ApiNotificationsController extends Controller {

    private static final Gson gson = INSTANCE;

    /** Wire DTO returned by {@link #list}. Keeps the column set stable
     *  even if we add internal columns to {@link Notification} later. */
    public record NotificationView(Long id, Long agentId, String agentName, String content,
                                    Long sourceTaskRunId, Long sourceTaskId,
                                    String createdAt, String acknowledgedAt) {

        public static NotificationView of(Notification n) {
            return new NotificationView(
                    n.id,
                    n.agent != null ? n.agent.id : null,
                    n.agent != null ? n.agent.name : null,
                    n.content,
                    n.sourceTaskRunId,
                    n.sourceTaskId,
                    n.createdAt != null ? n.createdAt.toString() : null,
                    n.acknowledgedAt != null ? n.acknowledgedAt.toString() : null);
        }
    }

    /**
     * GET /api/notifications — list notifications, newest first.
     *
     * @param status {@code "unread"} returns only un-acknowledged rows
     *               (the toast component's primary query); {@code "all"}
     *               returns every row; null defaults to {@code "unread"}
     * @param limit  hard cap on result count; defaults to 50
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "List notifications newest-first, filtered by unread/all status with a result cap")
    public static void list(String status, Integer limit) {
        int cap = limit != null && limit > 0 ? Math.min(limit, 500) : 50;
        var mode = status == null || status.isBlank() ? "unread" : status.toLowerCase();
        List<Notification> rows = switch (mode) {
            case "unread" -> Notification.findUnread(cap);
            case "all" -> Notification.findAllNewestFirst(cap);
            default -> {
                badRequest();
                yield List.of();
            }
        };
        var views = rows.stream().map(NotificationView::of).toList();
        renderJSON(gson.toJson(views));
    }

    /**
     * POST /api/notifications/{id}/ack — mark a notification acknowledged
     * (sets {@code acknowledgedAt = now}). Idempotent: re-ack'ing a row
     * that already has a non-null timestamp is a no-op so the toast
     * dismiss-on-click path doesn't have to track state.
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "Mark a notification acknowledged (idempotent), without deleting it")
    public static void ack(Long id) {
        var n = (Notification) NotificationService.findById(id);
        if (n == null) notFound();
        if (n.acknowledgedAt == null) {
            n.acknowledgedAt = Instant.now();
            n.save();
            EventLogger.info("notification",
                    n.agent != null ? n.agent.name : null, null,
                    "Notification %d acknowledged".formatted(n.id));
        }
        renderJSON(gson.toJson(NotificationView.of(n)));
    }

    /**
     * DELETE /api/notifications/{id} — hard-delete a notification. Used
     * by the toast / reminders-page dismiss button when the user wants
     * the row gone for good. No soft-delete column because acknowledged
     * rows already serve that purpose for the "I've seen it, keep it"
     * path; this endpoint is for "I never want to see this again."
     */
    @SuppressWarnings("java:S2259")
    @Operation(summary = "Hard-delete a notification by id")
    public static void delete(Long id) {
        var n = (Notification) NotificationService.findById(id);
        if (n == null) notFound();
        var agentName = n.agent != null ? n.agent.name : null;
        n.delete();
        EventLogger.info("notification", agentName, null,
                "Notification %d deleted".formatted(id));
        renderJSON("{\"status\":\"deleted\",\"id\":" + id + "}");
    }
}
