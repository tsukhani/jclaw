package services;

import agents.SkillLoader;
import models.Agent;
import models.SkillSecret;
import models.SkillSetting;
import org.jspecify.annotations.Nullable;
import utils.SecretRedactor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The operator's store of skill secrets (JF-323): what the Secrets panel lists, writes and deletes. Nothing here
 * returns a value. Every method takes the agent that owns the workspace, so a subagent's skill resolves to its root's.
 */
public final class SkillSecrets {

    public static final int MAX_VALUE_CHARS = 65_536;
    public static final int MAX_HOSTS = 16;
    private static final int MAX_HOST_CHARS = 253;
    private static final String LOG_CATEGORY = "skill";

    /** An environment-variable name, since the shell receives each secret under its name. */
    private static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern HOST_NAME = Pattern.compile(
            "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern WITH_PORT = Pattern.compile("(.+):(\\d{1,5})");

    /** Names a command's environment already carries, or that route and trust its HTTPS: a secret never replaces one. */
    private static final Set<String> RESERVED = Set.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR",
            "LANG", "TERM", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "NODE_EXTRA_CA_CERTS", "GIT_SSL_CAINFO");

    /** One secret as the panel shows it: never its value. */
    public record Listing(String name, List<String> hosts, Instant setAt) {}

    /**
     * A secret a skill says it needs (JF-348), from the {@value #DECLARATION_BLOCK} block of its SKILL.md: what the
     * panel offers the operator to add. {@code exampleHosts} are the skill author's examples, never bound to
     * anything. A secret is required unless its description starts with the word "optional".
     */
    public record Declared(String name, String description, List<String> exampleHosts, boolean optional) {}

    /**
     * A setting a skill says it needs (JF-357), from a {@code setting NAME=example   # what it is} line of the same
     * block: a value that is not secret, which the operator fills in on the panel. {@code example} is a hint only.
     */
    public record DeclaredSetting(String name, String description, String example, boolean optional) {}

    /** What a block declares: its secrets and its settings, each in the block's order. */
    public record Declarations(List<Declared> secrets, List<DeclaredSetting> settings) {}

    /**
     * The language of the fenced block in a skill's SKILL.md that declares its secrets (JF-353), one
     * {@code NAME=example-host   # what it is} per line and never a value. It sits in the file the agent already
     * reads, so the declaration and the instructions cannot drift apart, and no extra file needs a place in a skill.
     */
    public static final String DECLARATION_BLOCK = "secrets";
    private static final String SKILL_FILE = "SKILL.md";
    private static final Pattern FENCE = Pattern.compile(" {0,3}(`{3,}|~{3,})[ \\t]*([^\\s`]*).*");
    private static final Pattern OPTIONAL = Pattern.compile("(?i)optional\\b.*", Pattern.DOTALL);
    private static final int MAX_SKILL_BYTES = 262_144;
    private static final int MAX_DECLARED = 32;
    /** {@code [setting] [export] NAME=example   # description}: groups 1 the keyword, 2 the name, 3 the example, 4 the description. */
    private static final Pattern DECLARATION = Pattern.compile(
            "\\s*(?:(setting)\\s+)?(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^#]*?)\\s*(?:#\\s*(.*?))?\\s*");

    /** A secret with its value, for the proxy's grants alone: no route or tool ever returns one (JF-324). */
    public record Usable(String skill, String name, String value, List<String> hosts) {}

    /** A write the store refuses, with the message the operator reads. */
    public static final class Refused extends RuntimeException {
        private final String reason;

