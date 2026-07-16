import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.FunctionalTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * SPEC-apps CAP-4: the /api/apps enumeration endpoint scans public/apps/<slug>/
 * for app.json + index.html. Each test creates uniquely-named app dirs (safe
 * under play1's concurrent test engine) and cleans up only its own.
 */
class ApiAppsControllerTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-apps";
    private static final String TEST_PREFIX = "jclaw-test-app-";

    private Path appsDir;
    private final List<Path> created = new ArrayList<>();

    @BeforeEach
    void seedAndLogin() throws IOException {
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        appsDir = Play.getFile("public/apps").toPath();
        Files.createDirectories(appsDir);
        var loginBody = """
                {"username":"admin","password":"%s"}
                """.formatted(TEST_PASSWORD);
        assertIsOk(POST("/api/auth/login", "application/json", loginBody));
    }

    @AfterEach
    void cleanup() {
        AuthFixture.clearAdminPassword();
        created.forEach(ApiAppsControllerTest::deleteRecursive);
    }

    /** Create a uniquely-named app dir under public/apps/, tracked for cleanup. */
    private String makeApp(String manifest, boolean withIndex) throws IOException {
        var slug = TEST_PREFIX + UUID.randomUUID().toString().substring(0, 8);
        var dir = appsDir.resolve(slug);
        Files.createDirectories(dir);
        created.add(dir);
        if (manifest != null) Files.writeString(dir.resolve("app.json"), manifest);
        if (withIndex) Files.writeString(dir.resolve("index.html"), "<html></html>");
        return slug;
    }

    @Test
    void listsAValidAppWithDerivedUrlAndIcon() throws IOException {
        var slug = makeApp("""
                {"name":"Demo","version":"1.2.3","creator":"Tarun","icon":"icon.png","price":"$9/mo"}
                """, true);
        var response = GET("/api/apps");
        assertIsOk(response);
        assertContentType("application/json", response);
        var body = getContent(response);
        assertTrue(body.contains("\"id\":\"" + slug + "\""), "app id present: " + body);
        assertTrue(body.contains("\"url\":\"/apps/" + slug + "/\""), "derived launch url: " + body);
        assertTrue(body.contains("\"icon\":\"/apps/" + slug + "/icon.png\""), "resolved icon url: " + body);
        assertTrue(body.contains("\"name\":\"Demo\""), "manifest name: " + body);
        assertTrue(body.contains("\"price\":\"$9/mo\""), "manifest price: " + body);
    }

    @Test
    void skipsDirWithoutManifest() throws IOException {
        var slug = makeApp(null, true); // index.html but no app.json
        assertFalse(getContent(GET("/api/apps")).contains(slug), "dir without app.json is not listed");
    }

    @Test
    void skipsDirWithoutIndexHtml() throws IOException {
        var slug = makeApp("{\"name\":\"X\",\"version\":\"1\",\"creator\":\"Y\"}", false);
        assertFalse(getContent(GET("/api/apps")).contains(slug), "dir without index.html is not listed");
    }

    @Test
    void skipsMalformedManifestWithout500() throws IOException {
        var slug = makeApp("{ this is not valid json", true);
        var response = GET("/api/apps");
        assertIsOk(response); // still 200 — malformed manifest is skipped, never a 500
        assertFalse(getContent(response).contains(slug), "malformed manifest app is skipped");
    }

    @Test
    void requiresAuth() {
        POST("/api/auth/logout", "application/json", "{}");
        assertEquals(401, GET("/api/apps").status.intValue());
    }

    private static void deleteRecursive(Path root) {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                }
                catch (IOException _) {
                    // best-effort cleanup
                }
            });
        }
        catch (IOException _) {
            // best-effort cleanup
        }
    }
}
