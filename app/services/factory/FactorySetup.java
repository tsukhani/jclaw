package services.factory;

import com.google.gson.JsonObject;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Software Factory setup (JCLAW-1393): the prerequisites {@code install-agent.sh} checks, whether
 * each credential is set, and the write-only credential entry. No method returns a credential value.
 */
public final class FactorySetup {

    public static final String OK = "ok";
    public static final String MISSING = "missing";
    public static final String UNKNOWN = "unknown";

    public static final String FIELD_CLAUDE_OAUTH = "claudeOauthToken";
    public static final String FIELD_ANTHROPIC_KEY = "anthropicApiKey";
    public static final String FIELD_JIRA_URL = "jiraUrl";
    public static final String FIELD_JIRA_TOKEN = "jiraPersonalToken";
    public static final String FIELD_GITHUB = "githubToken";

    static final String KEY_OAUTH = "CLAUDE_CODE_OAUTH_TOKEN";
    static final String KEY_API = "ANTHROPIC_API_KEY";
    static final String KEY_JIRA_URL = "JIRA_URL";
    static final String KEY_JIRA_TOKEN = "JIRA_PERSONAL_TOKEN";
    static final String KEY_GITHUB = "GITHUB_TOKEN";

    static final int MIN_NODE = 24;
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    private static final List<String> FIELDS =
            List.of(FIELD_CLAUDE_OAUTH, FIELD_ANTHROPIC_KEY, FIELD_JIRA_URL, FIELD_JIRA_TOKEN, FIELD_GITHUB);
    private static final Pattern NODE_VERSION = Pattern.compile("^v(\\d+)\\.");

    /** @param state {@link #OK}, {@link #MISSING} or {@link #UNKNOWN}; {@code fix} is empty when ok */
    public record Prerequisite(String id, String label, String state, String fix) {}

    /**
     * @param installed     the harness LaunchAgent's plist exists
     * @param installJobId  the running install, else the latest one, else null
     * @param message       null on a read; what happened on a write
     */
    public record SetupView(boolean installed, List<Prerequisite> prerequisites, boolean hasModelCredential,
                            boolean hasJira, boolean hasGithub, @Nullable String installJobId,
                            @Nullable String message) {}

    private FactorySetup() {}

    public static SetupView view(@Nullable String message) {
        return view(System.getProperty("os.name", ""), message);
    }

    /** {@link #view(String)} as on a host whose {@code os.name} is {@code osName}. */
    public static SetupView view(String osName, @Nullable String message) {
        var model = env(FactoryHome.modelEnvFile());
        var jira = env(FactoryHome.jiraEnvFile());
        var github = env(FactoryHome.githubEnvFile());
        return new SetupView(launchAgentInstalled(), prerequisites(osName),
                set(model, KEY_OAUTH) || set(model, KEY_API),
                set(jira, KEY_JIRA_URL) && set(jira, KEY_JIRA_TOKEN),
                set(github, KEY_GITHUB),
                FactoryInstallJob.latestId(), message);
    }

    static List<Prerequisite> prerequisites(String osName) {
        var out = new ArrayList<Prerequisite>();
        out.add(osName.toLowerCase(Locale.ROOT).startsWith("mac")
                ? ok("macos", "macOS")
                : new Prerequisite("macos", "macOS", MISSING, "The factory runs only on macOS."));
        out.add(docker());
        out.add(node());
        out.add(checkout(FactoryHome.installer()));
        return out;
    }

    public static Prerequisite checkout(Path installer) {
        return Files.isRegularFile(installer)
                ? ok("checkout", "JClaw checkout")
                : new Prerequisite("checkout", "JClaw checkout", MISSING,
                        "Run JClaw from a checkout that has .sandcastle/install-agent.sh.");
    }

    private static Prerequisite docker() {
        var label = "Docker Desktop running";
        var res = probe(List.of("docker", "info", "--format", "{{.ServerVersion}}"));
        if (res == null || res.timedOut()) {
            return new Prerequisite("docker", label, UNKNOWN, "Docker did not answer; check Docker Desktop.");
        }
        if (res.exitCode() == -1) return new Prerequisite("docker", label, MISSING, "Install Docker Desktop.");
        if (res.exitCode() != 0) return new Prerequisite("docker", label, MISSING, "Start Docker Desktop.");
        return ok("docker", label);
    }

