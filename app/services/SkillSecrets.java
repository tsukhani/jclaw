package services;

import agents.SkillLoader;
import models.Agent;
import models.AgentSkillConfig;
import models.SkillSecret;
import models.SkillSetting;
import org.jspecify.annotations.Nullable;
import services.secrets.SecretProxy;
import utils.AppClock;
import utils.SecretRedactor;
import utils.UrlPaths;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The operator's store of skill secrets (JF-323): what the Secrets panel lists, writes and deletes. Only
 * {@link #usable}, for a command's grant, returns a value. Every method takes the agent that owns the workspace, so a
 * subagent's skill resolves to its root's.
 */
public final class SkillSecrets {

    public static final int MAX_VALUE_CHARS = 65_536;
    public static final int MAX_HOSTS = 16;
    private static final int MAX_HOST_CHARS = 253;
    public static final String LOG_CATEGORY = "skill";

    /** An environment-variable name, since the shell receives each secret under its name. */
    static final Pattern NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    // Possessive: every label before the last ends in a dot, so giving one back never helps a match (java:S5998).
    private static final Pattern HOST_NAME = Pattern.compile(
            "(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");
    private static final Pattern WITH_PORT = Pattern.compile("(.+):(\\d{1,5})");

    /** Names a command's environment already carries, or that route and trust its HTTPS: a secret never replaces one. */
    private static final Set<String> RESERVED = Set.of("PATH", "HOME", "USER", "LOGNAME", "SHELL", "PWD", "TMPDIR",
            "LANG", "TERM", "HTTPS_PROXY", "HTTP_PROXY", "ALL_PROXY", "NO_PROXY", "SSL_CERT_FILE", "SSL_CERT_DIR",
            "REQUESTS_CA_BUNDLE", "CURL_CA_BUNDLE", "NODE_EXTRA_CA_CERTS", "GIT_SSL_CAINFO");

    /**
     * One secret as the panel shows it: never its value, only whether it has one. {@code capture} is a renewed
     * secret's rule, and {@code renewedAt} when the secrets proxy last stored a value under it (JF-374).
     */
    public record Listing(String name, List<String> hosts, Instant setAt, boolean hasValue, @Nullable Capture capture,
                          @Nullable Instant renewedAt) {}

    /**
     * A secret a skill says it needs (JF-348), from the {@value #DECLARATION_BLOCK} block of its SKILL.md: what the
     * panel offers the operator to add. {@code exampleHosts} are the skill author's examples, never bound to
     * anything. A secret is required unless its description starts with the word "optional". A {@code renewed} one
     * (JF-374) is never required, since it starts empty, and {@code suggested} is the capture rule its line suggests:
     * a hint like the hosts, which binds nothing until the operator saves it. {@code unreadableSuggestion} is the text
     * after {@code from} when it is no rule the store would take.
     */
    public record Declared(String name, String description, List<String> exampleHosts, boolean optional, boolean renewed,
                           @Nullable Capture suggested, @Nullable String unreadableSuggestion) {

        public Declared(String name, String description, List<String> exampleHosts, boolean optional, boolean renewed,
                        @Nullable Capture suggested) {
            this(name, description, exampleHosts, optional, renewed, suggested, null);
        }

        public Declared(String name, String description, List<String> exampleHosts, boolean optional) {
            this(name, description, exampleHosts, optional, false, null);
        }
    }

    /**
     * Where the secrets proxy takes a renewed secret's new value (JF-374): {@code field}, a top-level JSON field or a
     * response header, of a success or redirect from one of the secret's hosts answering {@code method} on
     * {@code path}, sent with the stand-in of {@code credential}, one of the skill's secrets, once in
     * {@code credentialIn} and nowhere else: a sign-in made with other credentials is whoever supplied them, and the
     * answer to one that sent the credential any other way is withheld. The method is kept in
     * capitals and the path normalized ({@link UrlPaths#normalize}), so they compare as a server routes them.
     */
    public record Capture(String method, String path, From from, String field, String credential, Place credentialIn) {

        /**
         * Where a sign-in sends its credential: a top-level JSON field, a form field (multipart included), which may
         * name a nested one as {@code user[password]} does, a header, whose value may follow one scheme word such as
         * {@code Bearer}, or the password of Basic auth.
         */
        public record Place(Kind kind, @Nullable String name) {

            public enum Kind { JSON, FORM, HEADER, BASIC }

            /** How the API, a SKILL.md line and an event spell it, as in {@code json password} or {@code basic}. */
            public String wire() {
                var kind = kind().name().toLowerCase(Locale.ROOT);
                return name == null ? kind : kind + " " + name;
            }

            /** {@code text} as the operator typed it, or null when it names no place. */
            static @Nullable Place of(String text) {
                var words = text.strip().split("\\s+");
                Kind kind = null;
                for (var k : Kind.values()) {
                    if (k.name().equalsIgnoreCase(words[0])) kind = k;
                }
                if (kind == null) return null;
                if (kind == Kind.BASIC) return words.length == 1 ? new Place(kind, null) : null;
                var field = kind == Kind.FORM ? FORM_FIELD_SHAPE : FIELD_SHAPE;
                if (words.length != 2 || !field.matcher(words[1]).matches()) return null;
                return new Place(kind, words[1]);
            }
        }

        /** Where the value sits in the answer. */
        public enum From {
            JSON, HEADER;

            /** How the API and a SKILL.md line spell it. */
            public String wire() {
                return name().toLowerCase(Locale.ROOT);
            }

            static @Nullable From of(String wire) {
                for (var from : values()) {
                    if (from.wire().equalsIgnoreCase(wire.strip())) return from;
                }
                return null;
            }
        }

        private static final Pattern METHOD_SHAPE = Pattern.compile("[A-Za-z]{1,16}");
        private static final Pattern PATH_SHAPE = Pattern.compile("/[A-Za-z0-9\\-._~!$&'()*+,;=:@%/]{0,1023}");
        private static final Pattern FIELD_SHAPE = Pattern.compile("[A-Za-z0-9_$-]{1,128}");
        /** A form field may name a nested one, as in {@code user[password]} or {@code user.password}. */
        private static final Pattern FORM_FIELD_SHAPE = Pattern.compile("[A-Za-z0-9_$.\\[\\]-]{1,128}");
        private static final Set<String> COOKIE_HEADERS = Set.of("set-cookie", "set-cookie2", "cookie");

        /**
         * A rule from its parts as the operator typed them; {@code from} left out means {@code json}. Whether
         * {@code credential} is a secret of the rule's skill is {@link SkillSecrets#put}'s to judge.
         */
        public static Capture of(@Nullable String method, @Nullable String path, @Nullable String from,
                                 @Nullable String field, @Nullable String credential, @Nullable String credentialIn) {
            if (method == null || method.isBlank() || path == null || path.isBlank() || field == null || field.isBlank()) {
                throw new Refused("A capture rule needs a method, a path and a field.");
            }
            if (credential == null || credential.isBlank() || credentialIn == null || credentialIn.isBlank()) {
                throw new Refused("A capture rule needs the secret its sign-in sends and where it sends it, such as"
                        + " BACKOFFICE_PASSWORD in json password: JClaw takes a value only from a sign-in made with it.");
            }
            var m = method.strip();
            if (!METHOD_SHAPE.matcher(m).matches()) {
                throw new Refused("'%s' is not an HTTP method: give one such as POST.".formatted(m));
            }
            var p = path.strip();
            var normalized = PATH_SHAPE.matcher(p).matches() ? UrlPaths.normalize(p) : null;
            if (normalized == null) {
                throw new Refused(("'%s' is not a path a rule can match: give it as the service receives it, starting"
                        + " with /, such as /v1/sessions/current, with no host, query, space, encoded slash or backslash.")
                        .formatted(p));
            }
            var where = From.JSON;
            if (from != null && !from.isBlank()) {
                var named = From.of(from);
                if (named == null) {
                    throw new Refused("'%s' is not where a value can be taken from: give json or header."
                            .formatted(from.strip()));
                }
                where = named;
            }
            var f = field.strip();
            if (!FIELD_SHAPE.matcher(f).matches()) {
                throw new Refused(("'%s' is not a field name: give the JSON field or the header by its name alone, such"
                        + " as session or X-Session.").formatted(f));
            }
            if (where == From.HEADER && COOKIE_HEADERS.contains(f.toLowerCase(Locale.ROOT))) {
                throw new Refused(("'%s' is a cookie header: a rule cannot take a cookie, which the skill keeps in its own"
                        + " cookie jar.").formatted(f));
            }
            var c = credential.strip();
            if (!NAME.matcher(c).matches()) {
                throw new Refused("'%s' is not a secret name: give the secret the sign-in sends, such as BACKOFFICE_PASSWORD."
                        .formatted(c));
            }
            var place = Place.of(credentialIn);
            var header = place == null || place.kind() != Place.Kind.HEADER ? null : place.name();
            if (place == null || header != null && !SecretProxy.forwardsHeader(header)) {
                throw new Refused(("'%s' is not where a sign-in sends a secret: give json, form or header and the field's"
                        + " or header's name, such as json password, form user[password] or header api-key, or basic for"
                        + " the password of Basic auth. A JSON field is one at the top level. A header the secrets proxy"
                        + " does not forward, such as Host, cannot be one.")
                        .formatted(credentialIn.strip()));
            }
            return new Capture(m.toUpperCase(Locale.ROOT), normalized, where, f, c, place);
        }

        /** The rule as a SKILL.md line writes it after {@code from}, and as an event names it. */
        public String describe() {
            return method + " " + path + " " + from.wire() + " " + field + " using " + credential + " in "
                    + credentialIn.wire();
        }
    }

    /** A renewed secret's rule with the hosts it is bound to, for the secrets proxy: never its value. */
    public record Renewable(String skill, String name, List<String> hosts, Capture capture) {}

    /**
     * A setting a skill says it needs (JF-357), from a {@code setting NAME=example   # what it is} line of the same
     * block: a value that is not secret, which the operator fills in on the panel. {@code example} is a hint only.
     */
    public record DeclaredSetting(String name, String description, String example, boolean optional) {}

    /**
     * The skill of an agent that holds a value another of its skills lacks (JF-327): {@code switchedOff} when its switch
     * is off, {@code offered} when the agent is offered it: on, with every tool it needs and no value of its own
     * missing. {@code filledBySignIn} when it holds the name as a renewed secret with no value yet, which its sign-in
     * fills (JF-374). Not {@code installed} when its folder is gone: it still holds the name, which no other skill can
     * then add.
     */
    public record Holder(String skill, boolean switchedOff, boolean filledBySignIn, boolean offered, boolean installed) {

        /** {@code offered}, {@code switchedOff}, {@code notOffered} or {@code notInstalled}, as the API names it. */
        public String state() {
            if (!installed) return "notInstalled";
            if (switchedOff) return "switchedOff";
            return offered ? "offered" : "notOffered";
        }

        /** Of {@code holders}, as {@link Provided#holdersOf} returns them, each holder of a value whose switch is off. */
        public static Map<String, String> switchedOff(Map<String, Holder> holders) {
            var out = new LinkedHashMap<String, String>();
            holders.forEach((name, held) -> {
                if (held.switchedOff() && !held.filledBySignIn()) out.put(name, held.skill());
            });
            return out;
        }

        /** Of {@code holders}, each holder of a value that is switched on but not offered. */
        public static Map<String, String> notOffered(Map<String, Holder> holders) {
            var out = new LinkedHashMap<String, String>();
            holders.forEach((name, held) -> {
                if (!held.switchedOff() && !held.filledBySignIn()) out.put(name, held.skill());
            });
            return out;
        }

        /** Of {@code holders}, each whose sign-in fills the name. */
        public static Map<String, Holder> signIn(Map<String, Holder> holders) {
            var out = new LinkedHashMap<String, Holder>();
            holders.forEach((name, held) -> {
                if (held.filledBySignIn()) out.put(name, held);
            });
            return out;
        }
    }

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
    /**
     * A part of a name (between underscores) that reads as a key, token or password (JF-357). Such a name is always a
     * secret: the {@code setting} keyword is written by whoever writes SKILL.md, the agent included, and a password
     * declared as a setting would reach any command in clear, with no host limit.
     */
    private static final Pattern CREDENTIAL_PART = Pattern.compile(
            "[A-Z0-9]*KEY|PASS[A-Z0-9]*|PWD|TOKEN[A-Z0-9]*|SECRET[A-Z0-9]*|CREDENTIALS?|COOKIES?|AUTH[A-Z0-9]*|SESSION[A-Z0-9]*|PIN");

    /**
     * {@code [setting|renewed] [export] NAME=example   # description}: groups 1 the keyword, 2 the name, 3 the
     * example, 4 the description.
     */
    private static final Pattern DECLARATION = Pattern.compile(
            "\\s*(?:(setting|renewed)\\s+)?(?:export\\s+)?([A-Za-z_]\\w*)\\s*=\\s*([^#]*?)\\s*(?:#\\s*(.*?))?\\s*");
    private static final String RENEWED = "renewed";
    /** Stored rules already warned about, by row id and when it was saved. */
    private static final Set<String> UNREADABLE_RULES_WARNED = ConcurrentHashMap.newKeySet();
    /**
     * What opens a renewed secret's suggested rule (JF-374), in
     * {@code example-hosts from METHOD /path json|header FIELD using NAME in json|form|header FIELD} or {@code in basic}:
     * the first {@code from} standing alone, so a host such as {@code api.from.example.com} opens none, and a field
     * named {@code from} stays in the rule.
     */
    private static final Pattern SUGGESTED_RULE = Pattern.compile("(?:^|\\s)from(?=\\s|$)");

    /**
     * A secret with its value, for the proxy's grants alone: no route or tool ever returns one (JF-324). {@code renewed}
     * when its value is one the secrets proxy took under its capture rule (JF-374), so a running command takes it
     * rather than losing the secret; a value the operator typed stops it, as any secret's change does (JF-390).
     */
    public record Usable(String skill, String name, String value, List<String> hosts, boolean renewed) {

        public Usable(String skill, String name, String value, List<String> hosts) {
            this(skill, name, value, hosts, false);
        }
    }

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

    /** The log's word for what {@link #put} did to a secret. */
    private static String putVerb(boolean created, boolean valueChanged, boolean hostsChanged) {
        if (created) return "added";
        if (valueChanged) return "replaced";
        return hostsChanged ? "kept, hosts changed" : "kept";
    }

    /**
     * Create or replace {@code name}, keeping its capture rule. A null {@code value} keeps the stored one and changes
     * only the hosts, so replacing the hosts never needs the value typed again; a new secret needs one.
     */
    public static Listing put(Agent owner, String skill, String name, @Nullable String value, List<String> hosts) {
        return put(owner, skill, name, value, hosts, false, null);
    }

    /**
     * As {@link #put(Agent, String, String, String, List)}; when {@code setCapture}, the secret's capture rule becomes
     * {@code capture}, and a null one removes it (JF-374). A secret with a rule needs no value: the secrets proxy
     * stores one from the next sign-in.
     */
    public static Listing put(Agent owner, String skill, String name, @Nullable String value, List<String> hosts,
                              boolean setCapture, @Nullable Capture capture) {
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
        if (setCapture && capture != null && !capture.credential().equals(name)
                && SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, capture.credential())
                        .first() == null) {
            throw new Refused(("%s is not a secret of skill '%s': the rule names the secret its sign-in sends, so add"
                    + " that secret to this skill first, or name %s itself for a sign-in that sends the renewed key.")
                    .formatted(capture.credential(), skill, name));
        }
        if (setCapture && capture != null && !capture.credential().equals(name)) {
            SkillSecret credential = SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill,
                    capture.credential()).first();
            if (credential != null) refuseUnsendable(capture.credential(), credential, name, normalized);
        }
        SkillSecret row = SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        if (setCapture && capture != null && capture.credential().equals(name) && value == null
                && (row == null || row.value == null || row.value.isEmpty())) {
            throw new Refused(("A rule whose sign-in sends %s itself needs a value for %s to send: enter its current"
                    + " value too, or name the secret the sign-in sends, such as its password.").formatted(name, name));
        }
        boolean created = row == null;
        if (row == null) {
            if (value == null && (!setCapture || capture == null)) throw new Refused("A new secret needs a value.");
            row = new SkillSecret();
            row.agent = owner;
            row.skillName = skill;
            row.name = name;
            row.value = "";
        }
        var previous = captureOf(row);
        var rule = setCapture ? capture : previous;
        boolean hostsChanged = !created
                && !Set.copyOf(normalized).equals(Set.copyOf(Arrays.asList(row.hosts.split("\n"))));
        if (value != null) {
            row.value = value;
            row.renewedAt = null;
        } else if (rule == null && row.value != null && row.value.isEmpty()) {
            throw new Refused("A secret with no capture rule needs a value.");
        }
        row.hosts = String.join("\n", normalized);
        if (setCapture) setCapture(row, capture);
        // A value taken under another rule is no longer one this rule renewed.
        if (!Objects.equals(previous, rule)) row.renewedAt = null;
        row.save();
        if (created) SkillLoader.clearCache();
        afterWrite();
        var renewal = "";
        if (!Objects.equals(previous, rule)) {
            renewal = rule == null ? ", no longer renewed" : ", renewed from the answer to " + rule.describe();
        }
        EventLogger.info(LOG_CATEGORY, owner.name, null, "Secret %s %s for skill %s, sent only to %s%s".formatted(
                name, putVerb(created, value != null, hostsChanged), skill, String.join(", ", normalized), renewal));
        return listing(row);
    }

    /** Whether {@code value} can go in a header, where the proxy refuses a CR, an LF or a NUL. */
    public static boolean headerSafe(String value) {
        return value.indexOf('\r') < 0 && value.indexOf('\n') < 0 && value.indexOf('\0') < 0;
    }

    /**
     * Refuse a rule for {@code name}, sent to {@code hosts}, whose sign-in sends {@code credential}, held in
     * {@code row}, when the rule could never take a value: the credential has none to send, or is sent to none of the
     * hosts the sign-in goes to, so the proxy would refuse the sign-in.
     */
    private static void refuseUnsendable(String credential, SkillSecret row, String name, List<String> hosts) {
        if (row.value == null || row.value.isEmpty()) {
            throw new Refused(("%s has no value yet, so a sign-in that sends it sends nothing, and the rule could never"
                    + " take a value for %s: give %s a value first.").formatted(credential, name, credential));
        }
        var theirs = new HashSet<String>();
        for (var host : row.hosts.split("\n")) theirs.add(onDefaultPort(host));
        if (hosts.stream().map(SkillSecrets::onDefaultPort).noneMatch(theirs::contains)) {
            throw new Refused(("%s is sent only to %s, none of the hosts %s's sign-in goes to, so the rule could never"
                    + " take a value: give the two secrets a host in common.").formatted(credential,
                    String.join(", ", row.hosts.split("\n")), name));
        }
    }

    /** A stored host as a request reaches it: a name without a port is the same as on port 443. */
    private static String onDefaultPort(String host) {
        return host.endsWith(":443") ? host.substring(0, host.length() - 4) : host;
    }

    /**
     * Store {@code value}, which the secrets proxy took under {@code rule} from a sign-in answer, as renewed secret
     * {@code name} of {@code skill} (JF-374): the one write of a value that is not the operator's, so no route, tool
     * or command reaches it. The hosts stay as they are, and so does when the operator last set it. False, storing
     * nothing, when the secret is gone, its rule is no longer {@code rule}, or the value is empty, longer than
     * {@value #MAX_VALUE_CHARS} characters or holds what no header can carry ({@link #headerSafe}).
     */
    public static boolean storeCaptured(Agent owner, String skill, String name, Capture rule, String value) {
        if (value.isEmpty() || value.length() > MAX_VALUE_CHARS || !headerSafe(value)) return false;
        SkillSecret row = SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        if (row == null || !rule.equals(captureOf(row))) return false;
        row.value = value;
        row.markRenewed(AppClock.now());
        row.save();
        afterWrite();
        var ownerName = owner.name;
        Tx.afterCommit(() -> EventLogger.info(LOG_CATEGORY, ownerName, null,
                "Secret %s of skill %s renewed from the answer to %s %s".formatted(name, skill, rule.method(), rule.path())));
        return true;
    }

    /**
     * The agent's renewed secrets with a capture rule, for the secrets proxy to match each answer against: only those
     * of a switched-on skill, as a command gets a secret from no other (JF-326).
     */
    public static List<Renewable> renewable(Agent owner) {
        List<SkillSecret> rows = SkillSecret.find("agent = ?1 AND captureMethod IS NOT NULL ORDER BY skillName, name",
                owner).fetch();
        if (rows.isEmpty()) return List.of();
        var enabled = SkillLoader.enabledSkillFolders(owner.name);
        var out = new ArrayList<Renewable>();
        for (var row : rows) {
            var rule = captureOf(row);
            if (rule != null && enabled.contains(row.skillName)) {
                out.add(new Renewable(row.skillName, row.name, List.of(row.hosts.split("\n")), rule));
            }
        }
        return out;
    }

    /**
     * Once a secret's write commits, the mask learns its value, and every cache of what the agent holds rereads it:
     * the loader's, and the secrets proxy's capture rules, which it keeps until the loader's next clear.
     */
    private static void afterWrite() {
        Tx.afterCommit(() -> {
            CredentialMask.invalidate();
            SkillLoader.clearCache();
        });
    }

    private static @Nullable Capture captureOf(SkillSecret row) {
        var columns = Arrays.asList(row.captureMethod, row.capturePath, row.captureFrom, row.captureField,
                row.captureCredential, row.captureCredentialIn);
        if (columns.stream().allMatch(Objects::isNull)) return null;
        var from = row.captureFrom == null ? null : Capture.From.of(row.captureFrom);
        var place = row.captureCredentialIn == null ? null : Capture.Place.of(row.captureCredentialIn);
        if (columns.contains(null) || from == null || place == null) {
            // Every read of the rules meets the row; one warning per saved version of it is enough.
            if (UNREADABLE_RULES_WARNED.add(row.id + "@" + row.updatedAt)) {
                EventLogger.warn(LOG_CATEGORY, row.agent.name, null, ("Secret %s of skill %s has a stored capture rule"
                        + " JClaw cannot read%s, so nothing is taken under it: save its rule again in the Secrets panel.")
                        .formatted(row.name, row.skillName, columns.contains(null) ? ", saved only in part" : ""));
            }
            return null;
        }
        return new Capture(Objects.requireNonNull(row.captureMethod), Objects.requireNonNull(row.capturePath), from,
                Objects.requireNonNull(row.captureField), Objects.requireNonNull(row.captureCredential), place);
    }

    private static void setCapture(SkillSecret row, @Nullable Capture capture) {
        row.captureMethod = capture == null ? null : capture.method();
        row.capturePath = capture == null ? null : capture.path();
        row.captureFrom = capture == null ? null : capture.from().wire();
        row.captureField = capture == null ? null : capture.field();
        row.captureCredential = capture == null ? null : capture.credential();
        row.captureCredentialIn = capture == null ? null : capture.credentialIn().wire();
    }

    /**
     * Delete {@code name}; false when there is none. Refused while another secret's capture rule names it as the
     * secret its sign-in sends, since that rule could then never take a value.
     */
    public static boolean delete(Agent owner, String skill, String name) {
        SkillSecret row = SkillSecret.find("agent = ?1 AND skillName = ?2 AND name = ?3", owner, skill, name).first();
        if (row == null) return false;
        SkillSecret ruled = SkillSecret.find("agent = ?1 AND skillName = ?2 AND captureCredential = ?3 AND name <> ?3",
                owner, skill, name).first();
        if (ruled != null) {
            throw new Refused(("%s is the secret the capture rule of %s says its sign-in sends: change or remove that"
                    + " rule first.").formatted(name, ruled.name));
        }
        row.delete();
        SkillLoader.clearCache();
        afterWrite();
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
     * Secret {@code name} as {@code skillMd}'s declaration block declares it, read as a renewed line whether or not the
     * line still says {@code renewed}, or null when the block has no line for it. A reviewer that drops the word and
     * keeps the rule leaves a line whose rule a plain read would take as example hosts.
     */
    public static @Nullable Declared declaredAsRenewed(String skillMd, String name) {
        var block = splitDeclaration(skillMd).block();
        if (block == null) return null;
        for (var line : block.split("\\R")) {
            var m = DECLARATION.matcher(line);
            if (!m.matches() || !m.group(2).equals(name)) continue;
            var found = parse(RENEWED + " " + name + "=" + m.group(3) + (m.group(4) == null ? "" : "   # " + m.group(4)))
                    .secrets();
            return found.isEmpty() ? null : found.getFirst();
        }
        return null;
    }

    /**
     * A declaration block, fences included, written from parsed declarations alone: no comment line, no text that
     * did not parse, nothing after the fence. What it holds is only what {@link #declaredIn} and {@link #settingsIn}
     * would read back.
     */
    public static String declarationBlock(List<Declared> declared, List<DeclaredSetting> settings) {
        var out = new StringBuilder("```").append(DECLARATION_BLOCK).append('\n');
        for (var d : declared) {
            if (d.renewed()) out.append(RENEWED).append(' ');
            out.append(d.name()).append('=').append(String.join(", ", d.exampleHosts()));
            var suggested = d.suggested();
            if (d.renewed() && suggested != null) out.append(" from ").append(suggested.describe());
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
            if ("setting".equals(m.group(1)) && !looksLikeCredential(name)) {
                settings.add(new DeclaredSetting(name, description, m.group(3).strip(), optional));
            } else {
                boolean renewed = RENEWED.equals(m.group(1));
                var parts = renewed ? renewedParts(m.group(3)) : new RenewedParts(m.group(3), null);
                var examples = new ArrayList<String>();
                for (var example : parts.hosts().split("[,\\s]+")) {
                    if (example.isBlank()) continue;
                    try {
                        examples.addAll(normalizeHosts(List.of(example)));
                    } catch (Refused _) {
                        // An example that is no host is left out; the secret is still declared.
                    }
                }
                var rule = parts.rule();
                var suggested = suggestion(rule);
                var unreadable = suggested != null || rule == null || rule.isBlank() ? null : rule.strip();
                secrets.add(new Declared(name, description, List.copyOf(examples), optional, renewed, suggested,
                        unreadable));
            }
            if (secrets.size() + settings.size() == MAX_DECLARED) break;
        }
        return new Declarations(List.copyOf(secrets), List.copyOf(settings));
    }

    /** A renewed secret's example apart: its hosts, and the text of the rule after {@code from}, or null. */
    private record RenewedParts(String hosts, @Nullable String rule) {}

    private static RenewedParts renewedParts(String example) {
        var m = SUGGESTED_RULE.matcher(example);
        if (!m.find()) return new RenewedParts(example, null);
        return new RenewedParts(example.substring(0, m.start()).strip(), example.substring(m.end()).strip());
    }

    /**
     * The rule a renewed line suggests, as {@code METHOD /path [json|header] FIELD using NAME in PLACE}, its words
     * {@code using} and {@code in} in any case and a left-out kind meaning {@code json}, as {@link Capture#of} reads
     * one; or null when it suggests none or one the store would refuse.
     */
    private static @Nullable Capture suggestion(@Nullable String rule) {
        if (rule == null) return null;
        var words = rule.strip().split("\\s+");
        int using = -1;
        if (words.length > 4 && words[4].equalsIgnoreCase("using")) using = 4;
        else if (words.length > 3 && words[3].equalsIgnoreCase("using")) using = 3;
        if (using < 0 || words.length < using + 4 || words.length > using + 5
                || !words[using + 2].equalsIgnoreCase("in")) {
            return null;
        }
        try {
            return Capture.of(words[0], words[1], using == 4 ? words[2] : null, words[using - 1], words[using + 1],
                    String.join(" ", Arrays.asList(words).subList(using + 3, words.length)));
        } catch (Refused _) {
            return null;
        }
    }

    /**
     * Which of an agent's secrets a skill can be given (JF-348), read once and only when a skill declares one. A
     * command gets a secret only from a skill that is switched on (JF-326), so a secret counts for a skill when the
     * skill holds it itself or a switched-on skill of the agent does. A name is used once on an agent. A renewed
     * secret the proxy has not filled yet (JF-374) counts for its own skill alone, whose sign-in fills it: another
     * skill that declares the name needs a value it can send.
     */
    public static final class Provided {
        private final Agent owner;
        private final Supplier<Set<String>> enabledFolders;
        private @Nullable Map<String, String> holders;
        private @Nullable Map<String, String> unfilled;
        private @Nullable Set<String> enabled;

        private Provided(Agent owner, Supplier<Set<String>> enabledFolders) {
            this.owner = owner;
            this.enabledFolders = enabledFolders;
        }

        private Map<String, String> holders() {
            var known = holders;
            if (known == null) {
                known = new LinkedHashMap<>();
                var empty = new LinkedHashMap<String, String>();
                for (var secret : usable(owner)) {
                    (secret.value().isEmpty() ? empty : known).put(secret.name(), secret.skill());
                }
                // A name is used once on an agent, so a setting never shares one with a secret (JF-357).
                for (var setting : SkillSettings.usable(owner)) known.put(setting.name(), setting.skill());
                holders = known;
                unfilled = empty;
            }
            return known;
        }

        private Map<String, String> unfilled() {
            holders();
            return Objects.requireNonNull(unfilled);
        }

        private Set<String> enabled() {
            var known = enabled;
            if (known == null) {
                known = enabledFolders.get();
                enabled = known;
            }
            return known;
        }

        /**
         * The skill that holds a value for {@code name} on the agent, switched on or not, or null when none does: a
         * renewed secret not yet filled holds none.
         */
        public @Nullable String holder(String name) {
            return holders().get(name);
        }

        /**
         * The skill {@code name} is set in on the agent: the {@link #holder}, or the skill whose renewed secret holds
         * the name with no value yet. A name is used once on an agent, so no other skill can add it.
         */
        public @Nullable String setIn(String name) {
            var holder = holder(name);
            return holder != null ? holder : unfilled().get(name);
        }

        /** Whether {@code name} is set only as a renewed secret with no value yet, which its skill's sign-in fills. */
        public boolean filledBySignIn(String name) {
            return holder(name) == null && unfilled().containsKey(name);
        }

        /** Whether the skill in folder {@code skill} can be given {@code name}. */
        public boolean to(String skill, String name) {
            var holder = holder(name);
            if (holder != null) return holder.equals(skill) || enabled().contains(holder);
            return skill.equals(unfilled().get(name));
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
                if (!d.optional() && !d.renewed() && !to(folder.toString(), d.name())) out.add(d.name());
            }
            for (var d : declarations.settings()) {
                if (!d.optional() && !to(folder.toString(), d.name())) out.add(d.name());
            }
            return List.copyOf(out);
        }

        /**
         * For each of {@code missing} that another installed skill of the agent sets, that skill (JF-352, JF-327,
         * JF-374). A name {@link #missing} lists is never held with a value by a skill that is offered, and one set in
         * a skill no longer installed has no holder.
         */
        public Map<String, Holder> holdersOf(List<String> missing) {
            var out = new LinkedHashMap<String, Holder>();
            for (var name : missing) {
                var held = holderOf(name);
                if (held != null && held.installed()) out.put(name, held);
            }
            return out;
        }

        /** The skill {@code name} is {@link #setIn}, or null when none is; one whose folder is gone is not installed. */
        public @Nullable Holder holderOf(String name) {
            var holder = setIn(name);
            if (holder == null) return null;
            var skills = AgentService.workspacePath(owner.name).resolve("skills");
            var dir = WorkspaceFiles.resolveContained(skills, holder);
            if (dir == null || !Files.isRegularFile(dir.resolve(SKILL_FILE))) {
                return new Holder(holder, false, filledBySignIn(name), false, false);
            }
            var config = AgentSkillConfig.findByAgentAndSkill(owner, holder);
            boolean offered = enabled().contains(holder) && missing(dir).isEmpty();
            return new Holder(holder, config != null && !config.enabled, filledBySignIn(name), offered, true);
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
            if ("setting".equals(m.group(1))) {
                // A setting's example is free text, such as an address with a path; it may hold no key.
                if (masked(m.group(3)) || looksRandom(m.group(3).split("[^A-Za-z0-9]+"))) return true;
            } else {
                var parts = RENEWED.equals(m.group(1)) ? renewedParts(m.group(3)) : new RenewedParts(m.group(3), null);
                var rule = parts.rule();
                // A suggested rule is a method, a path and a field name: none of them random, none of them a key.
                if (rule != null && (masked(rule) || looksRandom(rule.split("[^A-Za-z0-9]+")))) return true;
                for (var example : parts.hosts().split("[,\\s]+")) {
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


    /** Whether {@code name} reads as a key, token or password, so that it is only ever a secret (JF-357). */
    public static boolean looksLikeCredential(String name) {
        for (var part : name.split("_")) {
            if (CREDENTIAL_PART.matcher(part).matches()) return true;
        }
        return false;
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

    /**
     * The agent's secrets whose values open, for a command's grant (JF-324). One that will not open is left out; a
     * renewed one not yet filled is in, empty, so the command's stand-in can take the value its sign-in brings (JF-374).
     */
    public static List<Usable> usable(Agent owner) {
        List<SkillSecret> rows = SkillSecret.find("agent = ?1 ORDER BY skillName, name", owner).fetch();
        var out = new ArrayList<Usable>();
        for (var row : rows) {
            if (row.value != null) {
                out.add(new Usable(row.skillName, row.name, row.value, List.of(row.hosts.split("\n")),
                        row.renewedAt != null && captureOf(row) != null));
            }
        }
        return out;
    }

    /** Delete every secret of a skill the operator removes from the agent, as its credentials folder goes with it. */
    public static int deleteForSkill(Agent owner, String skill) {
        int deleted = SkillSecret.delete("agent = ?1 AND skillName = ?2", owner, skill);
        if (deleted > 0) {
            SkillLoader.clearCache();
            afterWrite();
        }
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
        return new Listing(row.name, List.of(row.hosts.split("\n")), row.updatedAt,
                row.value != null && !row.value.isEmpty(), captureOf(row), row.renewedAt);
    }
}