        public Refused(String reason) {
            super(reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }

    private SkillSecrets() {}

    public static List<Listing> list(Agent owner, String skill) {
        List<SkillSecret> rows = SkillSecret.find("agent = ?1 AND skillName = ?2 ORDER BY name", owner, skill).fetch();
        var out = new ArrayList<Listing>();
        for (var row : rows) out.add(listing(row));
        return out;
    }

    /**
     * Create or replace {@code name}. A null {@code value} keeps the stored one and changes only the hosts, so
     * replacing the hosts never needs the value typed again; a new secret needs one.
     */
    public static Listing put(Agent owner, String skill, String name, @Nullable String value, List<String> hosts) {
        var rejection = nameRejection(name);
        if (rejection != null) throw new Refused(rejection);
        if (value != null && value.isEmpty()) throw new Refused("The value is empty.");
        if (value != null && value.length() > MAX_VALUE_CHARS) {
            throw new Refused("The value is longer than %d characters.".formatted(MAX_VALUE_CHARS));
        }
        var normalized = normalizeHosts(hosts);
        SkillSetting setting = SkillSetting.find("agent = ?1 AND name = ?2", owner, name).first();
        if (setting != null) {
            throw new Refused(("Skill '%s' on this agent has a setting named %s. A command finds a value by its name, so"
                    + " each name is used once on an agent.").formatted(setting.skillName, name));
        }
        SkillSecret elsewhere = SkillSecret.find("agent = ?1 AND name = ?2 AND skillName <> ?3", owner, name, skill).first();
        if (elsewhere != null) {
            throw new Refused(("Skill '%s' on this agent already has a secret named %s. A command finds a secret by its"
                    + " name, so each name is used once on an agent; that skill's commands can use it too.")
                    .formatted(elsewhere.skillName, name));
        }
        SkillSecret row = SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        boolean created = row == null;
        if (row == null) {
            if (value == null) throw new Refused("A new secret needs a value.");
            row = new SkillSecret();
            row.agent = owner;
            row.skillName = skill;
            row.name = name;
        }
        if (value != null) row.value = value;
        row.hosts = String.join("\n", normalized);
        row.save();
        if (created) SkillLoader.clearCache();
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Secret %s %s for skill %s, sent only to %s".formatted(
                name, created ? "added" : value != null ? "replaced" : "kept, hosts changed", skill,
                String.join(", ", normalized)));
        return listing(row);
    }

    public static boolean delete(Agent owner, String skill, String name) {
        SkillSecret row = SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        if (row == null) return false;
        row.delete();
        SkillLoader.clearCache();
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Secret %s deleted from skill %s".formatted(name, skill));
        return true;
    }

    /**
     * What the skill in {@code skillDir} declares, in the file's order, each name once. A line that is not a
     * declaration, a name the store would refuse and an example that is no host are skipped: a skill's file never
     * breaks the panel. Empty when the skill ships no file.
     */
    public static List<Declared> declared(Path skillDir) {
        var skillMd = readSkillMd(skillDir);
        return skillMd == null ? List.of() : declaredIn(skillMd);
    }

