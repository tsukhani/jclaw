package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.JPA;
import play.db.jpa.Model;
import utils.AppClock;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/**
 * A channel message waiting behind a busy turn, kept so a restart re-queues it (JCLAW-1521): the
 * platform acknowledged it before JClaw stored anything, and never sends it again. The drain deletes
 * the row in the transaction that appends the message's USER row.
 */
@Entity
@Table(name = "pending_inbound")
public class PendingInbound extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "conversation_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Conversation conversation;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    @Column(name = "channel_type", nullable = false)
    public String channelType;

    @Column(name = "peer_id")
    public String peerId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String text;

    /** The staged files as a JSON array of {@code AttachmentService.Input}; null when there are none. */
    @Column(columnDefinition = "TEXT")
    public String attachments;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = AppClock.now();
    }

    /** Rows from before {@code cutoff}, oldest first, so a conversation's messages re-queue in arrival order. */
    public static List<PendingInbound> createdBefore(Instant cutoff) {
        return PendingInbound.<PendingInbound>find("createdAt < ?1 ORDER BY id", cutoff).fetch();
    }

    public static void deleteByIds(Collection<Long> ids) {
        if (ids.isEmpty()) return;
        JPA.em().createQuery("DELETE FROM PendingInbound p WHERE p.id IN :ids")
                .setParameter("ids", ids).executeUpdate();
    }
}
