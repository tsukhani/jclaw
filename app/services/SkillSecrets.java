package services;

import agents.SkillLoader;
import models.Agent;
import models.SkillSecret;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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
     * A secret a skill says it needs (JF-348), from {@value #DECLARATION_FILE} in its folder: what the panel offers
     * the operator to add. {@code exampleHosts} are the skill author's examples, never bound to anything. A secret is
     * required unless its description starts with the word "optional".
     */
    public record Declared(String name, String description, List<String> exampleHosts, boolean optional) {}

    /** The file a skill declares its secrets in: {@code NAME=example-host   # what it is}, one per line, no value. */
    public static final String DECLARATION_FILE = "secrets.example";
    private static final Pattern OPTIONAL = Pattern.compile("(?i)optional\\b.*", Pattern.DOTALL);
    private static final int MAX_DECLARATION_BYTES = 16_384;
    private static final int MAX_DECLARED = 32;
    private static final Pattern DECLARATION = Pattern.compile(
            "\\s*(?:export\\s+)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*([^#]*?)\\s*(?:#\\s*(.*?))?\\s*");

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
        var file = skillDir.resolve(DECLARATION_FILE);
        List<String> lines;
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_DECLARATION_BYTES) return List.of();
            lines = Files.readAllLines(file);
        } catch (IOException | UncheckedIOException _) {
            return List.of();
        }
        var out = new ArrayList<Declared>();
        var seen = new LinkedHashSet<String>();
        for (var line : lines) {
            if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
            var m = DECLARATION.matcher(line);
            if (!m.matches()) continue;
            var name = m.group(1);
            if (nameRejection(name) != null || !seen.add(name)) continue;
            var examples = new ArrayList<String>();
            for (var example : m.group(2).split("[,\\s]+")) {
                if (example.isBlank()) continue;
                try {
                    examples.addAll(normalizeHosts(List.of(example)));
                } catch (Refused _) {
                    // An example that is no host is left out; the secret is still declared.
                }
            }
            var description = m.group(3) == null ? "" : m.group(3).strip();
            out.add(new Declared(name, description, List.copyOf(examples), OPTIONAL.matcher(description).matches()));
            if (out.size() == MAX_DECLARED) break;
        }
        return List.copyOf(out);
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
         * The required secrets the skill in {@code skillDir} declares and cannot be given: while any is listed, the
         * skill is not runnable.
         */
        public List<String> missing(Path skillDir) {
            var declared = declared(skillDir);
            var folder = skillDir.getFileName();
            if (declared.isEmpty() || folder == null) return List.of();
            var out = new ArrayList<String>();
            for (var d : declared) {
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

    /** Why {@code name} cannot name a secret, or null when it can. */
    private static @Nullable String nameRejection(String name) {
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
