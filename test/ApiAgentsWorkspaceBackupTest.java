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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;

/**
 * JCLAW-1251: {@code GET /api/agents/{id}/workspace-backup}. Every agent here carries a unique
 * name so its workspace directory under {@code workspace-test} is this class's own; no
 * {@code Fixtures.deleteDatabase}, since play1 runs test classes concurrently.
 */
class ApiAgentsWorkspaceBackupTest extends FunctionalTest {

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
        return "jclaw1251-" + stem + "-" + System.nanoTime();
    }

    private static Agent createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    private static Map<String, String> unzip(byte[] archive) throws IOException {
        var entries = new LinkedHashMap<String, String>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

    @Test
    void zipsTheWholeWorkspaceIncludingTheStandingOrdersFiles() throws IOException {
        login();
        var agent = createAgent(uniqueName("backup"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "notes/todo.txt", "todo");

        var response = GET("/api/agents/" + agent.id + "/workspace-backup");

        assertIsOk(response);
        assertEquals("attachment; filename=\"" + agent.name + "-workspace.zip\"",
                response.getHeader("Content-Disposition"));
        assertEquals("application/zip", response.contentType);
        assertTrue(response.chunked, "the archive leaves as it is built; nothing is staged whole");
        var entries = unzip(response.out.toByteArray());
        assertEquals("todo", entries.get("notes/todo.txt"), "paths are relative to the workspace root");
        assertTrue(entries.containsKey("AGENT.md"), "the Standing Orders files are in the backup: " + entries.keySet());
        assertTrue(entries.containsKey("SOUL.md"), "the Standing Orders files are in the backup: " + entries.keySet());
    }

    @Test
    void aSymlinkOutOfTheWorkspaceIsNotInTheBackup() throws IOException {
        login();
        var agent = createAgent(uniqueName("symlink"));
        Path secret = Files.createTempFile("jclaw1251-secret", ".txt");
        try {
            Files.writeString(secret, "not reachable from the backup");
            Files.createSymbolicLink(WorkspaceFiles.workspacePath(agent.name).resolve("escape.txt"), secret);

            var response = GET("/api/agents/" + agent.id + "/workspace-backup");

            assertIsOk(response);
            assertFalse(unzip(response.out.toByteArray()).containsKey("escape.txt"),
                    "the link is dropped by the guard rather than followed out of the workspace");
        }
        finally {
            Files.deleteIfExists(secret);
        }
    }

    @Test
    void agentPrincipalIsRefusedWithOperatorOnly() {
        var victim = createAgent(uniqueName("victim"));

        var response = asAgent(() -> GET(agentRequest(), "/api/agents/" + victim.id + "/workspace-backup"));

        assertStatus(403, response);
        assertTrue(getContent(response).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(response));
    }

    @Test
    void unknownAgentIs404() {
        login();
        assertStatus(404, GET("/api/agents/999999999/workspace-backup"));
    }
}
