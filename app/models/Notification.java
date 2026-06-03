package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * User-visible notification, surfaced through the global notification bar
 * (toast overlay in {@code layouts/default.vue}). Currently only reminder
 * fires produce Notification rows — when a {@link Task} with
 * {@code payloadType="reminder"} fires on the {@code web} channel,
 * {@link services.ReminderDispatcher} writes one of these instead of
 * appending a {@link Message} to a Conversation.
 *
 * <p>Telegram-channel reminders bypass this entity entirely — they go
 * straight to the user's bot binding via {@link channels.TelegramChannel#sendMessage}.
 * The split exists because Telegram users carry their notifications on
 * their phone; web users need an in-app surface.
 *
 * <p>Unacknowledged rows persist forever (operator's choice — reminders
 * are by definition things the user wants kept around). The
 * {@link controllers.ApiNotificationsController#delete} endpoint hard-
 * deletes a row when the user explicitly dismisses it.
 *
 * @see services.ReminderDispatcher
 * @see controllers.ApiNotificationsController
 */
@Entity
@Table(name = "notification", indexes = {
        @Index(name = "idx_notification_agent_ack", columnList = "agent_id,acknowledged_at"),
        @Index(name = "idx_notification_created", columnList = "created_at")
})
public class Notification extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    /**
     * Notification body shown to the user verbatim. For reminder
     * notifications this is the {@link Task#description} the operator
     * (or agent) configured at create time — no LLM round, no rephrasing.
     */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    /**
     * Optional link back to the {@link TaskRun} that produced this
     * notification. Stored as a raw id (not a JPA association) because
     * Notifications outlive their source TaskRun's audit retention; a
     * dangling id is the right semantics — the notification stays even
     * if the audit row is purged.
     */
    @Column(name = "source_task_run_id")
    public Long sourceTaskRunId;

    /**
     * Optional link back to the {@link Task} that produced this
     * notification. Set by {@link services.ReminderDispatcher} at write
     * time so the toast's trash button can cascade-delete the underlying
     * reminder Task without an extra join through TaskRun.
     *
     * <p>Plain Long for the same reason as {@link #sourceTaskRunId}: the
     * Task may have been hard-deleted (operator action on the Reminders
     * page) before the user dismisses the notification, and a null FK
     * association would refuse to load. The id may dangle; callers must
     * handle a 404 from {@code DELETE /api/tasks/{id}} as a no-op.
     */
    @Column(name = "source_task_id")
    public Long sourceTaskId;

    /**
     * {@code null} when unacknowledged (the dominant state — toast still
     * displayed); set to {@link Instant#now} when the user clicks the
     * toast or hits the ack endpoint. Acknowledged rows stay in the table
     * so the user can browse past reminders on the {@code /reminders}
     * page; only an explicit delete removes them.
     */
    @Column(name = "acknowledged_at")
    public Instant acknowledgedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = Instant.now();
    }

    /** Un-acknowledged notifications, newest first, capped at {@code limit}. */
    public static List<Notification> findUnread(int limit) {
        return Notification.find("acknowledgedAt IS NULL ORDER BY createdAt DESC").fetch(limit);
    }

    /** All notifications, newest first, capped at {@code limit}. */
    public static List<Notification> findAllNewestFirst(int limit) {
        return Notification.find("ORDER BY createdAt DESC").fetch(limit);
    }
}
