package services;

import models.Agent;
import models.AgentSkillConfig;
import org.jspecify.annotations.Nullable;
import utils.WorkspacePathGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What deleting or switching off an agent's skill does to the values it holds (JF-356). A name is used once on an
 * agent, and a command gets a value only from a skill that is switched on, so the agent's other skills that read one
 * by name stop when its holder goes. A delete moves each value to another skill that declares it, a switched-on one
 * first, then the first by name; a switch-off moves nothing, and names the switched-on skills that stop.
 */
public final class SkillRemoval {

    private static final String SKILLS_DIR = "skills";
    private static final String SKILL_FILE = "SKILL.md";
    private static final String CREDENTIALS_DIR = "credentials";

    public enum Kind { SECRET, SETTING }

    /**
     * A value the skill holds. {@code movesTo} is the skill a delete moves it to, or null when no other skill of the
     * agent declares it and the delete removes it; {@code usedBy} is every other skill that declares it, by name.
     */
    public record Value(Kind kind, String name, @Nullable String movesTo, List<String> usedBy) {}

    /** A switched-on skill that stops when the skill is switched off, and the names it needs from it. */
    public record Stop(String skill, List<String> names) {}

    /**
     * Everything a delete of the skill removes or moves, and what a switch-off stops. {@code credentialFiles} are the
     * files with content in its own {@code credentials/} folder, where skills kept their keys before the secrets store.
     * {@link #plan} names that exact outcome, so a delete confirmed against one the operator was shown can be refused
     * when anything has changed.
     */
    public record Impact(List<Value> values, boolean savedState, List<String> credentialFiles, int keptFiles,
                         List<Stop> stopsIfSwitchedOff) {

        /** Whether a delete asks first: it would remove something that may hold a key or a sign-in. */
        public boolean asks() {
            return !values.isEmpty() || savedState || !credentialFiles.isEmpty() || keptFiles > 0;
        }

        public String plan() {
            var text = new StringBuilder();
            for (var v : values) {
                text.append(v.kind()).append(' ').append(v.name()).append(" -> ")
                        .append(v.movesTo() == null ? "-" : v.movesTo()).append('\n');
            }
            for (var file : credentialFiles) text.append("file ").append(file).append('\n');
            text.append("state ").append(savedState).append("\nkept ").append(keptFiles);
            try {
                var digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
                return HexFormat.of().formatHex(digest, 0, 8);
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-256 is required of every JDK", e);
            }
        }
    }

    /** What the agent's other installed skills declare: each name, and whether they need it. */
    private record Declares(Map<String, Boolean> secrets, Map<String, Boolean> settings) {}

    private SkillRemoval() {}

    /** The impact of deleting or switching off {@code skill} on {@code agent}, whose values live with its workspace owner. */
    public static Impact of(Agent agent, String skill) {
        var owner = WorkspaceFiles.workspaceOwner(agent);
        var others = otherSkills(owner, skill);
        var enabled = switchedOn(owner, others.keySet());
        var values = new ArrayList<Value>();
        var serving = new TreeMap<String, Kind>();
        for (var secret : SkillSecrets.list(owner, skill)) {
            values.add(value(Kind.SECRET, secret.name(), others, enabled));
            // A renewed secret with no value yet serves no other skill: its own sign-in fills it.
            if (secret.hasValue()) serving.put(secret.name(), Kind.SECRET);
        }
        for (var setting : SkillSettings.list(owner, skill)) {
            values.add(value(Kind.SETTING, setting.name(), others, enabled));
            serving.put(setting.name(), Kind.SETTING);
        }
        var workspace = AgentService.workspacePath(agent.name);
        return new Impact(List.copyOf(values), hasSavedState(workspace, skill), credentialFiles(workspace, skill),
                keptFiles(owner, skill), stops(serving, others, enabled));
    }

    /**
     * Move each value {@code impact} says moves, inside the caller's transaction and before the skill's own values are
     * deleted. Returns the values moved.
     */
    public static List<Value> move(Agent agent, String skill, Impact impact) {
        var owner = WorkspaceFiles.workspaceOwner(agent);
        var moved = new ArrayList<Value>();
        for (var v : impact.values()) {
            var to = v.movesTo();
            if (to == null) continue;
            boolean done = v.kind() == Kind.SECRET
                    ? SkillSecrets.moveToSkill(owner, skill, v.name(), to)
                    : SkillSettings.moveToSkill(owner, skill, v.name(), to);
            if (done) moved.add(v);
        }
        return List.copyOf(moved);
    }

