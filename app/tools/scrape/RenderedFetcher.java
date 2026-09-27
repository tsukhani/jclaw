package tools.scrape;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.jspecify.annotations.Nullable;
import services.EventLogger;
import services.LocalSidecarDaemon;
import services.StealthSidecarManager;
import services.scrape.ScrapeObservation;
import services.scrape.ScrapeSidecarException;
import utils.HttpFactories;
import utils.HttpKeys;
import utils.SsrfGuard;
import utils.Urls;
import utils.WebExtraction;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Optional;
import java.util.concurrent.Semaphore;

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

    private static final String HTML = "text/html; charset=utf-8";

    /** A render is slow by nature: navigation, then a settle window or a challenge wait.
     *  Well above the budgets below so reaching this means the sidecar is wedged, not that
     *  the page was slow. Public because Play's tests live in the default package. */
    public static final Duration CALL_TIMEOUT = Duration.ofSeconds(120);

    /** Sent with every render rather than left to the sidecar's defaults, so the budgets are
     *  this class's to keep inside {@link #CALL_TIMEOUT}. */
    private static final Duration NAVIGATION_TIMEOUT = Duration.ofSeconds(35);
    /** How long a render polls a standing Cloudflare challenge, in place of its settle window
     *  (JCLAW-1306). */
    private static final Duration CHALLENGE_BUDGET = Duration.ofSeconds(45);

    /** serve.py's {@code DEFAULT_MAX_CONCURRENT}. A render queues for a slot here, where no timer
     *  runs, rather than in the sidecar against {@link #CALL_TIMEOUT}. Public because Play's tests
     *  live in the default package. */
    public static final int RENDER_SLOTS = 4;

    private static final Semaphore SLOTS = new Semaphore(RENDER_SLOTS, true);

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

    /** A rendered page, the challenge the render met as the sidecar's {@code X-Challenge} reported
     *  it — null when none stood — and the cf_clearance a session render was asked for, when the
     *  browser holds one. */
    public record Render(WebExtraction.FetchResult fetched, @Nullable String challenge,
                         ScrapeSessions.@Nullable Clearance clearance) {

        public Render(WebExtraction.FetchResult fetched, @Nullable String challenge) {
            this(fetched, challenge, null);
        }
    }

    /** The sidecar no longer holds the session a render named (JCLAW-1307). */
    public static final class SessionGoneException extends ScrapeSidecarException {
        public SessionGoneException(String message) {
            super(message, null);
        }
    }

    private RenderedFetcher() {}

    public static boolean available() {
        return StealthSidecarManager.available();
    }

    /** Render {@code url} in the ladder's default language. */
    public static WebExtraction.FetchResult fetch(String url) throws IOException {
        return fetch(url, ScrapeLadder.DEFAULT_LANGUAGE);
    }

    /** Render {@code url} in the shape the other rungs produce; {@link #render} also says
     *  which challenge the render met. */
    public static WebExtraction.FetchResult fetch(String url, String language)
            throws IOException {
        return render(url, language).fetched();
    }

    /**
     * Render {@code url} through the stealth sidecar.
     *
     * <p>{@code language} reaches the browser context, so an escalated page comes back
     * in the language the unescalated one would have. Without it a crawl asking for
     * Japanese got Japanese from rung 1 and English from rung 3, with only a rung
     * marker in the output to explain the difference.
     */
    public static Render render(String url, String language) throws IOException {
        // Authoritative check stays in the JVM. hostResolverRule throws every
        // SecurityException assertUrlSafe does, so an unsafe entry URL never reaches
        // the browser.
        var pins = pins(SsrfGuard.hostResolverRule(url));
        var request = post(StealthSidecarManager.ensureRunning(), "/render",
                renderRequest(url, language, pins));

        return inRenderSlot(() -> {
            try (var response = CLIENT.newCall(request).execute()) {
                return rendered(response, url);
            }
        });
    }

    /** A {@link SsrfGuard#hostResolverRule} as the sidecar's {@code pins} object. */
    static JsonObject pins(Optional<String> pinRule) {
        var pins = new JsonObject();
        // "MAP <host> <ip>" — the sidecar rebuilds the flag, so the JVM never has to
        // know Chromium's argument syntax and the guard never has to emit it.
        pinRule.ifPresent(rule -> {
            var parts = rule.split(" ");
            if (parts.length == 3) pins.addProperty(parts[1], parts[2]);
        });
        return pins;
    }

    /**
     * Open a browser session serving {@code host} alone, launched with {@code pins} and
     * {@code proxy} for its whole life (JCLAW-1307).
     *
     * @return the session's id, or null when every session the sidecar allows is open
     */
    static @Nullable String openSession(String host, JsonObject pins, String language,
                                        @Nullable ScrapeProxy proxy) throws IOException {
        var body = new JsonObject();
        body.addProperty("host", host);
        body.add("pins", pins);
        body.addProperty("language", language);
        if (proxy != null) body.add("proxy", proxy.toJson());
        var request = post(StealthSidecarManager.ensureRunning(), "/session/open", body);
        try (var response = CLIENT.newCall(request).execute()) {
            if (response.code() == 429) return null;
            var answer = response.body().string();
            if (!response.isSuccessful()) {
                throw new ScrapeSidecarException("stealth sidecar returned HTTP %d opening a session for %s: %s"
                        .formatted(response.code(), host, answer.strip()), null);
            }
            try {
                return JsonParser.parseString(answer).getAsJsonObject().get("id").getAsString();
            } catch (RuntimeException e) {
                throw new ScrapeSidecarException("stealth sidecar answered a session open with no id", e);
            }
        }
    }

    /**
     * Render {@code url} in session {@code id}, holding one of the {@link #RENDER_SLOTS}. The caller
     * has screened {@code url}.
     *
     * @param clearance whether to ask for the cf_clearance the browser holds for the session's host
     * @throws SessionGoneException when the sidecar no longer holds the session
     */
    static Render renderInSession(String id, String url, boolean clearance) throws IOException {
        var body = budgeted(url);
        body.addProperty("session", id);
        body.addProperty("clearance", clearance);
        var request = post(StealthSidecarManager.ensureRunning(), "/render", body);
        return inRenderSlot(() -> {
            try (var response = CLIENT.newCall(request).execute()) {
                if (response.code() == 404) throw new SessionGoneException("session gone for " + url);
                return rendered(response, url);
            }
        });
    }

    /** Close session {@code id}. Never starts the sidecar: one that is not running holds no session. */
    static void closeSession(String id) throws IOException {
        var body = new JsonObject();
        body.addProperty("id", id);
        try (var response = CLIENT.newCall(post(StealthSidecarManager.baseUrl(), "/session/close", body))
                .execute()) {
            if (!response.isSuccessful()) {
                throw new ScrapeSidecarException("stealth sidecar returned HTTP %d closing a session"
                        .formatted(response.code()), null);
            }
        }
    }

    private static Request post(String baseUrl, String path, JsonObject body) {
        return new Request.Builder()
                .url(baseUrl + path)
                .header(LocalSidecarDaemon.AUTH_HEADER, StealthSidecarManager.authToken())
                .post(RequestBody.create(body.toString(), JSON))
                .build();
    }

    /** One call to the sidecar. Public because Play's tests live in the default package. */
    @FunctionalInterface
    public interface SidecarCall<T> {
        T call() throws IOException;
    }

    /** Run {@code call} holding one of the {@link #RENDER_SLOTS}. Public because Play's tests live
     *  in the default package. */
    public static <T> T inRenderSlot(SidecarCall<T> call) throws IOException {
        try {
            SLOTS.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("interrupted waiting for a render slot");
        }
        try {
            return call.call();
        } finally {
            SLOTS.release();
        }
    }

    /** The body of one render request. Public because Play's tests live in the default package. */
    public static JsonObject renderRequest(String url, String language, JsonObject pins) {
        var payload = budgeted(url);
        payload.addProperty("language", language);
        payload.add("pins", pins);
        ScrapeProxy.current().ifPresent(proxy -> payload.add("proxy", proxy.toJson()));
        return payload;
    }

    /** What every render request carries, a session's included. */
    private static JsonObject budgeted(String url) {
        var payload = new JsonObject();
        payload.addProperty("url", url);
        payload.addProperty("maxBytes", WebExtraction.maxBodyBytes());
        payload.addProperty("timeoutMs", NAVIGATION_TIMEOUT.toMillis());
        payload.addProperty("challengeMs", CHALLENGE_BUDGET.toMillis());
        payload.addProperty("solveTurnstile", StealthSidecarManager.solveTurnstile());
        return payload;
    }

    /**
     * The sidecar's answer to a render of {@code url}, in the shape the other rungs produce.
     * Public because Play's tests live in the default package.
     *
     * @throws WebExtraction.HttpStatusException when the render ended on a status of 400 or
     *         above, carrying the rendered body for the classifier and the challenge report
     * @throws ScrapeSidecarException when the sidecar itself failed
     */
    public static Render rendered(Response response, String url) throws IOException {
        // Bounded like every other transport: a render settles into a DOM the origin
        // controls the size of, and readTimeout is disabled here, so an unbounded
        // read is the one place a page could push arbitrary bytes onto the heap.
        var body = WebExtraction.readBounded(response.body(), Urls.parse(url));
        if (!response.isSuccessful()) {
            throw new ScrapeSidecarException("stealth sidecar returned HTTP %d for %s: %s"
                    .formatted(response.code(), url,
                            new String(body, StandardCharsets.UTF_8).strip()), null);
        }
        reportBlockedHosts(response, url);
        WebExtraction.noteUpstreamTruncated(response.header("X-Upstream-Truncated"), url);
        var challenge = response.header(ScrapeObservation.CHALLENGE_HEADER);
        int status = renderedStatus(response, url);
        if (status >= 400) {
            var headers = new HashMap<>(WebExtraction.classifiedHeaders(response, "X-Upstream-"));
            if (challenge != null) headers.put(ScrapeObservation.CHALLENGE_HEADER, challenge);
            throw new WebExtraction.HttpStatusException(status, url, body, HTML, headers);
        }
        return new Render(new WebExtraction.FetchResult(body, HTML, finalUrl(response, url)), challenge,
                clearance(response));
    }

    private static ScrapeSessions.@Nullable Clearance clearance(Response response) {
        var cookie = response.header("X-Clearance");
        var userAgent = response.header("X-Clearance-User-Agent");
        return cookie == null || userAgent == null ? null : new ScrapeSessions.Clearance(userAgent, cookie);
    }

    /**
     * Where the settle or challenge window ended decides, when the sidecar reports it: an
     * interstitial served 403 that resolves itself ends on 200, and the settled body is the real
     * page.
     * Without it, the first navigation's status decides.
     */
    private static int renderedStatus(Response response, String url) {
        // "0" is the sidecar's own value for "no navigation response": it reports nothing,
        // and is not an error.
        var settled = response.header("X-Settled-Status");
        var status = settled == null || "0".equals(settled)
                ? response.header("X-Upstream-Status", "0") : settled;
        return upstreamStatus(status, url);
    }

    /**
     * Where the render landed, re-validated and normalized — the browser may have been redirected,
     * so a hop the sidecar's interceptor allowed still cannot return an unsafe final URL.
     *
     * <p>The guard reads the string as Chromium wrote it, because its own parse is the lenient one
     * (JCLAW-1285). What leaves is that string parsed: every other rung's final URL comes from a
     * {@link java.net.URI}, and {@code WebScrapeTool.pickVariant} matches this one against
     * alternates that do, so an un-normalized spelling would record one page under two URLs.
     *
     * <p>Public because Play's tests live in the default package.
     */
    public static String finalUrl(Response response, String requested) {
        var landed = response.header("X-Upstream-Url", requested);
        SsrfGuard.assertUrlSafe(landed);
        return Urls.parse(landed).toString();
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
