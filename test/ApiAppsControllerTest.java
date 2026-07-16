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
    void surfacesDesignatedAgentId() throws IOException {
        var slug = makeApp("""
                {"name":"Bot","version":"1.0.0","creator":"Tarun","agent":42}
                """, true);
        var body = getContent(GET("/api/apps"));
        assertTrue(body.contains("\"id\":\"" + slug + "\""), "app listed: " + body);
        assertTrue(body.contains("\"agent\":\"42\""), "designated agent id surfaced: " + body);
    }

    @Test
    void agentIsNullWhenManifestOmitsIt() throws IOException {
        makeApp("""
                {"name":"NoAgent","version":"1.0.0","creator":"Tarun"}
                """, true);
        assertTrue(getContent(GET("/api/apps")).contains("\"agent\":null"),
                "an app without an agent field surfaces agent:null");
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

    @Test
    void deleteRemovesTheAppDirectory() throws IOException {
        var slug = makeApp("{\"name\":\"Gone\",\"version\":\"1.0.0\"}", true);
        var dir = appsDir.resolve(slug);
        assertTrue(Files.isDirectory(dir), "app exists before delete");
        var response = DELETE("/api/apps/" + slug);
        assertIsOk(response);
        assertTrue(getContent(response).contains("\"deleted\":true"), "delete ack: " + getContent(response));
        assertFalse(Files.exists(dir), "app directory removed from disk");
        assertFalse(getContent(GET("/api/apps")).contains(slug), "deleted app no longer listed");
    }

    @Test
    void deleteRejectsInvalidSlug() {
        // An uppercase slug reaches the action but fails the lowercase-only regex,
        // so a malformed/traversal slug can never resolve to a directory to remove.
        assertEquals(400, DELETE("/api/apps/Not-A-Valid-Slug").status.intValue());
    }

    @Test
    void deleteReturns404ForMissingApp() {
        var slug = TEST_PREFIX + "missing-" + UUID.randomUUID().toString().substring(0, 8);
        assertEquals(404, DELETE("/api/apps/" + slug).status.intValue());
    }

    @Test
    void deleteRequiresAuth() throws IOException {
        var slug = makeApp("{\"name\":\"Keep\",\"version\":\"1.0.0\"}", true);
        POST("/api/auth/logout", "application/json", "{}");
        assertEquals(401, DELETE("/api/apps/" + slug).status.intValue());
        assertTrue(Files.exists(appsDir.resolve(slug)), "app untouched by an unauthenticated delete");
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
