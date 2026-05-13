import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import play.test.UnitTest;
import services.ConfigService;
import services.scanners.MalwareBazaarScanner;
import services.scanners.MetaDefenderCloudScanner;
import services.scanners.Scanner;
import services.scanners.ScannerRegistry;
import services.scanners.VirusTotalScanner;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end coverage for the three malware-scanner implementations
 * (VirusTotal, MetaDefender Cloud, MalwareBazaar). Each scanner fetches its
 * base URL from {@link ConfigService} and talks to it over OkHttp via
 * {@link services.scanners.ScannerDependencies}; the test spins up a
 * {@link HttpServer}-backed HTTP/1.1 mock on loopback, overrides the
 * scanner's URL config key to point at it, and asserts the scanner's
 * {@link Scanner.Verdict} against programmed responses.
 *
 * <p><strong>URL-override admin risk (JCLAW-147)</strong>: the
 * {@code scanner.*.url} config keys are writable only by an operator with
 * DB-write access. If an operator redirects a scanner to an attacker-
 * controlled host, the scanner sends its API key in cleartext (x-apikey /
 * apikey / Auth-Key headers). This is an operator-trust boundary, not a
 * prompt-injection surface — no allowlist is enforced in code. The
 * {@code scanner.*.url} keys are treated with the same trust as other
 * provider credentials the operator sets directly.
 */
class ScannerTest extends UnitTest {

    /** SHA-256 of the empty string — any hash shape works here; the scanners
     *  just format it into the request path. */
    private static final String SHA = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private HttpServer server;
    private AtomicReference<Handler> handlerRef;
    private AtomicInteger requestCount;

    @BeforeEach
    void setup() throws IOException {
        handlerRef = new AtomicReference<>((exchange) -> {
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        requestCount = new AtomicInteger();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requestCount.incrementAndGet();
            handlerRef.get().handle(exchange);
        });
        server.start();
    }

    @AfterEach
    void teardown() {
        server.stop(0);
        // Clear any scanner.* keys we may have set so the next test starts fresh.
        for (var k : new String[] {
                "scanner.virustotal.url", "scanner.virustotal.apiKey", "scanner.virustotal.enabled", "scanner.virustotal.timeoutMs",
                "scanner.metadefender.url", "scanner.metadefender.apiKey", "scanner.metadefender.enabled", "scanner.metadefender.timeoutMs",
                "scanner.malwarebazaar.url", "scanner.malwarebazaar.authKey", "scanner.malwarebazaar.enabled", "scanner.malwarebazaar.timeoutMs",
        }) ConfigService.delete(k);
        ConfigService.clearCache();
    }

    /** Lambda alias — checked-exception-friendly signature for the handler ref. */
    @FunctionalInterface
    interface Handler {
        void handle(com.sun.net.httpserver.HttpExchange ex) throws IOException;
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    private void respond(int status, String body) {
        handlerRef.set(exchange -> {
            var bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
    }

    private void respondEmpty(int status) {
        handlerRef.set(exchange -> {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
    }

    @Test
    void registryCreatesDefaultScannersAndConfigDefaults() {
        var names = ScannerRegistry.createDefaultScanners().stream()
                .map(Scanner::name)
                .toList();
        assertEquals(java.util.List.of("MalwareBazaar", "MetaDefender", "VirusTotal"), names);

        var keys = ScannerRegistry.defaultConfig().stream()
                .map(ScannerRegistry.ConfigDefault::key)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(keys.contains("scanner.malwarebazaar.authKey"));
        assertTrue(keys.contains("scanner.metadefender.apiKey"));
        assertTrue(keys.contains("scanner.virustotal.apiKey"));
    }

    // =============================================================
    // VirusTotalScanner
    // =============================================================

    @Test
    void virusTotal_enabledRequiresKey() {
        ConfigService.set("scanner.virustotal.apiKey", "");
        ConfigService.clearCache();
        VirusTotalScanner.class.getName(); // force load for static OneShotWarning
        assertFalse(new VirusTotalScanner().isEnabled(),
                "scanner without API key must stay disabled");
    }

    @Test
    void virusTotal_maliciousVerdictFromStats() {
        wireVirusTotal();
        respond(200, """
                {"data":{"attributes":{
                    "last_analysis_stats":{"malicious":3,"undetected":60,"harmless":5},
                    "last_analysis_results":{
                        "ESET":{"category":"malicious","result":"Win32/Foo"},
                        "Kaspersky":{"category":"malicious","result":"Trojan.X"}
                    }
                }}}
                """);
        var v = new VirusTotalScanner().lookup(SHA);
        assertTrue(v.malicious(), "stats.malicious>0 must produce malicious verdict");
        assertTrue(v.reason().contains("3/68") || v.reason().contains("3 engines"),
                "verdict must include detection counts; got: " + v.reason());
    }

    @Test
    void virusTotal_cleanVerdictFromZeroMalicious() {
        wireVirusTotal();
        respond(200, """
                {"data":{"attributes":{"last_analysis_stats":{"malicious":0,"undetected":70}}}}
                """);
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "malicious==0 must produce clean verdict");
    }

    @Test
    void virusTotal_404TreatedAsClean() {
        wireVirusTotal();
        respondEmpty(404);
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "404 (hash never seen) must fail-open to clean");
    }

    @Test
    void virusTotal_failsOpenOnUnauthorized() {
        wireVirusTotal();
        respondEmpty(401);
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "401 must fail-open to clean");
    }

    @Test
    void virusTotal_failsOpenOnRateLimit() {
        wireVirusTotal();
        respondEmpty(429);
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "429 (quota) must fail-open to clean");
    }

