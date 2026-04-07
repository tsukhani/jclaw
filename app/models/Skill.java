package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "skill", indexes = {
        @Index(name = "idx_skill_name", columnList = "name", unique = true)
})
public class Skill extends Model {

    @Column(nullable = false, unique = true)
    public String name;

    @Column(length = 500)
    public String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(name = "is_global", nullable = false)
    public boolean isGlobal = false;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    void onCreate() {
        var now = Instant.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public static Skill findByName(String name) {
        return Skill.find("name", name).first();
    }

    public static List<Skill> findGlobal() {
        return Skill.find("isGlobal", true).fetch();
    }
}
