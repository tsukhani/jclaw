import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The instance's setup, its host and lifecycle status and the routes that spend on an outside
 * service answer only the operator and {@code main} (JF-292), driven end to end with the bearer and
 * agent header {@code jclaw_api} sends.
 *
 * <p>Each route is paired with the operator's answer on it, since a broken header would 403 every
 * caller; the routes whose operator path reaches the network are left to their own controller
 * tests. {@code main}'s half is in {@code RequestPrincipalPolicyTest}: every route here asks
 * {@code isOperatorOrMain}, and {@code main} is provisioned at boot, where a sibling's
 * {@code Fixtures.deleteDatabase} can remove it mid-suite.
 */
class MainOnlyRoutesTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";
    private static final String NO_PROVIDER = "jf292-no-such-provider";

    /**
     * @param action         what the refusal says only the operator and main may do
     * @param operatorStatus the operator's status on the same request, or null where answering it
     *                       would dial out
     */
    private record Route(String method, String path, String action, Integer operatorStatus) {}

    private static final List<Route> MAIN_ONLY = List.of(
            // Instance setup.
            new Route("GET", "/api/mcp-servers", "read the MCP servers", 200),
            new Route("GET", "/api/mcp-servers/999999999", "read the MCP servers", 404),
            new Route("GET", "/api/config", "read the configuration", 200),
            new Route("GET", "/api/config/jf292.no.such.key", "read the configuration", 404),
            new Route("GET", "/api/logging/levels", "read the configuration", 200),
            new Route("GET", "/api/channels", "read the channel configurations", 200),
            new Route("GET", "/api/providers", "read the providers or their models", 200),
            new Route("GET", "/api/providers/" + NO_PROVIDER + "/models", "read the providers or their models", 404),
            new Route("GET", "/api/router/status", "read the model router's status and usage", null),
            new Route("GET", "/api/router/usage/ollama-cloud?range=1y", "read the model router's status and usage", 400),
            new Route("GET", "/api/subagents/acp-harnesses", "read the coding harness commands", 200),
            new Route("GET", "/api/subagents/acp-command", "read the coding harness commands", 200),
            // The operator's own UI state (JF-293); the operator's empty body is refused for its missing step.
            new Route("POST", "/api/onboarding/tour-progress", "record the guided tour's progress", 400),
            // Routes that spend on, or dial, an outside service.
            new Route("POST", "/api/mcp-servers/999999999/test", "test an MCP server", 404),
            new Route("POST", "/api/prompts/generate", "generate a prompt", 400),
            new Route("GET", "/api/prompts/export", "export the prompt library", 200),
            // The shared prompt library: every agent reads it, the operator and main change it (JF-314).
            new Route("POST", "/api/prompts", "create a prompt", 400),
            new Route("PUT", "/api/prompts/999999999", "change a prompt", 404),
            new Route("DELETE", "/api/prompts/999999999", "delete a prompt", 404),
            new Route("POST", "/api/providers/" + NO_PROVIDER + "/discover-models",
                    "probe a provider or discover its models", 400),
            new Route("GET", "/api/providers/" + NO_PROVIDER + "/video-models",
                    "probe a provider or discover its models", 400),
            new Route("GET", "/api/providers/" + NO_PROVIDER + "/embedding-models",
                    "probe a provider or discover its models", 404),
            new Route("GET", "/api/providers/" + NO_PROVIDER + "/reachable",
                    "probe a provider or discover its models", 200),
            new Route("POST", "/api/providers/" + NO_PROVIDER + "/embedding-probe",
                    "probe a provider or discover its models", 404),
            new Route("POST", "/api/providers/refresh-prices", "refresh the model prices", null),
            new Route("GET", "/api/printers/options", "read a printer's options", 200),
            // The host and the instance's lifecycle.
            new Route("GET", "/api/metrics/latency", "read the instance's metrics", 200),
            new Route("GET", "/api/metrics/db-pool", "read the instance's metrics", 200),
            new Route("GET", "/api/metrics/jvm", "read the instance's metrics", 200),
            new Route("GET", "/api/metrics/logs", "read the instance's metrics", 200),
            new Route("GET", "/api/breakers", "read the instance's metrics", 200),
            new Route("GET", "/api/workspace/stats", "read the instance's metrics", 200),
            new Route("GET", "/api/system/restart", "read the restart and upgrade status", 200),
            new Route("GET", "/api/system/upgrade", "read the restart and upgrade status", null),
            new Route("GET", "/api/system/upgrade/status", "read the restart and upgrade status", 204),
            new Route("GET", "/api/system/java-runtime", "read the restart and upgrade status", null));

    private Long customAgentId;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and the one row here is this
    // class's by its unique name.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        var name = "jf292-custom-" + UUID.randomUUID().toString().substring(0, 8);
        customAgentId = commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    @Test
    void aCustomAgentIsRefusedEveryMainOnlyRoute() {
        var wrong = new ArrayList<String>();
        for (var route : MAIN_ONLY) {
            var resp = asAgent(() -> send(agentRequest(customAgentId), route));
            var got = describe(resp);
            var expected = "403 " + AGENT_SCOPE + " Only the operator or the main agent may " + route.action() + ".";
            if (!expected.equals(got)) wrong.add(route.method() + " " + route.path() + " -> " + got);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void anUnstampedBearerIsRefusedEveryMainOnlyRoute() {
        var wrong = new ArrayList<String>();
        for (var route : MAIN_ONLY) {
            var resp = asAgent(() -> send(bareBearerRequest(), route));
            var got = describe(resp);
            var expected = "403 " + AGENT_SCOPE + " Only the operator or the main agent may " + route.action() + ".";
            if (!expected.equals(got)) wrong.add(route.method() + " " + route.path() + " -> " + got);
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void theOperatorIsAnsweredOnEveryMainOnlyRoute() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
        var wrong = new ArrayList<String>();
        for (var route : MAIN_ONLY) {
            if (route.operatorStatus() == null) continue;
            var resp = switch (route.method()) {
                case "POST" -> POST(route.path(), "application/json", "{}");
                case "PUT" -> PUT(route.path(), "application/json", "{}");
                case "DELETE" -> DELETE(route.path());
                default -> GET(route.path());
            };
            if (!route.operatorStatus().equals(resp.status)) {
                wrong.add(route.method() + " " + route.path() + " -> " + describe(resp));
            }
        }
        assertEquals(List.of(), wrong);
    }

    @Test
    void aCustomAgentStillReadsTheCatalogsEveryAgentShares() {
        for (var path : List.of("/api/timezones", "/api/prompts/categories", "/api/prompts", "/api/slash-commands",
                "/api/status", "/api/onboarding/tour-status")) {
            assertStatus(200, asAgent(() -> GET(agentRequest(customAgentId), path)));
        }
    }

    // --- helpers ------------------------------------------------------------------------

    /** {@code "<status> <code> <message>"} for an error body, and the status alone otherwise. */
    private static String describe(Http.Response response) {
        var body = getContent(response);
        try {
            var json = JsonParser.parseString(body);
            if (json.isJsonObject() && json.getAsJsonObject().has("code") && json.getAsJsonObject().has("message")) {
                return response.status + " " + json.getAsJsonObject().get("code").getAsString() + " "
                        + json.getAsJsonObject().get("message").getAsString();
            }
        } catch (RuntimeException _) {
            // Not JSON: the status is the whole answer.
        }
        return String.valueOf(response.status);
    }

    private static Http.Response send(Http.Request request, Route route) {
        return switch (route.method()) {
            case "POST" -> POST(request, route.path(), "application/json", "{}");
            case "PUT" -> PUT(request, route.path(), "application/json", "{}");
            case "DELETE" -> DELETE(request, route.path());
            default -> GET(request, route.path());
        };
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

    private static Http.Request agentRequest(Long agentId) {
        var request = bareBearerRequest();
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** A bearer with no agent stamp, a token used outside the tool. */
    private static Http.Request bareBearerRequest() {
        var request = newRequest();
        var token = AuthFixture.seedBearerToken();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + token));
        return request;
    }

    /** A bearer call answers with a Set-Cookie carrying the agent principal, and FunctionalTest's
     *  saved cookies are JVM-global, so one left behind would 403 a sibling class. */
    private Http.Response asAgent(Supplier<Http.Response> call) {
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }
}
