package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;

import java.time.Instant;

/**
 * Tracks the last time a user messaged a Cloud-API binding, for the WhatsApp
 * 24-hour customer-service window (JCLAW-447). Meta only permits free-form
 * (non-template) outbound for 24h after the user's last inbound message; outside
 * that window a business must send a pre-approved template. This entity records
 * the inbound timestamp per (binding, peer) so {@link channels.WhatsAppChannel}
 * can decide free-form vs. template at send time.
 *
 * <p>Cloud-API-only: it's written by {@link controllers.WebhookWhatsAppController}
 * on every successful inbound parse (the WhatsApp-Web transport has no such window
 * — its session is a logged-in client, not a business API). One row per
 * (bindingId, peerId), upserted by {@link #recordInbound(Long, String, Instant)}.
 */
@Entity
@Table(name = "whatsapp_conversation_window",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_wa_window_binding_peer", columnNames = {"binding_id", "peer_id"}),
        indexes = @Index(name = "idx_wa_window_binding_peer", columnList = "binding_id,peer_id"))
public class WhatsAppConversationWindow extends Model {

    /** The {@link WhatsAppBinding} this window belongs to. Lazy because nothing navigates
     *  from the window back to the binding; the association exists for the cascade, so a
     *  deleted binding cannot leave behind a window whose non-null id names nothing. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "binding_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public WhatsAppBinding binding;

    /** The peer (the user's E.164 phone number / wa-id) that messaged the binding. */
    @Column(name = "peer_id", nullable = false)
    public String peerId;

    /** When the user last messaged the binding — the start of the 24h window. */
    @Column(name = "last_user_message_at", nullable = false)
    public Instant lastUserMessageAt;

    /**
     * Upsert the window for {@code (bindingId, peerId)} to {@code at}. Must run
     * inside a JPA transaction. Idempotent — advances an existing row's timestamp,
     * never moves it back, and inserts a new row otherwise.
     */
    public static void recordInbound(Long bindingId, String peerId, Instant at) {
        if (bindingId == null || peerId == null || peerId.isBlank() || at == null) {
            return;
        }
        var existing = findRow(bindingId, peerId);
        if (existing == null) {
            WhatsAppBinding owner = WhatsAppBinding.findById(bindingId);
            // The binding can be deleted between the webhook arriving and this write; a
            // window for one that no longer exists is what the FK is here to prevent.
            if (owner == null) return;
            existing = new WhatsAppConversationWindow();
            existing.binding = owner;
            existing.peerId = peerId;
        } else if (!at.isAfter(existing.lastUserMessageAt)) {
            // A late redelivery of an older message must not close a window a newer one opened.
            return;
        }
        existing.lastUserMessageAt = at;
        existing.save();
    }

    /** The window row for {@code (bindingId, peerId)}, or null when none exists.
     *  Named {@code findRow} (not {@code find}) to avoid shadowing Play's
     *  {@code Model.find(String, Object...)} query finder. */
    public static WhatsAppConversationWindow findRow(Long bindingId, String peerId) {
        if (bindingId == null || peerId == null) return null;
        return WhatsAppConversationWindow.find(
                "binding.id = ?1 and peerId = ?2", bindingId, peerId).first();
    }

    /**
     * Whether free-form (non-template) outbound is currently allowed for
     * {@code (bindingId, peerId)} — i.e. the user messaged within the last 24h.
     * No row (the user never messaged) → false, so a business-initiated reach-out
     * must use a template.
     */
    public static boolean isWithinWindow(Long bindingId, String peerId, Instant now) {
        var row = findRow(bindingId, peerId);
        if (row == null || row.lastUserMessageAt == null) return false;
        return !row.lastUserMessageAt.isBefore(now.minusSeconds(24L * 60 * 60));
    }
}
