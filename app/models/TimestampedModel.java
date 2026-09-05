package models;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import play.db.jpa.Model;
import utils.AppClock;

import java.time.Instant;

/**
 * Shared {@code created_at}/{@code updated_at} columns and the lifecycle callbacks that
 * maintain them, for entities whose timestamp handling is the plain one: stamp both on
 * insert, bump {@code updated_at} on every update.
 *
 * <p>Extracted because nine entities carried a byte-identical copy of the field pair and
 * both callbacks. Follows the mapped-superclass approach already proven by
 * {@link AgentFeatureConfig} (JCLAW-408) and {@link AgentBoundBinding} (JCLAW-723) — the
 * latter now inherits from here rather than declaring its own copy.
 *
 * <p><b>Finders stay on the subclasses.</b> Play's enhancer binds the active-record statics
 * ({@code find}, {@code findAll}, {@code findById}) to each class that declares
 * {@code @Entity}, so a mapped superclass gets none and every typed finder must live on the
 * concrete entity. The column mapping is unaffected: JPA flattens these fields into each
 * subclass's own table under the same names, so the generated DDL is identical to what the
 * per-class copies produced.
 *
 * <p><b>Two entities deliberately opt out</b> rather than inherit and override, because
 * their callbacks do more than stamp a time: {@link Memory} clamps its importance score in
 * both, and {@link VideoGenerationJob} preserves a caller-supplied {@code createdAt} instead
 * of overwriting it. Folding those in would mean relying on JPA callback-override semantics
 * that nothing in this repo currently pins with a test — not worth it for two classes.
 */
@MappedSuperclass
public abstract class TimestampedModel extends Model {

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = AppClock.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = AppClock.now();
    }
}
