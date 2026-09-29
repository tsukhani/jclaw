package tools.scrape;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import org.jspecify.annotations.Nullable;
import utils.SsrfGuard;
import utils.TransientRetryInterceptor;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Settings &gt; Proxy Providers' Test connection (JCLAW-1323): one request through the saved
 * scrape proxy, on a client built as rung 1's is but without its retries, to an IP echo.
 */
public final class ScrapeProxyCheck {

    /** Plain http, so an HTTP proxy relays the request itself and a refusal arrives as its status line, not a failed CONNECT. */
    private static final String ECHO_URL = "http://api.ipify.org/";

    /** Each retry would be another request on a paid plan, and the panel promises one. */
    private static final OkHttpClient BASE = withoutRetries(SsrfGuard.buildGuardedClient(10, 30));

    private static volatile @Nullable String echoUrlForTest;

    private ScrapeProxyCheck() {}

    /**
     * What the check saw. {@code status} and {@code reason} are the proxy's or the echo's HTTP status line;
     * {@code error} is set when nothing answered, or when there was no proxy to test. {@code proxy} is
     * null only when there was no proxy.
     */
    public record ProxyCheckResult(boolean ok, @Nullable String ip, long ms, @Nullable Integer status,
                                   @Nullable String reason, @Nullable String error, @Nullable ProxyHost proxy) {}

    /**
     * Where this machine's resolver sent the proxy's host, so a filtering resolver's block page shows as
     * such. {@code address} is the host itself for a literal and null for a name that did not resolve;
     * {@code reverseName} is looked up only for a name, after a failed check, and is null when there is none.
     */
    public record ProxyHost(String host, @Nullable String address, @Nullable String reverseName) {}

    /** Points the check at a local fixture; a Play request thread sees no ScopedValue a test binds. */
    public static void setEchoUrlForTest(@Nullable String url) {
        echoUrlForTest = url;
    }

    /** Tests the saved proxy, and never dials direct when there is none. */
    public static ProxyCheckResult run() {
        Optional<ScrapeProxy> proxy;
        try {
            proxy = ScrapeProxy.current();
        } catch (IllegalStateException e) {
            return failed(0, messageOf(e));
        }
        if (proxy.isEmpty()) {
            return failed(0, "No proxy configured, so nothing was sent. Save one and switch it on first.");
        }
        var override = echoUrlForTest;
        return through(proxy.get(), override != null ? override : ECHO_URL);
    }

    private static ProxyCheckResult through(ScrapeProxy proxy, String echoUrl) {
        var host = proxy.host();
        // URI keeps an IPv6 literal's brackets.
        boolean literal = isAddress(host.replace("[", "").replace("]", ""));
        var address = literal ? host : resolve(host);
        var result = call(proxy, echoUrl);
        var reverseName = literal || result.ok() || address == null ? null : reverseName(address);
        return new ProxyCheckResult(result.ok(), result.ip(), result.ms(), result.status(), result.reason(),
                result.error(), new ProxyHost(host, address, reverseName));
    }

    private static ProxyCheckResult call(ScrapeProxy proxy, String echoUrl) {
        var call = proxy.apply(BASE).newCall(new Request.Builder().url(echoUrl).get().build());
        long start = System.nanoTime();
        try (var response = call.execute()) {
            long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            var reason = response.message().isBlank() ? null : response.message();
            if (!response.isSuccessful()) {
                return new ProxyCheckResult(false, null, ms, response.code(), reason, null, null);
            }
            var body = response.peekBody(256).string().strip();
            if (!isAddress(body)) {
                return new ProxyCheckResult(false, null, ms, response.code(), reason,
                        "The IP echo answered with something that is not an address.", null);
            }
            return new ProxyCheckResult(true, body, ms, response.code(), reason, null, null);
        } catch (SecurityException e) {
            return failed(0, "The check was refused before anything was sent: " + messageOf(e));
        } catch (IOException e) {
            return failed(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start),
                    "Nothing answered through the proxy: " + messageOf(e));
        }
    }

    /** The first address, which OkHttp dials first. */
    private static @Nullable String resolve(String host) {
        try {
            return InetAddress.getAllByName(host)[0].getHostAddress();
        } catch (UnknownHostException _) {
            return null;
        }
    }

    private static @Nullable String reverseName(String address) {
        var name = InetAddress.ofLiteral(address).getCanonicalHostName();
        return name.equals(address) ? null : name;
    }

    private static boolean isAddress(String text) {
        try {
            InetAddress.ofLiteral(text);
            return true;
        } catch (IllegalArgumentException _) {
            return false;
        }
    }

    private static OkHttpClient withoutRetries(OkHttpClient client) {
        var builder = client.newBuilder();
        builder.interceptors().removeIf(TransientRetryInterceptor.class::isInstance);
        return builder.build();
    }

    private static ProxyCheckResult failed(long ms, String error) {
        return new ProxyCheckResult(false, null, ms, null, null, error, null);
    }

    private static String messageOf(Exception e) {
        return Objects.requireNonNullElse(e.getMessage(), e.getClass().getSimpleName());
    }
}
