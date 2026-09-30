package tools.scrape;

import org.jspecify.annotations.Nullable;
import services.ConfigService;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.Set;

/**
 * DataImpulse's plan types (JCLAW-1334): each has its own login and password, behind one gateway, and
 * {@link WebScrapeSettings#PROXY_DATAIMPULSE_PLAN} names the one the scrape proxy uses. The gateway,
 * rotation and targeting are shared, so only the credentials are per plan.
 */
public final class DataImpulsePlans {

    public static final String PREFIX = "web_scrape.proxy.dataimpulse.";

    /** Plan id to the label the dashboard gives it, in the dashboard's order. */
    public static final SequencedMap<String, String> PLANS = plans();

    private static final Set<String> GATEWAY_HOSTS = Set.of("gw.dataimpulse.com", "74.81.81.81");

    /** Where a job's plan goes out when the saved proxy URL is not a DataImpulse gateway (JCLAW-1335). */
    public static final String DEFAULT_GATEWAY = "http://gw.dataimpulse.com:823";

    // DataImpulse puts targeting after the login: login__cr.de;sessttl.30
    private static final String PARAMS_SEPARATOR = "__";

    private static volatile @Nullable String gatewayHostForTest;

    /** A plan's username and password as the proxy receives them. */
    public record Credentials(String plan, String username, String password) {}

    private DataImpulsePlans() {}

    private static SequencedMap<String, String> plans() {
        var plans = new LinkedHashMap<String, String>();
        plans.put("residential", "Residential");
        plans.put("premium-residential", "Premium Residential");
        plans.put("mobile", "Mobile");
        plans.put("datacenter", "Datacenter");
        return Collections.unmodifiableSequencedMap(plans);
    }

    public static String loginKey(String plan) {
        return PREFIX + plan + ".login";
    }

    public static String passwordKey(String plan) {
        return PREFIX + plan + ".password";
    }

    /** Also treats {@code host} as a gateway, so a test can stand a local stub in for DataImpulse; null undoes it. */
    public static void setGatewayHostForTest(@Nullable String host) {
        gatewayHostForTest = host;
    }

    /** Whether {@code url} is an http:// DataImpulse gateway, where the plans apply; SOCKS5 carries no credentials. */
    public static boolean isGateway(String url) {
        URI uri;
        try {
            uri = new URI(url.strip());
        } catch (Exception _) {
            return false;
        }
        if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return false;
        var host = uri.getHost().toLowerCase(Locale.ROOT);
        return GATEWAY_HOSTS.contains(host) || host.equals(gatewayHostForTest);
    }

    /** The active plan's credentials when {@code url} is a gateway and a plan is set, or empty when the generic keys apply. */
    public static Optional<Credentials> active(String url) {
        var plan = ConfigService.get(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN, "").strip();
        if (plan.isEmpty() || !isGateway(url)) return Optional.empty();
        return Optional.of(credentials(plan));
    }

    /** {@code plan}'s stored credentials, with the shared targeting appended to its login. */
    public static Credentials credentials(String plan) {
        return new Credentials(plan,
                username(ConfigService.get(loginKey(plan), ""), ConfigService.get(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING, "")),
                ConfigService.get(passwordKey(plan), ""));
    }

    /** {@code plan}'s dashboard label, such as "Mobile", or the id itself for an unknown plan. */
    public static String label(String plan) {
        return PLANS.getOrDefault(plan, plan);
    }

    /** Whether {@code plan} has both a login and a password saved. */
    public static boolean hasCredentials(String plan) {
        return !ConfigService.get(loginKey(plan), "").isBlank() && !ConfigService.get(passwordKey(plan), "").isBlank();
    }

    /** The plans that can be named, in the dashboard's order. */
    public static List<String> withCredentials() {
        return PLANS.keySet().stream().filter(DataImpulsePlans::hasCredentials).toList();
    }

