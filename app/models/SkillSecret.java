package models;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.jspecify.annotations.Nullable;
import play.db.jpa.Model;
import utils.AppClock;
import utils.CredentialCipher;

import java.time.Instant;

/**
 * A secret one of an agent's skills uses (JF-323): the value, sealed with {@link CredentialCipher} under
 * {@link #VALUE_FIELD}, and the exact hosts it may be sent to. Only the operator writes one, and no route returns a
 * value. Keyed by the agent that owns the workspace the skill sits in.
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

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = AppClock.now();
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
