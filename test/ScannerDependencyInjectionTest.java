import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.scanners.MalwareBazaarScanner;
import services.scanners.MetaDefenderCloudScanner;
import services.scanners.ScannerConfig;
import services.scanners.ScannerDependencies;
import services.scanners.ScannerHttpClient;
import services.scanners.VirusTotalScanner;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Coverage for the dependency-injection seam introduced by
 * {@link ScannerDependencies} et al. — the {@code Scanner*} interfaces let us
 * exercise behaviors that a real-HTTP test (see {@code ScannerTest}) cannot
 * cleanly assert: the exact wire shape of each scanner's outbound request, the
 * specific warning message logged on each failure path, and the base class's
 * classification of network-level exceptions vs. HTTP-status failures.
 *
 * <p>The HTTP-server-backed {@code ScannerTest} remains the source of truth
 * for end-to-end happy paths (real socket, real {@code OkHttpClient}, real
 * {@code ConfigService}). These tests run in tens of microseconds with no
 * sockets — fast enough that future scanner implementations can rely on the
 * DI for unit-test coverage and use the slower integration path only for
 * smoke tests.
 *
 * <p>JCLAW-188 reshaped the 11 fixtures around OkHttp {@link Request} +
 * {@link Response} after the scanner DI moved off the JDK HttpClient.
 */
public class ScannerDependencyInjectionTest extends UnitTest {

    /** SHA-256 of the empty string — content doesn't matter, only the wire shape does. */
    private static final String SHA =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    // =====================================================================
    // Request-shape verification — pins each scanner's outbound contract
    // =====================================================================

