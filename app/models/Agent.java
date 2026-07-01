package models;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
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
     * JCLAW-465: per-agent content-compression enable. Nullable so rows created
     * before this column carry no explicit choice — those resolve via
     * {@link #compressionEffective()} to the role default (main agent on, custom
     * agents off). New agents get an explicit value at creation; the agent edit
     * form writes the operator's override.
     */
    @Column(name = "compression_enabled")
    public Boolean compressionEnabled;

    /**
     * JCLAW-463: per-agent, per-content-type compression sub-toggles, each gated
     * by the master {@link #compressionEnabled}. Nullable and resolving to ON
     * under an enabled master (operators opt a type OUT, not in) — see
     * {@link #compressionJsonEffective()} / {@link #compressionCodeEffective()}.
     * JSON migrated here from the former global {@code chat.compression.json.enabled}.
     */
    @Column(name = "compression_json")
    public Boolean compressionJson;

    @Column(name = "compression_code")
    public Boolean compressionCode;

    @Column(name = "compression_text")
    public Boolean compressionText;

    /**
     * JCLAW-464: per-agent text-compression aggressiveness (0–1) — the minimum
     * shrink the statistical text compressor must achieve to keep its rewrite.
     * Nullable, resolving to {@code TextCompressor.DEFAULT_TARGET_RATIO} (0.3) in
     * the pipeline. Held as a raw Double so the model carries no
     * services.compression dependency.
     */
    @Column(name = "compression_target_ratio")
    public Double compressionTargetRatio;

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
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent parentAgent;

    /**
     * JCLAW-500: whether this agent may spawn subagents under the external ACP
     * harness ({@code runtime=acp}). The acp runtime launches an
     * operator-configured external process that runs OUTSIDE JClaw's per-agent
     * tool gating and workspace confinement, so it is a privileged capability:
     * the main agent always has it ({@link #isMain()} short-circuits the gate),
     * every other agent is denied unless an operator explicitly grants it.
     * {@code @ColumnDefault} keeps the ALTER safe on a populated table — existing
     * rows default to false. See {@code tools.SubagentSpawnTool#acpRuntimeError}.
     */
    @Column(name = "acp_allowed", nullable = false)
    @ColumnDefault("false")
    public boolean acpAllowed = false;

    /**
     * JCLAW-534: per-agent memory auto-capture enable. On by default for every
     * agent; an operator turns it off in the agent's Memory section to stop
     * capturing memories for that agent from then on. NOT NULL with a DDL default
     * keeps the ALTER safe on a populated table (same pattern as {@link #acpAllowed}).
     */
    @Column(name = "memory_autocapture_enabled", nullable = false)
    @ColumnDefault("true")
    public boolean memoryAutocaptureEnabled = true;

    /**
     * JCLAW-534: per-agent override for the model that runs memory extraction.
     * Null means "inherit the agent's default model" ({@link #modelProvider} /
     * {@link #modelId}); an operator sets these only to point extraction at a
     * different (e.g. cheaper) provider/model. Resolved via
     * {@link #autocaptureProviderEffective()} / {@link #autocaptureModelEffective()}.
     */
    @Column(name = "memory_autocapture_provider")
    public String memoryAutocaptureProvider;

    @Column(name = "memory_autocapture_model")
    public String memoryAutocaptureModel;

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

    /**
     * Whether this agent is a spawned subagent (has a parent). Subagents process
     * delegated work rather than the operator's direct input, so callers like
     * memory auto-capture (JCLAW-539) skip them — capturing there would flood
     * memory with facts not tied to the operator's own turns.
     */
    public boolean isSubagent() {
        return parentAgent != null;
    }

    /**
     * Effective compression-enabled state (JCLAW-465): the explicit per-agent
     * value when set, otherwise the role default — the main agent on, custom
     * agents off. The compression pipeline gates per agent through this.
     */
    public boolean compressionEffective() {
        return compressionEnabled != null ? compressionEnabled : isMain();
    }

    /**
     * Whether JSON compression is effective for this agent (JCLAW-463): the
     * master must be on AND the per-type sub-toggle not explicitly off (null
     * resolves to on under the master). Mirrored for each content type.
     */
    public boolean compressionJsonEffective() {
        return compressionEffective() && (compressionJson == null || compressionJson);
    }

    public boolean compressionCodeEffective() {
        return compressionEffective() && (compressionCode == null || compressionCode);
    }

    public boolean compressionTextEffective() {
        return compressionEffective() && (compressionText == null || compressionText);
    }

    public static List<Agent> findEnabled() {
        return Agent.find("enabled", true).fetch();
    }

    /**
     * JCLAW-534: provider that runs memory extraction for this agent — the
     * explicit override when set, otherwise the agent's default provider.
     */
    public String autocaptureProviderEffective() {
        return memoryAutocaptureProvider != null && !memoryAutocaptureProvider.isBlank()
                ? memoryAutocaptureProvider : modelProvider;
    }

    /**
     * JCLAW-534: model that runs memory extraction for this agent — the explicit
     * override when set, otherwise the agent's default model.
     */
    public String autocaptureModelEffective() {
        return memoryAutocaptureModel != null && !memoryAutocaptureModel.isBlank()
                ? memoryAutocaptureModel : modelId;
    }
}
