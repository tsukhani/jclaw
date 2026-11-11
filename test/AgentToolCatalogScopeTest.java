import agents.ToolAction;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import controllers.ApiToolsController;
import controllers.RequestPrincipal;
import models.Agent;
import models.AgentToolConfig;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The tool catalogs show an agent other than {@code main} only the MCP servers it may call (JF-293), driven with
 * the bearer and agent header {@code jclaw_api} sends. A server's handle names a service the operator connected,
 * and its actions say what that service does.
 *
 * <p>Each case reads the operator's answer beside the agent's, so the built-in catalog is shown unchanged and a
 * broken header cannot pass for a filter. {@code main} is provisioned at boot, where a sibling's
 * {@code Fixtures.deleteDatabase} can remove it mid-suite, so its half is the policy call at the end.
 *
 * <p>Holds {@link ToolRegistrySync}: the two servers here are published into the process-wide registry.
 */
class AgentToolCatalogScopeTest extends FunctionalTest {

    private static final List<ToolAction> ACTIONS = List.of(
            new ToolAction("create_issue", "Create an issue."),
            new ToolAction("search_issues", "Search issues."));

    /** A registry entry carrying only what the catalogs and the single read report; nothing executes it. */
    private record CatalogTool(String name, String group, boolean isServerLevel, String shortDescription,
                               String description, List<ToolAction> actions) implements ToolRegistry.Tool {
        @Override
        public String category() {
            return "MCP";
        }

        @Override
        public String icon() {
            return "plug";
        }

        @Override
        public Map<String, Object> parameters() {
            return Map.of("type", "object");
        }

        @Override
        public String execute(String argsJson, Agent agent) {
            throw new AssertionError("a catalog test runs no tool");
        }
    }