    @Test
    public void virusTotal_buildsGetRequestWithApiKeyHeader() {
        // Pins the wire contract: GET to {baseUrl}files/{sha}, x-apikey header,
        // Accept: application/json. A future refactor that accidentally drops
        // the x-apikey header (e.g., switching to Authorization: Bearer) would
        // not fail any HttpServer test that ignores headers — this catches it.
        var deps = new FakeDeps();
        deps.config.put("scanner.virustotal.url", "https://vt.example/v3/");
        deps.config.put("scanner.virustotal.apiKey", "key-vt");
        deps.responder = (req, timeoutMs) -> stubResponse(req, 200,
                "{\"data\":{\"attributes\":{\"last_analysis_stats\":{\"malicious\":0}}}}");

        new VirusTotalScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size(), "exactly one request must fire");
        var req = deps.requests.get(0);
        assertEquals("GET", req.method());
        assertEquals("https://vt.example/v3/files/" + SHA, req.url().toString());
        assertEquals("key-vt", req.header("x-apikey"));
        assertEquals("application/json", req.header("Accept"));
    }

    @Test
    public void metaDefender_buildsGetRequestWithApikeyHeader() {
        // MetaDefender uses the lowercase header name "apikey" — distinct from
        // VirusTotal's "x-apikey". Pin that distinction so a future copy-paste
        // mistake doesn't accidentally unify them.
        var deps = new FakeDeps();
        deps.config.put("scanner.metadefender.url", "https://md.example/v4/");
        deps.config.put("scanner.metadefender.apiKey", "key-md");
        deps.responder = (req, timeoutMs) -> stubResponse(req, 200,
                "{\"scan_results\":{\"scan_all_result_i\":0}}");

        new MetaDefenderCloudScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        var req = deps.requests.get(0);
        assertEquals("GET", req.method());
        assertEquals("https://md.example/v4/hash/" + SHA, req.url().toString());
        assertEquals("key-md", req.header("apikey"));
        assertEquals("application/json", req.header("Accept"));
    }

    @Test
    public void malwareBazaar_buildsPostFormRequestWithAuthKeyHeader() throws Exception {
        // MalwareBazaar is the odd one out: POST + form-encoded body + the
        // distinctive Auth-Key header. Pin all three because each diverges
        // from the other two scanners.
        var deps = new FakeDeps();
        deps.config.put("scanner.malwarebazaar.url", "https://mb.example/api/v1/");
        deps.config.put("scanner.malwarebazaar.authKey", "key-mb");
        deps.responder = (req, timeoutMs) -> stubResponse(req, 200,
                "{\"query_status\":\"hash_not_found\"}");

        new MalwareBazaarScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        var req = deps.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals("https://mb.example/api/v1/", req.url().toString());
        assertEquals("key-mb", req.header("Auth-Key"));
        var contentType = req.body() != null && req.body().contentType() != null
                ? req.body().contentType().toString() : "";
        assertTrue(contentType.startsWith("application/x-www-form-urlencoded"),
                "body MIME must be form-urlencoded (with optional charset suffix): " + contentType);
        var expectedBody = "query=get_info&hash=" + SHA;
        try (var sink = new Buffer()) {
            req.body().writeTo(sink);
            assertEquals(expectedBody, sink.readUtf8(),
                    "body must be the form-encoded query=get_info&hash=<sha>");
        }
    }

    @Test
    public void malwareBazaar_omitsAuthKeyHeaderWhenKeyIsBlank() {
        // Defensive: if a future change skips the isEnabled() gate (e.g.,
        // because the orchestrator changes), MalwareBazaar's lookup() must
        // not send a blank Auth-Key header. The branch is in the lookup body
        // (only adds the header when the key is non-blank) — the DI lets us
        // pin it without going through the orchestrator.
        var deps = new FakeDeps();
        deps.config.put("scanner.malwarebazaar.url", "https://mb.example/api/v1/");
        deps.config.put("scanner.malwarebazaar.authKey", "");
        deps.responder = (req, timeoutMs) -> stubResponse(req, 200,
                "{\"query_status\":\"hash_not_found\"}");

        new MalwareBazaarScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        assertNull(deps.requests.get(0).header("Auth-Key"),
                "blank authKey must result in no Auth-Key header at all");
    }

    // =====================================================================
    // Config fallback — ConfiguredHashScanner uses ScannerConfig.get(k,fb)
    // =====================================================================

    @Test
    public void virusTotal_fallsBackToProductionUrlWhenConfigUnset() {
        // The base class reads URL via config().get(key, fallback). When the
        // operator hasn't set scanner.virustotal.url, the request must still
        // go to the production endpoint. DI pins that the fallback path is
        // wired up correctly without needing to mutate ConfigService.
        var deps = new FakeDeps();
        deps.config.put("scanner.virustotal.apiKey", "key");
        deps.responder = (req, timeoutMs) -> stubResponse(req, 200,
                "{\"data\":{\"attributes\":{\"last_analysis_stats\":{\"malicious\":0}}}}");

        new VirusTotalScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        assertTrue(deps.requests.get(0).url().toString().startsWith("https://www.virustotal.com/api/v3/"),
                "default URL must be applied via the config fallback path: "
                        + deps.requests.get(0).url());
    }

    // =====================================================================
    // Captured-log assertions — what the HttpServer tests can't see
    // =====================================================================

    @Test
    public void virusTotal_logsHttp500WithStatusAndFailOpenMarker() {
        var deps = configuredVtDeps();
        deps.responder = (req, timeoutMs) -> stubResponse(req, 500, "internal server error");

        var verdict = new VirusTotalScanner(deps.build()).lookup(SHA);

        assertFalse(verdict.malicious(), "5xx must fail open to clean");
        assertFalse(deps.warnings.isEmpty(), "5xx must log a warning");
        var warning = deps.warnings.get(0);
        assertTrue(warning.contains("VirusTotal"), "warning must name the scanner: " + warning);
        assertTrue(warning.contains("HTTP 500"), "warning must include the status code: " + warning);
        assertTrue(warning.contains("failing open"), "warning must mark the fail-open behavior: " + warning);
    }

    @Test
    public void metaDefender_logsNetworkExceptionRootCause() {
        // IOException thrown by the HttpClient itself (DNS failure, socket
        // reset before any HTTP response) is a distinct path from a 5xx
        // response. The DI is the only way to exercise it deterministically;
        // the HttpServer mock can simulate a 5xx but cannot make OkHttp
        // throw before reading a response.
        var deps = configuredMdDeps();
        deps.responder = (req, timeoutMs) -> { throw new IOException("simulated network failure"); };

        var verdict = new MetaDefenderCloudScanner(deps.build()).lookup(SHA);

        assertFalse(verdict.malicious(), "network exception must fail open");
        assertFalse(deps.warnings.isEmpty(), "network exception must log a warning");
        var warning = deps.warnings.get(0);
        assertTrue(warning.contains("MetaDefender"), "warning must name the scanner: " + warning);
        assertTrue(warning.contains("simulated network failure"),
                "warning must surface the root-cause message: " + warning);
    }

    @Test
    public void malwareBazaar_logsUnknownQueryStatus() {
        // MalwareBazaar's lookup parses query_status; "ok" and "hash_not_found"
        // / "no_results" are the documented values. Anything else (illegal
        // auth key, rate-limited, etc.) falls through to a warning + clean.
        // The DI lets us assert that the warning carries the unknown status
        // verbatim so an operator reading the log knows what came back.
        var deps = configuredMbDeps();
        deps.responder = (req, timeoutMs) -> stubResponse(req, 200,
                "{\"query_status\":\"illegal_auth_key\"}");

        var verdict = new MalwareBazaarScanner(deps.build()).lookup(SHA);

        assertFalse(verdict.malicious(), "unknown query_status must fail open");
        assertFalse(deps.warnings.isEmpty(), "unknown status must log a warning");
        assertTrue(deps.warnings.get(0).contains("illegal_auth_key"),
                "warning must include the unexpected status verbatim: " + deps.warnings.get(0));
    }

    @Test
    public void virusTotal_404IsCleanAndSilent() {
        // VT/MD pass cleanOnNotFound=true to sendJsonLookup. The base class
        // returns Verdict.clean() WITHOUT logging — the absence of a warning
        // is part of the contract (404 means "hash never seen", not an error
        // worth alerting on). MalwareBazaar's lookup uses cleanOnNotFound=false
        // because it conveys not-found via the JSON body, not the status code.
        var deps = configuredVtDeps();
        deps.responder = (req, timeoutMs) -> stubResponse(req, 404, "");

        var verdict = new VirusTotalScanner(deps.build()).lookup(SHA);

        assertFalse(verdict.malicious(), "404 must produce a clean verdict");
        assertTrue(deps.warnings.isEmpty(),
                "404 (cleanOnNotFound=true path) must NOT log a warning: " + deps.warnings);
    }

    // =====================================================================
    // Exception classification — InterruptedIOException distinct from generic IOException
    // =====================================================================

    @Test
    public void interruptedIoExceptionRestoresThreadInterruptStatus() {
        // ConfiguredHashScanner.sendJsonLookup catches InterruptedIOException
        // separately from generic IOException because Java requires the catch
        // to re-set Thread.currentThread().interrupt(). OkHttp throws
        // InterruptedIOException when its Call.timeout fires or the call's
        // thread is interrupted mid-read; the DI lets us deterministically
        // inject that exception without genuinely interrupting a thread.
        var deps = configuredVtDeps();
        deps.responder = (req, timeoutMs) -> { throw new InterruptedIOException("simulated"); };
        // Clear any pre-existing interrupt state from previous tests.
        Thread.interrupted();

        var verdict = new VirusTotalScanner(deps.build()).lookup(SHA);

        assertFalse(verdict.malicious(), "interrupted lookup must fail open");
        assertTrue(Thread.interrupted(),
                "InterruptedIOException catch must re-set the thread's interrupt flag");
        assertFalse(deps.warnings.isEmpty(), "interrupted must log a warning");
        assertTrue(deps.warnings.get(0).contains("interrupted"),
                "warning must distinguish interrupted from generic IO: " + deps.warnings.get(0));
    }

    // =====================================================================
    // isEnabled() contract — purely config-driven, never fires HTTP
    // =====================================================================

    @Test
    public void isEnabledNeverFiresHttpRequest() {
        // A subtle but important contract: isEnabled() is called by the
        // orchestrator on every scan to decide which scanners to consult. If
        // it ever became HTTP-bound (e.g., a "ping the API to check creds"
        // change), every skill-install would block on N HTTP round-trips.
        // Pin the contract that isEnabled() is purely config-driven.
        var deps = new FakeDeps();
        deps.config.put("scanner.virustotal.enabled", "true");
        deps.config.put("scanner.virustotal.apiKey", "key");

        var scanner = new VirusTotalScanner(deps.build());
        assertTrue(scanner.isEnabled(), "scanner with key set must be enabled");
        scanner.isEnabled(); // call again
        scanner.isEnabled(); // and again

        assertEquals(0, deps.requests.size(),
                "isEnabled() must NEVER fire an HTTP request, no matter how often it's called");
    }

    // =====================================================================
    // Helpers
    // =====================================================================

    /**
     * A {@link ScannerDependencies} builder that captures every outbound
     * request, every emitted warning, and lets each test program a per-call
     * {@link ScannerHttpClient} responder. Config reads come from a mutable
     * map; missing keys return {@code null} (matching {@code ConfigService}).
     */
    static class FakeDeps {
        final Map<String, String> config = new HashMap<>();
        final List<Request> requests = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        ScannerHttpClient responder = (req, timeoutMs) -> stubResponse(req, 200, "{}");

        ScannerDependencies build() {
            return new ScannerDependencies(
                    new ScannerConfig() {
                        @Override public String get(String key) { return config.get(key); }
                        @Override public String get(String key, String fallback) {
                            return config.getOrDefault(key, fallback);
                        }
                    },
                    (request, timeoutMs) -> {
                        requests.add(request);
                        return responder.send(request, timeoutMs);
                    },
                    warnings::add
            );
        }
    }

    private FakeDeps configuredVtDeps() {
        var d = new FakeDeps();
        d.config.put("scanner.virustotal.url", "https://vt.test/");
        d.config.put("scanner.virustotal.apiKey", "key");
        return d;
    }

    private FakeDeps configuredMdDeps() {
        var d = new FakeDeps();
        d.config.put("scanner.metadefender.url", "https://md.test/");
        d.config.put("scanner.metadefender.apiKey", "key");
        return d;
    }

    private FakeDeps configuredMbDeps() {
        var d = new FakeDeps();
        d.config.put("scanner.malwarebazaar.url", "https://mb.test/");
        d.config.put("scanner.malwarebazaar.authKey", "key");
        return d;
    }

    /** Build a minimal OkHttp {@link Response} stub. */
    private static Response stubResponse(Request request, int code, String body) {
        return new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message(code == 200 ? "OK" : "stub")
                .body(ResponseBody.create(body, MediaType.get("application/json")))
                .build();
    }
}