    /**
     * The proxy a job pins for {@code plan} (JCLAW-1335), whatever {@link WebScrapeSettings#PROXY_ENABLED} says:
     * the saved proxy URL when it is a gateway, so the operator's gateway and rotation hold, else
     * {@link #DEFAULT_GATEWAY}.
     *
     * @throws IllegalStateException naming the plan when it is unknown or its credentials are gone
     */
    public static ScrapeProxy proxyFor(String plan) {
        if (!PLANS.containsKey(plan) || !hasCredentials(plan)) {
            throw new IllegalStateException("The DataImpulse " + label(plan)
                    + " plan has no saved login and password, so this job cannot use it.");
        }
        var saved = ConfigService.get(WebScrapeSettings.PROXY_URL, "");
        var credentials = credentials(plan);
        return ScrapeProxy.parse(isGateway(saved) ? saved : DEFAULT_GATEWAY, "true",
                        credentials.username(), credentials.password())
                .orElseThrow()
                .withPlan(plan);
    }

    /** {@code login__targeting}, or the login alone when there is no targeting. */
    public static String username(String login, String targeting) {
        var l = login.strip();
        var t = targeting.strip();
        return l.isEmpty() || t.isEmpty() ? l : l + PARAMS_SEPARATOR + t;
    }

    /** A legacy username split at its first {@code __}: the login, then the targeting (blank when there is none). */
    public static String[] splitUsername(String username) {
        var u = username.strip();
        int at = u.indexOf(PARAMS_SEPARATOR);
        return at < 0 ? new String[] {u, ""} : new String[] {u.substring(0, at), u.substring(at + PARAMS_SEPARATOR.length())};
    }

    /** A message naming what a {@code web_scrape.proxy.dataimpulse.*} key must be, or null when {@code value} is acceptable. */
    public static @Nullable String rejectionFor(String key, String value) {
        if (key.equals(WebScrapeSettings.PROXY_DATAIMPULSE_PLAN)) return planRejection(value.strip());
        if (key.equals(WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING)) return targetingRejection(value);
        var rest = key.substring(PREFIX.length());
        int dot = rest.indexOf('.');
        var plan = dot < 0 ? rest : rest.substring(0, dot);
        var suffix = dot < 0 ? "" : rest.substring(dot + 1);
        if (!PLANS.containsKey(plan) || !(suffix.equals("login") || suffix.equals("password"))) {
            return key + " is not a DataImpulse setting; plans are " + String.join(", ", PLANS.keySet())
                    + ", each with a login and a password.";
        }
        if (value.chars().anyMatch(Character::isISOControl)) {
            return "A DataImpulse " + suffix + " cannot contain control characters.";
        }
        return suffix.equals("login") ? loginRejection(value) : null;
    }

    private static @Nullable String planRejection(String plan) {
        if (plan.isEmpty()) return null;
        var label = PLANS.get(plan);
        if (label == null) {
            return WebScrapeSettings.PROXY_DATAIMPULSE_PLAN + " must be one of " + String.join(", ", PLANS.keySet()) + ".";
        }
        if (!hasCredentials(plan)) {
            return "The DataImpulse " + label + " plan has no saved login and password; save both before using it.";
        }
        return null;
    }

    private static @Nullable String loginRejection(String login) {
        if (login.isEmpty()) return null;
        if (login.contains(PARAMS_SEPARATOR) || login.chars().anyMatch(c -> Character.isWhitespace(c) || ":;@".indexOf(c) >= 0)) {
            return "Enter the DataImpulse login alone, as the dashboard shows it, with no spaces, :, ;, @ or __.";
        }
        // abc_ would compose as abc___cr.de, which DataImpulse reads as the login abc.
        if (login.endsWith("_")) return "A DataImpulse login cannot end in an underscore.";
        return null;
    }

    private static @Nullable String targetingRejection(String targeting) {
        return targeting.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c) || c == ':' || c == '@')
                ? WebScrapeSettings.PROXY_DATAIMPULSE_TARGETING + " cannot contain spaces, :, @ or control characters."
                : null;
    }
}
