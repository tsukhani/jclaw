package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "event_log")
public class EventLog extends Model {

    @Column(nullable = false)
    public Instant timestamp;

    @Column(nullable = false, length = 10)
    public String level;

    @Column(nullable = false, length = 50)
    public String category;

    @Column(name = "agent_id")
    public String agentId;

    @Column(length = 50)
    public String channel;

    @Column(nullable = false, length = 500)
    public String message;

    @Column(columnDefinition = "TEXT")
    public String details;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
        if (timestamp == null) {
            timestamp = createdAt;
        }
    }

    public static List<EventLog> findRecent(int limit) {
        return EventLog.find("ORDER BY timestamp DESC").fetch(limit);
    }

    public static long deleteOlderThan(Instant cutoff) {
        return EventLog.delete("timestamp < ?1", cutoff);
    }
}
