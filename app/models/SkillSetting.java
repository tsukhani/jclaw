package models;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import play.db.jpa.Model;
import utils.AppClock;

import java.time.Instant;

/**
 * A setting one of an agent's skills uses (JF-357): a value that is not secret, such as a service's address, a user
 * name or a path, which a command receives as is. Only the operator writes one, and the Secrets panel shows it. Keyed
 * like {@link SkillSecret}, by the agent that owns the workspace the skill sits in.
 */
@Entity
@Table(name = SkillSetting.TABLE, uniqueConstraints = @UniqueConstraint(name = "uk_skill_setting",
        columnNames = {"agent_id", "skill_name", "name"}))
public class SkillSetting extends Model {

    public static final String TABLE = "skill_setting";

    @ManyToOne(optional = false)
    @JoinColumn(name = "agent_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    public Agent agent;

    @Column(name = "skill_name", nullable = false, length = 128)
    public String skillName;

    @Column(nullable = false, length = 64)
    public String name;

    // VALUE is a reserved word in H2.
    @Column(name = "setting_value", nullable = false, columnDefinition = "TEXT")
    public String value;

    @Column(name = "updated_at", nullable = false)
    public Instant updatedAt;

    @PrePersist
    @PreUpdate
    void stamp() {
        updatedAt = AppClock.now();
    }
}
