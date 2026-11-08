package services;

import models.Agent;
import org.jspecify.annotations.Nullable;
import utils.SsrfGuard;
import utils.Urls;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What an agent may do through the Chrome bridge. The extension drives the operator's own,
 * logged-in browser, so a page the agent opens is a page opened as the operator: every rule
 * here exists because the agent's reach is the operator's.
 *
 * <p>Each agent has its own site allowlist. Two overrides exist and both are for the main agent
 * alone: opening any site, and submitting a form without asking. Any other agent is bound by its
 * allowlist and by approval, whatever is set. All three live under {@code agent.<name>.chromeBridge.}
 * so deleting or renaming an agent carries them with it, and only the operator-only config write
 * can change them, so an agent cannot widen its own reach.
 */
public final class ChromeBridgePolicy {

    public static final String ALLOWED_SITES = "allowedSites";
    public static final String ALLOW_ALL_SITES = "allowAllSites";
    public static final String SUBMIT_WITHOUT_APPROVAL = "submitWithoutApproval";

    static final int MAX_PATTERNS = 200;

    private static final Pattern KEY = Pattern.compile(
            "agent\\.(.+)\\.chromeBridge\\.(" + ALLOWED_SITES + "|" + ALLOW_ALL_SITES + "|" + SUBMIT_WITHOUT_APPROVAL + ")");

    private static final Pattern HOST_PATTERN = Pattern.compile(
            "(\\*\\.)?[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,]+");

    private ChromeBridgePolicy() {}

    /** The config key holding {@code setting} for the agent named {@code agentName}. */
    public static String key(String agentName, String setting) {
        return AgentService.AGENT_CONFIG_PREFIX + agentName + ".chromeBridge." + setting;
    }

    /** Why {@code agent} may not open {@code url}, or null when it may. */
    public static @Nullable String visitRefusal(Agent agent, String url) {
        URI uri;
        try {
            uri = Urls.parse(url);
        } catch (RuntimeException _) {
            return "not a valid URL";
        }
        var scheme = uri.getScheme();
        var host = uri.getHost();
        if (scheme == null || host == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "only http(s) URLs can be opened";
        }
        var lower = host.toLowerCase(Locale.ROOT);
        var allowAll = agent.isMain() && ConfigService.getBoolean(key(agent.name, ALLOW_ALL_SITES), false);
        if (!allowAll && allowedSites(agent).stream().noneMatch(p -> hostMatches(p, lower))) {
            return "site not on the allowlist: " + lower;
        }
        // Even allow-all stops here: the operator's browser is signed in to JClaw itself, so
        // loopback, the LAN and this instance's own address would let the agent act as the operator.
        try {
            SsrfGuard.assertUrlSafe(url);
        } catch (SecurityException _) {
            return "loopback, private-network and malformed addresses are never allowed";
        }
        return null;
    }

    /** True when a form submission by {@code agent} must be approved by the operator first. */
    public static boolean submitNeedsApproval(Agent agent) {
        return !(agent.isMain() && ConfigService.getBoolean(key(agent.name, SUBMIT_WITHOUT_APPROVAL), false));
    }

    public static List<String> allowedSites(Agent agent) {
        var raw = ConfigService.get(key(agent.name, ALLOWED_SITES));
        return raw == null ? List.of() : split(raw);
    }

    /** {@code example.com} matches that host only; {@code *.example.com} its subdomains, not the apex. */
    static boolean hostMatches(String pattern, String host) {
        if (pattern.startsWith("*.")) return host.endsWith(pattern.substring(1));
        return host.equals(pattern);
    }

    /** The refusal for a write to an {@code agent.<name>.chromeBridge.*} key, or null when it may be stored. */
    public static @Nullable String rejectionFor(String key, String value) {
        var m = KEY.matcher(key);
        return m.matches() ? rejectionFor(key, value, Agent.findByName(m.group(1))) : null;
    }

    /** As {@link #rejectionFor(String, String)} with the key's agent already resolved, or null when there is none. */
    public static @Nullable String rejectionFor(String key, String value, @Nullable Agent agent) {
        var m = KEY.matcher(key);
        if (!m.matches()) return null;
        if (agent == null) return "No agent named '" + m.group(1) + "'.";
        var setting = m.group(2);
        if (setting.equals(ALLOWED_SITES)) return sitesRejection(key, value);
        if (!agent.isMain()) {
            return "Allowing every site and submitting without approval can only be set for the main agent.";
        }
        return value.equals("true") || value.equals("false") ? null : key + " must be true or false.";
    }

    private static @Nullable String sitesRejection(String key, String value) {
        var patterns = split(value);
        if (patterns.size() > MAX_PATTERNS) {
            return key + " holds at most " + MAX_PATTERNS + " sites.";
        }
        for (var p : patterns) {
            if (!HOST_PATTERN.matcher(p).matches()) {
                return key + ": \"" + p + "\" is not a host name; use example.com or *.example.com, "
                        + "with no scheme, port or path.";
            }
        }
        return null;
    }

    private static List<String> split(String raw) {
        var out = new ArrayList<String>();
        for (var token : SEPARATORS.split(raw.strip())) {
            if (!token.isEmpty()) out.add(token.toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
