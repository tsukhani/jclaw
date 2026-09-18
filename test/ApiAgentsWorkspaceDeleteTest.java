import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.Tx;
import services.WorkspaceFiles;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-1249: {@code DELETE /api/agents/{id}/workspace-tree/{path}}. Every agent here carries a
 * unique name so its workspace directory under {@code workspace-test} is this class's own;
 * no {@code Fixtures.deleteDatabase}, since play1 runs test classes concurrently.
 */
class ApiAgentsWorkspaceDeleteTest extends FunctionalTest {

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
        return "jclaw1249-" + stem + "-" + System.nanoTime();
    }

    private static Agent createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    @Test
    void deletesAFileAndLeavesTheRestOfTheWorkspace() {
        login();
        var agent = createAgent(uniqueName("file"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "stray.txt", "junk");
        WorkspaceFiles.writeWorkspaceFile(agent.name, "keep.txt", "keep");
        var root = WorkspaceFiles.workspacePath(agent.name);

        var response = DELETE("/api/agents/" + agent.id + "/workspace-tree/stray.txt");

        assertIsOk(response);
        assertFalse(Files.exists(root.resolve("stray.txt")), "the file is gone from disk");
        assertTrue(Files.exists(root.resolve("keep.txt")), "a sibling is untouched");
    }

    @Test
    void deletesAFolderWithItsWholeSubtree() {
        login();
        var agent = createAgent(uniqueName("folder"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "downloads/report.pdf", "pdf");
        WorkspaceFiles.writeWorkspaceFile(agent.name, "downloads/nested/deep.bin", "deep");
        var root = WorkspaceFiles.workspacePath(agent.name);

        var response = DELETE("/api/agents/" + agent.id + "/workspace-tree/downloads");

        assertIsOk(response);
        assertFalse(Files.exists(root.resolve("downloads")), "the folder and everything under it is gone");
        assertTrue(Files.exists(root.resolve("AGENT.md")), "the rest of the workspace survives");
    }

    @Test
    void refusesAStandingOrdersFileWhateverTheUiShowed() {
        login();
        var agent = createAgent(uniqueName("persona"));
        var root = WorkspaceFiles.workspacePath(agent.name);

        var response = DELETE("/api/agents/" + agent.id + "/workspace-tree/AGENT.md");

        assertStatus(403, response);
        assertTrue(getContent(response).contains("cannot be deleted"),
                "expected the refusal message; got: " + getContent(response));
        assertTrue(Files.exists(root.resolve("AGENT.md")), "the persona file is still on disk");
    }

    @Test
    void refusesTheWorkspaceRootItself() {
        login();
        var agent = createAgent(uniqueName("root"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "keep.txt", "keep");
        var root = WorkspaceFiles.workspacePath(agent.name);

        var response = DELETE("/api/agents/" + agent.id + "/workspace-tree/");

        assertStatus(403, response);
        assertTrue(Files.exists(root.resolve("keep.txt")), "the workspace is intact");
    }

    @Test
    void agentPrincipalIsRefusedWithOperatorOnly() {
        var victim = createAgent(uniqueName("victim"));
        WorkspaceFiles.writeWorkspaceFile(victim.name, "secret.txt", "secret");
        var root = WorkspaceFiles.workspacePath(victim.name);

        var response = asAgent(() ->
                DELETE(agentRequest(), "/api/agents/" + victim.id + "/workspace-tree/secret.txt"));

        assertStatus(403, response);
        assertTrue(getContent(response).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(response));
        assertTrue(Files.exists(root.resolve("secret.txt")), "an agent cannot prune another's workspace");
    }

    @Test
    void aMissingTargetIs404() {
        login();
        var agent = createAgent(uniqueName("missing"));

        var response = DELETE("/api/agents/" + agent.id + "/workspace-tree/never-existed.txt");

        assertStatus(404, response);
    }

    @Test
    void unknownAgentIs404() {
        login();
        var response = DELETE("/api/agents/999999999/workspace-tree/anything.txt");
        assertStatus(404, response);
    }
}
