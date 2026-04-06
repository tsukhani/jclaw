package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "memory")
public class Memory extends Model {

    @Column(name = "agent_id", nullable = false)
    public String agentId;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String text;

    @Column(length = 50)
    public String category;

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

    public static List<Memory> findByAgent(String agentId) {
        return Memory.find("agentId", agentId).fetch();
    }

    public static List<Memory> searchByText(String agentId, String query, int limit) {
        return Memory.find("agentId = ?1 AND LOWER(text) LIKE ?2",
                agentId, "%" + query.toLowerCase() + "%").fetch(limit);
    }
}
