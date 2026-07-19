import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
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
import java.util.function.Supplier;

/**
 * JCLAW-764 — the AD-1 provenance gate, AD-3 fail-closed resolution, and (Slice B)
 * the AD-2/AD-4/AD-6 execution wiring: a fresh {@code channelType="app"}
 * conversation delegating to the agent-run pipeline, viewable by the operator.
 *
 * <p>Drives real HTTP requests carrying {@code Referer} / {@code Sec-Fetch-Site}
 * so the {@code AuthCheck} gate and the reset-password/logout guards run
 * end-to-end. Each test uses a uniquely-named app dir + agent (safe under play1's
 * concurrent test engine) and cleans up only its own. The designated agent has no
 * configured LLM provider, so {@code AgentRunner.run} returns a graceful error
 * response — enough to prove the invoke wiring; the LLM path is covered by
 * {@code AgentRunnerCoreTest}.
 */
class ApiAppInvokeControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-invoke";
    private static final String PREFIX = "jclaw-test-invoke-";
    private static final String LOGIN_BODY = "{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}";
    private static final String MSG_BODY = "{\"message\":\"hi\"}";

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
        assertStatus(403, appOrigin("GET", "/api/agents", appSlug));
    }

    @Test
    void appOriginAllowedToOwnInvoke() {
        var r = appOrigin("POST", "/api/apps/" + appSlug + "/invoke", appSlug);
        assertStatus(200, r);
        assertTrue(getContent(r).contains("conversationId"));
        assertTrue(getContent(r).contains("response"));
    }

    @Test
    void appOriginBlockedFromAnotherAppsInvoke() throws IOException {
        var other = makeApp("{\"name\":\"Other\",\"version\":\"1.0.0\",\"agent\":\"" + agentId + "\"}");
        assertStatus(403, appOrigin("POST", "/api/apps/" + other + "/invoke", appSlug));
    }

    @Test
    void spaRequestUnaffected() {
        assertStatus(200, fromReferer("GET", "/api/agents", "http://localhost/chat"));
    }

    @Test
    void resetPasswordBlockedFromAppOrigin() {
        assertStatus(403, appOrigin("POST", "/api/auth/reset-password", appSlug));
        assertIsOk(POST("/api/auth/login", "application/json", LOGIN_BODY));
    }

    @Test
    void logoutBlockedFromAppOrigin() {
        assertStatus(403, appOrigin("POST", "/api/auth/logout", appSlug));
    }

    @Test
    void unauthenticatedInvokeReturns401() {
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

    // ── AD-2 / AD-4 / AD-6 execution wiring (Slice B) ─────────────────

    @Test
    void invokeRequiresMessage() {
        // A valid app but an empty body → 400 (fail-closed on missing input).
        var r = appOrigin("POST", "/api/apps/" + appSlug + "/invoke", appSlug, "{}");
        assertStatus(400, r);
        assertTrue(getContent(r).contains("no_input"));
    }

    @Test
    void invokeResolvesAgentFromSlugIgnoringBody() {
        // AD-2: bogus agent params in the body must be ignored — the agent is the
        // slug's designated agent, so the created conversation is bound to it.
        var r = appOrigin("POST", "/api/apps/" + appSlug + "/invoke", appSlug,
                "{\"message\":\"hi\",\"agentId\":\"999\",\"agent\":\"999\"}");
        assertStatus(200, r);
        var boundAgentId = commitFreshTx(() -> {
            Conversation c = Conversation.find(
                    "channelType = ?1 and peerId = ?2 order by id desc", "app", appSlug).first();
            return c == null ? null : c.agent.id;
        });
        assertEquals(agentId, boundAgentId);
    }

    @Test
    void invokeCreatesAppOwnedConversationViewableByOperator() {
        // AD-4: each invoke creates a fresh channelType="app" conversation, and the
        // operator can see it — filtering Conversations by the "app" channel returns it.
        var invoked = appOrigin("POST", "/api/apps/" + appSlug + "/invoke", appSlug);
        assertStatus(200, invoked);
        var convId = JsonParser.parseString(getContent(invoked))
                .getAsJsonObject().get("conversationId").getAsLong();

        var list = GET("/api/conversations?channel=app");
        assertIsOk(list);
        assertTrue(getContent(list).contains(String.valueOf(convId)),
                "operator's app-channel conversation list should include " + convId + ": " + getContent(list));
    }

    // ── request helpers ───────────────────────────────────────────────

    private Http.Response appOrigin(String method, String url, String refererSlug) {
        return appOrigin(method, url, refererSlug, MSG_BODY);
    }

    private Http.Response appOrigin(String method, String url, String refererSlug, String body) {
        return send(method, url, "http://localhost/apps/" + refererSlug + "/", "same-origin", true, body);
    }

    private Http.Response fromReferer(String method, String url, String referer) {
        return send(method, url, referer, "same-origin", true, "{}");
    }

    private Http.Response noAuth(String method, String url, String refererSlug) {
        return send(method, url, "http://localhost/apps/" + refererSlug + "/", "same-origin", false, MSG_BODY);
    }

    private Http.Response send(String method, String url, String referer, String secFetch, boolean auth, String body) {
        var req = newRequest();
        req.method = method;
        var qIdx = url.indexOf('?');
        req.url = url;
        req.path = qIdx >= 0 ? url.substring(0, qIdx) : url;
        req.querystring = qIdx >= 0 ? url.substring(qIdx + 1) : "";
        req.contentType = "application/json";
        req.body = new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
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
        return commitFreshTx(() -> {
            var a = new Agent();
            a.name = "inv-" + UUID.randomUUID().toString().substring(0, 8);
            // A deliberately unresolvable provider: AgentRunner.run returns the
            // "No LLM provider configured" RunResult immediately (no network), which
            // is enough to exercise the invoke wiring deterministically in CI.
            a.modelProvider = "nonexistent";
            a.modelId = "model";
            a.enabled = true;
            a.save();
            return a.id;
        });
    }

    /** Run {@code block} in its own committed transaction on a fresh thread (the
     *  FunctionalTest carrier thread's tx doesn't commit until the test returns). */
    private static <T> T commitFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(services.Tx.run(block::get));
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
