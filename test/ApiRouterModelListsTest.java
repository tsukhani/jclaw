import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import llm.ProviderRegistry;
import llm.routing.RouterPolicy;
import llm.routing.TaskClass;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.ConfigService;
import services.Tx;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * {@code PUT /api/router/models/{taskClass}}: the one router write open to an agent, and to no agent but
 * {@code main}.
 *
 * <p>Holds {@link JevBreakerTestSync}, as every class writing the process-global {@code router.*} lists does.
 * {@code main} itself is not driven over HTTP: it is provisioned at boot, and an earlier class's
 * {@code Fixtures.deleteDatabase} can remove it, so {@code RequestPrincipalPolicyTest} pins the
 * {@code isOperatorOrMain} matrix this route asks. Here the operator is allowed and a custom agent refused on
 * the same route, which a blanket refusal could not fake.
 */
class ApiRouterModelListsTest extends FunctionalTest {

    private static final String TEST_PASSWORD = "testpass-123";
    private static final String CHAT = RouterPolicy.modelsKey(TaskClass.CHAT);
    private static final String REASONING = RouterPolicy.modelsKey(TaskClass.REASONING);
    private static final String SHAPE =
            "models must be a JSON array of {\"provider\":\"…\",\"model\":\"…\"} objects; send [] to clear the list.";

    private String provider;
    private Long customAgentId;