    /**
     * Of {@code folders}, those the operator has not switched off. Read from the switches themselves rather than the
     * loader's cached scan, which a switch would otherwise wait on while a scan is running.
     */
    private static Set<String> switchedOn(Agent owner, Set<String> folders) {
        var on = new TreeSet<>(folders);
        List<AgentSkillConfig> off = AgentSkillConfig.find("agent = ?1 AND enabled = false", owner).fetch();
        for (var config : off) on.remove(config.skillName);
        return on;
    }

    private static Value value(Kind kind, String name, Map<String, Declares> others, Set<String> enabled) {
        var usedBy = new ArrayList<String>();
        for (var other : others.entrySet()) {
            var declared = kind == Kind.SECRET ? other.getValue().secrets() : other.getValue().settings();
            if (declared.containsKey(name)) usedBy.add(other.getKey());
        }
        var movesTo = usedBy.stream().filter(enabled::contains).findFirst()
                .orElse(usedBy.isEmpty() ? null : usedBy.getFirst());
        return new Value(kind, name, movesTo, List.copyOf(usedBy));
    }

    private static List<Stop> stops(Map<String, Kind> serving, Map<String, Declares> others, Set<String> enabled) {
        var out = new ArrayList<Stop>();
        for (var other : others.entrySet()) {
            if (!enabled.contains(other.getKey())) continue;
            var names = new ArrayList<String>();
            serving.forEach((name, kind) -> {
                var declared = kind == Kind.SECRET ? other.getValue().secrets() : other.getValue().settings();
                if (Boolean.TRUE.equals(declared.get(name))) names.add(name);
            });
            if (!names.isEmpty()) out.add(new Stop(other.getKey(), List.copyOf(names)));
        }
        return List.copyOf(out);
    }

    /** The owner's installed skills other than {@code skill}, by folder name, as {@link SkillSecrets.Provided} finds them. */
    private static Map<String, Declares> otherSkills(Agent owner, String skill) {
        var skills = AgentService.workspacePath(owner.name).resolve(SKILLS_DIR);
        var out = new TreeMap<String, Declares>();
        if (!Files.isDirectory(skills)) return out;
        try (var entries = Files.newDirectoryStream(skills)) {
            for (var entry : entries) {
                var name = entry.getFileName().toString();
                if (name.equals(skill)) continue;
                var dir = WorkspaceFiles.resolveContained(skills, name);
                if (dir == null || !Files.isRegularFile(dir.resolve(SKILL_FILE))) continue;
                out.put(name, declares(dir));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }

    // Required as Provided#missing counts it: a renewed secret is filled by its own skill's sign-in.
    private static Declares declares(Path dir) {
        var secrets = new TreeMap<String, Boolean>();
        for (var d : SkillSecrets.declared(dir)) secrets.put(d.name(), !d.optional() && !d.renewed());
        var settings = new TreeMap<String, Boolean>();
        for (var d : SkillSecrets.declaredSettings(dir)) settings.put(d.name(), !d.optional());
        return new Declares(secrets, settings);
    }

    /** Whether the skill's {@code skill-state/} folder holds anything, judged as its delete judges it: never through a link. */
    private static boolean hasSavedState(Path workspace, String skill) {
        if (!WorkspacePathGuard.isOneSegment(skill)) return false;
        var root = workspace.resolve(WorkspacePathGuard.SKILL_STATE_DIR);
        var state = root.resolve(skill);
        if (Files.isSymbolicLink(root) || !Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) return false;
        try (var entries = Files.newDirectoryStream(state)) {
            return entries.iterator().hasNext();
        } catch (IOException _) {
            return false;
        }
    }

    /** The files with content under the skill's {@code credentials/} folder, any letter case, never through a link. */
    private static List<String> credentialFiles(Path workspace, String skill) {
        if (!WorkspacePathGuard.isOneSegment(skill)) return List.of();
        var dir = workspace.resolve(SKILLS_DIR).resolve(skill);
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var out = new TreeSet<String>();
        try (var entries = Files.newDirectoryStream(dir)) {
            for (var entry : entries) {
                if (!entry.getFileName().toString().equalsIgnoreCase(CREDENTIALS_DIR)
                        || !Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                try (var walk = Files.walk(entry)) {
                    walk.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && hasContent(file))
                            .forEach(file -> out.add(dir.relativize(file).toString().replace('\\', '/')));
                }
            }
        } catch (IOException | UncheckedIOException _) {
            return List.copyOf(out);
        }
        return List.copyOf(out);
    }

    private static boolean hasContent(Path file) {
        try {
            return Files.size(file) > 0;
        } catch (IOException _) {
            return false;
        }
    }

    private static int keptFiles(Agent owner, String skill) {
        try {
            return KeptSkillFiles.list(owner, skill).size();
        } catch (IOException | UncheckedIOException _) {
            return 0;
        }
    }
}
