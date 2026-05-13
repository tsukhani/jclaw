import okhttp3.*;
import org.junit.jupiter.api.*;
import play.test.UnitTest;
import tools.WebFetchTool;
import models.Agent;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies {@link WebFetchTool}'s post-SsrfGuard HTTP path: the manual redirect
 * loop, error surfaces (too many redirects, blank Location, SSL), body
 * truncation, and the {@code html} mode workspace-save filename sanitisation.
 *
 * <p>Tests swap {@code WebFetchTool.CLIENT} (package-private test seam) for a
 * client whose {@link Interceptor} returns pre-enqueued responses without
 * ever opening a socket. This avoids MockWebServer entirely — which is fine
 * because {@code mockwebserver 4.x} is excluded from this build's classpath
 * (see build.gradle.kts, and the JCLAW-143 follow-on that documents why).
 *
 * <p>The {@code assertSafeScheme} calls on the real URL still run (scheme +
 * literal-IP checks), which is what the first scenario exercises: the initial
 * URL is accepted but the 302-pointed-to-AWS-metadata URL is rejected on hop 2.
 */
class WebFetchToolTest extends UnitTest {

    private static final Field CLIENT_FIELD;
    static {
        try {
            CLIENT_FIELD = WebFetchTool.class.getDeclaredField("CLIENT");
            CLIENT_FIELD.setAccessible(true);
        } catch (NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }

    private QueueInterceptor queue;
    private OkHttpClient originalClient;

    @BeforeEach
    void setup() throws Exception {
        queue = new QueueInterceptor();
        originalClient = (OkHttpClient) CLIENT_FIELD.get(null);
        CLIENT_FIELD.set(null, new OkHttpClient.Builder()
                .addInterceptor(queue)
                .followRedirects(false)
                .followSslRedirects(false)
                .callTimeout(5, TimeUnit.SECONDS)
                .build());
    }

    @AfterEach
    void teardown() throws Exception {
        CLIENT_FIELD.set(null, originalClient);
    }

    /** Interceptor that pops one queued response per call. Throws if an
     *  unexpected extra call arrives (keeps tests honest). Also supports
     *  enqueueing IOExceptions so the catch-all branches are exercisable. */
    static final class QueueInterceptor implements Interceptor {
        private final Deque<Object> entries = new ArrayDeque<>();

        void enqueue(Response.Builder b) { entries.add(b); }
        void enqueueThrow(IOException e) { entries.add(e); }

        @Override
        public Response intercept(Chain chain) throws IOException {
            var entry = entries.poll();
            if (entry == null) {
                throw new IOException("no response enqueued for " + chain.request().url());
            }
            if (entry instanceof IOException ex) throw ex;
            if (entry instanceof Response.Builder b) {
                return b.request(chain.request()).protocol(Protocol.HTTP_1_1).build();
            }
            throw new IOException("unexpected queue entry type: " + entry.getClass());
        }
    }

    private static Response.Builder ok(String body, String contentType) {
        return new Response.Builder()
                .code(200)
                .message("OK")
                .body(ResponseBody.create(body,
                        okhttp3.MediaType.parse(contentType)));
    }

    private static Response.Builder redirect(String location) {
        var b = new Response.Builder()
                .code(302)
                .message("Found")
                .body(ResponseBody.create("", null));
        if (location != null) b.addHeader("Location", location);
        return b;
    }

    // =====================
    // 1. Redirect to AWS metadata endpoint — blocked on hop 2
    // =====================

    @Test
    void redirectToMetadataEndpointRejected() {
        queue.enqueue(redirect("http://169.254.169.254/latest/meta-data/"));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/\"}", null);
        assertTrue(result.contains("SSRF guard"),
                "expected SSRF rejection for AWS metadata hop; got: " + result);
        assertTrue(result.contains("169.254.169.254"),
                "error should name the blocked IP; got: " + result);
    }

    // =====================
    // 2. Redirect chain longer than MAX_REDIRECTS (=5)
    // =====================

