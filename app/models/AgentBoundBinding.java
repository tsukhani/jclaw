package models;

import jakarta.persistence.Column;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.function.Function;

/**
 * JCLAW-723: shared JPA state, timestamps, and lifecycle for the per-agent
 * channel bindings ({@link TelegramBinding}, {@link SlackBinding},
 * {@link WhatsAppBinding}), which were otherwise repeating the same {@code agent}
 * FK, {@code enabled} flag, {@code created_at}/{@code updated_at} columns, and
 * timestamp callbacks. Mirrors {@link AgentFeatureConfig}'s mapped-superclass
 * approach: the concrete subclasses keep their own {@code @Table}/indexes/
 * {@code @Cache}, their channel-specific columns, and their typed finders.
 *
 * <p><b>Privacy invariant.</b> The {@code agent_id} join column is {@code unique},
 * so exactly one binding may reference a given agent. This is a privacy
 * constraint, not a modelling nicety: agent memory is scoped by agentId alone,
 * so binding one agent to a second identity (a second bot/number/workspace)
 * would share memories across those end-users. The uniqueness is enforced here
 * at the schema, in {@link controllers.ApiBindingController} on the CRUD path,
 * and in the frontend autocomplete.
 *
 * <p><b>Finders stay on the subclasses.</b> Play's enhancer binds the
 * active-record statics ({@code find}, {@code findAll}, {@code findById}) to each
 * concrete {@code @Entity} class, so {@code findByAgent}, {@code findAllEnabled},
 * {@code findByBotToken}, and friends must live on the subclasses. The one finder
 * whose <em>logic</em> (not just its query) is worth sharing — the parent-chain
 * walk — lives here as {@link #findByAgentOrAncestor(Agent, Function)},
 * parameterized by the subclass's own {@code findByAgent}.
 */
@MappedSuperclass
public abstract class AgentBoundBinding extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    @Column(nullable = false)
    public boolean enabled = true;

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

    /**
     * Resolve a binding by walking the {@link Agent#parentAgent} chain — the
     * calling agent's own binding when present, otherwise the nearest ancestor's,
     * otherwise null. Subagents (spawned by {@code subagent_spawn}) have no binding
     * of their own, so a child's outbound {@code message(channel=...)} reaches the
     * identity its root ancestor owns. Bounded 64-hop walk guards against a cyclic
     * {@code parent_agent_id}. CRUD callers (admin add/remove binding) must use the
     * subclass {@code findByAgent} instead — they manage a specific row by exact
     * identity and must not be confused by an inherited match.
     *
     * @param byAgent the subclass's own {@code findByAgent}, e.g. {@code TelegramBinding::findByAgent}
     */
    protected static <T extends AgentBoundBinding> T findByAgentOrAncestor(
            Agent agent, Function<Agent, T> byAgent) {
        var cur = agent;
        int hops = 0;
        while (cur != null && hops++ < 64) {
            var binding = byAgent.apply(cur);
            if (binding != null) return binding;
            cur = cur.parentAgent;
        }
        return null;
    }
}
