package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import play.db.jpa.Model;
import utils.AppClock;
import utils.TokenHasher;

import java.time.Instant;

/**
 * The pairing credential a browser extension presents to open the Chrome bridge socket.
 *
 * <p>A separate row type from {@link ApiToken} on purpose: that one authenticates {@code /api/**}
 * calls, so a bridge token that doubled as one would hand the extension the whole API. Only the
 * SHA-256 hash is stored; the plaintext is shown once, when the operator pairs.
 */
@Entity
@Table(name = "chrome_bridge_token")
public class ChromeBridgeToken extends Model {

    @Column(name = "secret_hash", nullable = false, length = 64, unique = true)
    public String secretHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "last_connected_at")
    public Instant lastConnectedAt;

    /** Set rather than deleting the row, so a revoked pairing stays distinguishable from none. */
    @Column(name = "revoked_at")
    public Instant revokedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) createdAt = AppClock.now();
    }

    /** The unrevoked row for {@code plaintext}, or null. */
    public static ChromeBridgeToken findActiveByPlaintext(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        return ChromeBridgeToken.find("secretHash = ?1 AND revokedAt IS NULL",
                TokenHasher.hash(plaintext)).first();
    }
}