    private static @Nullable String readSkillMd(Path skillDir) {
        var file = skillDir.resolve(SKILL_FILE);
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_SKILL_BYTES) return null;
            return Files.readString(file);
        } catch (IOException | UncheckedIOException _) {
            return null;
        }
    }

    /** The secrets {@code skillMd}'s declaration block declares; empty when it has none. */
    public static List<Declared> declaredIn(String skillMd) {
        var block = splitDeclaration(skillMd).block();
        return block == null ? List.of() : declarationsIn(block);
    }

    /**
     * A declaration block, fences included, written from parsed declarations alone: no comment line, no text that
     * did not parse, nothing after the fence. What it holds is only what {@link #declaredIn} and {@link #settingsIn}
     * would read back.
     */
    public static String declarationBlock(List<Declared> declared, List<DeclaredSetting> settings) {
        var out = new StringBuilder("```").append(DECLARATION_BLOCK).append('\n');
        for (var d : declared) {
            out.append(d.name()).append('=').append(String.join(", ", d.exampleHosts()));
            if (!d.description().isEmpty()) out.append("   # ").append(d.description());
            out.append('\n');
        }
        for (var d : settings) {
            out.append("setting ").append(d.name()).append('=').append(d.example());
            if (!d.description().isEmpty()) out.append("   # ").append(d.description());
            out.append('\n');
        }
        return out.append("```").toString();
    }

    /** {@code description} still marking the secret optional, so a rewritten description keeps what it meant. */
    public static String keepOptional(String description, boolean optional) {
        if (!optional || OPTIONAL.matcher(description).matches()) return description;
        return description.isEmpty() ? "Optional" : "Optional. " + description;
    }

    /** A SKILL.md apart: the lines inside its declaration block, or null when it has none, and everything else. */
    public record Split(@Nullable String block, String rest) {}

    /**
     * {@code skillMd} with its declaration block set apart: the first top-level fenced block whose language is
     * {@value #DECLARATION_BLOCK}. A block inside another fence is an example of the format, as in a guide that shows
     * it, and declares nothing; a block the file never closes declares nothing either.
     */
    public static Split splitDeclaration(String skillMd) {
        var lines = skillMd.split("\\R", -1);
        var at = declarationFences(lines);
        if (at == null) return new Split(null, skillMd);
        var block = new StringBuilder();
        var rest = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            (i > at.open() && i < at.close() ? block : rest).append(lines[i]).append('\n');
        }
        return new Split(block.toString(), rest.toString());
    }

    /** {@code fenced} is the declaration block with its fences; {@code text} has {@code replacement} in its place. */
    public record Lifted(String fenced, String block, String text) {}

    /** {@code skillMd} with its declaration block, fences included, replaced by {@code replacement}; null when none. */
    public static @Nullable Lifted liftDeclaration(String skillMd, String replacement) {
        var lines = skillMd.split("\\R", -1);
        var at = declarationFences(lines);
        if (at == null) return null;
        var fenced = String.join("\n", Arrays.asList(lines).subList(at.open(), at.close() + 1));
        var block = new StringBuilder();
        for (int i = at.open() + 1; i < at.close(); i++) block.append(lines[i]).append('\n');
        var text = new ArrayList<String>(Arrays.asList(lines).subList(0, at.open()));
        text.add(replacement);
        text.addAll(Arrays.asList(lines).subList(at.close() + 1, lines.length));
        return new Lifted(fenced, block.toString(), String.join("\n", text));
    }

    private record Fences(int open, int close) {}

    private static @Nullable Fences declarationFences(String[] lines) {
        String fence = null;
        boolean declaring = false;
        int open = -1;
        for (int i = 0; i < lines.length; i++) {
            if (fence == null) {
                var m = FENCE.matcher(lines[i]);
                if (m.matches()) {
                    fence = m.group(1);
                    declaring = m.group(2).equals(DECLARATION_BLOCK);
                    open = i;
                }
            } else if (closes(lines[i], fence)) {
                if (declaring) return new Fences(open, i);
                fence = null;
                declaring = false;
            }
        }
        return null;
    }

    /** A fence closes on a run of its own character, at least as long, with nothing after it. */
    private static boolean closes(String line, String fence) {
        var run = line.strip();
        return run.length() >= fence.length() && run.chars().allMatch(c -> c == fence.charAt(0));
    }

    private static List<Declared> declarationsIn(String block) {
        return parse(block).secrets();
    }

    /** The settings {@code skillMd}'s declaration block declares; empty when it has none. */
    public static List<DeclaredSetting> settingsIn(String skillMd) {
        var block = splitDeclaration(skillMd).block();
        return block == null ? List.of() : parse(block).settings();
    }

    /** The settings the skill in {@code skillDir} declares in its SKILL.md. */
    public static List<DeclaredSetting> declaredSettings(Path skillDir) {
        var skillMd = readSkillMd(skillDir);
        return skillMd == null ? List.of() : settingsIn(skillMd);
    }

    private static Declarations parse(String block) {
        var secrets = new ArrayList<Declared>();
        var settings = new ArrayList<DeclaredSetting>();
        var seen = new LinkedHashSet<String>();
        for (var line : block.split("\\R")) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var m = DECLARATION.matcher(line);
            if (!m.matches()) continue;
            var name = m.group(2);
            if (nameRejection(name) != null || !seen.add(name)) continue;
            var description = m.group(4) == null ? "" : m.group(4).strip();
            boolean optional = OPTIONAL.matcher(description).matches();
            if (m.group(1) != null) {
                settings.add(new DeclaredSetting(name, description, m.group(3).strip(), optional));
            } else {
                var examples = new ArrayList<String>();
                for (var example : m.group(3).split("[,\\s]+")) {
                    if (example.isBlank()) continue;
                    try {
                        examples.addAll(normalizeHosts(List.of(example)));
                    } catch (Refused _) {
                        // An example that is no host is left out; the secret is still declared.
                    }
                }
                secrets.add(new Declared(name, description, List.copyOf(examples), optional));
            }
            if (secrets.size() + settings.size() == MAX_DECLARED) break;
        }
        return new Declarations(List.copyOf(secrets), List.copyOf(settings));
    }

    /**
     * Which of an agent's secrets a skill can be given (JF-348), read once and only when a skill declares one. A
     * command gets a secret only from a skill that is switched on (JF-326), so a secret counts for a skill when the
     * skill holds it itself or a switched-on skill of the agent does. A name is used once on an agent.
     */
    public static final class Provided {
        private final Agent owner;
        private final Supplier<Set<String>> enabledFolders;
        private @Nullable Map<String, String> holders;
        private @Nullable Set<String> enabled;

        private Provided(Agent owner, Supplier<Set<String>> enabledFolders) {
            this.owner = owner;
            this.enabledFolders = enabledFolders;
        }

        private Map<String, String> holders() {
            var known = holders;
            if (known == null) {
                known = new LinkedHashMap<>();
                for (var secret : usable(owner)) known.put(secret.name(), secret.skill());
                // A name is used once on an agent, so a setting never shares one with a secret (JF-357).
                for (var setting : SkillSettings.usable(owner)) known.put(setting.name(), setting.skill());
                holders = known;
            }
            return known;
        }

        private Set<String> enabled() {
            var known = enabled;
            if (known == null) {
                known = enabledFolders.get();
                enabled = known;
            }
            return known;
        }

        /** The skill that holds {@code name} on the agent, switched on or not, or null when none does. */
        public @Nullable String holder(String name) {
            return holders().get(name);
        }

        /** Whether the skill in folder {@code skill} can be given {@code name}. */
        public boolean to(String skill, String name) {
            var holder = holder(name);
            return holder != null && (holder.equals(skill) || enabled().contains(holder));
        }

        /**
         * The required secrets and settings the skill in {@code skillDir} declares and cannot be given: while any is
         * listed, the skill is not runnable.
         */
        public List<String> missing(Path skillDir) {
            var skillMd = readSkillMd(skillDir);
            var folder = skillDir.getFileName();
            var block = skillMd == null ? null : splitDeclaration(skillMd).block();
            if (block == null || folder == null) return List.of();
            var declarations = parse(block);
            var out = new ArrayList<String>();
            for (var d : declarations.secrets()) {
                if (!d.optional() && !to(folder.toString(), d.name())) out.add(d.name());
            }
            for (var d : declarations.settings()) {
                if (!d.optional() && !to(folder.toString(), d.name())) out.add(d.name());
            }
            return List.copyOf(out);
        }
    }

    /** What the skills of {@code owner} can be given, by the skills the loader says are switched on. */
    public static Provided provided(Agent owner) {
        return new Provided(owner, () -> SkillLoader.enabledSkillFolders(owner.name));
    }

    /** As {@link #provided(Agent)}, for the loader itself, which cannot ask its own cache while filling it. */
    public static Provided provided(Agent owner, Supplier<Set<String>> enabledFolders) {
        return new Provided(owner, enabledFolders);
    }

    /**
     * Whether a declaration block's text holds something that is not a declaration (JF-353): a name or a word where
     * the example host belongs that looks like a key, or anything {@link SecretRedactor} masks in a comment, a
     * description or a line that declares nothing. Only the {@code NAME=} that starts a declaration is set aside:
     * naming a secret is what the block is for, so {@code API_KEY=api.example.com} is no finding, though the redactor
     * alone would mask it.
     */
    public static boolean holdsMoreThanDeclarations(String text) {
        for (var line : text.split("\\R")) {
            if (line.isBlank()) continue;
            var m = DECLARATION.matcher(line);
            if (line.stripLeading().startsWith("#") || !m.matches()) {
                if (masked(line.strip().replaceFirst("^#+", ""))) return true;
                continue;
            }
            if (masked(m.group(2)) || looksRandom(m.group(2).split("_"))) return true;
            if (m.group(1) != null) {
                // A setting's example is free text, such as an address with a path; it may hold no key.
                if (masked(m.group(3)) || looksRandom(m.group(3).split("[^A-Za-z0-9]+"))) return true;
            } else {
                for (var example : m.group(3).split("[,\\s]+")) {
                    if (example.isBlank()) continue;
                    if (!isPlausibleExampleHost(example) || masked(example)) return true;
                }
            }
            if (m.group(4) != null && masked(m.group(4))) return true;
        }
        return false;
    }

    /** A part longer than 24 characters that mixes letters and digits reads as a key, not a word or a label. */
    private static boolean looksRandom(String[] parts) {
        for (var part : parts) {
            if (part.length() > 24 && part.chars().anyMatch(Character::isLetter) && part.chars().anyMatch(Character::isDigit)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A host name or address as the store takes one, with a dot unless it is localhost, and no long label mixing
     * letters and digits: a key written where the host belongs is one unbroken word, or a dotted run of random ones.
     */
    private static boolean isPlausibleExampleHost(String example) {
        String host;
        try {
            host = normalizeHosts(List.of(example)).getFirst();
        } catch (Refused _) {
            return false;
        }
        return (host.contains(".") || host.startsWith("localhost")) && !looksRandom(host.split("[.:]"));
    }

    private static boolean masked(String text) {
        return !SecretRedactor.redact(text).equals(text);
    }


    /** Why {@code name} cannot name a secret, or null when it can. */
    static @Nullable String nameRejection(String name) {
        if (!NAME.matcher(name).matches()) {
            return "'%s' is not a secret name: use capital letters, digits and underscores, starting with a"
                    .formatted(name) + " letter, at most 64 characters, as in NAS_PASSWORD.";
        }
        if (RESERVED.contains(name) || name.startsWith("JCLAW_")) {
            return "'%s' is a name a command's environment already uses; choose another.".formatted(name);
        }
        return null;
    }

    /** The agent's secrets whose values open, for a command's grant (JF-324). One that will not open is left out. */
    public static List<Usable> usable(Agent owner) {
        List<SkillSecret> rows = SkillSecret.find("agent = ?1 ORDER BY skillName, name", owner).fetch();
        var out = new ArrayList<Usable>();
        for (var row : rows) {
            if (row.value != null) out.add(new Usable(row.skillName, row.name, row.value, List.of(row.hosts.split("\n"))));
        }
        return out;
    }

    /** Delete every secret of a skill the operator removes from the agent, as its credentials folder goes with it. */
    public static int deleteForSkill(Agent owner, String skill) {
        int deleted = SkillSecret.delete("agent = ?1 AND skillName = ?2", owner, skill);
        if (deleted > 0) SkillLoader.clearCache();
        return deleted;
    }

    /**
     * The hosts as stored: trimmed, lowercase, without a trailing dot, each once, in the order given. Refuses anything
     * but a bare host name or IPv4 address with an optional port: no scheme, path, wildcard or user.
     */
    public static List<String> normalizeHosts(List<String> raw) {
        if (raw.isEmpty()) throw new Refused("Add at least one host the secret may be sent to.");
        var out = new LinkedHashSet<String>();
        for (var entry : raw) {
            var host = entry.strip().toLowerCase(Locale.ROOT);
            if (host.isEmpty()) continue;
            String port = null;
            var withPort = WITH_PORT.matcher(host);
            if (withPort.matches()) {
                host = withPort.group(1);
                port = withPort.group(2);
                int number = Integer.parseInt(port);
                if (number < 1 || number > 65_535) throw new Refused("'%s' has no valid port.".formatted(entry.strip()));
                port = String.valueOf(number);
            }
            if (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            if (host.length() > MAX_HOST_CHARS || !HOST_NAME.matcher(host).matches()) {
                throw new Refused(("'%s' is not a host: give the name alone, such as api.example.com or"
                        + " api.example.com:8443, with no https://, path or *.").formatted(entry.strip()));
            }
            out.add(port == null ? host : host + ":" + port);
        }
        if (out.isEmpty()) throw new Refused("Add at least one host the secret may be sent to.");
        if (out.size() > MAX_HOSTS) throw new Refused("A secret may name at most %d hosts.".formatted(MAX_HOSTS));
        return List.copyOf(out);
    }

    private static Listing listing(SkillSecret row) {
        return new Listing(row.name, List.of(row.hosts.split("\n")), row.updatedAt);
    }
}
