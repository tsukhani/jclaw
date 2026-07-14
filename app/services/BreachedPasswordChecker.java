package services;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import play.Logger;
import play.Play;
import utils.HttpFactories;
import utils.PlayConfig;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * JCLAW-741: screen a candidate password against known breach corpora before
 * it is accepted, so the single admin account cannot be protected by a password
 * that already sits in a public dump (the highest-value password control per
 * NIST 800-63B).
 *
 * <p>Two layers, network-optional and fail-open on the network:
 * <ol>
 *   <li><b>Online</b> — Have I Been Pwned's <em>k-anonymity</em> range API. Only
 *       the first five hex chars of the password's SHA-1 leave the process; the
 *       full password and full hash never do. The {@code Add-Padding} response
 *       (decoy suffixes with {@code count:0}) hides how many real hits the
 *       prefix has, so match detection requires a positive count. Best-effort
 *       and time-boxed.</li>
 *   <li><b>Offline</b> — a bundled common-password list, consulted only when the
 *       online call is disabled, unreachable, or times out. Local-first rule: a
 *       slow or failed HIBP lookup must <em>never</em> block setting a password;
 *       it degrades to the offline list.</li>
 * </ol>
 *
 * <p>No mutable static state: the online decision is a plain method gated by
 * static config and {@link #decide} is a pure function of (password, online
 * result). That keeps the class deterministic under play1's concurrent
 * unit+functional test execution — tests exercise {@link #decide},
 * {@link #suffixInRange}, {@link #sha1Hex}, and the offline list directly, and
 * {@code %test} disables the online call so functional setup never hits the
 * network.
 */
public final class BreachedPasswordChecker {

    private BreachedPasswordChecker() {}

    static final String CFG_ONLINE_ENABLED = "auth.password.breach-check.enabled";
    static final String CFG_TIMEOUT_MS = "auth.password.breach-check.timeout-ms";
    private static final int DEFAULT_TIMEOUT_MS = 3000;
    private static final String HIBP_RANGE_URL = "https://api.pwnedpasswords.com/range/";
    private static final String OFFLINE_LIST_PATH = "conf/common-passwords.txt";

    /**
     * Whether {@code password} appears in a known breach. Never throws and never
     * blocks beyond the configured online timeout: the online result (if any)
     * wins, otherwise the offline list decides.
     */
    public static boolean isBreached(String password) {
        if (password == null || password.isBlank()) return false;
        return decide(password, onlineCheck(password));
    }

    /**
     * Pure composition of the two layers: use the online verdict when present,
     * otherwise fall back to the offline list. Public as a test seam (this
     * repo's tests live in the default package) so the fallback contract can be
     * verified without a network or global state.
     */
    public static boolean decide(String password, Optional<Boolean> online) {
        return online.orElseGet(() -> offlineContains(password));
    }

    /** Query HIBP's k-anonymity range API. Returns empty (not "safe") on any
     *  failure so the caller falls back to the offline list. */
    private static Optional<Boolean> onlineCheck(String password) {
        if (!onlineEnabled()) return Optional.empty();
        try {
            String sha1 = sha1Hex(password);
            String prefix = sha1.substring(0, 5);
            String suffix = sha1.substring(5);
            Request req = new Request.Builder()
                    .url(HIBP_RANGE_URL + prefix)
                    .header("Add-Padding", "true")
                    .header("User-Agent", "JClaw")
                    .get()
                    .build();
            Call call = HttpFactories.general().newCall(req);
            call.timeout().timeout(timeoutMs(), TimeUnit.MILLISECONDS);
            try (Response resp = call.execute()) {
                // OkHttp 5's Response.body() is non-null, so no null check here.
                if (!resp.isSuccessful()) return Optional.empty();
                return Optional.of(suffixInRange(resp.body().string(), suffix));
            }
        } catch (Exception e) {
            EventLogger.warn("auth", "Breach check online lookup failed; using offline list: " + e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Whether {@code suffix} appears with a positive count in a HIBP range
     * response body (lines of {@code SUFFIX:count}). The {@code count > 0} guard
     * is essential: with {@code Add-Padding} the body contains decoy entries at
     * {@code count:0} that must not read as a breach.
     */
    public static boolean suffixInRange(String body, String suffix) {
        for (String line : body.split("\\r?\\n")) {
            int colon = line.indexOf(':');
            // Short-circuit protects substring() when there is no colon.
            if (colon < 0 || !line.substring(0, colon).trim().equalsIgnoreCase(suffix)) continue;
            try {
                return Long.parseLong(line.substring(colon + 1).trim()) > 0;
            } catch (NumberFormatException _) {
                // Malformed count on an otherwise-matching line: treat as a hit
                // (conservative) rather than silently passing a breached hash.
                return true;
            }
        }
        return false;
    }

    /** Uppercase hex SHA-1, matching HIBP's range-response casing. SHA-1 here is
     *  an API contract with HIBP, not a security primitive. */
    public static String sha1Hex(String s) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1")
                    .digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) sb.append("%02X".formatted(b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable — JDK install broken?", e);
        }
    }

    /** Case-insensitive membership in the bundled common-password list.
     *  Public as a test seam (default-package tests). */
    public static boolean offlineContains(String password) {
        return OfflineHolder.LIST.contains(password.toLowerCase(Locale.ROOT));
    }

    /** Lazy, thread-safe load of the bundled list via the on-demand holder
     *  idiom — read once on first use, immutable thereafter. */
    private static final class OfflineHolder {
        static final Set<String> LIST = load();

        private static Set<String> load() {
            Set<String> set = new HashSet<>();
            try {
                File f = Play.getFile(OFFLINE_LIST_PATH);
                if (f != null && f.exists()) {
                    for (String line : Files.readAllLines(f.toPath(), StandardCharsets.UTF_8)) {
                        String t = line.trim();
                        if (t.isEmpty() || t.startsWith("#")) continue;
                        set.add(t.toLowerCase(Locale.ROOT));
                    }
                } else {
                    Logger.warn("BreachedPasswordChecker: offline list missing at %s "
                            + "(online HIBP check still applies)", OFFLINE_LIST_PATH);
                }
            } catch (Exception e) {
                Logger.warn("BreachedPasswordChecker: failed to load offline list: %s", e.getMessage());
            }
            return Set.copyOf(set);
        }
    }

    private static boolean onlineEnabled() {
        return "true".equalsIgnoreCase(
                Play.configuration.getProperty(CFG_ONLINE_ENABLED, "true").trim());
    }

    private static int timeoutMs() {
        return PlayConfig.intOr(CFG_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
    }
}
