import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.Play;
import play.test.UnitTest;
import services.WorkspaceFiles;
import tools.AppInstallTool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * JCLAW-768: {@link AppInstallTool} stages, validates, and installs hosted apps
 * between the agent workspace and {@code public/apps/}, so the app-creator flow
 * never writes {@code public/apps/} directly (which {@code WorkspacePathGuard}
 * forbids). Uses a transient {@link Agent} (name only) — {@code workspacePath}
 * resolves an uncommitted name verbatim — so the test keeps a zero-DB footprint,
 * and unique per-test slugs/agents keep it safe under the concurrent test engine.
 */
class AppInstallToolTest extends UnitTest {

    private final AppInstallTool tool = new AppInstallTool();
    private Agent agent;
    private Path workspace;
    private final List<Path> cleanup = new ArrayList<>();

    @BeforeEach
    void setup() {
        agent = new Agent();
        agent.name = "appinstall-test-agent-" + UUID.randomUUID().toString().substring(0, 8);
        workspace = WorkspaceFiles.workspacePath(agent.name);
        cleanup.add(workspace);
    }

    @AfterEach
    void cleanupDirs() {
        cleanup.forEach(AppInstallToolTest::deleteDir);
    }

    /** A unique install slug, tracked for cleanup under public/apps/. */
    private String slug() {
        var s = "jclaw-test-app-" + UUID.randomUUID().toString().substring(0, 8);
        cleanup.add(Play.getFile("public/apps/" + s).toPath());
        return s;
    }

    private Path buildInWorkspace(String dir, String appJson) throws IOException {
        var d = workspace.resolve(dir);
        Files.createDirectories(d);
        Files.writeString(d.resolve("index.html"), "<html><body>app</body></html>");
        if (appJson != null) Files.writeString(d.resolve("app.json"), appJson);
        return d;
    }

    private static String manifest(String name, String version) {
        return "{\"name\":\"" + name + "\",\"version\":\"" + version + "\"}";
    }

    private String run(String json) {
        return tool.execute(json, agent);
    }

    @Test
    void validateAcceptsWellFormedApp() throws IOException {
        var slug = slug();
        buildInWorkspace(slug, manifest("Demo", "1.0.0"));
        var res = run("{\"action\":\"validate\",\"slug\":\"" + slug + "\"}");
        assertTrue(JsonParser.parseString(res).getAsJsonObject().get("valid").getAsBoolean(),
                "well-formed app is valid: " + res);
    }

    @Test
    void validateFlagsMissingVersion() throws IOException {
        var slug = slug();
        var d = workspace.resolve(slug);
        Files.createDirectories(d);
        Files.writeString(d.resolve("index.html"), "<html></html>");
        Files.writeString(d.resolve("app.json"), "{\"name\":\"NoVersion\"}");
        var res = run("{\"action\":\"validate\",\"slug\":\"" + slug + "\"}");
        assertFalse(JsonParser.parseString(res).getAsJsonObject().get("valid").getAsBoolean(),
                "missing version is invalid: " + res);
        assertTrue(res.contains("version"), "issue names version: " + res);
    }

    @Test
    void installPublishesWorkspaceAppToPublicApps() throws IOException {
        var slug = slug();
        buildInWorkspace(slug, manifest("Demo", "2.1.0"));
        var res = run("{\"action\":\"install\",\"slug\":\"" + slug + "\"}");
        var o = JsonParser.parseString(res).getAsJsonObject();
        assertTrue(o.get("installed").getAsBoolean(), "install ok: " + res);
        assertEquals("/apps/" + slug + "/", o.get("url").getAsString());
        var installed = Play.getFile("public/apps/" + slug).toPath();
        assertTrue(Files.isRegularFile(installed.resolve("index.html")), "index.html installed");
        assertTrue(Files.isRegularFile(installed.resolve("app.json")), "app.json installed");
    }

    @Test
    void installRejectsMalformedAppWithoutWriting() throws IOException {
        var slug = slug();
        var d = workspace.resolve(slug);
        Files.createDirectories(d);
        Files.writeString(d.resolve("index.html"), "<html></html>"); // no app.json
        var res = run("{\"action\":\"install\",\"slug\":\"" + slug + "\"}");
        assertTrue(res.startsWith("Error:"), "rejected: " + res);
        assertFalse(Files.exists(Play.getFile("public/apps/" + slug).toPath()),
                "nothing written to public/apps on a rejected install");
    }

    @Test
    void installRejectsTraversalSlug() {
        var res = run("{\"action\":\"install\",\"slug\":\"../evil\"}");
        assertTrue(res.startsWith("Error:"), "traversal slug rejected: " + res);
    }

    @Test
    void stageCopiesInstalledAppIntoWorkspace() throws IOException {
        var slug = slug();
        buildInWorkspace(slug, manifest("Demo", "1.0.0"));
        run("{\"action\":\"install\",\"slug\":\"" + slug + "\"}");
        deleteDir(workspace.resolve(slug)); // remove the workspace copy first
        var res = run("{\"action\":\"stage\",\"slug\":\"" + slug + "\"}");
        var o = JsonParser.parseString(res).getAsJsonObject();
        assertTrue(o.get("staged").getAsBoolean(), "staged: " + res);
        assertFalse(o.get("alreadyPresent").getAsBoolean(), "fresh stage: " + res);
        assertTrue(Files.isRegularFile(workspace.resolve(slug).resolve("index.html")),
                "app copied into workspace");
    }

    @Test
    void stageKeepsExistingWorkspaceCopy() throws IOException {
        var slug = slug();
        buildInWorkspace(slug, manifest("Demo", "1.0.0"));
        run("{\"action\":\"install\",\"slug\":\"" + slug + "\"}");
        // Workspace copy still present -> stage keeps it, reporting alreadyPresent.
        var res = run("{\"action\":\"stage\",\"slug\":\"" + slug + "\"}");
        assertTrue(JsonParser.parseString(res).getAsJsonObject().get("alreadyPresent").getAsBoolean(),
                "existing workspace copy kept: " + res);
    }

    private static void deleteDir(Path root) {
        if (root == null || !Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
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
