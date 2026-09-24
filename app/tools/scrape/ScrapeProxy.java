package tools.scrape;

import com.google.gson.JsonObject;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import org.jspecify.annotations.Nullable;
import services.ConfigService;
import utils.SsrfGuard;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The operator's outbound proxy for scraping (JCLAW-1271): every rung routes through it, and
 * nothing outside scraping does.
 *
 * <p><b>SSRF.</b> Without a proxy the scrape clients resolve the target themselves and
 * {@link SsrfGuard#SAFE_DNS} refuses a private answer at connect. Behind a proxy that resolver
 * only ever sees the proxy's own host, and the proxy looks the target up again. So
 * {@link #client} checks the target with {@link SsrfGuard#assertUrlSafe} before every request —
 * one call per redirect hop, since the clients never follow redirects themselves — and fails
 * closed on a name it cannot resolve. What remains is a record that changes between that check
 * and the proxy's own lookup: it reaches what the proxy's network can reach, not this host's.
 * That is accepted because the proxy is an egress the operator chose. A {@code socks5} proxy
 * narrows it further for rung 2, where curl resolves locally and keeps the guard's address pin.
 *
 * <p>Credentials are HTTP-only. A SOCKS5 username would need a JVM-wide
 * {@link java.net.Authenticator} on rung 1, and Chromium cannot authenticate to SOCKS at all.
 */
public record ScrapeProxy(Kind kind, String host, int port,
                          @Nullable String username, @Nullable String password) {

    public enum Kind { HTTP, SOCKS5 }

    /**
     * The configured proxy, or empty when none is set or it is switched off.
     *
     * @throws IllegalStateException if the stored URL is invalid — a value written around the
     *         API. Scraping direct while the operator expects a proxy would be the silent failure.
     */
    public static Optional<ScrapeProxy> current() {
        return parse(ConfigService.get(WebScrapeSettings.PROXY_URL, ""),
                ConfigService.get(WebScrapeSettings.PROXY_ENABLED, "true"),
                ConfigService.get(WebScrapeSettings.PROXY_USERNAME, ""),
                ConfigService.get(WebScrapeSettings.PROXY_PASSWORD, ""));
    }

    /** {@link #current} over given values — public so a test need not write the process-wide keys. */
    public static Optional<ScrapeProxy> parse(String url, String enabled, String username, String password) {
        var u = url.strip();
        if (u.isEmpty() || "false".equalsIgnoreCase(enabled.strip())) return Optional.empty();
        var user = blankToNull(username);
        var pass = blankToNull(password);
        var rejection = urlRejection(u, user != null || pass != null);
        if (rejection != null) {
            throw new IllegalStateException("The scrape proxy is misconfigured: " + rejection);
        }
        var uri = URI.create(u);
        var kind = "socks5".equalsIgnoreCase(uri.getScheme()) ? Kind.SOCKS5 : Kind.HTTP;
        return Optional.of(new ScrapeProxy(kind, Objects.requireNonNull(uri.getHost()), uri.getPort(), user, pass));
    }

    /**
     * {@code base} routed through the configured proxy, or {@code base} itself when there is
     * none, so a client a test substitutes is used as given.
     */
    public static OkHttpClient client(OkHttpClient base) {
        return current().map(p -> p.apply(base)).orElse(base);
    }

    /** {@code base} routed through this proxy. Public so a test can build one without the config. */
    public OkHttpClient apply(OkHttpClient base) {
        var builder = base.newBuilder()
                .proxy(new Proxy(kind == Kind.SOCKS5 ? Proxy.Type.SOCKS : Proxy.Type.HTTP,
                        InetSocketAddress.createUnresolved(host, port)))
                // Behind a proxy this resolver sees only the proxy's own host, which the
                // operator may put on loopback or the LAN; the target is checked below.
                .dns(SsrfGuard.PROVIDER_SAFE_DNS)
                .addInterceptor(chain -> {
                    SsrfGuard.assertUrlSafe(chain.request().url().toString());
                    return chain.proceed(chain.request());
                });
        if (kind == Kind.HTTP && username != null) {
            var credential = Credentials.basic(username, password == null ? "" : password);
            builder.proxyAuthenticator((_, response) ->
                    // Already sent and refused: answering again would loop on a wrong password.
                    response.request().header("Proxy-Authorization") != null ? null
                            : response.request().newBuilder()
                                    .header("Proxy-Authorization", credential).build());
        }
        return builder.build();
    }

    /** The shape both sidecars read from a request's {@code proxy} field. */
    public JsonObject toJson() {
        var json = new JsonObject();
        json.addProperty("url", (kind == Kind.SOCKS5 ? "socks5" : "http") + "://" + host + ":" + port);
        if (username != null) json.addProperty("username", username);
        if (password != null) json.addProperty("password", password);
        return json;
    }

    /** The generated form would print the password into any log line or exception carrying this. */
    @Override
    public String toString() {
        return "ScrapeProxy[%s %s:%d%s]".formatted(kind, host, port, username == null ? "" : ", authenticated");
    }

    /** A message naming what the proxy URL must be, or null when {@code value} is acceptable. */
    static @Nullable String urlRejection(String value) {
        return urlRejection(value, hasCredentials());
    }

    /** {@link #urlRejection(String)} with the credential state given rather than read. */
    public static @Nullable String urlRejection(String value, boolean hasCredentials) {
        if (value.isEmpty()) return null;
        URI uri;
        try {
            uri = new URI(value);
        } catch (Exception _) {
            return WebScrapeSettings.PROXY_URL + " is not a URL; use http://host:port or socks5://host:port.";
        }
        var scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("socks5")) {
            return WebScrapeSettings.PROXY_URL + " must start with http:// or socks5://.";
        }
        if (uri.getRawUserInfo() != null) {
            // The URL is returned unmasked on every config read; the password key is not.
            return "Put proxy credentials in " + WebScrapeSettings.PROXY_USERNAME + " and "
                    + WebScrapeSettings.PROXY_PASSWORD + ", not in the URL.";
        }
        if (uri.getHost() == null || uri.getPort() <= 0) {
            return WebScrapeSettings.PROXY_URL + " needs a host and a port, e.g. http://proxy.example:8080.";
        }
        if ((uri.getRawPath() != null && !uri.getRawPath().isEmpty() && !uri.getRawPath().equals("/"))
                || uri.getRawQuery() != null) {
            return WebScrapeSettings.PROXY_URL + " takes only a scheme, host and port.";
        }
        if (isBlockedLiteral(uri.getHost())) {
            return WebScrapeSettings.PROXY_URL + " points at a link-local, multicast or unspecified "
                    + "address, which is never a proxy.";
        }
        if (scheme.equals("socks5") && hasCredentials) {
            return "SOCKS5 proxies are used without credentials; clear "
                    + WebScrapeSettings.PROXY_USERNAME + " and " + WebScrapeSettings.PROXY_PASSWORD + " first.";
        }
        return null;
    }

    /** A message naming what a proxy credential must be, or null when {@code value} is acceptable. */
    static @Nullable String credentialRejection(String value) {
        return credentialRejection(value, ConfigService.get(WebScrapeSettings.PROXY_URL, ""));
    }

    /** {@link #credentialRejection(String)} with the proxy URL given rather than read. */
    public static @Nullable String credentialRejection(String value, String proxyUrl) {
        if (value.isEmpty()) return null;
        if (value.chars().anyMatch(Character::isISOControl)) {
            return "Proxy credentials cannot contain control characters.";
        }
        if (proxyUrl.strip().regionMatches(true, 0, "socks5:", 0, 7)) {
            return "SOCKS5 proxies are used without credentials; use an http:// proxy to authenticate.";
        }
        return null;
    }

    private static boolean hasCredentials() {
        return !ConfigService.get(WebScrapeSettings.PROXY_USERNAME, "").isBlank()
                || !ConfigService.get(WebScrapeSettings.PROXY_PASSWORD, "").isBlank();
    }

    /** Only an IP literal is judged at save time; a hostname is screened when it is resolved. */
    private static boolean isBlockedLiteral(String host) {
        var bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        if (!bare.matches("[0-9.]+|[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*")) return false;
        try {
            return SsrfGuard.isBlockedForProvider(InetAddress.getByName(bare));
        } catch (UnknownHostException _) {
            return true;
        }
    }

    private static @Nullable String blankToNull(String value) {
        var v = value.strip();
        return v.isEmpty() ? null : v;
    }
}
