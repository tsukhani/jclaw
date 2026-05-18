import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import play.mvc.Http.Response;

/**
 * JCLAW-315: extend the bare {@link ApplicationTest} coverage by exercising
 * the SPA catch-all branches.
 *
 * <p>Note on AC drift: the JCLAW-315 ticket lists an {@code unknownAction}
 * 404 path and a {@code version} action; neither exists in
 * {@link controllers.Application}. The application version is served by
 * {@code ApiController.status} at {@code GET /api/status} and is already
 * covered by {@code ControllerApiTest}; the catch-all {@code spa} action
 * IS where unknown-path handling lives, so that's what we cover here.
 */
class ApplicationControllerTest extends FunctionalTest {

    @Test
    void indexReturnsHtmlContent() {
        Response response = GET("/");
        assertIsOk(response);
        assertContentType("text/html", response);
        assertCharset(play.Play.defaultWebEncoding, response);
        // Body assertion intentionally omitted: Application.index uses
        // renderBinary(File) when public/spa/index.html exists, which in
        // Play 1.x's FunctionalTest bypasses response.out (sendfile path).
        // getContent(response) returns blank for that branch even though
        // the live response is correct. Status + content-type + charset
        // are the parts of the contract we can verify here; full body
        // checks belong in an e2e (Playwright) spec.
    }

    @Test
    void spaCatchAllServesIndexForUnknownPath() {
        // SPA fallback: any path that doesn't match an API/static/_nuxt
        // route is delegated to Application.spa, which serves the SPA
        // index.html so client-side routing can take over. In dev tests
        // (no SPA build) this returns 404 with a "SPA not built" message;
        // in CI/prod it returns 200 with the HTML shell.
        Response response = GET("/some/spa/route");
        var status = response.status.intValue();
        assertTrue(status == 200 || status == 404,
                "spa catch-all must be 200 (built) or 404 (not built), got " + status);
        if (status == 404) {
            var body = getContent(response);
            assertTrue(body.contains("SPA not built"),
                    "404 must explain the SPA isn't built: " + body);
        } else {
            assertContentType("text/html", response);
        }
    }

    @Test
    void spaCatchAllRejectsTraversal() {
        // The spa() action checks for ".." in the path before any file
        // resolution; a traversal attempt must NOT serve a file from above
        // public/spa/. The fallback path then either 200s with index.html
        // (built) or 404s — never leaks a file outside the SPA root.
        Response response = GET("/..%2F..%2Fconf%2Fapplication.conf");
        var status = response.status.intValue();
        // Whatever the response is, the body must not be the application.conf
        // contents.
        var body = response.out == null ? "" : response.out.toString();
        assertFalse(body.contains("application.version="),
                "spa catch-all must not leak conf/application.conf: " + body);
        assertFalse(body.contains("application.secret"),
                "spa catch-all must not leak conf/application.conf: " + body);
        assertTrue(status == 200 || status == 404,
                "traversal must collapse to 200-SPA-shell or 404-not-built, got " + status);
    }

    @Test
    void apiStatusReturnsApplicationVersion() {
        // The application.version key in application.conf is surfaced
        // through ApiController.status — verify it round-trips so the
        // AC ("Application.version returns the application.version") is
        // covered against the action that actually exposes it.
        Response response = GET("/api/status");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"applicationVersion\""),
                "/api/status must include applicationVersion field: " + body);
        // The value must look like a version string (e.g. "0.12.7"). The
        // conf/application.conf value is the source of truth; we don't
        // pin to a literal here because the file is bumped every release.
        assertTrue(body.matches(".*\"applicationVersion\"\\s*:\\s*\"[0-9]+\\.[0-9]+\\.[0-9]+.*\".*"),
                "applicationVersion must be a semver-shaped string: " + body);
    }
}