    @BeforeEach
    void seed() {
        JevBreakerTestSync.acquire();
        provider = "rml-" + UUID.randomUUID().toString().substring(0, 8);
        customAgentId = commitInFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", "http://127.0.0.1:1/v1");
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models", "[{\"id\":\"m1\",\"name\":\"M1\",\"contextWindow\":1000},"
                    + "{\"id\":\"m2\",\"name\":\"M2\",\"contextWindow\":1000}]");
            ConfigService.set(CHAT, list("m1"));
            return customAgent().id;
        });
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the provider must register");
        AuthFixture.seedAdminPassword(TEST_PASSWORD);
        login();
    }

    @AfterEach
    void cleanUp() {
        try {
            clearCookies();
            AuthFixture.clearAdminPassword();
            commitInFreshTx(() -> {
                for (var c : TaskClass.values()) ConfigService.delete(RouterPolicy.modelsKey(c));
                for (var suffix : List.of(".baseUrl", ".apiKey", ".models")) ConfigService.delete("provider." + provider + suffix);
                return null;
            });
        } finally {
            JevBreakerTestSync.release();
        }
    }

    @Test
    void theOperatorReplacesAClassListInTheOrderGiven() {
        var resp = PUT("/api/router/models/reasoning", "application/json", body("m2", "m1"));

        assertIsOk(resp);
        assertEquals(JsonParser.parseString("{\"taskClass\":\"reasoning\",\"models\":" + list("m2", "m1") + "}"),
                JsonParser.parseString(getContent(resp)));
        assertEquals(list("m2", "m1"), storedValue(REASONING));
    }

    @Test
    void aModelListedTwiceIsStoredOnceWhereItFirstAppears() {
        var resp = PUT("/api/router/models/reasoning", "application/json", body("m2", "m1", "m2"));

        assertIsOk(resp);
        assertEquals(list("m2", "m1"), storedValue(REASONING));
    }

    @Test
    void anEmptyListClearsTheClassAsThePanelDoes() {
        assertIsOk(PUT("/api/router/models/reasoning", "application/json", body("m2")));

        var resp = PUT("/api/router/models/reasoning", "application/json", "{\"models\":[]}");

        assertIsOk(resp);
        assertEquals(JsonParser.parseString("{\"taskClass\":\"reasoning\",\"models\":[]}"),
                JsonParser.parseString(getContent(resp)));
        assertNull(storedValue(REASONING), "an emptied class keeps no row, so it uses the chat list");
    }

    @Test
    void aCustomAgentIsRefusedAndTheListIsUnchanged() {
        var resp = asAgent(() -> PUT(agentRequest(customAgentId), "/api/router/models/chat", "application/json", body("m2")));

        assertStatus(403, resp);
        assertEquals("agent_scope", field(resp, "code"));
        assertEquals("Only the operator or the main agent may change the model router's lists.", field(resp, "message"));
        login();
        assertEquals(list("m1"), storedValue(CHAT));
    }

    @Test
    void anAgentRequestNamingNoAgentIsRefused() {
        var resp = asAgent(() -> PUT(bareBearerRequest(), "/api/router/models/chat", "application/json", body("m2")));

        assertStatus(403, resp);
        assertEquals("agent_scope", field(resp, "code"));
        login();
        assertEquals(list("m1"), storedValue(CHAT));
    }

    @Test
    void aModelItsProviderDoesNotRegisterIsRefusedWithTheReason() {
        var resp = PUT("/api/router/models/chat", "application/json", body("m2", "m9"));

        assertStatus(400, resp);
        assertEquals("invalid_request", field(resp, "code"));
        assertEquals("Provider '" + provider + "' has no model with id 'm9'.", field(resp, "message"));
        assertEquals(list("m1"), storedValue(CHAT));
    }

    @Test
    void anUnknownTaskClassIsRefusedNamingTheClasses() {
        var classes = Arrays.stream(TaskClass.values()).map(TaskClass::id).collect(Collectors.joining(", "));

        var resp = PUT("/api/router/models/vision", "application/json", body("m1"));

        assertStatus(400, resp);
        assertEquals("invalid_request", field(resp, "code"));
        assertEquals("taskClass must be one of " + classes + "; got 'vision'.", field(resp, "message"));
    }

    @Test
    void aBodyThatIsNotAListOfProviderModelPairsIsRefused() {
        for (var bad : List.of("{}", "{\"models\":\"m1\"}", "{\"models\":[\"m1\"]}",
                "{\"models\":[{\"provider\":\"" + provider + "\"}]}",
                "{\"models\":[{\"provider\":\"" + provider + "\",\"model\":\" \"}]}")) {
            var resp = PUT("/api/router/models/chat", "application/json", bad);
            assertEquals(400, resp.status, bad);
            assertEquals(SHAPE, field(resp, "message"), bad);
        }
        assertEquals(list("m1"), storedValue(CHAT));
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"%s\"}".formatted(TEST_PASSWORD)));
    }

    /** The stored list as the operator's panel reads it, or null when the class has no row. */
    private static String storedValue(String key) {
        var resp = GET("/api/config/" + key);
        if (resp.status == 404) return null;
        assertIsOk(resp);
        var value = JsonParser.parseString(getContent(resp)).getAsJsonObject().get("value");
        return value.isJsonNull() ? null : value.getAsString();
    }

    private String list(String... models) {
        return Arrays.stream(models)
                .map(m -> "{\"provider\":\"" + provider + "\",\"model\":\"" + m + "\"}")
                .collect(Collectors.joining(",", "[", "]"));
    }

    private String body(String... models) {
        return "{\"models\":" + list(models) + "}";
    }

    private static String field(Http.Response resp, String name) {
        return JsonParser.parseString(getContent(resp)).getAsJsonObject().get(name).getAsString();
    }

    private static Agent customAgent() {
        var existing = Agent.findByName("router-lists-test-agent");
        if (existing != null) return existing;
        var agent = new Agent();
        agent.name = "router-lists-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "gpt-4.1";
        agent.enabled = true;
        agent.save();
        return agent;
    }

    /** A bearer request stamped with the calling agent, as {@code JClawApiTool} builds it. */
    private static Http.Request agentRequest(Long agentId) {
        var request = bareBearerRequest();
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    private static Http.Request bareBearerRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }

    /** Without the operator's session cookie, which FunctionalTest folds into every request; the bearer's own
     *  agent cookie is dropped after, so it 403s no later request. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        clearCookies();
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
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
