import com.google.gson.JsonParser;
import controllers.ApiController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import play.test.Fixtures;
import play.test.FunctionalTest;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GET /api/workspace/stats — the dashboard's workspace-disk-footprint line.
 * Session-gated (unlike its sibling /api/status health check), and backed by
 * {@link ApiController#directorySizeBytes} whose sum/absent/never-throw
 * contract is pinned here against a real temp tree.
 */
class ApiControllerWorkspaceStatsTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
    }

    private void login() {
        var body = """
                {"username": "admin", "password": "changeme"}
                """;
        assertIsOk(POST("/api/auth/login", "application/json", body));
    }

    @Test
    void workspaceStatsRequiresAuth() {
        assertEquals(401, GET("/api/workspace/stats").status.intValue());
    }

    @Test
    void workspaceStatsReportsANonNegativeByteTotal() {
        login();
        var response = GET("/api/workspace/stats");
        assertIsOk(response);
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        // The repo's workspace/ root exists and carries agent files, so the
        // walk must produce a real positive total — not the -1 failure marker
        // and not a hardcoded 0.
        assertTrue(json.get("bytes").getAsLong() > 0,
                "workspace/ exists with content, so bytes must be positive: " + json);
    }

    @Test
    void directorySizeSumsNestedRegularFiles(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.txt"), "abc");                    // 3 bytes
        Files.createDirectories(dir.resolve("sub/deep"));
        Files.writeString(dir.resolve("sub/deep/b.txt"), "hello");        // 5 bytes
        Files.writeString(dir.resolve("sub/empty.txt"), "");              // 0 bytes
        assertEquals(8L, ApiController.directorySizeBytes(dir),
                "size must be the recursive sum of regular-file bytes");
    }

    @Test
    void directorySizeIsZeroForAMissingDirectory(@TempDir Path dir) {
        assertEquals(0L, ApiController.directorySizeBytes(dir.resolve("never-created")),
                "an absent workspace reads as empty, not as an error");
    }
}
