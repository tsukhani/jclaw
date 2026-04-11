package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "agent")
public class Agent extends Model {

    public static final String MAIN_AGENT_NAME = "main";

    @Column(nullable = false, unique = true)
    public String name;

    @Column(name = "model_provider", nullable = false)
    public String modelProvider;

    @Column(name = "model_id", nullable = false)
    public String modelId;

    @Column(nullable = false)
    public boolean enabled = true;

    @Column(name = "thinking_mode")
    public String thinkingMode;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @OneToMany(mappedBy = "agent", cascade = CascadeType.ALL, orphanRemoval = true)
    public List<AgentBinding> bindings;

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

    public static Agent findByName(String name) {
        return Agent.find("name", name).first();
    }

    public boolean isMain() {
        return MAIN_AGENT_NAME.equalsIgnoreCase(name);
    }

    public static List<Agent> findEnabled() {
        return Agent.find("enabled", true).fetch();
    }
}
