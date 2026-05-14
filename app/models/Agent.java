package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "agent")
// JCLAW-205: Hibernate L2 cache via Caffeine. Agent is the most-looked-up
// entity in the chat path (Agent.findById / findByName, ~30 call sites);
// READ_WRITE strategy keeps strict consistency on save/delete via the
// SessionFactory's transactional cache invalidation. Hand-rolled service-
// layer caching of findById is now redundant — JCLAW-204 was downsized
// accordingly.
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class Agent extends Model {

    public static final String MAIN_AGENT_NAME = "main";

    @Column(nullable = false, unique = true)
    public String name;

    @Column(name = "model_provider", nullable = false)
    public String modelProvider;

    @Column(name = "model_id", nullable = false)
    public String modelId;

    /**
     * Operator-supplied short description of the agent's purpose, shown in the
     * edit form next to the name. Optional; capped at 255 chars so the column
     * stays within a varchar for any RDBMS and the UI never has to wrap a huge
     * blob. Null and blank are equivalent — the service layer collapses both
     * to null on save.
     */
    @Column(length = 255)
    public String description;

    @Column(nullable = false)
    public boolean enabled = true;

    /**
     * Reasoning-effort level for this agent, or {@code null} when reasoning is
     * disabled (or the model doesn't support it). Must be one of the values
     * advertised by the selected model's {@code thinkingLevels}; the API layer
     * is responsible for validating against the active provider/model pair —
     * this column just persists the operator's choice verbatim.
     */
    @Column(name = "thinking_mode")
    public String thinkingMode;

    /**
     * Optional parent agent (JCLAW-264). Set when this Agent was spawned as a
     * subagent of another Agent; null for top-level (operator-owned) agents,
     * which is the default for every row created before JCLAW-264. Subagent
     * spawning logic lives in JCLAW-265 — this column only carries the
     * hierarchy.
     */
    @ManyToOne
    @JoinColumn(name = "parent_agent_id")
    public Agent parentAgent;

    @Column(name = "created_at", nullable = false, updatable = false)
    public Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    // Hibernate PersistentBag isn't Serializable, but JClaw never serializes
    // JPA entities off-heap (no session replication, no caching). The
    // Serializable on GenericModel is incidental — fields are JPA-tracked.
    @SuppressWarnings("java:S1948")
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