    @Test
    void virusTotal_failsOpenOnServerError() {
        wireVirusTotal();
        respondEmpty(500);
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "5xx must fail-open to clean");
    }

    @Test
    void virusTotal_failsOpenOnMalformedJson() {
        wireVirusTotal();
        respond(200, "{not valid json");
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "JSON parse error must fail-open to clean");
    }

    @Test
    void virusTotal_failsOpenOnMissingDataField() {
        wireVirusTotal();
        respond(200, "{}");
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "missing data field must fail-open to clean");
    }

    @Test
    void virusTotal_failsOpenOnTimeout() {
        wireVirusTotal();
        ConfigService.set("scanner.virustotal.timeoutMs", "100");
        ConfigService.clearCache();
        handlerRef.set(exchange -> {
            try { Thread.sleep(500); } catch (InterruptedException _) {}
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        assertFalse(new VirusTotalScanner().lookup(SHA).malicious(),
                "connect/read timeout must fail-open to clean");
    }

    private void wireVirusTotal() {
        ConfigService.set("scanner.virustotal.url", baseUrl());
        ConfigService.set("scanner.virustotal.apiKey", "test-key");
        ConfigService.clearCache();
    }

    // =============================================================
    // MetaDefenderCloudScanner
    // =============================================================

    @Test
    void metaDefender_maliciousVerdictFromResultCode1() {
        wireMetaDefender();
        respond(200, """
                {"scan_results":{
                    "scan_all_result_i":1,
                    "scan_details":{
                        "ESET":{"threat_found":"Win32/EICAR"},
                        "Kaspersky":{"threat_found":"EICAR-Test-File"}
                    }
                }}
                """);
        var v = new MetaDefenderCloudScanner().lookup(SHA);
        assertTrue(v.malicious(), "scan_all_result_i=1 must produce malicious verdict");
        assertTrue(v.reason().contains("EICAR") || v.reason().contains("Win32"),
                "verdict must name at least one engine threat; got: " + v.reason());
    }

    @Test
    void metaDefender_cleanVerdictFromResultCode0() {
        wireMetaDefender();
        respond(200, """
                {"scan_results":{"scan_all_result_i":0}}
                """);
        assertFalse(new MetaDefenderCloudScanner().lookup(SHA).malicious(),
                "scan_all_result_i=0 must produce clean verdict");
    }

    @Test
    void metaDefender_404TreatedAsClean() {
        wireMetaDefender();
        respondEmpty(404);
        assertFalse(new MetaDefenderCloudScanner().lookup(SHA).malicious(),
                "404 must fail-open to clean");
    }

    @Test
    void metaDefender_failsOpenOnUnauthorized() {
        wireMetaDefender();
        respondEmpty(401);
        assertFalse(new MetaDefenderCloudScanner().lookup(SHA).malicious());
    }

    @Test
    void metaDefender_failsOpenOnRateLimit() {
        wireMetaDefender();
        respondEmpty(429);
        assertFalse(new MetaDefenderCloudScanner().lookup(SHA).malicious());
    }

    @Test
    void metaDefender_failsOpenOnMalformedJson() {
        wireMetaDefender();
        respond(200, "{not json");
        assertFalse(new MetaDefenderCloudScanner().lookup(SHA).malicious());
    }

    @Test
    void metaDefender_failsOpenOnMissingScanResults() {
        wireMetaDefender();
        respond(200, "{}");
        assertFalse(new MetaDefenderCloudScanner().lookup(SHA).malicious(),
                "MetaDefender may return sparse record; must treat as clean");
    }

    private void wireMetaDefender() {
        ConfigService.set("scanner.metadefender.url", baseUrl());
        ConfigService.set("scanner.metadefender.apiKey", "test-key");
        ConfigService.clearCache();
    }

    // =============================================================
    // MalwareBazaarScanner
    // =============================================================

    @Test
    void malwareBazaar_maliciousFromOkStatus() {
        wireMalwareBazaar();
        respond(200, """
                {"query_status":"ok","data":[{"signature":"TrojanDownloader.Win32.Agent"}]}
                """);
        var v = new MalwareBazaarScanner().lookup(SHA);
        assertTrue(v.malicious(), "query_status=ok must produce malicious verdict");
        assertTrue(v.reason().contains("Trojan"),
                "verdict reason must include the signature; got: " + v.reason());
    }

    @Test
    void malwareBazaar_cleanFromHashNotFound() {
        wireMalwareBazaar();
        respond(200, """
                {"query_status":"hash_not_found"}
                """);
        assertFalse(new MalwareBazaarScanner().lookup(SHA).malicious(),
                "hash_not_found must produce clean verdict");
    }

    @Test
    void malwareBazaar_cleanFromNoResults() {
        wireMalwareBazaar();
        respond(200, """
                {"query_status":"no_results"}
                """);
        assertFalse(new MalwareBazaarScanner().lookup(SHA).malicious());
    }

    @Test
    void malwareBazaar_failsOpenOnUnknownStatus() {
        wireMalwareBazaar();
        respond(200, """
                {"query_status":"illegal_auth_key"}
                """);
        assertFalse(new MalwareBazaarScanner().lookup(SHA).malicious(),
                "unknown status must fail-open to clean");
    }

    @Test
    void malwareBazaar_failsOpenOnRateLimit() {
        wireMalwareBazaar();
        respondEmpty(429);
        assertFalse(new MalwareBazaarScanner().lookup(SHA).malicious());
    }

    @Test
    void malwareBazaar_failsOpenOnMalformedJson() {
        wireMalwareBazaar();
        respond(200, "{not json");
        assertFalse(new MalwareBazaarScanner().lookup(SHA).malicious());
    }

    @Test
    void malwareBazaar_failsOpenOnEmptyBody() {
        wireMalwareBazaar();
        respond(200, "");
        assertFalse(new MalwareBazaarScanner().lookup(SHA).malicious(),
                "empty body must fail-open to clean");
    }

    private void wireMalwareBazaar() {
        ConfigService.set("scanner.malwarebazaar.url", baseUrl());
        ConfigService.set("scanner.malwarebazaar.authKey", "test-key");
        ConfigService.clearCache();
    }

    // =============================================================
    // URL-override admin-trust pin (JCLAW-147)
    // =============================================================

    @Test
    void urlOverrideAcceptsOperatorSetHost() {
        // Pin the documented admin-trust boundary: scanner.*.url is treated as
        // operator-configured and has no scheme/host allowlist enforced in code.
        // If a future change introduces such an allowlist, this test will need
        // to move to asserting the allowlist behaviour instead — and the
        // class-level JavaDoc on ScannerTest + the JCLAW-147 ticket need an
        // update explaining why the posture changed.
        wireVirusTotal();
        respond(200, """
                {"data":{"attributes":{"last_analysis_stats":{"malicious":0}}}}
                """);
        new VirusTotalScanner().lookup(SHA);
        assertEquals(1, requestCount.get(),
                "scanner must accept an operator-configured URL without "
                        + "scheme/host gating; if this fails, the allowlist "
                        + "has been added and the admin-trust model shifted");
    }

    // silence unused-import on Consumer when tests evolve
    @SuppressWarnings("unused") private Consumer<Object> _suppress;
}
