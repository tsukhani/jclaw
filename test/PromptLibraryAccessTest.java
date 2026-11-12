import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import models.Prompt;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * The prompt library is shared (JF-314): every agent that has {@code jclaw_api} lists and reads every
 * prompt, and only the operator and {@code main} create, change or delete one. Driven end to end with
 * the bearer and agent header {@code jclaw_api} sends.
 *
 * <p>{@code main}'s half is {@code RequestPrincipalPolicyTest}'s, through {@code isOperatorOrMain}, the
 * check behind {@link RequestPrincipal#requireOperatorOrMain}: {@code main} is provisioned at boot,
 * where a sibling's {@code Fixtures.deleteDatabase} can remove it mid-suite.
 */
class PromptLibraryAccessTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";
    private static final Set<String> PROMPT_KEYS =
            Set.of("id", "title", "content", "tags", "category", "categoryLabel", "createdAt", "updatedAt");
    private static final Set<String> AGENT_LIST_ROW_KEYS = Set.of("id", "title", "tags", "category", "categoryLabel",
            "contentPreview", "contentTruncated", "createdAt", "updatedAt");

    private record Seed(Long agentId, String name) {}

    private Seed agent;
    /** In every title this test writes, so a list can be narrowed to this test's rows. */
    private String marker;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this
    // class's by its agent's unique name or its title's marker.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        marker = "jf314-" + UUID.randomUUID().toString().substring(0, 8);
        agent = seedAgent("jf314-agent-");
    }

    @AfterEach
    void cleanUp() {
        clearCookies();
        commit(() -> Prompt.delete("title LIKE ?1", marker + "%"));
    }

    // --- what a custom agent reaches ------------------------------------------------------

    @Test
    void aCustomAgentListsEveryPromptTheOperatorsIncluded() {
        long first = createAsOperator(marker + " first", "first content");
        long second = createAsOperator(marker + " second", "second content");

        var list = asAgent(() -> GET(agentRequest(agent.agentId()), "/api/prompts?title=" + marker));

        assertStatus(200, list);
        var expected = new ArrayList<>(List.of(first + ":" + marker + " first", second + ":" + marker + " second"));
        expected.sort(String::compareTo);
        var listed = idsAndTitles(list);
        listed.sort(String::compareTo);
        assertEquals(expected, listed);
    }

    @Test
    void aCustomAgentReadsAnyPromptWhole() {
        long id = createAsOperator(marker + " review", "the whole review rubric");

        var read = asAgent(() -> GET(agentRequest(agent.agentId()), "/api/prompts/" + id));

        assertStatus(200, read);
        assertEquals(List.of(marker + " review", "the whole review rubric"),
                List.of(json(read).get("title").getAsString(), json(read).get("content").getAsString()));
    }

    @Test
    void aCustomAgentCannotCreateAPrompt() {
        assertRefused("Only the operator or the main agent may create a prompt.",
                asAgent(() -> POST(agentRequest(agent.agentId()), "/api/prompts", "application/json",
                        promptBody(marker + " by the agent", "c", "CODING"))));

        assertEquals(0L, (long) commit(() -> Prompt.count("title = ?1", marker + " by the agent")));
    }

    @Test
    void aCustomAgentCannotChangeOrDeleteAPrompt() {
        long id = createAsOperator(marker + " operator", "operator content");

        assertRefused("Only the operator or the main agent may change a prompt.",
                asAgent(() -> PUT(agentRequest(agent.agentId()), "/api/prompts/" + id, "application/json",
                        "{\"title\":\"" + marker + " rewritten\",\"content\":\"rewritten\"}")));
        assertRefused("Only the operator or the main agent may delete a prompt.",
                asAgent(() -> DELETE(agentRequest(agent.agentId()), "/api/prompts/" + id)));

        assertEquals(List.of(marker + " operator", "operator content"), stored(id));
    }

    @Test
    void anUnknownPromptIsNotFoundForACustomAgentsRead() {
        assertStatus(404, asAgent(() -> GET(agentRequest(agent.agentId()), "/api/prompts/999999999")));
    }

    @Test
    void anUnstampedBearerCreatesNoPrompt() {
        assertRefused("Only the operator or the main agent may create a prompt.",
                asAgent(() -> POST(bareBearerRequest(), "/api/prompts", "application/json",
                        promptBody(marker + " unstamped", "c", "CODING"))));

        assertEquals(0L, (long) commit(() -> Prompt.count("title = ?1", marker + " unstamped")));
    }

    // --- the shape of a prompt ------------------------------------------------------------

    @Test
    void aPromptCarriesNoOwnerAndAnOwnerInTheBodyIsNotStored() {
        login();
        var created = POST("/api/prompts", "application/json", "{\"title\":\"" + marker + " owned\",\"content\":\"c\","
                + "\"category\":\"CODING\",\"ownerAgentId\":" + agent.agentId() + "}");
        assertStatus(200, created);
        long id = json(created).get("id").getAsLong();

        var updated = PUT("/api/prompts/" + id, "application/json", "{\"ownerAgentId\":" + agent.agentId() + "}");
        assertStatus(200, updated);
        var read = GET("/api/prompts/" + id);

        assertEquals(List.of(PROMPT_KEYS, PROMPT_KEYS, PROMPT_KEYS),
                List.of(json(created).keySet(), json(updated).keySet(), json(read).keySet()));
        assertEquals(List.of(marker + " owned", "c"), stored(id));
    }

    @Test
    void aCustomAgentsListRowCarriesAPreviewAndNoOwner() {
        createAsOperator(marker + " row", "row content");

        var list = asAgent(() -> GET(agentRequest(agent.agentId()), "/api/prompts?title=" + marker));

        assertStatus(200, list);
        assertEquals(AGENT_LIST_ROW_KEYS, rows(list).get(0).getAsJsonObject().keySet());
    }

    // --- the operator ---------------------------------------------------------------------

    @Test
    void theOperatorCreatesChangesAndDeletesAPrompt() {
        long id = createAsOperator(marker + " operator", "operator content");

        login();
        var edited = PUT("/api/prompts/" + id, "application/json", "{\"content\":\"edited by the operator\"}");
        assertStatus(200, edited);
        assertEquals(List.of(marker + " operator", "edited by the operator"), stored(id));

        var deleted = DELETE("/api/prompts/" + id);
        assertStatus(200, deleted);
        assertEquals("{\"status\":\"ok\",\"deleted\":true}", getContent(deleted));
        assertNull(stored(id));
    }

    @Test
    void deletingAnAgentLeavesThePromptsAsTheyAre() {
        long id = createAsOperator(marker + " kept", "kept content");

        login();
        assertStatus(200, DELETE("/api/agents/" + agent.agentId()));

        assertNull(commit(() -> Agent.findById(agent.agentId())));
        assertEquals(List.of(marker + " kept", "kept content"), stored(id));
    }

    // --- helpers ------------------------------------------------------------------------

    private static void assertRefused(String message, Http.Response response) {
        assertStatus(403, response);
        assertEquals(List.of(AGENT_SCOPE, message), codeAndMessage(response), getContent(response));
    }

    private static List<String> codeAndMessage(Http.Response response) {
        var body = json(response);
        return List.of(body.get("code").getAsString(), body.get("message").getAsString());
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static JsonArray rows(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonArray();
    }

    private static List<String> idsAndTitles(Http.Response response) {
        var out = new ArrayList<String>();
        for (var row : rows(response)) {
            var o = row.getAsJsonObject();
            out.add(o.get("id").getAsLong() + ":" + o.get("title").getAsString());
        }
        return out;
    }

    private static String promptBody(String title, String content, String category) {
        var body = new JsonObject();
        body.addProperty("title", title);
        body.addProperty("content", content);
        body.addProperty("category", category);
        return body.toString();
    }

    private long createAsOperator(String title, String content) {
        login();
        try {
            var created = POST("/api/prompts", "application/json", promptBody(title, content, "CODING"));
            assertStatus(200, created);
            return json(created).get("id").getAsLong();
        } finally {
            clearCookies();
        }
    }

    /** The stored title and content, or null when the row is gone. */
    private static List<String> stored(long id) {
        return commit(() -> {
            Prompt row = Prompt.findById(id);
            return row == null ? null : List.of(row.title, row.content);
        });
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    private static Seed seedAgent(String prefix) {
        var name = prefix + UUID.randomUUID().toString().substring(0, 8);
        var id = commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            return agent.id;
        });
        return new Seed(id, name);
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
