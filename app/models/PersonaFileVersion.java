package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;

import java.time.Instant;
import java.util.List;

/**
 * JF-136: one approved version of a persona file (SOUL.md, IDENTITY.md, USER.md, BOOTSTRAP.md, AGENT.md) of a
 * root agent's workspace. The newest row for an {@code (agent, filename)} is what the prompt reads; a file on disk
 * that differs from it waits for the operator.
 */
@Entity
@Table(name = "persona_file_version", indexes = {
        @Index(name = "idx_persona_file_version_agent_file", columnList = "agent_id,filename")
})
public class PersonaFileVersion extends Model {

    /** Who approved a version: the operator, the agent in a trusted turn, JClaw's own write, or the upgrade. */
    public enum Source { OPERATOR, AGENT, JCLAW, UPGRADE }

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    @Column(nullable = false)
    public String filename;

    @Column(nullable = false, columnDefinition = "TEXT")
    public String content;

    @Column(nullable = false, length = 64)
    public String sha256;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    public Source source;

    @Column(name = "approved_at", nullable = false)
    public Instant approvedAt;

    /** The versions of one file, newest first. */
    public static List<PersonaFileVersion> history(Long agentId, String filename) {
        return find("agent.id = ?1 AND filename = ?2 ORDER BY id DESC", agentId, filename).fetch();
    }
}
