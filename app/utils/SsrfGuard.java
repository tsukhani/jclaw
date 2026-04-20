package utils;

import okhttp3.Dns;
import okhttp3.OkHttpClient;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * SSRF-hardened HTTP client factory used by tools that fetch LLM-supplied URLs.
 *
 * <p>The JDK's {@code java.net.http.HttpClient} exposes no DNS hook, so a
 * prompt-injected LLM can point {@code web_fetch} at loopback, RFC-1918,
 * link-local (AWS/GCP metadata), or multicast ranges and hit internal
 * services the platform operator never meant to expose. {@code SsrfGuard}
 * wraps OkHttp's {@link Dns} interface so hostname resolution is the gate:
 * any unsafe IP (on any branch of a multi-A-record answer) throws
 * {@link UnknownHostException} before OkHttp ever opens a socket.
 *
 * <p>Scope is intentionally narrow — this is for LLM-emitted URLs only.
 * Fixed admin-configured endpoints (LLM provider base URLs, channel APIs,
 * malware scanners) continue to use {@link HttpClients#GENERAL} /
 * {@link HttpClients#LLM} without SSRF overhead.
 *
 * <p>Limits of this design:
 * <ul>
 *   <li>Does not pin a resolved IP for the actual connect — OkHttp resolves
 *       via this {@code Dns}, caches nothing, then connects. A DNS rebinding
 *       attacker that wins the race between our resolve and OkHttp's connect
 *       could still slip through. Closing that TOCTOU gap would require
 *       switching to Netty's {@code AddressResolverGroup} pattern. For
 *       LLM-emitted URLs (single per-call attacker-controlled DNS response,
 *       high reputational cost per attempt), this is "close enough".</li>
 *   <li>Callers must still set {@code followRedirects(false)} and walk
 *       redirect hops manually, re-checking each target against the scheme
 *       allowlist — otherwise a 302 to {@code file:///etc/passwd} would be
 *       followed automatically.</li>
 * </ul>
 */
public final class SsrfGuard {

    private SsrfGuard() {}

    /** Schemes the guarded client will accept. Rejects {@code file://},
     *  {@code ftp://}, {@code gopher://}, {@code data:}, etc. */
    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    /**
     * An OkHttp {@link Dns} that rejects any hostname resolving to a
     * non-routable range: loopback (127.0.0.0/8, ::1), link-local
     * (169.254.0.0/16 — AWS/GCP/Azure metadata), site-local / RFC-1918
     * (10/8, 172.16/12, 192.168/16), multicast, or the unspecified 0.0.0.0.
     * Rejects even if only <em>one</em> A record is unsafe — prevents the
     * attacker-controlled DNS from mixing a safe and an unsafe IP.
     */
    public static final Dns SAFE_DNS = hostname -> {
        InetAddress[] addrs;
        try {
            addrs = InetAddress.getAllByName(hostname);
        } catch (UnknownHostException e) {
            throw e;
        }
        for (var addr : addrs) {
            if (isUnsafe(addr)) {
                throw new UnknownHostException(
                        "SSRF guard: host %s resolves to blocked address %s"
                                .formatted(hostname, addr.getHostAddress()));
            }
        }
        return List.of(addrs);
    };

    /**
     * Visible for testing. Returns true if the address is in a range the
     * guard forbids for LLM-supplied URLs.
     */
    public static boolean isUnsafe(InetAddress addr) {
        return addr.isLoopbackAddress()       // 127.0.0.0/8, ::1
                || addr.isAnyLocalAddress()   // 0.0.0.0, ::
                || addr.isLinkLocalAddress()  // 169.254.0.0/16, fe80::/10
                || addr.isSiteLocalAddress()  // 10/8, 172.16/12, 192.168/16, fec0::/10
                || addr.isMulticastAddress(); // 224/4
    }

    /**
     * Validate a URL's scheme and, if the host is a literal IP, validate the
     * IP too. The caller must invoke this <em>before</em> handing the URL to
     * {@link #buildGuardedClient()}, and again on every redirect target.
     *
     * <p>The literal-IP path is belt-and-suspenders: OkHttp's {@link Dns}
     * interface is only consulted for hostnames that need resolving —
     * literal {@code http://10.0.0.1/} or {@code http://169.254.169.254/}
     * bypass the custom resolver because the host is "already resolved".
     * Without this pre-check a prompt-injected LLM could reach RFC-1918 or
     * cloud metadata endpoints directly by IP.
     *
     * @throws SecurityException if the scheme is not {@code http} or
     *         {@code https}, the URI has no host, or the host parses as a
     *         literal IP in an unsafe range.
     */
    public static void assertSafeScheme(URI uri) {
        var scheme = uri.getScheme();
        if (scheme == null || !ALLOWED_SCHEMES.contains(scheme.toLowerCase())) {
            throw new SecurityException(
                    "SSRF guard: scheme not allowed: %s (only http/https)".formatted(scheme));
        }
        var host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new SecurityException("SSRF guard: URL has no host");
        }
        // If the host is a literal IP, validate it here because OkHttp's
        // Dns callback won't fire for already-resolved addresses.
        if (isLikelyIpLiteral(host)) {
            try {
                var addr = InetAddress.getByName(host);
                if (isUnsafe(addr)) {
                    throw new SecurityException(
                            "SSRF guard: host is a blocked IP literal: %s"
                                    .formatted(addr.getHostAddress()));
                }
            } catch (UnknownHostException e) {
                throw new SecurityException(
                        "SSRF guard: cannot parse host as IP: " + host, e);
            }
        }
    }

    /**
     * Cheap heuristic: treat the host as a literal IP if it's pure
     * digits+dots (IPv4) or wrapped in brackets (IPv6 RFC 3986 form). False
     * positives still flow into {@link InetAddress#getByName} which handles
     * edge cases (octal, mixed notation) correctly.
     */
    private static boolean isLikelyIpLiteral(String host) {
        if (host.startsWith("[") && host.endsWith("]")) return true; // [::1]
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (!((c >= '0' && c <= '9') || c == '.')) return false;
        }
        return !host.isEmpty();
    }

    /**
     * Build an OkHttp client wired to {@link #SAFE_DNS} with redirects
     * disabled. The caller owns redirect handling so each hop can be
     * re-validated through {@link #assertSafeScheme(URI)}.
     *
     * @param connectTimeoutSeconds connect timeout
     * @param callTimeoutSeconds    end-to-end timeout
     */
    public static OkHttpClient buildGuardedClient(int connectTimeoutSeconds,
                                                  int callTimeoutSeconds) {
        return new OkHttpClient.Builder()
                .dns(SAFE_DNS)
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(connectTimeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(callTimeoutSeconds, TimeUnit.SECONDS)
                .build();
    }
}
