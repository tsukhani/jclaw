package services;

import models.Agent;
import models.SkillSecret;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
        if (!NAME.matcher(name).matches()) {
            throw new Refused("'%s' is not a secret name: use capital letters, digits and underscores, starting with a"
                    .formatted(name) + " letter, at most 64 characters, as in NAS_PASSWORD.");
        }
        if (RESERVED.contains(name) || name.startsWith("JCLAW_")) {
            throw new Refused("'%s' is a name a command's environment already uses; choose another.".formatted(name));
        }
        if (value != null && value.isEmpty()) throw new Refused("The value is empty.");
        if (value != null && value.length() > MAX_VALUE_CHARS) {
            throw new Refused("The value is longer than %d characters.".formatted(MAX_VALUE_CHARS));
        }
        var normalized = normalizeHosts(hosts);
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
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Secret %s %s for skill %s, sent only to %s".formatted(
                name, created ? "added" : value != null ? "replaced" : "kept, hosts changed", skill,
                String.join(", ", normalized)));
        return listing(row);
    }

    public static boolean delete(Agent owner, String skill, String name) {
        SkillSecret row = SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        if (row == null) return false;
        row.delete();
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Secret %s deleted from skill %s".formatted(name, skill));
        return true;
    }

    /** Delete every secret of a skill the operator removes from the agent, as its credentials folder goes with it. */
    public static int deleteForSkill(Agent owner, String skill) {
        return SkillSecret.delete("agent = ?1 AND skillName = ?2", owner, skill);
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
