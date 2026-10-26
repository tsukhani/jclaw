package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PersistenceException;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import play.db.jpa.Model;

import java.sql.SQLException;
import java.time.Instant;

/**
 * A WhatsApp message id JClaw has handed to its inbound path (JCLAW-1520), so a redelivery of
 * that message is answered and dropped even after a restart. The unique {@code wamid} is the
 * claim: two concurrent deliveries of one message insert once, and the loser reads as seen.
 * Rows are pruned by {@link jobs.WhatsAppAcceptedMessageCleanupJob}.
 */
@Entity
@Table(name = "whatsapp_accepted_message",
        uniqueConstraints = @UniqueConstraint(name = "uq_wa_accepted_wamid", columnNames = "wamid"),
        indexes = @Index(name = "idx_wa_accepted_at", columnList = "accepted_at"))
public class WhatsAppAcceptedMessage extends Model {

    private static final String UNIQUE_VIOLATION = "23505";

    @Column(name = "wamid", nullable = false)
    public String wamid;

    @Column(name = "accepted_at", nullable = false)
    public Instant acceptedAt;

    /** Whether {@code wamid} was already handed to the inbound path. Must run inside a transaction. */
    public static boolean isRecorded(String wamid) {
        return count("wamid = ?1", wamid) > 0;
    }

    /**
     * Record {@code wamid} as accepted at {@code at}; true when this call inserted it, false when
     * it was already recorded. Must run inside a transaction of its own: a duplicate leaves that
     * transaction rollback-only. Any failure other than the unique violation propagates.
     */
    public static boolean claim(String wamid, Instant at) {
        if (isRecorded(wamid)) return false;
        var row = new WhatsAppAcceptedMessage();
        row.wamid = wamid;
        row.acceptedAt = at;
        try {
            row.save();
            return true;
        } catch (PersistenceException e) {
            if (!isUniqueViolation(e)) throw e;
            return false;
        }
    }

    private static boolean isUniqueViolation(Throwable e) {
        for (var cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && UNIQUE_VIOLATION.equals(sql.getSQLState())) return true;
        }
        return false;
    }
}
