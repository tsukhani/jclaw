import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import play.mvc.Http.Response;
import play.test.FunctionalTest;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

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

    /**
     * The SPA shell must always revalidate: it references content-hashed
     * {@code _nuxt/} chunks, so a cached index.html keeps pointing at stale
     * chunk hashes and a new frontend build never reaches users — the bug
     * GitHub issue 9 reported. The header is a bare {@code response.setHeader}
     * duplicated in {@code Application.index} and {@code Application.spa} with
     * no shared call path, so nothing but these two tests stops a refactor
     * dropping it silently. {@code nuxtCacheControl} is separately unit-tested,
     * but that pure function isn't where the risk is (JCLAW-886).
     *
     * <p>Both tests discriminate on the file rather than the status code:
     * unbuilt, {@code index} still returns 200 {@code text/html} (a plain
     * "SPA not built" page) and sets no cache header at all, so a status check
     * can't tell the branches apart. The fixed {@code public/spa/index.html}
     * path also can't be created here the way {@code AppAssetCacheControlTest}
     * creates unique dirs under {@code public/apps} — play1 runs tests
     * concurrently, so writing it would race and could stomp a real build.
     */
    @Test
    void indexSendsNoCacheOnSpaShell() {
        assumeTrue(play.Play.getFile("public/spa/index.html").exists(),
                "SPA not built — no shell to assert the cache header against");
        Response response = GET("/");
        assertIsOk(response);
        assertEquals("no-cache", response.getHeader("Cache-Control"),
                "the SPA shell must revalidate or stale chunk hashes get pinned");
    }

    @Test
    void spaCatchAllSendsNoCacheOnSpaShell() {
        // Same contract on the client-routing fallback: Application.spa serves
        // the identical shell, so it carries the identical requirement.
        assumeTrue(play.Play.getFile("public/spa/index.html").exists(),
                "SPA not built — no shell to assert the cache header against");
        Response response = GET("/some/spa/route");
        assertIsOk(response);
        assertEquals("no-cache", response.getHeader("Cache-Control"),
                "the SPA catch-all shell must revalidate, like index");
    }

    /**
     * Covers the immutable half of the cache split — and, more importantly,
     * proves the two no-cache tests above aren't vacuous.
     *
     * <p>Play's {@code addEtag} rewrites {@code Cache-Control} to
     * {@code no-cache} on every response in DEV. If that ran under
     * {@link FunctionalTest}, the shell assertions would still pass with
     * {@code Application.index}'s {@code setHeader} deleted, and this whole
     * regression guard would be theatre. A content-hashed chunk coming back
     * {@code immutable} is only possible when nothing is rewriting the header,
     * so this test failing invalidates the two above rather than standing
     * alone — hence the shared assertion message.
     */
    @Test
    void hashedNuxtChunkIsImmutable() {
        var nuxtDir = play.Play.getFile("public/spa/_nuxt");
        assumeTrue(nuxtDir.isDirectory(), "SPA not built — no hashed chunks to probe");
        var chunk = java.util.Arrays.stream(nuxtDir.listFiles())
                .filter(f -> f.isFile() && f.getName().endsWith(".js"))
                .findFirst();
        assumeTrue(chunk.isPresent(), "no hashed chunk under public/spa/_nuxt");

        Response response = GET("/_nuxt/" + chunk.get().getName());
        assertIsOk(response);
        assertEquals("public, max-age=31536000, immutable",
                response.getHeader("Cache-Control"),
                "content-hashed chunks must cache for a year; if this reads 'no-cache' "
                        + "the harness is rewriting headers and the shell tests are vacuous");
    }

    @Test
    void siteManifestNamesTheBuildStatusReportsAndStartsAtTheRoot() {
        String productName = JsonParser.parseString(getContent(GET("/api/status")))
                .getAsJsonObject().get("productName").getAsString();
        assertFalse(productName.isEmpty(), "an empty name is what kept Chrome from installing the app");

        Response response = GET("/site.webmanifest");
        assertIsOk(response);
        assertContentType("application/manifest+json", response);
        assertEquals("no-cache", response.getHeader("Cache-Control"));
        var expected = JsonParser.parseString("""
                {"name":"%1$s","short_name":"%1$s","start_url":"/","display":"standalone",
                 "theme_color":"#ffffff","background_color":"#ffffff",
                 "icons":[{"src":"/android-chrome-192x192.png","sizes":"192x192","type":"image/png"},
                          {"src":"/android-chrome-512x512.png","sizes":"512x512","type":"image/png"}]}
                """.formatted(productName));
        assertEquals(expected, JsonParser.parseString(getContent(response)));
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
