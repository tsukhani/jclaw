import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Http.Response;
import play.test.FunctionalTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Hosted-app static serving (SPEC-apps) is routed through
 * {@link controllers.Application#appAsset} rather than a bare
 * {@code staticDir:public/apps}, so every file carries
 * {@code Cache-Control: no-cache}. These mini-apps keep stable filenames
 * (index.html, scripts), so an operator's edit must surface on the next load
 * instead of being pinned for an hour by Play's {@code http.cacheControl} — the
 * caching bug this replaced the {@code staticDir} route to fix.
 *
 * <p>Each test creates a uniquely-named app dir (safe under play1's concurrent
 * test engine) and removes only its own in {@link #cleanup()}. Dirs carry no
 * {@code app.json}, so the {@code /api/apps} registry never counts them.
 */
class AppAssetCacheControlTest extends FunctionalTest {

    private static final String PREFIX = "jclaw-test-appasset-";
    private final List<Path> created = new ArrayList<>();

    /** Create a temp app dir with an index.html; returns the dir's slug. */
    private String newApp(String indexHtml) throws IOException {
        Path appsDir = Play.getFile("public/apps").toPath();
        Files.createDirectories(appsDir);
        Path dir = appsDir.resolve(PREFIX + UUID.randomUUID());
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("index.html"), indexHtml);
        created.add(dir);
        return dir.getFileName().toString();
    }

    @AfterEach
    void cleanup() {
        for (Path dir : created) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException _) {}
                });
            } catch (IOException _) {}
        }
    }

    @Test
    void indexHtmlServedInlineWithNoCache() throws IOException {
        String slug = newApp("<!doctype html><title>hosted</title>");
        Response r = GET("/apps/" + slug + "/index.html");
        assertIsOk(r);
        assertContentType("text/html", r);
        assertEquals("no-cache", r.getHeader("Cache-Control"),
                "hosted-app files must revalidate so edits surface immediately");
    }

    @Test
    void directoryRequestServesIndexWithNoCache() throws IOException {
        String slug = newApp("<!doctype html><title>hosted</title>");
        // A bare directory request must resolve to index.html (staticDir parity).
        Response r = GET("/apps/" + slug + "/");
        assertIsOk(r);
        assertEquals("no-cache", r.getHeader("Cache-Control"));
    }

    @Test
    void subAssetServedWithNoCache() throws IOException {
        String slug = newApp("<!doctype html><title>hosted</title>");
        Files.writeString(Play.getFile("public/apps/" + slug + "/app.js").toPath(),
                "console.log('v2')");
        Response r = GET("/apps/" + slug + "/app.js");
        assertIsOk(r);
        assertEquals("no-cache", r.getHeader("Cache-Control"),
                "non-hashed app scripts must also revalidate, not just index.html");
    }

    @Test
    void unknownAppIsNotFound() {
        Response r = GET("/apps/" + PREFIX + "does-not-exist/");
        assertStatus(404, r);
    }

    @Test
    void traversalDoesNotLeakConf() {
        // A ".." escape must never serve a file from above public/apps/.
        Response r = GET("/apps/..%2F..%2Fconf%2Fapplication.conf");
        var body = r.out == null ? "" : r.out.toString();
        assertFalse(body.contains("application.version="),
                "app asset route must not leak conf/application.conf");
        assertFalse(body.contains("application.secret"),
                "app asset route must not leak conf/application.conf");
        assertTrue(r.status == 404 || r.status == 200,
                "traversal must collapse to a safe 404 (or 200), got " + r.status);
    }
}
