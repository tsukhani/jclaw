package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostUpdate;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;
import play.db.jpa.Model;
import utils.AppClock;
import utils.CredentialCipher;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * A secret one of an agent's skills uses (JF-323): the value, sealed with {@link CredentialCipher} under
 * {@link #VALUE_FIELD}, and the exact hosts it may be sent to. Only the operator writes one, bar a renewed secret's
 * value, which the secrets proxy takes from a sign-in answer under the operator's capture rule (JF-374); no route
 * returns a value. A renewed secret saved without a value is empty until the proxy fills it. Keyed by the agent that
 * owns the workspace the skill sits in.
 */
@Entity
@Table(name = SkillSecret.TABLE, uniqueConstraints = @UniqueConstraint(name = "uk_skill_secret",
        columnNames = {"agent_id", "skill_name", "name"}))
// An update writes only the changed columns, so a value that will not decrypt (read as null) survives a hosts change.
@DynamicUpdate
public class SkillSecret extends Model {

    public static final String TABLE = "skill_secret";
    // VALUE is a reserved word in H2.
    public static final String VALUE_COLUMN = "secret_value";
    /** The cipher's associated data for every value, so a value sealed here opens in no other column. */
    public static final String VALUE_FIELD = TABLE + "." + VALUE_COLUMN;

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    @Column(name = "skill_name", nullable = false, length = 128)
    public String skillName;

    @Column(nullable = false, length = 64)
    public String name;

    @Column(name = VALUE_COLUMN, nullable = false, columnDefinition = "TEXT")
    @Convert(converter = ValueConverter.class)
    @JsonIgnore
    public String value;

    /** One host per line, lowercase: a name or an IPv4 address, with an optional {@code :port}. */
    @Column(nullable = false, columnDefinition = "TEXT")
    public String hosts;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    /**
     * The operator's capture rule for a renewed secret (JF-374): the method, normalized path, {@code json} or
     * {@code header}, and field of the sign-in answer the secrets proxy takes the new value from, and the secret the
     * sign-in sends with where it sends it. All six or none.
     */
    @Column(name = "capture_method", length = 16)
    public @Nullable String captureMethod;

    @Column(name = "capture_path", length = 1024)
    public @Nullable String capturePath;

    @Column(name = "capture_from", length = 8)
    public @Nullable String captureFrom;

    @Column(name = "capture_field", length = 128)
    public @Nullable String captureField;

    @Column(name = "capture_credential", length = 64)
    public @Nullable String captureCredential;

    /** As {@code json password}, {@code form password}, {@code header api-key} or {@code basic}. */
    @Column(name = "capture_credential_in", length = 140)
    public @Nullable String captureCredentialIn;

    /** When the secrets proxy last stored a value taken under the capture rule; null until it has. */
    @Column(name = "renewed_at")
    public @Nullable Instant renewedAt;

    /** Set by {@link #markRenewed}, for the flush that writes the captured value. */
    @Transient
    private boolean renewing;

    /**
     * Mark the value as the one the secrets proxy took at {@code at}: {@link #updatedAt} stays when the operator last
     * saved the secret.
     */
    public void markRenewed(Instant at) {
        renewing = true;
        renewedAt = at.truncatedTo(ChronoUnit.MICROS);
    }

    @PrePersist
    @PreUpdate
    void stamp() {
        // H2 keeps microseconds and Linux's clock has nanoseconds: the time a save reports must be the one it stores.
        if (!renewing) updatedAt = AppClock.now().truncatedTo(ChronoUnit.MICROS);
    }

    /** The operator's next save of this instance, in the same session, is a save of the operator's again. */
    @PostPersist
    @PostUpdate
    void flushed() {
        renewing = false;
    }

    /** Seals {@link #value}; a stored value that will not decrypt reads as null, as if unset. */
    public static final class ValueConverter implements AttributeConverter<String, String> {
        @Override
        public @Nullable String convertToDatabaseColumn(@Nullable String plaintext) {
            if (plaintext == null || plaintext.isEmpty()) return plaintext;
            return CredentialCipher.current().encrypt(VALUE_FIELD, plaintext);
        }

        @Override
        public @Nullable String convertToEntityAttribute(@Nullable String stored) {
            if (stored == null) return null;
            return CredentialCipher.current().open(VALUE_FIELD, stored).plaintext();
        }
    }
}
