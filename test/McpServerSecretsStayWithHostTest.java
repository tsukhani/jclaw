import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import models.McpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@code PUT /api/mcp-servers/{id}}: the headers saved with an HTTP server stay with the origin they were saved
 * for (JF-291). An update that moves the server to another scheme, host or port sends it only the headers the
 * same request carries; one that keeps the origin keeps them.
 *
 * <p>Every server here is saved disabled, so no request leaves the JVM.
 */
class McpServerSecretsStayWithHostTest extends FunctionalTest {

    private static final String SAVED = "http://127.0.0.1:1/mcp";
    private static final String SAVED_CONFIG = """
            {"url":"%s","headers":{"Authorization":"Bearer real-token","X-Tier":"gold"}}""".formatted(SAVED);
    private static final String MOVED_REFUSAL = "Authorization was saved for the server's previous address and is "
            + "not sent to a new one: enter the value again.";

    private final List<Long> serverIds = new ArrayList<>();

    // No Fixtures.deleteDatabase(): every row here is this class's, by a name no other class uses.
    @BeforeEach
    void signIn() {
        AuthFixture.seedAdminPassword("changeme");
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
    }

    @AfterEach
    void removeServers() {
        clearCookies();
        commit(() -> {
            for (var id : serverIds) {
                McpServer row = McpServer.findById(id);
                if (row != null) row.delete();
            }
            return null;
        });
    }

    @Test
    void movingAServerToAnotherHostSendsItNoneOfTheSavedHeaders() {
        var id = savedServer();

        var moved = PUT("/api/mcp-servers/" + id, "application/json", "{\"url\":\"http://127.0.0.2:1/mcp\"}");

        assertIsOk(moved);
        assertEquals("{}", json(moved).getAsJsonObject("headers").toString());
        assertStored("{\"url\":\"http://127.0.0.2:1/mcp\"}", id);
    }

    @Test
    void movingAServerToAnotherPortOfTheSameHostSendsItNoneOfTheSavedHeaders() {
        var id = savedServer();

        assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", "{\"url\":\"http://127.0.0.1:2/mcp\"}"));

        assertStored("{\"url\":\"http://127.0.0.1:2/mcp\"}", id);
    }

    @Test
    void aNewPathOnTheSameHostKeepsTheSavedHeaders() {
        var id = savedServer();

        var kept = PUT("/api/mcp-servers/" + id, "application/json", "{\"url\":\"http://127.0.0.1:1/v2/mcp\"}");

        assertIsOk(kept);
        assertEquals("{\"Authorization\":\"Bear****\",\"X-Tier\":\"****\"}", json(kept).getAsJsonObject("headers").toString());
        assertStored("""
                {"url":"http://127.0.0.1:1/v2/mcp","headers":{"Authorization":"Bearer real-token","X-Tier":"gold"}}""", id);
    }

    @Test
    void anUpdateThatLeavesTheUrlAloneKeepsTheSavedHeaders() {
        var id = savedServer();

        assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", "{\"requiresApproval\":true,\"headers\":{\"X-Tier\":\"silver\"}}"));

        assertStored("""
                {"url":"%s","headers":{"Authorization":"Bearer real-token","X-Tier":"silver"}}""".formatted(SAVED), id);
    }

    @Test
    void aSavedSecretsMaskSentWithANewHostIsRefusedAndTheServerStaysAsItWas() {
        var id = savedServer();

        // What the MCP Servers form sends when only the URL was edited: each untouched secret as its mask.
        var refused = PUT("/api/mcp-servers/" + id, "application/json", """
                {"url":"http://127.0.0.2:1/mcp","headers":{"Authorization":"Bear****","X-Tier":"****"}}""");

        assertStatus(400, refused);
        assertEquals(List.of("invalid_request", MOVED_REFUSAL),
                List.of(json(refused).get("code").getAsString(), json(refused).get("message").getAsString()));
        assertStored(SAVED_CONFIG, id);
    }

    @Test
    void aSavedSecretsMaskSentWithTheSameHostIsSwappedForTheSecret() {
        var id = savedServer();

        assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", """
                {"url":"http://127.0.0.1:1/v2/mcp","headers":{"Authorization":"Bear****","X-Tier":"****"}}"""));

        assertStored("""
                {"url":"http://127.0.0.1:1/v2/mcp","headers":{"Authorization":"Bearer real-token","X-Tier":"gold"}}""", id);
    }

    @Test
    void headersEnteredAgainWithANewHostAreSavedForIt() {
        var id = savedServer();

        assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", """
                {"url":"http://127.0.0.2:1/mcp","headers":{"Authorization":"Bearer for-the-new-host"}}"""));

        assertStored("""
                {"url":"http://127.0.0.2:1/mcp","headers":{"Authorization":"Bearer for-the-new-host"}}""", id);
    }

    @Test
    void aSwitchToStdioKeepsNoSavedHeaderAndRestoresNoMask() {
        var id = savedServer();

        var masked = PUT("/api/mcp-servers/" + id, "application/json", """
                {"transport":"STDIO","command":"node","env":{"API_KEY":"Bear****"}}""");
        assertStatus(400, masked);
        assertEquals("API_KEY holds a saved secret's mask rather than a value: enter the value again.",
                json(masked).get("message").getAsString());
        assertStored(SAVED_CONFIG, id);

        assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", "{\"transport\":\"STDIO\",\"command\":\"node\"}"));
        assertStored("{\"command\":\"node\"}", id);
    }

    @Test
    void aSwitchFromStdioToHttpKeepsNoSavedEnvValue() {
        var id = created("""
                {"name":"%s","enabled":false,"transport":"STDIO","command":"node","env":{"API_KEY":"sk-real-secret"}}"""
                .formatted(uniqueName()));

        assertIsOk(PUT("/api/mcp-servers/" + id, "application/json", "{\"transport\":\"HTTP\",\"url\":\"" + SAVED + "\"}"));

        assertStored("{\"url\":\"" + SAVED + "\"}", id);
    }

    // --- helpers ------------------------------------------------------------------------

    /** A disabled HTTP server at {@link #SAVED} with a secret header and a plain one. */
    private long savedServer() {
        return created("""
                {"name":"%s","enabled":false,"transport":"HTTP","url":"%s",
                 "headers":{"Authorization":"Bearer real-token","X-Tier":"gold"}}""".formatted(uniqueName(), SAVED));
    }

    private long created(String body) {
        var response = POST("/api/mcp-servers", "application/json", body);
        assertIsOk(response);
        var id = json(response).get("id").getAsLong();
        serverIds.add(id);
        return id;
    }

    private static String uniqueName() {
        return "jf291-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    /**
     * The row's decrypted config equals {@code expected} in a fresh transaction. It is read again for a few
     * seconds first, since the request's commit can reach another connection a moment after the response.
     */
    private static void assertStored(String expected, long id) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        var stored = storedConfig(id);
        while (!expected.equals(stored) && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
            stored = storedConfig(id);
        }
        assertEquals(expected, stored);
    }

    private static String storedConfig(long id) {
        return commit(() -> McpServer.<McpServer>findById(id).configJson);
    }

    private static <T> T commit(Supplier<T> block) {
        var ref = new AtomicReference<T>();
        var err = new AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        if (err.get() != null) throw new IllegalStateException(err.get());
        return ref.get();
    }
}
