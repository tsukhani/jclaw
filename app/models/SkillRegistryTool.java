package models;

import jakarta.persistence.*;
import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;
import play.db.jpa.JPA;
import play.db.jpa.Model;

import java.util.List;

/**
 * Blessed shell-command contributions declared by a promoted global skill.
 * One row per (skill, command) pair. Rows are rewritten on every promotion
 * (old set cleared, new set from the skill's {@code commands:} frontmatter
 * inserted) and deleted on explicit un-promotion from the registry.
 *
 * <p>Per the skill-allowlist design: this table is the <em>registry</em>
 * authority for what a promoted skill claims to provide. It is NOT read at
 * shell-exec validation time — the per-agent {@link AgentSkillAllowedTool}
 * table is the live source. This table exists so the install-time snapshot
 * has a canonical source and so registry deletion is observable.
 */
@Entity
@Table(name = "skill_registry_tool", indexes = {
        @Index(name = "idx_skill_registry_tool_skill", columnList = "skill_name"),
        @Index(name = "idx_skill_registry_tool_unique", columnList = "skill_name,tool_name", unique = true)
})
// JCLAW-205 follow-up: read every time a skill's tool contributions
// are consulted (shell-allowlist union, etc.); rewritten only at
// skill promote/un-promote time. {@link #deleteBySkill} runs bulk
// JPQL DELETE, which does NOT invalidate the L2 entity cache
// automatically — so each bulk-delete site explicitly evicts the
// region after firing. Cheaper than converting the delete to per-row
// (skills typically declare <10 tool contributions, but the region-
// evict scales independent of N).
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
public class SkillRegistryTool extends Model {

    @Column(name = "skill_name", nullable = false)
    public String skillName;

    @Column(name = "tool_name", nullable = false)
    public String toolName;

    public static List<SkillRegistryTool> findBySkill(String skillName) {
        return SkillRegistryTool.find("skillName = ?1", skillName).fetch();
    }

    public static void deleteBySkill(String skillName) {
        SkillRegistryTool.delete("skillName = ?1", skillName);
        // Bulk DML doesn't fire entity lifecycle callbacks → L2 keeps
        // stale rows until the next entity-save or restart. Explicit
        // region evict here keeps the cache honest for the next reader.
        JPA.em().getEntityManagerFactory().getCache().evict(SkillRegistryTool.class);
    }
}
