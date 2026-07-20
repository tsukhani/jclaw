import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http;
import play.test.FunctionalTest;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-764/765 — the AD-1 provenance gate, AD-3 fail-closed resolution, and the
 * AD-2/AD-4/AD-6 execution wiring. 765a makes invoke a multipart endpoint: a
 * {@code message} form field plus optional file uploads of any type, staged through
 * the shared {@code UploadStaging} path and passed to the agent run.
 *
 * <p>Drives real HTTP requests carrying {@code Referer} / {@code Sec-Fetch-Site} so
 * the gate + guards run end-to-end. The designated agent has no configured LLM
 * provider, so {@code AgentRunner.run} returns a graceful error response while still
 * persisting the conversation + attachments — enough to prove the wiring
 * deterministically; the LLM path is covered by {@code AgentRunnerCoreTest}.
 */
class ApiAppInvokeControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-invoke";
    private static final String PREFIX = "jclaw-test-invoke-";
    private static final String LOGIN_BODY = "{\"username\":\"admin\",\"password\":\"" + TEST_PASSWORD + "\"}";
    private static final Map<String, File> NO_FILES = Map.of();
    private static final Map<String, String> MSG = Map.of("message", "hi");

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
        var r = invoke(appSlug, appSlug, MSG, NO_FILES);
        assertStatus(200, r);
        assertTrue(getContent(r).contains("conversationId"));
        assertTrue(getContent(r).contains("response"));
    }

    @Test
    void appOriginBlockedFromAnotherAppsInvoke() throws IOException {
        var other = makeApp("{\"name\":\"Other\",\"version\":\"1.0.0\",\"agent\":\"" + agentId + "\"}");
        assertStatus(403, invoke(other, appSlug, MSG, NO_FILES)); // target != referer slug
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
    void setupBlockedFromAppOrigin() {
        // DiD (VulnHunter follow-up): setup() is unauthenticated and gate-less by nature;
        // an app-originated call must still be refused (403) before the credential-bootstrap
        // path — and the gate fires ahead of the "already set" 409, so a password being
        // seeded in the fixture doesn't mask it.
        assertStatus(403, appOrigin("POST", "/api/auth/setup", appSlug));
    }

    @Test
    void unauthenticatedInvokeReturns401() {
        assertStatus(401, invokeNoAuth(appSlug));
    }

    // ── AD-3 fail-closed resolution ───────────────────────────────────

    @Test
    void invokeNoSuchApp() {
        var slug = PREFIX + "ghost";
        assertStatus(404, invoke(slug, slug, MSG, NO_FILES));
    }

    @Test
    void invokeNoDesignatedAgentFailsClosed() throws IOException {
        var slug = makeApp("{\"name\":\"NoAgent\",\"version\":\"1.0.0\"}");
        var r = invoke(slug, slug, MSG, NO_FILES);
        assertStatus(400, r);
        assertTrue(getContent(r).contains("no_agent"));
    }

    @Test
    void invokeUnknownAgentFailsClosed() throws IOException {
        var slug = makeApp("{\"name\":\"Ghost\",\"version\":\"1.0.0\",\"agent\":\"999999999\"}");
        var r = invoke(slug, slug, MSG, NO_FILES);
        assertStatus(400, r);
        assertTrue(getContent(r).contains("unknown_agent"));
    }

    @Test
    void invokeNonNumericAgentFailsClosed() throws IOException {
        var slug = makeApp("{\"name\":\"Bad\",\"version\":\"1.0.0\",\"agent\":\"not-a-number\"}");
        var r = invoke(slug, slug, MSG, NO_FILES);
        assertStatus(400, r);
        assertTrue(getContent(r).contains("bad_agent"));
    }

    // ── AD-2 / AD-4 / AD-6 execution wiring ───────────────────────────

    @Test
    void invokeRequiresMessageOrFiles() {
        // Valid app, but neither a message nor files → 400.
        var r = invoke(appSlug, appSlug, Map.of(), NO_FILES);
        assertStatus(400, r);
        assertTrue(getContent(r).contains("no_input"));
    }

    @Test
    void invokeResolvesAgentFromSlugIgnoringBody() {
        // AD-2: bogus agent params in the body must be ignored — the agent comes from
        // the slug, so the created conversation is bound to it.
        var r = invoke(appSlug, appSlug, Map.of("message", "hi", "agent", "999", "agentId", "999"), NO_FILES);
        assertStatus(200, r);
        var boundAgentId = commitFreshTx(() -> {
            Conversation c = latestAppConversation(appSlug);
            return c == null ? null : c.agent.id;
        });
        assertEquals(agentId, boundAgentId);
    }

    @Test
    void invokeCreatesAppOwnedConversationViewableByOperator() {
        // AD-4: fresh channelType="app" conversation, visible to the operator under the app channel.
        var invoked = invoke(appSlug, appSlug, MSG, NO_FILES);
        assertStatus(200, invoked);
        var convId = JsonParser.parseString(getContent(invoked))
                .getAsJsonObject().get("conversationId").getAsLong();

        var list = GET("/api/conversations?channel=app");
        assertIsOk(list);
        assertTrue(getContent(list).contains(String.valueOf(convId)),
                "operator's app-channel conversation list should include " + convId + ": " + getContent(list));
    }

    @Test
    void invokeAcceptsFileUploadAndAttachesItToTheRun() throws IOException {
        // 765a: a file upload of any type is staged via the shared path and reaches the
        // agent run — it lands as a persisted attachment on the app conversation.
        var tmp = File.createTempFile("jclaw-inv-upload-", ".txt");
        tmp.deleteOnExit();
        Files.writeString(tmp.toPath(), "the RFP contents");

        var r = invoke(appSlug, appSlug, Map.of("message", "summarize this"), Map.of("files", tmp));
        assertStatus(200, r);

        var attachmentCount = commitFreshTx(() -> {
            Conversation c = latestAppConversation(appSlug);
            return c == null ? 0 : MessageAttachment.find("message.conversation = ?1", c).fetch().size();
        });
        assertTrue(attachmentCount >= 1,
                "the uploaded file should be a persisted attachment on the app conversation");
    }

    // ── AD-5 / JCLAW-765: produced-file output + scoped download ──────────

    @Test
    void invokeResponseIncludesFilesArray() {
        // The response always carries a files array — empty here, since the no-provider
        // agent runs no tools and produces nothing. Proves the output wiring is present.
        var r = invoke(appSlug, appSlug, MSG, NO_FILES);
        assertStatus(200, r);
        var obj = JsonParser.parseString(getContent(r)).getAsJsonObject();
        assertTrue(obj.has("files") && obj.get("files").isJsonArray(), "response must include a files array");
        assertEquals(0, obj.getAsJsonArray("files").size(), "a no-provider run produces no files");
    }

    @Test
    void fileServesThisAppsGeneratedAttachment() {
        var pdf = "%PDF-1.4 generated report".getBytes(StandardCharsets.ISO_8859_1);
        var uuid = seedGeneratedAttachment("app", appSlug, "report.pdf", "application/pdf", pdf);
        var r = GET("/api/apps/" + appSlug + "/files/" + uuid);
        // renderBinary streams via Play's sendfile pipeline, which FunctionalTest doesn't
        // capture into the body buffer — so assert the serve contract via headers.
        assertIsOk(r);
        var disposition = r.headers.get("Content-Disposition");
        assertNotNull(disposition, "Content-Disposition header should be set");
        assertTrue(disposition.value().startsWith("attachment"),
                "produced doc disposition should be attachment: " + disposition.value());
        assertTrue(disposition.value().contains("report.pdf"),
                "disposition should carry the produced filename: " + disposition.value());
        assertNotNull(r.contentType, "Content-Type must be set on the served file");
    }

    @Test
    void fileRejectsAnotherAppsAttachment() throws IOException {
        // Scoping by conversation ownership: a valid uuid from ANOTHER app's conversation
        // is a 404 under this app's route (peerId mismatch), not a leak.
        var other = makeApp("{\"name\":\"Other\",\"version\":\"1.0.0\",\"agent\":\"" + agentId + "\"}");
        var uuid = seedGeneratedAttachment("app", other, "secret.pdf", "application/pdf",
                "%PDF secret".getBytes(StandardCharsets.ISO_8859_1));
        assertStatus(404, GET("/api/apps/" + appSlug + "/files/" + uuid));
    }

    @Test
    void fileRejectsNonAppConversationAttachment() {
        // A chat attachment (channelType != "app") is never reachable via an app's files
        // route, even when its peerId happens to equal the slug.
        var uuid = seedGeneratedAttachment("web", appSlug, "chat.pdf", "application/pdf",
                "%PDF chat".getBytes(StandardCharsets.ISO_8859_1));
        assertStatus(404, GET("/api/apps/" + appSlug + "/files/" + uuid));
    }

    @Test
    void appOriginAllowedToOwnFilesRoute() {
        // AD-1 whitelist: an app-originated GET to its OWN files route reaches the endpoint
        // (200), not a 403 — the second route the gate now permits for the matching slug.
        var uuid = seedGeneratedAttachment("app", appSlug, "own.pdf", "application/pdf",
                "%PDF own".getBytes(StandardCharsets.ISO_8859_1));
        assertIsOk(fileFromAppOrigin(appSlug, appSlug, uuid));
    }

    @Test
    void appOriginBlockedFromAnotherAppsFilesRoute() {
        // The files whitelist stays slug-scoped: app <appSlug> (Referer) cannot use another
        // slug's files route — the gate 403s before the endpoint (URL slug != Referer slug).
        var uuid = seedGeneratedAttachment("app", appSlug, "own.pdf", "application/pdf",
                "%PDF own".getBytes(StandardCharsets.ISO_8859_1));
        assertStatus(403, fileFromAppOrigin("someotherapp", appSlug, uuid));
    }

    // ── AD-5 / JCLAW-766: server-side per-app invoke limit ────────────────

    @Test
    void invokeRejectedWhenOverPerAppLimit() throws IOException {
        // An app.json "limit" tightens the per-window cap. With limit=1 the first invoke
        // runs; a second within the same window is rejected with 429. The %test window is
        // widened (application.conf) so both invokes land in one window deterministically.
        var slug = makeApp("{\"name\":\"Capped\",\"version\":\"1.0.0\",\"agent\":\""
                + agentId + "\",\"limit\":1}");
        assertStatus(200, invoke(slug, slug, MSG, NO_FILES));
        var second = invoke(slug, slug, MSG, NO_FILES);
        assertStatus(429, second);
        assertTrue(getContent(second).contains("rate_limited"), "got: " + getContent(second));
    }

    // ── request helpers ───────────────────────────────────────────────

    /** A multipart invoke POST carrying app-origin headers (Referer under /apps/<refererSlug>/).
     *  Play's multipart POST(Request, …) helper auto-attaches the saved login cookies. */
    private Http.Response invoke(String targetSlug, String refererSlug,
                                 Map<String, String> params, Map<String, File> files) {
        var req = newRequest();
        req.headers.put("referer", new Http.Header("referer", "http://localhost/apps/" + refererSlug + "/"));
        req.headers.put("sec-fetch-site", new Http.Header("sec-fetch-site", "same-origin"));
        return POST(req, "/api/apps/" + targetSlug + "/invoke", params, files);
    }

    /** An invoke request WITHOUT the session cookie. AuthCheck 401s before the action
     *  binds the body, so a plain (non-multipart) POST via makeRequest — which does not
     *  auto-attach savedCookies — is enough to exercise the unauthenticated path. */
    private Http.Response invokeNoAuth(String slug) {
        var req = newRequest();
        req.method = "POST";
        req.url = "/api/apps/" + slug + "/invoke";
        req.path = req.url;
        req.querystring = "";
        req.contentType = "application/json";
        req.body = new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8));
        req.headers.put("referer", new Http.Header("referer", "http://localhost/apps/" + slug + "/"));
        req.headers.put("sec-fetch-site", new Http.Header("sec-fetch-site", "same-origin"));
        return makeRequest(req);
    }

    /** A non-multipart request from an app origin (for the non-invoke gate checks). */
    private Http.Response appOrigin(String method, String url, String refererSlug) {
        return send(method, url, "http://localhost/apps/" + refererSlug + "/", "same-origin");
    }

    /** An authenticated request from an arbitrary same-origin Referer (e.g. an SPA page). */
    private Http.Response fromReferer(String method, String url, String referer) {
        return send(method, url, referer, "same-origin");
    }

    /** An app-origin GET to a files route (Referer under /apps/<refererSlug>/, same-origin). */
    private Http.Response fileFromAppOrigin(String urlSlug, String refererSlug, String uuid) {
        return send("GET", "/api/apps/" + urlSlug + "/files/" + uuid,
                "http://localhost/apps/" + refererSlug + "/", "same-origin");
    }

    private Http.Response send(String method, String url, String referer, String secFetch) {
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
        attachSavedCookies(req);
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

    private static Conversation latestAppConversation(String slug) {
        return Conversation.find("channelType = ?1 and peerId = ?2 order by id desc", "app", slug).first();
    }

    /** Seed a {@code generated} attachment on a fresh conversation of the given channel/peer,
     *  writing real bytes to the agent workspace, and return its uuid — the row the download
     *  endpoint resolves. Runs in its own committed tx so the in-process handler sees it. */
    private String seedGeneratedAttachment(String channelType, String peerId,
                                           String filename, String mime, byte[] bytes) {
        return commitFreshTx(() -> {
            Agent agent = Agent.findById(agentId);
            Conversation conv = services.ConversationService.create(agent, channelType, peerId);
            Message msg = services.ConversationService.appendAssistantMessage(conv, "here is your file", null);
            MessageAttachment att = services.AttachmentService.persistGeneratedAttachment(
                    agent, msg, bytes, mime, null, filename);
            return att.uuid;
        });
    }

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
            // "No LLM provider configured" RunResult immediately (no network).
            a.modelProvider = "nonexistent";
            a.modelId = "model";
            a.enabled = true;
            a.save();
            return a.id;
        });
    }

    /** Run {@code block} in its own committed transaction on a fresh thread. */
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
