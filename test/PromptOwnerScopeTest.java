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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * An agent other than {@code main} reaches only the prompts it owns (JF-292), driven end to end with
 * the bearer and agent header {@code jclaw_api} sends. Who owns a new prompt (JF-307) and who changes
 * a prompt's owner (JF-308) are {@code PromptOwnerChoiceTest}'s.
 *
 * <p>Each case pairs a refusal with a permission on the same route, since a broken header would 403
 * every caller. {@code main}'s half is in {@code RequestPrincipalPolicyTest}: the row check is
 * {@code mayReachRow} with the prompt's owner, a null owner included, and the list pin is
 * {@code scopedAgentId}, and {@code main} is provisioned at boot, where a sibling's
 * {@code Fixtures.deleteDatabase} can remove it mid-suite.
 */
class PromptOwnerScopeTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";
    private static final String UNSCOPED = "This request is agent-originated but names no agent, so it cannot be scoped.";

    private record Seed(Long agentId, String name) {}

    private Seed own;
    private Seed alien;
    /** In every title this test writes, so the operator's list can be narrowed to this test's rows. */
    private String marker;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this
    // class's by its agent's unique name or its title's marker.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        marker = "jf292-" + UUID.randomUUID().toString().substring(0, 8);
        own = seedAgent("jf292-own-");
        alien = seedAgent("jf292-alien-");
    }

    @AfterEach
    void cleanUp() {
        clearCookies();
        commit(() -> Prompt.delete("title LIKE ?1", marker + "%"));
    }

    // --- one prompt ---------------------------------------------------------------------

    @Test
    void aPromptAnAgentCreatesIsThatAgentsAndItReadsItWhole() {
        var created = asAgent(() -> POST(agentRequest(own.agentId()), "/api/prompts", "application/json",
                promptBody(marker + " mine", "own content", "CODING")));
        assertStatus(200, created);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(created)));
        long id = json(created).get("id").getAsLong();

        var read = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/" + id));
        assertStatus(200, read);
        assertEquals(List.of(marker + " mine", "own content", String.valueOf(own.agentId()), own.name()),
                List.of(json(read).get("title").getAsString(), json(read).get("content").getAsString(),
                        owner(json(read)).get(0), owner(json(read)).get(1)));
    }

    @Test
    void anotherAgentCannotReadChangeOrDeleteAnAgentsPrompt() {
        long id = createAs(own.agentId(), marker + " mine", "own content");
        var refusal = "Prompt " + id + " belongs to another agent.";

        assertRefused(refusal, asAgent(() -> GET(agentRequest(alien.agentId()), "/api/prompts/" + id)));
        assertRefused(refusal, asAgent(() -> PUT(agentRequest(alien.agentId()), "/api/prompts/" + id,
                "application/json", "{\"title\":\"" + marker + " hijacked\",\"content\":\"hijacked\"}")));
        assertRefused(refusal, asAgent(() -> DELETE(agentRequest(alien.agentId()), "/api/prompts/" + id)));

        assertEquals(List.of(marker + " mine", "own content"), stored(id));
    }

    @Test
    void anAgentChangesAndDeletesItsOwnPrompt() {
        long id = createAs(own.agentId(), marker + " mine", "own content");

        var updated = asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id, "application/json",
                "{\"content\":\"edited\"}"));
        assertStatus(200, updated);
        assertEquals(List.of("edited", String.valueOf(own.agentId()), own.name()),
                List.of(json(updated).get("content").getAsString(), owner(json(updated)).get(0),
                        owner(json(updated)).get(1)));

        var deleted = asAgent(() -> DELETE(agentRequest(own.agentId()), "/api/prompts/" + id));
        assertStatus(200, deleted);
        assertEquals("{\"status\":\"ok\",\"deleted\":true}", getContent(deleted));
        assertNull(stored(id));
    }

    @Test
    void anUpdateCannotGiveAnAgentsPromptToAnotherAgent() {
        long id = createAs(own.agentId(), marker + " mine", "own content");

        assertRefused("An agent other than main cannot change a prompt's owner: leave ownerAgentId out, or send its own id.",
                asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id, "application/json",
                        "{\"content\":\"given away\",\"ownerAgentId\":" + alien.agentId()
                                + ",\"ownerAgentName\":\"" + alien.name() + "\"}")));
        assertEquals(List.of(marker + " mine", "own content"), stored(id));

        assertRefused("Prompt " + id + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(alien.agentId()), "/api/prompts/" + id)));
        var read = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/" + id));
        assertStatus(200, read);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(read)));
    }

    @Test
    void anUnknownPromptIsNotFoundForAnAgent() {
        assertStatus(404, asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/999999999")));
        assertStatus(404, asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/999999999",
                "application/json", "{\"content\":\"x\"}")));
        assertStatus(404, asAgent(() -> DELETE(agentRequest(own.agentId()), "/api/prompts/999999999")));
    }

    // --- the operator's prompts ---------------------------------------------------------

    @Test
    void aPromptWithNoOwnerIsHiddenFromACustomAgent() {
        long id = createAsOperator(marker + " operator", "operator content");
        var refusal = "Prompt " + id + " is the operator's; an agent other than main reaches only its own prompts.";

        assertRefused(refusal, asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/" + id)));
        assertRefused(refusal, asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id,
                "application/json", "{\"content\":\"hijacked\"}")));
        assertRefused(refusal, asAgent(() -> DELETE(agentRequest(own.agentId()), "/api/prompts/" + id)));
        assertEquals(List.of(marker + " operator", "operator content"), stored(id));

        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals("[]", getContent(list));
    }

    @Test
    void theOperatorsOwnPromptHasNoOwner() {
        long id = createAsOperator(marker + " operator", "operator content");

        login();
        var read = GET("/api/prompts/" + id);
        assertStatus(200, read);
        assertEquals(Arrays.asList(null, null), owner(json(read)));
    }

    @Test
    void theOperatorSeesEditsAndDeletesAnAgentsPromptAndSeesWhoOwnsIt() {
        long mine = createAs(own.agentId(), marker + " mine", "own content");
        long operators = createAsOperator(marker + " operator", "operator content");

        login();
        var list = GET("/api/prompts?title=" + marker);
        assertStatus(200, list);
        var owners = new ArrayList<String>();
        for (var row : rows(list)) {
            var o = row.getAsJsonObject();
            owners.add(o.get("id").getAsLong() + ":" + owner(o).get(1));
        }
        owners.sort(String::compareTo);
        var expected = new ArrayList<>(List.of(mine + ":" + own.name(), operators + ":null"));
        expected.sort(String::compareTo);
        assertEquals(expected, owners);

        var edited = PUT("/api/prompts/" + mine, "application/json", "{\"content\":\"edited by the operator\"}");
        assertStatus(200, edited);
        assertEquals(List.of("edited by the operator", String.valueOf(own.agentId()), own.name()),
                List.of(json(edited).get("content").getAsString(), owner(json(edited)).get(0),
                        owner(json(edited)).get(1)));

        assertStatus(200, DELETE("/api/prompts/" + mine));
        assertNull(stored(mine));
    }

    // --- lists --------------------------------------------------------------------------

    @Test
    void anAgentsListHoldsOnlyItsOwnPrompts() {
        long mine = createAs(own.agentId(), marker + " mine", "own content");
        createAs(alien.agentId(), marker + " theirs", "alien content");
        createAsOperator(marker + " operator", "operator content");

        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals(List.of(mine + ":" + marker + " mine"), idsAndTitles(list));

        // The title filter narrows the caller's rows; it never widens them to another owner's.
        var theirs = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts?title=theirs"));
        assertStatus(200, theirs);
        assertEquals("[]", getContent(theirs));
    }

    @Test
    void anAgentsListRowNamesItsOwner() {
        createAs(own.agentId(), marker + " mine", "own content");

        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(rows(list).get(0).getAsJsonObject()));
    }

    // --- an unidentified agent ----------------------------------------------------------

    @Test
    void anUnstampedBearerReachesNoPromptAndCreatesNone() {
        long id = createAsOperator(marker + " operator", "operator content");

        var list = asAgent(() -> GET(bareBearerRequest(), "/api/prompts"));
        assertStatus(403, list);
        assertEquals(List.of(AGENT_SCOPE, UNSCOPED), codeAndMessage(list));

        assertRefused("Prompt " + id + " is the operator's; an agent other than main reaches only its own prompts.",
                asAgent(() -> GET(bareBearerRequest(), "/api/prompts/" + id)));

        var created = asAgent(() -> POST(bareBearerRequest(), "/api/prompts", "application/json",
                promptBody(marker + " unstamped", "c", "CODING")));
        assertStatus(403, created);
        assertEquals(List.of(AGENT_SCOPE, UNSCOPED), codeAndMessage(created));
        assertEquals(0L, (long) commit(() -> Prompt.count("title = ?1", marker + " unstamped")));
    }

    // --- deleting the owner -------------------------------------------------------------

    @Test
    void deletingAnAgentKeepsItsPromptsAndLeavesThemWithTheOperator() {
        long id = createAs(own.agentId(), marker + " mine", "own content");
        login();
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(GET("/api/prompts/" + id))));

        assertStatus(200, DELETE("/api/agents/" + own.agentId()));
        assertNull(commit(() -> Agent.findById(own.agentId())));

        var read = GET("/api/prompts/" + id);
        assertStatus(200, read);
        assertEquals(Arrays.asList(marker + " mine", "own content", null, null),
                Arrays.asList(json(read).get("title").getAsString(), json(read).get("content").getAsString(),
                        owner(json(read)).get(0), owner(json(read)).get(1)));
        clearCookies();

        assertRefused("Prompt " + id + " is the operator's; an agent other than main reaches only its own prompts.",
                asAgent(() -> GET(agentRequest(alien.agentId()), "/api/prompts/" + id)));
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

    /** A prompt row's owner as {@code [id, name]}, each null when the wire carries none. */
    private static List<String> owner(JsonObject row) {
        return Arrays.asList(text(row, "ownerAgentId"), text(row, "ownerAgentName"));
    }

    private static String text(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull() ? row.get(key).getAsString() : null;
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

    private long createAs(Long agentId, String title, String content) {
        var created = asAgent(() -> POST(agentRequest(agentId), "/api/prompts", "application/json",
                promptBody(title, content, "CODING")));
        assertStatus(200, created);
        return json(created).get("id").getAsLong();
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
