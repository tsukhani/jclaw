package services;

import play.Play;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * The {@code application.conf} ceiling for the config keys that carry privilege (JCLAW-1022).
 *
 * <p>Config rows are writable at runtime; {@code application.conf} is not reachable from the
 * config table at all. So for a privileged key the conf value is the authority and a DB row may
 * only <em>tighten</em> it — the shape {@link AppInvokeLimits} already uses for app invoke caps,
 * generalised here because "tighten" means something different per key: a set intersects, a
 * grant boolean can only be withdrawn, an approval policy can only get stricter, and a provider
 * base URL has no tighter form at all.
 *
 * <p><b>The ceiling is opt-in per key.</b> With no conf entry the DB row passes through
 * unchanged, so nothing an operator configured through Settings changes on upgrade. Adding the
 * key to {@code application.conf} is what switches the ceiling on — and for
 * {@code shell.allowlist} that deliberately moves <em>widening</em> the allowlist from a web
 * form to a file on disk plus a restart, while leaving narrowing available in the UI.
 *
 * <p>Enforcement is at the read, in {@link ConfigService#get(String)}, rather than at each sink.
 * {@code provider.*.baseUrl} alone has nine readers; gating the choke point covers them, the
 * shell allowlist, both per-agent bypass flags and the dangerous-action gate at once, and holds
 * for a row that reached the table by some path other than the API.
 */
public final class PrivilegedConfig {

    /** How a DB row is reconciled against the conf ceiling for a key. */
    public enum Tightening {
        /** Comma-separated set: effective = DB ∩ conf. */
        SET_INTERSECTION,
        /** A grant: conf {@code false} pins it false, so a row may only withdraw it. */
        BOOLEAN_AND,
        /** Ordered {@code allow < ask < deny}: effective = whichever is stricter. */
        POLICY_FLOOR,
        /** No tighter value exists, so conf wins outright and the row is ignored. */
        PINNED
    }

    private record Rule(Predicate<String> matches, Tightening how) {}

    private static final String KEY_ALLOWLIST = "shell.allowlist";
    private static final String KEY_OFF_CHANNEL_POLICY = "tool.approval.offChannelPolicy";

    /** {@code allow} is the sink's reading of anything unrecognized, so rank it the same way —
     *  a ceiling that disagreed with the gate about what a value means would be worse than none. */
    private static final List<String> POLICY_ORDER = List.of("allow", "ask", "deny");

    private static final List<Rule> RULES = List.of(
            new Rule(KEY_ALLOWLIST::equals, Tightening.SET_INTERSECTION),
            new Rule(k -> k.startsWith("agent.")
                    && (k.endsWith(".shell.bypassAllowlist") || k.endsWith(".shell.allowGlobalPaths")),
                    Tightening.BOOLEAN_AND),
            new Rule(KEY_OFF_CHANNEL_POLICY::equals, Tightening.POLICY_FLOOR),
            new Rule(k -> k.startsWith("provider.") && k.endsWith(".baseUrl"), Tightening.PINNED));

    private PrivilegedConfig() {}

    /** The tightening rule for {@code key}, or null when the key carries no privilege. */
    public static Tightening ruleFor(String key) {
        if (key == null) return null;
        return RULES.stream().filter(r -> r.matches().test(key)).map(Rule::how).findFirst().orElse(null);
    }

    /**
     * The effective value of {@code key} once the conf ceiling is applied to {@code dbValue}.
     *
     * <p>Returns {@code dbValue} untouched for an unprivileged key or when conf declares no
     * ceiling. When the row is absent the conf value stands on its own, so declaring a ceiling
     * also sets the value — otherwise a caller's code default would quietly outrank it.
     */
    public static String reconcile(String key, String dbValue) {
        return reconcile(key, dbValue, ceilingFor(key));
    }

    /** {@link #reconcile(String, String)} against an explicit {@code ceiling}. Pure: play1 runs
     *  test classes concurrently, so the ceiling is passed in rather than read from the
     *  process-global {@code Play.configuration} that a test would otherwise have to mutate. */
    public static String reconcile(String key, String dbValue, String ceiling) {
        var rule = ruleFor(key);
        if (rule == null) return dbValue;
        if (ceiling == null || ceiling.isBlank()) return dbValue;
        if (dbValue == null) return ceiling;

        return switch (rule) {
            case SET_INTERSECTION -> intersect(dbValue, ceiling);
            case BOOLEAN_AND -> String.valueOf(bool(ceiling) && bool(dbValue));
            case POLICY_FLOOR -> policyRank(dbValue) >= policyRank(ceiling) ? dbValue : ceiling;
            case PINNED -> ceiling;
        };
    }

    /**
     * Why {@code candidate} may not be stored for {@code key}, or null when it may.
     *
     * <p>Read-side reconciliation already makes a loosening row inert, so this exists for the
     * operator rather than for the attacker: without it a save that cannot take effect still
     * answers 200, and the setting silently reads back as something else.
     */
    public static String rejectionFor(String key, String candidate) {
        return rejectionFor(key, candidate, ceilingFor(key));
    }

    /** {@link #rejectionFor(String, String)} against an explicit {@code ceiling}; pure, for the
     *  same reason as {@link #reconcile(String, String, String)}. */
    public static String rejectionFor(String key, String candidate, String ceiling) {
        var rule = ruleFor(key);
        if (rule == null || candidate == null) return null;
        if (ceiling == null || ceiling.isBlank()) return null;

        var effective = reconcile(key, candidate, ceiling);
        if (sameValue(rule, effective, candidate)) return null;

        return ("'%s' is capped by application.conf, which allows '%s'. A stored value may only "
                + "tighten it, and '%s' would take effect as '%s'. Edit application.conf and restart "
                + "to widen it.").formatted(key, ceiling, candidate, effective);
    }

    private static String ceilingFor(String key) {
        return Play.configuration == null ? null : Play.configuration.getProperty(key);
    }

    private static boolean sameValue(Tightening rule, String a, String b) {
        if (rule == Tightening.SET_INTERSECTION) return parseSet(a).equals(parseSet(b));
        return a != null && a.strip().equalsIgnoreCase(b.strip());
    }

    private static String intersect(String dbValue, String ceiling) {
        var permitted = parseSet(ceiling);
        return parseSet(dbValue).stream().filter(permitted::contains).collect(Collectors.joining(","));
    }

    /** Order-preserving so the intersection reads back in the order the operator wrote it. */
    private static Set<String> parseSet(String raw) {
        if (raw == null) return Set.of();
        return Arrays.stream(raw.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static boolean bool(String raw) {
        return Boolean.parseBoolean(raw.strip());
    }

    private static int policyRank(String raw) {
        return Math.max(0, POLICY_ORDER.indexOf(raw.strip().toLowerCase(Locale.ROOT)));
    }
}
