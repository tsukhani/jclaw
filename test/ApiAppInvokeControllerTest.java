import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-764 Slice A — the AD-1 provenance gate + AD-3 fail-closed resolution.
 *
 * <p>Drives real HTTP requests carrying {@code Referer} / {@code Sec-Fetch-Site}
 * so the {@code AuthCheck} gate and the reset-password/logout guards run
 * end-to-end. Each test uses a uniquely-named app dir (safe under play1's
 * concurrent test engine) and cleans up only its own.
 */
class ApiAppInvokeControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-invoke";
    private static final String PREFIX = "jclaw-test-invoke-";
    private static final String LOGIN_BODY = "{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}";

    private Path appsDir;
    private Long agentId;
    private String appSlug;
    private final List<Path> created = new ArrayList<>();

    @BeforeEach
    void seedLoginAndApp() throws IOException {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        appsDir = Play.getFile("public/apps").toPath();
        Files.createDirectories(appsDir);
        agentId = commitFreshAgent();
        appSlug = makeApp("{\"name\":\"Inv\",\"version\":\"1.0.0\",\"agent\":\"" + agentId + "\"}");
        assertIsOk(POST("/api/auth/login", "application/json", LOGIN_BODY));
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
        created.forEach(ApiAppInvokeControllerTest::deleteRecursive);
    }

    // ── AD-1 provenance gate ──────────────────────────────────────────

    @Test
    void appOriginBlockedFromOtherApi() {
        // An app's fetch to any endpoint other than its own invoke → 403.
        assertStatus(403, appOrigin("GET", "/api/agents", appSlug));
    }

    @Test
    void appOriginAllowedToOwnInvoke() {
        var r = appOrigin("POST", "/api/apps/" + appSlug + "/invoke", appSlug);
        assertStatus(200, r); // Slice A stub
        assertTrue(getContent(r).contains("\"stub\":true"));
    }

    @Test
    void appOriginBlockedFromAnotherAppsInvoke() throws IOException {
        var other = makeApp("{\"name\":\"Other\",\"version\":\"1.0.0\",\"agent\":\"" + agentId + "\"}");
        // Referer is appSlug's page but the target is other's invoke route → cross-slug 403.
        assertStatus(403, appOrigin("POST", "/api/apps/" + other + "/invoke", appSlug));
    }

    @Test
    void spaRequestUnaffected() {
        // Same-origin SPA request (Referer not under /apps/<slug>/) keeps full authority.
        assertStatus(200, fromReferer("GET", "/api/agents", "http://localhost/chat"));
    }

    @Test
    void resetPasswordBlockedFromAppOrigin() {
        assertStatus(403, appOrigin("POST", "/api/auth/reset-password", appSlug));
        // The credential must be intact — a fresh login still succeeds (reset never ran).
        assertIsOk(POST("/api/auth/login", "application/json", LOGIN_BODY));
    }

    @Test
    void logoutBlockedFromAppOrigin() {
        assertStatus(403, appOrigin("POST", "/api/auth/logout", appSlug));
    }

    @Test
    void unauthenticatedInvokeReturns401() {
        // App-origin headers but no session cookie → 401 before any resolution.
        assertStatus(401, noAuth("POST", "/api/apps/" + appSlug + "/invoke", appSlug));
    }

    // ── AD-3 fail-closed resolution ───────────────────────────────────

    @Test
    void invokeNoSuchApp() {
        var slug = PREFIX + "ghost";
        assertStatus(404, appOrigin("POST", "/api/apps/" + slug + "/invoke", slug));
    }

    @Test
    void invokeNoDesignatedAgentFailsClosed() throws IOException {
        var slug = makeApp("{\"name\":\"NoAgent\",\"version\":\"1.0.0\"}");
        var r = appOrigin("POST", "/api/apps/" + slug + "/invoke", slug);
        assertStatus(400, r);
        assertTrue(getContent(r).contains("no_agent"));
    }

    @Test
    void invokeUnknownAgentFailsClosed() throws IOException {
        var slug = makeApp("{\"name\":\"Ghost\",\"version\":\"1.0.0\",\"agent\":\"999999999\"}");
        var r = appOrigin("POST", "/api/apps/" + slug + "/invoke", slug);
        assertStatus(400, r);
        assertTrue(getContent(r).contains("unknown_agent"));
    }

    @Test
    void invokeNonNumericAgentFailsClosed() throws IOException {
        var slug = makeApp("{\"name\":\"Bad\",\"version\":\"1.0.0\",\"agent\":\"not-a-number\"}");
        var r = appOrigin("POST", "/api/apps/" + slug + "/invoke", slug);
        assertStatus(400, r);
        assertTrue(getContent(r).contains("bad_agent"));
    }

    // ── request helpers ───────────────────────────────────────────────

    /** App-originated request: Referer under /apps/<refererSlug>/ + Sec-Fetch-Site: same-origin, authenticated. */
    private Http.Response appOrigin(String method, String url, String refererSlug) {
        return send(method, url, "http://localhost/apps/" + refererSlug + "/", "same-origin", true);
    }

    /** Authenticated request from an arbitrary same-origin Referer (e.g. an SPA page). */
    private Http.Response fromReferer(String method, String url, String referer) {
        return send(method, url, referer, "same-origin", true);
    }

    /** App-originated request WITHOUT a session cookie. */
    private Http.Response noAuth(String method, String url, String refererSlug) {
        return send(method, url, "http://localhost/apps/" + refererSlug + "/", "same-origin", false);
    }

    private Http.Response send(String method, String url, String referer, String secFetch, boolean auth) {
        var req = newRequest();
        req.method = method;
        var qIdx = url.indexOf('?');
        req.url = url;
        req.path = qIdx >= 0 ? url.substring(0, qIdx) : url;
        req.querystring = qIdx >= 0 ? url.substring(qIdx + 1) : "";
        req.contentType = "application/json";
        req.body = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        if (referer != null) req.headers.put("referer", new Http.Header("referer", referer));
        if (secFetch != null) req.headers.put("sec-fetch-site", new Http.Header("sec-fetch-site", secFetch));
        if (auth) attachSavedCookies(req);
        return makeRequest(req);
    }

    /** The login helpers stash cookies on a package-private static field newRequest() doesn't
     *  copy; reattach them via reflection or every custom request is unauthenticated (401). */
    private static void attachSavedCookies(Http.Request req) {
        try {
            var f = play.test.FunctionalTest.class.getDeclaredField("savedCookies");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            var cookies = (java.util.Map<String, Http.Cookie>) f.get(null);
            if (cookies != null) req.cookies = cookies;
        } catch (Exception _) {
            // field may shift across play versions — an unauthenticated request surfaces as 401.
        }
    }

    // ── fixtures ──────────────────────────────────────────────────────

    private String makeApp(String manifest) throws IOException {
        var slug = PREFIX + UUID.randomUUID().toString().substring(0, 8);
        var dir = appsDir.resolve(slug);
        Files.createDirectories(dir);
        created.add(dir);
        Files.writeString(dir.resolve("app.json"), manifest);
        Files.writeString(dir.resolve("index.html"), "<html></html>");
        return slug;
    }

    /** Seed an Agent on a committed fresh tx so the in-process HTTP handler sees the row. */
    private static Long commitFreshAgent() {
        var ref = new AtomicReference<Long>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                services.Tx.run(() -> {
                    var a = new Agent();
                    a.name = "inv-" + UUID.randomUUID().toString().substring(0, 8);
                    a.modelProvider = "openrouter";
                    a.modelId = "gpt-4.1";
                    a.enabled = true;
                    a.save();
                    ref.set(a.id);
                });
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static void deleteRecursive(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException _) {
                    // best-effort cleanup
                }
            });
        } catch (IOException _) {
            // best-effort cleanup
        }
    }
}
