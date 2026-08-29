package tools.scrape;

import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import services.EventLogger;
import services.LocalSidecarDaemon;
import services.StealthSidecarManager;
import services.scrape.ScrapeSidecarException;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Rung 3: render through the stealth browser sidecar (JCLAW-1088).
 *
 * <p>Not a {@link WebExtraction.Transport}. Rungs 1 and 2 hand each redirect back so the
 * JVM can re-validate it; a browser follows redirects internally and cannot be asked to
 * stop. Containment therefore moves rather than disappearing, and it is layered exactly
 * as {@code PlaywrightBrowserTool} layers it in-JVM (JCLAW-731):
 *
 * <ol>
 *   <li>the entry URL is validated here and pinned to the address {@link SsrfGuard}
 *       actually resolved, closing the rebinding window between our lookup and the
 *       browser's;</li>
 *   <li>the sidecar's route interceptor range-checks every further host the page
 *       reaches — redirects and subresources both — and aborts the non-public ones,
 *       reporting them back in {@code X-Blocked-Hosts}, which this class logs.</li>
 * </ol>
 */
public final class RenderedFetcher {

    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);

    private static final String EVENT_CATEGORY = "scrape";

    /** A render is slow by nature: navigation, then a settle window for a challenge to
     *  resolve itself. Well above the sidecar's own per-render timeout so reaching this
     *  means the sidecar is wedged, not that the page was slow. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    private static final OkHttpClient CLIENT = HttpFactories.general().newBuilder()
            .callTimeout(CALL_TIMEOUT)
            // Bound by callTimeout, not by the general client's 30s per-read timeout.
            // A render legitimately sends nothing while the browser launches, navigates
            // and settles, and the 30s default cut 64 corpus entries off mid-render and
            // reported them as TIMEOUT — a latency artifact indistinguishable, in the
            // report, from an origin refusing us. Same tradeoff SidecarHttpClient
            // documents: with readTimeout=0 a hung socket is bounded ONLY by callTimeout.
            .readTimeout(Duration.ZERO)
            .build();

    private RenderedFetcher() {}

    public static boolean available() {
        return StealthSidecarManager.available();
    }

    /** Render {@code url} in the ladder's default language. */
    public static WebExtraction.FetchResult fetch(String url) throws IOException {
        return fetch(url, ScrapeLadder.DEFAULT_LANGUAGE);
    }

    /**
     * Render {@code url} and return it in the same shape the other rungs produce.
     *
     * <p>{@code language} reaches the browser context, so an escalated page comes back
     * in the language the unescalated one would have. Without it a crawl asking for
     * Japanese got Japanese from rung 1 and English from rung 3, with only a rung
     * marker in the output to explain the difference.
     */
    public static WebExtraction.FetchResult fetch(String url, String language)
            throws IOException {
        // Authoritative check stays in the JVM. hostResolverRule throws every
        // SecurityException assertUrlSafe does, so an unsafe entry URL never reaches
        // the browser.
        var pinRule = SsrfGuard.hostResolverRule(url);
        var baseUrl = StealthSidecarManager.ensureRunning();

        var payload = new JsonObject();
        payload.addProperty("url", url);
        payload.addProperty("language", language);
        var pins = new JsonObject();
        // "MAP <host> <ip>" — the sidecar rebuilds the flag, so the JVM never has to
        // know Chromium's argument syntax and the guard never has to emit it.
        pinRule.ifPresent(rule -> {
            var parts = rule.split(" ");
            if (parts.length == 3) pins.addProperty(parts[1], parts[2]);
        });
        payload.add("pins", pins);
        payload.addProperty("maxBytes", WebExtraction.maxBodyBytes());

        var request = new Request.Builder()
                .url(baseUrl + "/render")
                .header(LocalSidecarDaemon.AUTH_HEADER, StealthSidecarManager.authToken())
                .post(RequestBody.create(payload.toString(), JSON))
                .build();

        try (var response = CLIENT.newCall(request).execute()) {
            // Bounded like every other transport: a render settles into a DOM the origin
            // controls the size of, and readTimeout is disabled here, so an unbounded
            // read is the one place a page could push arbitrary bytes onto the heap.
            var body = WebExtraction.readBounded(response.body(), URI.create(url));
            if (!response.isSuccessful()) {
                throw new ScrapeSidecarException("stealth sidecar returned HTTP %d for %s: %s"
                        .formatted(response.code(), url,
                                new String(body, StandardCharsets.UTF_8).strip()), null);
            }
            reportBlockedHosts(response, url);
            WebExtraction.noteUpstreamTruncated(response.header("X-Upstream-Truncated"), url);
            // "0" is the sidecar's own value for "navigation returned no response
            // object", not a missing header — it is not an error.
            var status = response.header("X-Upstream-Status", "0");
            if (!"0".equals(status) && upstreamStatus(status, url) >= 400) {
                throw new IOException("HTTP %s fetching %s".formatted(status, url));
            }
            var finalUrl = response.header("X-Upstream-Url", url);
            // The browser may have been redirected; re-validate where it landed so a
            // hop the interceptor allowed still cannot return an unsafe final URL.
            SsrfGuard.assertUrlSafe(URI.create(finalUrl).toString());
            return new WebExtraction.FetchResult(body, "text/html; charset=utf-8", finalUrl);
        }
    }

    /** The count is logged, never parsed: a total in a shape this JVM does not recognize
     *  belongs in the line rather than turning a diagnostic into a fault. */
    private static void reportBlockedHosts(Response response, String url) {
        var hosts = response.header("X-Blocked-Hosts");
        var total = response.header("X-Blocked-Hosts-Count");
        if (hosts == null && total == null) return;
        EventLogger.info(EVENT_CATEGORY,
                "%s: the render reached hosts the sidecar aborted".formatted(url),
                "%s (total: %s)".formatted(hosts == null ? "unnamed" : hosts,
                        total == null ? "unreported" : total));
    }

    /** Fails as a sidecar fault rather than letting an unchecked parse error escape a
     *  method declared to throw {@link IOException}, as rung 2 already does. */
    private static int upstreamStatus(String status, String url) {
        try {
            return Integer.parseInt(status);
        } catch (NumberFormatException e) {
            throw new ScrapeSidecarException(
                    "stealth sidecar sent a non-numeric upstream status for " + url, e);
        }
    }
}
