package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;

@Entity
@Table(name = "channel_config")
public class ChannelConfig extends Model {

    @Column(name = "channel_type", nullable = false, unique = true)
    public String channelType;

    @Column(name = "config_json", nullable = false, columnDefinition = "TEXT")
    public String configJson;

    @Column(nullable = false)
    public boolean enabled = false;

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

    public static ChannelConfig findByType(String channelType) {
        return ChannelConfig.find("channelType", channelType).first();
    }
}
