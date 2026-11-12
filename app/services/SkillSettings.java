package services;

import agents.SkillLoader;
import models.Agent;
import models.SkillSecret;
import models.SkillSetting;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The operator's store of skill settings (JF-357): the values a skill needs that are not secret, such as an address,
 * a user name or a path. The Secrets panel shows them in clear and only the operator writes them. A command receives
 * each as a plain environment variable, under the rule that gives it a secret.
 */
public final class SkillSettings {

    public static final int MAX_VALUE_CHARS = 2_048;
    private static final String LOG_CATEGORY = "skill";

    public record Listing(String name, String value, Instant setAt) {}

    /** A setting a command may be given: which skill holds it, by name. */
    public record Usable(String skill, String name, String value) {}

    private SkillSettings() {}

    public static List<Listing> list(Agent owner, String skill) {
        List<SkillSetting> rows = SkillSetting.find("agent = ?1 AND skillName = ?2 ORDER BY name", owner, skill).fetch();
        var out = new ArrayList<Listing>();
        for (var row : rows) out.add(new Listing(row.name, row.value, row.updatedAt));
        return out;
    }

    /** Create or replace {@code name}. A name is used once on an agent, across its secrets and its settings. */
    public static Listing put(Agent owner, String skill, String name, String value) {
        var rejection = SkillSecrets.nameRejection(name);
        if (rejection != null) throw new SkillSecrets.Refused(rejection);
        if (value.isEmpty()) throw new SkillSecrets.Refused("The value is empty.");
        if (value.length() > MAX_VALUE_CHARS) {
            throw new SkillSecrets.Refused("The value is longer than %d characters.".formatted(MAX_VALUE_CHARS));
        }
        // A command receives it as one environment variable: a line break or a NUL would end or split it.
        if (value.chars().anyMatch(c -> c == '\n' || c == '\r' || c == 0)) {
            throw new SkillSecrets.Refused("A setting is one line: remove the line breaks.");
        }
        SkillSecret secret = SkillSecret.find("agent = ?1 AND name = ?2", owner, name).first();
        if (secret != null) {
            throw new SkillSecrets.Refused(("Skill '%s' on this agent has a secret named %s. A command finds a value by"
                    + " its name, so each name is used once on an agent.").formatted(secret.skillName, name));
        }
        SkillSetting elsewhere = SkillSetting.find("agent = ?1 AND name = ?2 AND skillName <> ?3", owner, name, skill).first();
        if (elsewhere != null) {
            throw new SkillSecrets.Refused(("Skill '%s' on this agent already has a setting named %s. Each name is used"
                    + " once on an agent; that skill's commands can use it too.").formatted(elsewhere.skillName, name));
        }
        SkillSetting row = SkillSetting.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        boolean created = row == null;
        if (row == null) {
            row = new SkillSetting();
            row.agent = owner;
            row.skillName = skill;
            row.name = name;
        }
        row.value = value;
        row.save();
        if (created) SkillLoader.clearCache();
        EventLogger.info(LOG_CATEGORY, owner.name, null,
                "Setting %s %s for skill %s".formatted(name, created ? "added" : "changed", skill));
        return new Listing(row.name, row.value, row.updatedAt);
    }

    public static boolean delete(Agent owner, String skill, String name) {
        SkillSetting row = SkillSetting.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        if (row == null) return false;
        row.delete();
        SkillLoader.clearCache();
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Setting %s deleted from skill %s".formatted(name, skill));
        return true;
    }

    /** Every setting of the agent, for a command's environment and for whether a skill is runnable. */
    public static List<Usable> usable(Agent owner) {
        List<SkillSetting> rows = SkillSetting.find("agent = ?1 ORDER BY skillName, name", owner).fetch();
        var out = new ArrayList<Usable>();
        for (var row : rows) out.add(new Usable(row.skillName, row.name, row.value));
        return out;
    }

    /** Delete every setting of a skill the operator removes from the agent, as its secrets go with it. */
    public static int deleteForSkill(Agent owner, String skill) {
        int deleted = SkillSetting.delete("agent = ?1 AND skillName = ?2", owner, skill);
        if (deleted > 0) SkillLoader.clearCache();
        return deleted;
    }
}
