import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.Tx;
import services.WorkspaceFiles;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

/**
 * JCLAW-1248: {@code GET /api/agents/{id}/workspace-download/{path}}. Every agent here carries a
 * unique name so its workspace directory under {@code workspace-test} is this class's own;
 * no {@code Fixtures.deleteDatabase}, since play1 runs test classes concurrently.
 */
class ApiAgentsWorkspaceDownloadTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    private void login() {
        clearCookies();
        var response = POST("/api/auth/login", "application/json",
                "{\"username\": \"admin\", \"password\": \"changeme\"}");
        assertIsOk(response);
    }

    /** A request carrying the internal bearer token — indistinguishable from a jclaw_api call. */
    private static Http.Request agentRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }

    /** The bearer response stamps the shared cookie jar with the agent principal; wipe it. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        }
        finally {
            clearCookies();
        }
    }

    private static <T> T fetchInFreshTx(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            }
            catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }

    private static String uniqueName(String stem) {
        return "jclaw1248-" + stem + "-" + System.nanoTime();
    }

    private static Agent createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    /** Entry name to entry body, in the order the archive lists them. */
    static Map<String, String> unzip(byte[] archive) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void servesAFileAsAnAttachmentWithItsOwnNameAndBytes() throws IOException {
        login();
        var agent = createAgent(uniqueName("file"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "downloads/report.pdf", "pdf-bytes");

        var response = GET("/api/agents/" + agent.id + "/workspace-download/downloads/report.pdf");

        assertIsOk(response);
        assertEquals("attachment; filename=\"report.pdf\"", response.getHeader("Content-Disposition"));
        assertTrue(response.direct instanceof File, "the file is served from disk rather than re-encoded");
        assertEquals("pdf-bytes", Files.readString(((File) response.direct).toPath()));
    }

    @Test
    void zipsAFolderWithNamesRelativeToIt() throws IOException {
        login();
        var agent = createAgent(uniqueName("folder"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "downloads/report.pdf", "pdf-bytes");
        WorkspaceFiles.writeWorkspaceFile(agent.name, "downloads/nested/a.bin", "abc");
        WorkspaceFiles.writeWorkspaceFile(agent.name, "elsewhere.txt", "not in this archive");

        var response = GET("/api/agents/" + agent.id + "/workspace-download/downloads");

        assertIsOk(response);
        assertEquals("attachment; filename=\"downloads.zip\"", response.getHeader("Content-Disposition"));
        assertEquals("application/zip", response.contentType);
        assertTrue(response.chunked, "the archive leaves as it is built; nothing is staged whole");
        var entries = unzip(response.out.toByteArray());
        assertEquals(List.of("nested/", "nested/a.bin", "report.pdf"), List.copyOf(entries.keySet()));
        assertEquals("pdf-bytes", entries.get("report.pdf"));
    }

    @Test
    void aSymlinkOutOfTheWorkspaceIsNotInTheArchive() throws IOException {
        login();
        var agent = createAgent(uniqueName("symlink"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "downloads/keep.txt", "kept");
        Path secret = Files.createTempFile("jclaw1248-secret", ".txt");
        try {
            Files.writeString(secret, "not reachable from the archive");
            Files.createSymbolicLink(
                    WorkspaceFiles.workspacePath(agent.name).resolve("downloads/escape.txt"), secret);

            var response = GET("/api/agents/" + agent.id + "/workspace-download/downloads");

            assertIsOk(response);
            var entries = unzip(response.out.toByteArray());
            assertEquals(List.of("keep.txt"), List.copyOf(entries.keySet()),
                    "the link is dropped by the guard rather than followed out of the workspace");
        }
        finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void agentPrincipalIsRefusedWithOperatorOnly() {
        var victim = createAgent(uniqueName("victim"));
        WorkspaceFiles.writeWorkspaceFile(victim.name, "downloads/report.pdf", "pdf-bytes");

        var file = asAgent(() -> GET(agentRequest(),
                "/api/agents/" + victim.id + "/workspace-download/downloads/report.pdf"));
        var folder = asAgent(() -> GET(agentRequest(),
                "/api/agents/" + victim.id + "/workspace-download/downloads"));

        for (var response : List.of(file, folder)) {
            assertStatus(403, response);
            assertTrue(getContent(response).contains("operator_only"),
                    "expected the operator_only error code; got: " + getContent(response));
        }
    }

    @Test
    void aPathLeavingTheWorkspaceIsRefused() {
        login();
        var agent = createAgent(uniqueName("escape"));

        var response = GET("/api/agents/" + agent.id + "/workspace-download/../../conf/application.conf");

        assertStatus(403, response);
    }

    @Test
    void anEntryThatIsNotThereIs404() {
        login();
        var agent = createAgent(uniqueName("missing"));

        assertStatus(404, GET("/api/agents/" + agent.id + "/workspace-download/nope.txt"));
        assertStatus(404, GET("/api/agents/999999999/workspace-download/nope.txt"));
    }
}
