package models;

import jakarta.persistence.*;
import play.db.jpa.Model;

/**
 * JCLAW-408: shared JPA state for the per-agent feature-config entities
 * ({@link AgentSkillConfig}, {@link AgentToolConfig}), which were otherwise
 * byte-for-byte identical apart from their table and single discriminating
 * column. Holds the common {@code agent} FK and the {@code enabled} flag.
 *
 * <p>Concrete subclasses declare their own {@code @Table}/indexes/{@code @Cache},
 * their single {@code skillName}/{@code toolName} column, and their typed
 * active-record finders — Play's enhancer binds the static {@code find(...)}
 * methods to the concrete {@code @Entity} class, so the finders must stay on
 * the subclasses. {@code @MappedSuperclass} maps these fields into each
 * subclass's own table; there is no shared table and no inheritance
 * discriminator.
 */
@MappedSuperclass
public abstract class AgentFeatureConfig extends Model {

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    public Agent agent;

    @Column(nullable = false)
    public boolean enabled = true;
}
