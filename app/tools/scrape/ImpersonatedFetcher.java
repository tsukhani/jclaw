package tools.scrape;

import com.google.gson.JsonObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import services.FetchSidecarManager;
import services.LocalSidecarDaemon;
import services.scrape.ScrapeSidecarException;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;
import utils.WebExtraction;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

/**
 * Rung 2 of the escalation ladder: fetch through the TLS-impersonating sidecar
 * (JCLAW-1087).
 *
 * <p>This is a {@link WebExtraction.Transport} and nothing more. The redirect walk,
 * the per-hop {@link SsrfGuard} re-validation and the text extraction all stay in
 * {@link WebExtraction}, shared with rung 1 — so the two lanes cannot drift apart on
 * the containment rules, which is the failure this seam exists to prevent.
 *
 * <p>The sidecar answers 200 for "the exchange completed" and reports the origin's
 * own status in {@code X-Upstream-Status}. Collapsing those two would make a 403
 * raised by the sidecar indistinguishable from one served by Cloudflare, and the
 * block classifier reads exactly that distinction.
 */
public final class ImpersonatedFetcher {

    private static final MediaType JSON = MediaType.get(HttpKeys.APPLICATION_JSON);

    /** Bound on one sidecar round trip. The sidecar applies its own per-origin timeout
     *  well inside this, so hitting this one means the sidecar itself is wedged. */
    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(90);

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

    private ImpersonatedFetcher() {}

    /** Whether this install can attempt rung 2 at all, without spawning anything. */
    public static boolean available() {
        return FetchSidecarManager.available();
    }

    /**
     * A transport bound to the sidecar. Spawning is deferred to the first exchange so
     * building one is free — the ladder can construct it and never pay for a rung it
     * does not reach.
     */
    public static WebExtraction.Transport transport() {
        return (uri, headers) -> {
            // The guard runs here as well as in the redirect walk, and it pins:
            // hostResolverRule throws everything assertUrlSafe does AND returns the
            // address it actually validated. Sending the hostname alone left curl to
            // resolve it a second time, so a record that changed between the two
            // lookups reached an address the guard had never seen — the rebinding
            // window rung 3 closes at launch and this one had left open.
            var pinRule = SsrfGuard.hostResolverRule(uri.toString());
            var baseUrl = FetchSidecarManager.ensureRunning();

            var payload = new JsonObject();
            payload.addProperty("url", uri.toString());
            var pins = new JsonObject();
            // "MAP <host> <ip>" — the sidecar turns it into a CURLOPT_RESOLVE entry,
            // so the JVM never has to know curl's argument syntax, exactly as the
            // render sidecar rebuilds Chromium's flag from the same rule.
            pinRule.ifPresent(rule -> {
                var parts = rule.split(" ");
                if (parts.length == 3) pins.addProperty(parts[1], parts[2]);
            });
            payload.add("pins", pins);
            payload.addProperty("timeoutMs", CALL_TIMEOUT.toMillis() / 2);
            payload.addProperty("maxBytes", WebExtraction.maxBodyBytes());
            var hdrs = new JsonObject();
            headers.forEach(hdrs::addProperty);
            payload.add("headers", hdrs);

            var request = new Request.Builder()
                    .url(baseUrl + "/fetch")
                    .header(LocalSidecarDaemon.AUTH_HEADER, FetchSidecarManager.authToken())
                    .post(RequestBody.create(payload.toString(), JSON))
                    .build();

            try (var response = CLIENT.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    // Carry the sidecar's own error body: it names the cause (a TLS
                    // verification failure reads very differently from an origin refusing
                    // us), and without it the harness reports a bare 502 that has to be
                    // reproduced by hand to diagnose.
                    var detail = response.body().string();
                    throw new ScrapeSidecarException(
                            "fetch sidecar returned HTTP %d for %s: %s"
                                    .formatted(response.code(), uri, truncate(detail)), null);
                }
                var upstream = response.header("X-Upstream-Status");
                if (upstream == null) {
                    throw new ScrapeSidecarException(
                            "fetch sidecar response carried no X-Upstream-Status for " + uri, null);
                }
                // Bounded like rungs 1 and 3: readTimeout is disabled on this client, so
                // an orphan from an older build holding the port buffers straight onto
                // the heap otherwise. The token proves the caller held the secret, not
                // that the process answering is the build this JVM expects.
                var body = WebExtraction.readBounded(response.body(), uri);
                WebExtraction.noteUpstreamTruncated(
                        response.header("X-Upstream-Truncated"), uri.toString());
                return new WebExtraction.Exchange(
                        Integer.parseInt(upstream),
                        body,
                        response.header("X-Upstream-Content-Type", ""),
                        response.header("X-Upstream-Location"));
            } catch (NumberFormatException e) {
                throw new ScrapeSidecarException("fetch sidecar sent a non-numeric upstream status", e);
            }
        };
    }

    /** Collapse a sidecar error body to one short line for an exception message. */
    private static String truncate(String s) {
        if (s == null) return "";
        var oneLine = s.replaceAll("\\s+", " ").strip();
        return oneLine.length() > 200 ? oneLine.substring(0, 200) + "\u2026" : oneLine;
    }

    /** Fetch {@code url} through the sidecar, walking redirects under SsrfGuard. */
    public static WebExtraction.FetchResult fetch(String url, Map<String, String> headers)
            throws IOException {
        return WebExtraction.fetch(url, headers, transport());
    }
}
