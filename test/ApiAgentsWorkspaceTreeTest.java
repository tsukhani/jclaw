import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.Tx;
import services.WorkspaceFiles;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * JCLAW-1247: {@code GET /api/agents/{id}/workspace-tree}. Every agent here carries a
 * unique name so its workspace directory under {@code workspace-test} is this class's own;
 * no {@code Fixtures.deleteDatabase}, since play1 runs test classes concurrently.
 */
class ApiAgentsWorkspaceTreeTest extends FunctionalTest {

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
        return "jclaw1247-" + stem + "-" + System.nanoTime();
    }

    private static Agent createAgent(String name) {
        return fetchInFreshTx(() -> AgentService.create(name, "openrouter", "gpt-4.1"));
    }

    private static JsonObject entryNamed(JsonArray entries, String name) {
        for (var el : entries) {
            var obj = el.getAsJsonObject();
            if (name.equals(obj.get("name").getAsString())) return obj;
        }
        throw new AssertionError("no entry named " + name + " in " + entries);
    }

    @Test
    void listsTheTreeWithProtectedMarkersAggregateSizesAndATotal() {
        login();
        var agent = createAgent(uniqueName("tree"));
        WorkspaceFiles.writeWorkspaceFile(agent.name, "notes/todo.txt", "todo");
        WorkspaceFiles.writeWorkspaceFile(agent.name, ".hidden", "xy");

        var response = GET("/api/agents/" + agent.id + "/workspace-tree");

        assertIsOk(response);
        assertContentType("application/json", response);
        var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
        var entries = body.getAsJsonArray("entries");
        assertTrue(body.get("total").getAsLong() > 6,
                "total covers the scaffolded persona files as well as the two written here");
        var agentMd = entryNamed(entries, "AGENT.md");
        assertTrue(agentMd.get("protected").getAsBoolean(), "a Standing Orders file is marked protected");
        assertEquals("file", agentMd.get("kind").getAsString());
        assertTrue(agentMd.get("children").isJsonNull(), "a file entry carries no children");
        var notes = entryNamed(entries, "notes");
        assertEquals("dir", notes.get("kind").getAsString());
        assertEquals(4, notes.get("size").getAsLong(), "a folder's size aggregates its contents");
        assertFalse(notes.get("protected").getAsBoolean());
        var todo = entryNamed(notes.getAsJsonArray("children"), "todo.txt");
        assertEquals("notes/todo.txt", todo.get("path").getAsString());
        assertEquals(2, entryNamed(entries, ".hidden").get("size").getAsLong(), "dotfiles are listed");
    }

    @Test
    void aSubAgentListsItsRootAgentsWorkspace() {
        login();
        var parent = createAgent(uniqueName("parent"));
        WorkspaceFiles.writeWorkspaceFile(parent.name, "shared.txt", "shared");
        var child = fetchInFreshTx(() -> AgentService.create(uniqueName("child"), "openrouter",
                "gpt-4.1", null, null, false, parent));

        var response = GET("/api/agents/" + child.id + "/workspace-tree");

        assertIsOk(response);
        var entries = JsonParser.parseString(getContent(response)).getAsJsonObject().getAsJsonArray("entries");
        assertEquals(6, entryNamed(entries, "shared.txt").get("size").getAsLong(),
                "the sub-agent sees the shared workspace its parent owns");
    }

    @Test
    void agentPrincipalIsRefusedWithOperatorOnly() {
        var victim = createAgent(uniqueName("victim"));

        var response = asAgent(() -> GET(agentRequest(), "/api/agents/" + victim.id + "/workspace-tree"));

        assertStatus(403, response);
        assertTrue(getContent(response).contains("operator_only"),
                "expected the operator_only error code; got: " + getContent(response));
    }

    @Test
    void unknownAgentIs404() {
        login();
        var response = GET("/api/agents/999999999/workspace-tree");
        assertStatus(404, response);
    }
}