    private Long agentId;
    /** The server the operator switched on for the agent, and the one left off. */
    private String granted;
    private String withheld;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and the rows here are this
    // class's by its agent's unique name.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        granted = "jf293-on-" + suffix;
        withheld = "jf293-off-" + suffix;
        ToolRegistrySync.canonicalForTest();
        publishServer(granted);
        publishServer(withheld);
        agentId = commit(() -> {
            var agent = new Agent();
            agent.name = "jf293-catalog-" + suffix;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var grant = new AgentToolConfig();
            grant.agent = agent;
            grant.toolName = handle(granted);
            grant.enabled = true;
            grant.save();
            return agent.id;
        });
        ToolRegistry.clearDisabledToolsCache();
    }

    @AfterEach
    void release() {
        ToolRegistry.unpublishExternal(granted);
        ToolRegistry.unpublishExternal(withheld);
        ToolRegistrySync.release();
        clearCookies();
    }

    @Test
    void anAgentsCatalogsListTheBuiltInToolsAndOnlyTheServersItMayCall() {
        for (var url : List.of("/api/tools", "/api/tools/meta", "/api/agents/" + agentId + "/tools")) {
            var operators = asOperator(() -> rows(GET(url)));
            assertTrue(names(operators).containsAll(List.of("filesystem", handle(granted), handle(withheld))),
                    "premise: the operator's " + url + " lists a built-in tool and both servers: " + names(operators));

            var agents = rows(asAgent(() -> GET(agentRequest(agentId), url)));

            assertEquals(shownTo(operators, granted), agents, url);
        }
    }

    @Test
    void aServerTheAgentMayNotCallReadsByNameAsAnUnknownToolDoes() {
        var unknown = "jf293_no_such_tool";
        var notFound = asAgent(() -> GET(agentRequest(agentId), "/api/tools/" + unknown));
        assertEquals(404, notFound.status.intValue());
        assertEquals(List.of("not_found", "No tool named '" + unknown + "' is registered."), codeAndMessage(notFound));

        for (var name : List.of(handle(withheld), handle(withheld) + "_create_issue")) {
            var resp = asAgent(() -> GET(agentRequest(agentId), "/api/tools/" + name));

            assertEquals(404, resp.status.intValue(), name);
            assertEquals(getContent(notFound).replace(unknown, name), getContent(resp));
        }
    }

    @Test
    void aServerTheAgentMayCallAndABuiltInToolReadByNameAsTheOperatorReadsThem() {
        for (var name : List.of(handle(granted), handle(granted) + "_create_issue", "filesystem", "jclaw_api")) {
            var operators = asOperator(() -> GET("/api/tools/" + name));
            assertEquals(200, operators.status.intValue(), name);

            var agents = asAgent(() -> GET(agentRequest(agentId), "/api/tools/" + name));

            assertEquals(200, agents.status.intValue(), name);
            assertEquals(getContent(operators), getContent(agents), name);
        }
    }

    @Test
    void anAgentOriginatedRequestThatNamesNoAgentIsShownNoServer() {
        for (var url : List.of("/api/tools", "/api/tools/meta")) {
            var operators = asOperator(() -> rows(GET(url)));

            var unstamped = rows(asAgent(() -> GET(bareBearerRequest(), url)));

            assertEquals(shownTo(operators, null), unstamped, url);
        }
        assertEquals(404, asAgent(() -> GET(bareBearerRequest(), "/api/tools/" + handle(granted))).status.intValue());
    }

    @Test
    void theOperatorReadsAServerNoAgentWasGranted() {
        var resp = asOperator(() -> GET("/api/tools/" + handle(withheld)));

        assertEquals(200, resp.status.intValue());
        assertEquals("MCP server " + withheld + ", in full.",
                JsonParser.parseString(getContent(resp)).getAsJsonObject().get("description").getAsString());
    }

    @Test
    void theOperatorAndMainAreShownEveryServerAndAnyOtherAgentItsOwn() {
        var main = new Agent();
        main.name = Agent.MAIN_AGENT_NAME;
        var custom = commit(() -> (Agent) Agent.findById(agentId));

        assertNull(ApiToolsController.mcpServersShownTo(false, null), "the operator: no filter");
        assertNull(ApiToolsController.mcpServersShownTo(true, main), "main: no filter");
        assertEquals(Set.of(granted), commit(() -> ApiToolsController.mcpServersShownTo(true, custom)));
        assertEquals(Set.of(), ApiToolsController.mcpServersShownTo(true, null), "an agent that names itself nowhere");
    }

    // --- helpers ------------------------------------------------------------------------

    private static String handle(String server) {
        return "mcp_" + server;
    }

    /** An MCP-shaped group: its server-level handle listing {@link #ACTIONS}, and one adapter per action. */
    private static void publishServer(String server) {
        var tools = new ArrayList<ToolRegistry.Tool>();
        tools.add(new CatalogTool(handle(server), server, true, "Handle for " + server + ".",
                "MCP server " + server + ", in full.", ACTIONS));
        for (var action : ACTIONS) {
            tools.add(new CatalogTool(handle(server) + "_" + action.name(), server, false, action.description(),
                    action.description(), List.of(action)));
        }
        ToolRegistry.publishExternal(server, tools);
    }

    /** {@code rows} as an agent that may call {@code server} alone is shown them: every built-in tool, and that server. */
    private static JsonArray shownTo(JsonArray rows, @Nullable String server) {
        var shown = new JsonArray();
        for (var row : rows) {
            var group = row.getAsJsonObject().get("group");
            if (group.isJsonNull() || group.getAsString().equals(server)) shown.add(row);
        }
        return shown;
    }

    private static JsonArray rows(Http.Response response) {
        assertEquals(200, response.status.intValue(), getContent(response));
        return JsonParser.parseString(getContent(response)).getAsJsonArray();
    }

    private static List<String> names(JsonArray rows) {
        var out = new ArrayList<String>();
        for (var row : rows) out.add(row.getAsJsonObject().get("name").getAsString());
        return out;
    }

    private static List<String> codeAndMessage(Http.Response response) {
        var json = JsonParser.parseString(getContent(response)).getAsJsonObject();
        return List.of(json.get("code").getAsString(), json.get("message").getAsString());
    }

    private <T> T asOperator(Supplier<T> call) {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
        try {
            return call.get();
        } finally {
            clearCookies();
        }
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
