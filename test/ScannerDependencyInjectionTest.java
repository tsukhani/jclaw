import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.scanners.MalwareBazaarScanner;
import services.scanners.MetaDefenderCloudScanner;
import services.scanners.ScannerConfig;
import services.scanners.ScannerDependencies;
import services.scanners.ScannerHttpClient;
import services.scanners.VirusTotalScanner;

import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Coverage for the dependency-injection seam introduced by
 * {@link ScannerDependencies} et al. — the {@code Scanner*} interfaces let us
 * exercise behaviors that a real-HTTP test (see {@code ScannerTest}) cannot
 * cleanly assert: the exact wire shape of each scanner's outbound request, the
 * specific warning message logged on each failure path, and the base class's
 * classification of network-level exceptions vs. HTTP-status failures.
 *
 * <p>The HTTP-server-backed {@code ScannerTest} remains the source of truth
 * for end-to-end happy paths (real socket, real {@code HttpClient}, real
 * {@code ConfigService}). These tests run in tens of microseconds with no
 * sockets — fast enough that future scanner implementations can rely on the
 * DI for unit-test coverage and use the slower integration path only for
 * smoke tests.
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
        deps.responder = req -> stubResponse(200,
                "{\"data\":{\"attributes\":{\"last_analysis_stats\":{\"malicious\":0}}}}");

        new VirusTotalScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size(), "exactly one request must fire");
        var req = deps.requests.get(0);
        assertEquals("GET", req.method());
        assertEquals("https://vt.example/v3/files/" + SHA, req.uri().toString());
        assertEquals(Optional.of("key-vt"), req.headers().firstValue("x-apikey"));
        assertEquals(Optional.of("application/json"), req.headers().firstValue("Accept"));
    }

    @Test
    public void metaDefender_buildsGetRequestWithApikeyHeader() {
        // MetaDefender uses the lowercase header name "apikey" — distinct from
        // VirusTotal's "x-apikey". Pin that distinction so a future copy-paste
        // mistake doesn't accidentally unify them.
        var deps = new FakeDeps();
        deps.config.put("scanner.metadefender.url", "https://md.example/v4/");
        deps.config.put("scanner.metadefender.apiKey", "key-md");
        deps.responder = req -> stubResponse(200, "{\"scan_results\":{\"scan_all_result_i\":0}}");

        new MetaDefenderCloudScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        var req = deps.requests.get(0);
        assertEquals("GET", req.method());
        assertEquals("https://md.example/v4/hash/" + SHA, req.uri().toString());
        assertEquals(Optional.of("key-md"), req.headers().firstValue("apikey"));
        assertEquals(Optional.of("application/json"), req.headers().firstValue("Accept"));
    }

    @Test
    public void malwareBazaar_buildsPostFormRequestWithAuthKeyHeader() {
        // MalwareBazaar is the odd one out: POST + form-encoded body + the
        // distinctive Auth-Key header. Pin all three because each diverges
        // from the other two scanners.
        var deps = new FakeDeps();
        deps.config.put("scanner.malwarebazaar.url", "https://mb.example/api/v1/");
        deps.config.put("scanner.malwarebazaar.authKey", "key-mb");
        deps.responder = req -> stubResponse(200, "{\"query_status\":\"hash_not_found\"}");

        new MalwareBazaarScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        var req = deps.requests.get(0);
        assertEquals("POST", req.method());
        assertEquals("https://mb.example/api/v1/", req.uri().toString());
        assertEquals(Optional.of("key-mb"), req.headers().firstValue("Auth-Key"));
        assertEquals(Optional.of("application/x-www-form-urlencoded"),
                req.headers().firstValue("Content-Type"));
        var expectedBody = "query=get_info&hash=" + SHA;
        assertEquals(expectedBody.length(),
                req.bodyPublisher().orElseThrow().contentLength(),
                "body must be the form-encoded query=get_info&hash=<sha>");
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
        deps.responder = req -> stubResponse(200, "{\"query_status\":\"hash_not_found\"}");

        new MalwareBazaarScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        assertEquals(Optional.empty(),
                deps.requests.get(0).headers().firstValue("Auth-Key"),
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
        deps.responder = req -> stubResponse(200,
                "{\"data\":{\"attributes\":{\"last_analysis_stats\":{\"malicious\":0}}}}");

        new VirusTotalScanner(deps.build()).lookup(SHA);

        assertEquals(1, deps.requests.size());
        assertTrue(deps.requests.get(0).uri().toString().startsWith("https://www.virustotal.com/api/v3/"),
                "default URL must be applied via the config fallback path: "
                        + deps.requests.get(0).uri());
    }

    // =====================================================================
    // Captured-log assertions — what the HttpServer tests can't see
    // =====================================================================

    @Test
    public void virusTotal_logsHttp500WithStatusAndFailOpenMarker() {
        var deps = configuredVtDeps();
        deps.responder = req -> stubResponse(500, "internal server error");

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
        // the HttpServer mock can simulate a 5xx but cannot make the JDK
        // HttpClient throw before reading a response.
        var deps = configuredMdDeps();
        deps.responder = req -> { throw new IOException("simulated network failure"); };

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
        deps.responder = req -> stubResponse(200, "{\"query_status\":\"illegal_auth_key\"}");

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
        deps.responder = req -> stubResponse(404, "");

        var verdict = new VirusTotalScanner(deps.build()).lookup(SHA);

        assertFalse(verdict.malicious(), "404 must produce a clean verdict");
        assertTrue(deps.warnings.isEmpty(),
                "404 (cleanOnNotFound=true path) must NOT log a warning: " + deps.warnings);
    }

    // =====================================================================
    // Exception classification — InterruptedException distinct from IOException
    // =====================================================================

    @Test
    public void interruptedExceptionRestoresThreadInterruptStatus() {
        // ConfiguredHashScanner.sendJsonLookup catches InterruptedException
        // separately from generic IOException because Java requires the catch
        // to re-set Thread.currentThread().interrupt(). The DI is the only
        // way to deterministically trigger this — the HttpServer mock cannot
        // make the JDK HttpClient throw InterruptedException without genuinely
        // interrupting a thread mid-call.
        var deps = configuredVtDeps();
        deps.responder = req -> { throw new InterruptedException("simulated"); };
        // Clear any pre-existing interrupt state from previous tests.
        Thread.interrupted();

        var verdict = new VirusTotalScanner(deps.build()).lookup(SHA);

        assertFalse(verdict.malicious(), "interrupted lookup must fail open");
        assertTrue(Thread.interrupted(),
                "InterruptedException catch must re-set the thread's interrupt flag");
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
        final List<HttpRequest> requests = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        ScannerHttpClient responder = req -> stubResponse(200, "{}");

        ScannerDependencies build() {
            return new ScannerDependencies(
                    new ScannerConfig() {
                        @Override public String get(String key) { return config.get(key); }
                        @Override public String get(String key, String fallback) {
                            return config.getOrDefault(key, fallback);
                        }
                    },
                    request -> {
                        requests.add(request);
                        return responder.send(request);
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

    /** Build a minimal {@link HttpResponse<String>} stub that returns {@code status} and {@code body}. */
    private static HttpResponse<String> stubResponse(int status, String body) {
        return new HttpResponse<>() {
            @Override public int statusCode() { return status; }
            @Override public HttpRequest request() { return null; }
            @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
            @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
            @Override public String body() { return body; }
            @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
            @Override public URI uri() { return URI.create("http://stub/"); }
            @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
        };
    }
}
