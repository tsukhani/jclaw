package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;
import play.db.jpa.JPA;
import play.db.jpa.Model;
import utils.AppClock;

import java.time.Instant;
import java.util.List;

/**
 * A message JClaw sent to a channel outside any conversation, such as a task result, kept so a
 * reply quoting it can carry its text into the agent's turn (JCLAW-1295). A WhatsApp quote and a
 * Slack thread name only the platform's id for the message they answer.
 */
@Entity
@Table(name = "delivered_message", indexes = @Index(name = "idx_delivered_message_lookup",
        columnList = "channel_type,platform_message_id"))
public class DeliveredMessage extends Model {

    @Column(name = "channel_type", nullable = false)
    public String channelType;

    /** Where it was sent: a Slack channel id, a WhatsApp number. */
    @Column(name = "chat_id", nullable = false)
    public String chatId;

    /** The platform's id for the sent message: a Slack {@code ts}, a WhatsApp message id. */
    @Column(name = "platform_message_id", nullable = false)
    public String platformMessageId;

    /** Completes "Replying to …", e.g. "the result of task 'payslip'". */
    @Column(nullable = false)
    public String source;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String text;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    /** When a Slack thread reply first carried this message; later replies find it in the conversation. */
    @Column(name = "quoted_at")
    public Instant quotedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = AppClock.now();
    }

    /** One row per id: a WhatsApp message chunked into several sends is quotable by any of them. */
    public static void record(String channelType, String chatId, List<String> platformMessageIds,
                              String source, String text) {
        for (var id : platformMessageIds) {
            var row = new DeliveredMessage();
            row.channelType = channelType;
            row.chatId = chatId;
            row.platformMessageId = id;
            row.source = source;
            row.text = text;
            row.save();
        }
    }

    /** Stamp {@link #quotedAt}; true only for the call that stamped it, so concurrent replies quote it once. */
    public static boolean markFirstQuote(Long id) {
        return JPA.em().createQuery(
                        "UPDATE DeliveredMessage d SET d.quotedAt = :now WHERE d.id = :id AND d.quotedAt IS NULL")
                .setParameter("now", AppClock.now())
                .setParameter("id", id)
                .executeUpdate() == 1;
    }

    /** The delivered message with this id, or null; {@code chatId} null matches any chat. */
    public static @Nullable DeliveredMessage findDelivered(String channelType, @Nullable String chatId,
                                                           String platformMessageId) {
        return chatId == null
                ? DeliveredMessage.find("channelType = ?1 and platformMessageId = ?2", channelType, platformMessageId).first()
                : DeliveredMessage.find("channelType = ?1 and chatId = ?2 and platformMessageId = ?3",
                        channelType, chatId, platformMessageId).first();
    }
}
