package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;
import utils.AppClock;

import java.time.Instant;

/**
 * A recorded act of checking a {@link Memory} (JCLAW-1318). The trust tier is computed from
 * these rows, never stored: any {@code human:} actor makes a memory human-reviewed.
 */
@Entity
@Table(name = "memory_verification", indexes = {
        @Index(name = "idx_memory_verification_memory", columnList = "memory_id")
})
public class MemoryVerification extends Model {

    public enum Kind { CONFIRMED, EDITED }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "memory_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Memory memory;

    @Column(nullable = false, length = 200)
    public String actor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    public Kind kind;

    @Column(name = "verified_at", nullable = false)
    public Instant verifiedAt;

    @PrePersist
    void onCreate() {
        if (verifiedAt == null) verifiedAt = AppClock.now();
    }

    /** Record a verification of {@code memory} by {@code actor}. */
    public static MemoryVerification record(Memory memory, String actor, Kind kind) {
        var v = new MemoryVerification();
        v.memory = memory;
        v.actor = actor;
        v.kind = kind;
        v.save();
        return v;
    }
}