    @Test
    void tooManyRedirectsReturnsError() {
        // Enqueue 7 consecutive redirects so the MAX_REDIRECTS (5) cap trips.
        for (int i = 0; i < 7; i++) {
            queue.enqueue(redirect("http://example.test/hop" + (i + 1)));
        }
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/\"}", null);
        assertTrue(result.contains("Too many redirects"),
                "expected redirect-cap error; got: " + result);
    }

    // =====================
    // 3. 3xx response with missing or blank Location header
    // =====================

    @Test
    void missingLocationHeaderReturnsError() {
        queue.enqueue(redirect(null)); // no Location header
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/\"}", null);
        assertTrue(result.contains("no Location header"),
                "expected missing-Location error; got: " + result);
    }

    @Test
    void blankLocationHeaderReturnsError() {
        queue.enqueue(redirect("")); // explicit blank Location
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/\"}", null);
        assertTrue(result.contains("no Location header"),
                "blank Location must surface as no-Location error; got: " + result);
    }

    // =====================
    // 4. SSLException branches (lines 104–111)
    // =====================

    @Test
    void sslExceptionReturnsCertificateError() {
        queue.enqueueThrow(new javax.net.ssl.SSLHandshakeException(
                "test-injected certificate failure"));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"https://example.test/\"}", null);
        assertTrue(result.contains("SSL/TLS"),
                "expected SSL/TLS error string; got: " + result);
        assertTrue(result.contains("certificate"),
                "error should mention certificate; got: " + result);
    }

    @Test
    void sslExceptionWrappedAsCauseReturnsCertificateError() {
        // The second branch (lines 108–110) handles SSLException wrapped
        // inside a generic Exception — OkHttp sometimes surfaces it that way.
        queue.enqueueThrow(new IOException("wrapper",
                new javax.net.ssl.SSLException("wrapped ssl error")));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"https://example.test/\"}", null);
        assertTrue(result.contains("SSL/TLS"),
                "wrapped SSLException should still surface as SSL error; got: " + result);
    }

    // =====================
    // 5. Body truncation (text and html modes)
    // =====================

    @Test
    void textModeBodyOverCapIsTruncated() {
        // Non-HTML body over MAX_TEXT_LENGTH (50,000) triggers truncation.
        var body = "x".repeat(60_000);
        queue.enqueue(ok(body, "text/plain"));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/\"}", null);
        assertTrue(result.contains("[Truncated: content exceeds 50000"),
                "text-mode truncation marker missing; got tail: "
                        + result.substring(Math.max(0, result.length() - 200)));
    }

    @Test
    void htmlModeBodyOverCapWithoutAgentIsTruncated() {
        // html mode + body > MAX_HTML_LENGTH + no agent → plain truncation
        // (the auto-save branch requires agent != null).
        var body = "<html><body>" + "y".repeat(110_000) + "</body></html>";
        queue.enqueue(ok(body, "text/html"));
        var tool = new WebFetchTool();
        var result = tool.execute(
                "{\"url\":\"http://example.test/\",\"mode\":\"html\"}", null);
        assertTrue(result.contains("[Truncated: HTML exceeds 100000"),
                "html-mode truncation marker missing");
    }

    // =====================
    // 6. html mode auto-save with host-derived filename
    // =====================

    @Test
    void htmlModeOverTextLengthAutoSavesWithHostFilename() {
        // Body between MAX_TEXT_LENGTH (50k) and MAX_HTML_LENGTH (100k)
        // triggers the auto-save branch when agent is present. Verifies the
        // branch fires and the filename is derived from the URL host.
        //
        // Note: we intentionally do NOT try to feed weird chars (tilde, etc.)
        // through the host to demonstrate regex replacement — Java's
        // {@link java.net.URI} rejects most sub-delim/unreserved-extended
        // chars at parse time, so they never reach the sanitisation regex.
        // A normal host exercises the branch; the regex protects against the
        // edge case where a future URI parser is more permissive.
        var body = "z".repeat(70_000);
        queue.enqueue(ok(body, "text/html"));

        var agent = new Agent();
        agent.name = "webfetch-test-" + System.nanoTime();

        var tool = new WebFetchTool();
        // AgentService.writeWorkspaceFile may fail without a workspace dir,
        // but the return string is built from the filename before the write,
        // so the substring assertion holds regardless.
        var result = tool.execute(
                "{\"url\":\"http://example.test/\",\"mode\":\"html\"}", agent);
        assertTrue(result.contains("HTML saved"),
                "expected auto-save marker; got: " + result);
        assertTrue(result.contains("example.test.html"),
                "expected host-derived filename; got: " + result);
        // Filename portion must only contain allowlist chars [a-zA-Z0-9.-]
        // (or underscore, which the regex produces as replacement for
        // anything outside the allowlist).
        var markerIdx = result.indexOf("'");
        var closeIdx = result.indexOf("'", markerIdx + 1);
        if (markerIdx >= 0 && closeIdx > markerIdx) {
            var filename = result.substring(markerIdx + 1, closeIdx);
            assertTrue(filename.matches("[a-zA-Z0-9._-]+"),
                    "filename has disallowed chars: " + filename);
        }
    }
}