    private static Prerequisite node() {
        var label = "Node " + MIN_NODE + " or newer";
        var fix = "Install Node " + MIN_NODE + " or newer.";
        var res = probe(List.of("node", "--version"));
        if (res == null || res.timedOut()) return new Prerequisite("node", label, UNKNOWN, "Node did not answer.");
        if (res.exitCode() == -1) return new Prerequisite("node", label, MISSING, fix);
        Integer major = res.exitCode() == 0 ? nodeMajor(res.output()) : null;
        if (major == null) return new Prerequisite("node", label, UNKNOWN, "Could not read the Node version.");
        return major >= MIN_NODE ? ok("node", label) : new Prerequisite("node", label, MISSING, fix);
    }

    private static @Nullable Integer nodeMajor(String output) {
        for (var line : output.lines().map(String::trim).toList()) {
            var m = NODE_VERSION.matcher(line);
            if (!m.find()) continue;
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException _) {
                return null;
            }
        }
        return null;
    }

    private static FactoryProcess.@Nullable ExecResult probe(List<String> command) {
        try {
            return FactoryProcess.run(command, PROBE_TIMEOUT);
        } catch (RuntimeException _) {
            return null;
        }
    }

    private static Prerequisite ok(String id, String label) {
        return new Prerequisite(id, label, OK, "");
    }

    private static boolean launchAgentInstalled() {
        return Files.isRegularFile(Path.of(System.getProperty("user.home"), "Library", "LaunchAgents",
                FactoryStatus.LAUNCH_AGENT + ".plist"));
    }

    private static Map<String, String> env(Path file) {
        try {
            return FactoryHome.readEnv(file);
        } catch (IOException _) {
            return Map.of();
        }
    }

    private static boolean set(Map<String, String> env, String key) {
        var v = env.get(key);
        return v != null && !v.isBlank();
    }

    /**
     * Validate every field of {@code body}, then write them: a model credential replaces the
     * other kind, since Claude Code prefers {@code ANTHROPIC_API_KEY} when both are set.
     *
     * @return why nothing was written, naming the field and never its value; null once written
     */
    public static @Nullable String applyCredentials(JsonObject body) throws IOException {
        if (body.isEmpty()) return "No credentials given; accepted fields are " + String.join(", ", FIELDS) + ".";
        var values = new LinkedHashMap<String, String>();
        for (var e : body.entrySet()) {
            var field = e.getKey();
            if (!FIELDS.contains(field)) {
                return field + " is not a credential field; accepted fields are " + String.join(", ", FIELDS) + ".";
            }
            var el = e.getValue();
            if (!el.isJsonPrimitive() || !el.getAsJsonPrimitive().isString()) return field + " must be a string.";
            var raw = el.getAsString();
            if (raw.indexOf('\n') >= 0 || raw.indexOf('\r') >= 0) return field + " must be a single line.";
            var value = raw.strip();
            if (value.isEmpty()) return field + " must not be blank.";
            values.put(field, value);
        }
        if (values.containsKey(FIELD_CLAUDE_OAUTH) && values.containsKey(FIELD_ANTHROPIC_KEY)) {
            return "Send either " + FIELD_CLAUDE_OAUTH + " or " + FIELD_ANTHROPIC_KEY + ", not both.";
        }
        var url = values.get(FIELD_JIRA_URL);
        if (url != null && !httpUrl(url)) return FIELD_JIRA_URL + " must be an http or https URL.";

        var oauth = values.get(FIELD_CLAUDE_OAUTH);
        var api = values.get(FIELD_ANTHROPIC_KEY);
        if (oauth != null) FactoryHome.writeEnvFile(FactoryHome.modelEnvFile(), Map.of(KEY_OAUTH, oauth), Set.of(KEY_API));
        if (api != null) FactoryHome.writeEnvFile(FactoryHome.modelEnvFile(), Map.of(KEY_API, api), Set.of(KEY_OAUTH));
        var jira = new LinkedHashMap<String, String>();
        if (url != null) jira.put(KEY_JIRA_URL, url);
        var jiraToken = values.get(FIELD_JIRA_TOKEN);
        if (jiraToken != null) jira.put(KEY_JIRA_TOKEN, jiraToken);
        if (!jira.isEmpty()) FactoryHome.writeEnvFile(FactoryHome.jiraEnvFile(), jira, Set.of());
        var github = values.get(FIELD_GITHUB);
        if (github != null) FactoryHome.writeEnvFile(FactoryHome.githubEnvFile(), Map.of(KEY_GITHUB, github), Set.of());
        return null;
    }

    private static boolean httpUrl(String value) {
        try {
            var uri = new URI(value);
            var scheme = uri.getScheme();
            return scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && uri.getHost() != null;
        } catch (URISyntaxException _) {
            return false;
        }
    }
}
