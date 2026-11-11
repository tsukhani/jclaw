import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
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
 * Who owns a prompt, driven end to end: the operator by session, an agent with the bearer and agent
 * header {@code jclaw_api} sends. A new prompt's creator picks its owner (JF-307), and the operator
 * changes it on an edit (JF-308).
 *
 * <p>{@code main}'s half is in {@code RequestPrincipalPolicyTest}. The check on a named owner, on a
 * create and on an edit alike, is {@code mayReachRow} with that agent, which answers {@code main} as
 * it answers the operator: true whoever it names ({@code mainReachesEveryAgentsRows}), and true for
 * an id no agent has and for a null ({@code anOwnerlessRowIsOperatorAndMainOnly}), so {@code main}
 * gets the operator's 400 or the operator's change there and never a custom agent's 403.
 * {@code main} is provisioned at boot, where a sibling's {@code Fixtures.deleteDatabase} can remove
 * it mid-suite, so no test here drives it.
 */
class PromptOwnerChoiceTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";
    private static final String INVALID_REQUEST = "invalid_request";
    private static final String OWN_PROMPTS_ONLY =
            "An agent other than main owns every prompt it creates: leave ownerAgentId out, or send its own id.";
    private static final String OWNER_NOT_CHANGED =
            "An agent other than main cannot change a prompt's owner: leave ownerAgentId out, or send its own id.";
    private static final String NOT_AN_ID = "ownerAgentId must be an agent's id, a number.";
    private static final String UNSCOPED = "This request is agent-originated but names no agent, so it cannot be scoped.";
    private static final long NO_SUCH_AGENT = 999_999_999L;

    private record Seed(Long agentId, String name) {}

    private Seed own;
    private Seed alien;
    /** In every title this test writes, so its rows can be counted and removed apart from a sibling's. */
    private String marker;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this
    // class's by its agent's unique name or its title's marker.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        marker = "jf307-" + UUID.randomUUID().toString().substring(0, 8);
        own = seedAgent("jf307-own-");
        alien = seedAgent("jf307-alien-");
    }

    @AfterEach
    void cleanUp() {
        clearCookies();
        commit(() -> Prompt.delete("title LIKE ?1", marker + "%"));
    }

    // --- the operator -------------------------------------------------------------------

    @Test
    void theOperatorNamesAnAgentAndThePromptIsThatAgents() {
        login();
        var created = POST("/api/prompts", "application/json",
                promptBody(marker + " for own", "operator wrote this", new JsonPrimitive(own.agentId())));

        assertStatus(200, created);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(created)));
        assertEquals(own.agentId(), storedOwner(json(created).get("id").getAsLong()));
    }

    @Test
    void theAgentTheOperatorNamesListsReadsEditsAndDeletesThePrompt() {
        long id = createAsOperator(marker + " for own", "operator wrote this", new JsonPrimitive(own.agentId()));

        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals(List.of(id + ":" + marker + " for own"), idsAndTitles(list));

        var read = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/" + id));
        assertStatus(200, read);
        assertEquals(List.of(marker + " for own", "operator wrote this", String.valueOf(own.agentId()), own.name()),
                List.of(json(read).get("title").getAsString(), json(read).get("content").getAsString(),
                        owner(json(read)).get(0), owner(json(read)).get(1)));

        var edited = asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id, "application/json",
                "{\"content\":\"edited by its owner\"}"));
        assertStatus(200, edited);
        assertEquals(List.of("edited by its owner", String.valueOf(own.agentId()), own.name()),
                List.of(json(edited).get("content").getAsString(), owner(json(edited)).get(0),
                        owner(json(edited)).get(1)));

        // The owner's reach is the named agent's alone: a second agent gets the refusal on the same row.
        assertRefused("Prompt " + id + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(alien.agentId()), "/api/prompts/" + id)));

        var deleted = asAgent(() -> DELETE(agentRequest(own.agentId()), "/api/prompts/" + id));
        assertStatus(200, deleted);
        assertEquals("{\"status\":\"ok\",\"deleted\":true}", getContent(deleted));
        assertNull(stored(id));
    }

    @Test
    void aPromptTheOperatorCreatesWithNoOwnerNamedIsTheOperators() {
        login();
        var absent = POST("/api/prompts", "application/json", promptBody(marker + " absent", "c", null));
        assertStatus(200, absent);
        assertEquals(Arrays.asList(null, null), owner(json(absent)));
        assertNull(storedOwner(json(absent).get("id").getAsLong()));

        var explicitNull = POST("/api/prompts", "application/json",
                promptBody(marker + " null", "c", JsonNull.INSTANCE));
        assertStatus(200, explicitNull);
        assertEquals(Arrays.asList(null, null), owner(json(explicitNull)));
        assertNull(storedOwner(json(explicitNull).get("id").getAsLong()));
    }

    @Test
    void theOperatorNamingAnIdNoAgentHasIsRefusedAndNothingIsSaved() {
        login();
        var refused = POST("/api/prompts", "application/json",
                promptBody(marker + " nobody", "c", new JsonPrimitive(NO_SUCH_AGENT)));

        assertStatus(400, refused);
        assertEquals(List.of(INVALID_REQUEST, "ownerAgentId 999999999 names no agent."), codeAndMessage(refused));
        assertEquals(0L, written());
    }

    @Test
    void theOperatorNamingAnOwnerThatIsNotANumberIsRefusedAndNothingIsSaved() {
        login();
        var word = POST("/api/prompts", "application/json",
                promptBody(marker + " word", "c", new JsonPrimitive("research-bot")));
        assertStatus(400, word);
        assertEquals(List.of(INVALID_REQUEST, NOT_AN_ID), codeAndMessage(word));

        var object = POST("/api/prompts", "application/json", promptBody(marker + " object", "c", new JsonObject()));
        assertStatus(400, object);
        assertEquals(List.of(INVALID_REQUEST, NOT_AN_ID), codeAndMessage(object));

        assertEquals(0L, written());
    }

    // --- an agent other than main -------------------------------------------------------

    @Test
    void anAgentOtherThanMainNamingAnotherOwnerIsRefusedAndNothingIsSaved() {
        assertRefused(OWN_PROMPTS_ONLY, asAgent(() -> POST(agentRequest(own.agentId()), "/api/prompts",
                "application/json", promptBody(marker + " given away", "c", new JsonPrimitive(alien.agentId())))));
        // An id no agent has is refused the same way, so the answer never says which ids exist.
        assertRefused(OWN_PROMPTS_ONLY, asAgent(() -> POST(agentRequest(own.agentId()), "/api/prompts",
                "application/json", promptBody(marker + " to nobody", "c", new JsonPrimitive(NO_SUCH_AGENT)))));
        assertRefused(OWN_PROMPTS_ONLY, asAgent(() -> POST(agentRequest(own.agentId()), "/api/prompts",
                "application/json", promptBody(marker + " to a word", "c", new JsonPrimitive("research-bot")))));

        assertEquals(0L, written());
    }

    @Test
    void anAgentOtherThanMainNamingItselfOwnsThePrompt() {
        var named = asAgent(() -> POST(agentRequest(own.agentId()), "/api/prompts", "application/json",
                promptBody(marker + " mine", "own content", new JsonPrimitive(own.agentId()))));

        assertStatus(200, named);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(named)));
        assertEquals(own.agentId(), storedOwner(json(named).get("id").getAsLong()));
    }

    @Test
    void anAgentSendingANullOwnerOwnsThePrompt() {
        var created = asAgent(() -> POST(agentRequest(own.agentId()), "/api/prompts", "application/json",
                promptBody(marker + " null", "own content", JsonNull.INSTANCE)));

        assertStatus(200, created);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(created)));
        assertEquals(own.agentId(), storedOwner(json(created).get("id").getAsLong()));
    }

    @Test
    void anOwnerNameInACreateBodyIsNotRead() {
        var body = JsonParser.parseString(promptBody(marker + " named", "own content", null)).getAsJsonObject();
        body.addProperty("ownerAgentName", alien.name());
        var created = asAgent(() -> POST(agentRequest(own.agentId()), "/api/prompts", "application/json",
                body.toString()));

        assertStatus(200, created);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(created)));
        assertEquals(own.agentId(), storedOwner(json(created).get("id").getAsLong()));
    }

    @Test
    void anUnstampedBearerNamingAnOwnerCreatesNothing() {
        var created = asAgent(() -> POST(bareBearerRequest(), "/api/prompts", "application/json",
                promptBody(marker + " unstamped", "c", new JsonPrimitive(own.agentId()))));

        assertStatus(403, created);
        assertEquals(List.of(AGENT_SCOPE, UNSCOPED), codeAndMessage(created));
        assertEquals(0L, written());
    }

    // --- an edit changes the owner: the operator (JF-308) -------------------------------

    @Test
    void theOperatorGivesItsPromptToAnAgentOnAnEdit() {
        long id = createAsOperator(marker + " operator", "c", null);

        var given = asOperator(() -> PUT("/api/prompts/" + id, "application/json",
                "{\"content\":\"edited\",\"ownerAgentId\":" + own.agentId() + "}"));

        assertStatus(200, given);
        assertEquals(List.of("edited", String.valueOf(own.agentId()), own.name()),
                List.of(json(given).get("content").getAsString(), owner(json(given)).get(0),
                        owner(json(given)).get(1)));
        assertEquals(Arrays.asList(marker + " operator", "edited", "CODING", null, own.agentId()), storedRow(id));
    }

    @Test
    void theOperatorMovesAnAgentsPromptToAnotherAgent() {
        long id = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));

        var moved = asOperator(() -> PUT("/api/prompts/" + id, "application/json",
                "{\"ownerAgentId\":" + alien.agentId() + "}"));

        assertStatus(200, moved);
        assertEquals(List.of(String.valueOf(alien.agentId()), alien.name()), owner(json(moved)));
        assertEquals(Arrays.asList(marker + " for own", "c", "CODING", null, alien.agentId()), storedRow(id));
    }

    @Test
    void aNullOwnerOnAnEditMakesThePromptTheOperators() {
        long id = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));

        var taken = asOperator(() -> PUT("/api/prompts/" + id, "application/json", "{\"ownerAgentId\":null}"));

        assertStatus(200, taken);
        assertEquals(Arrays.asList(null, null), owner(json(taken)));
        assertEquals(Arrays.asList(marker + " for own", "c", "CODING", null, null), storedRow(id));
    }

    @Test
    void anEditThatSendsNoOwnerLeavesTheOwnerUnchanged() {
        long agents = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));
        long operators = createAsOperator(marker + " operator", "c", null);

        // An owner's name is not the field: only ownerAgentId is read.
        var edited = asOperator(() -> PUT("/api/prompts/" + agents, "application/json",
                "{\"content\":\"edited\",\"ownerAgentName\":\"" + alien.name() + "\"}"));
        assertStatus(200, edited);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(edited)));
        assertEquals(Arrays.asList(marker + " for own", "edited", "CODING", null, own.agentId()), storedRow(agents));

        var editedOperators = asOperator(() -> PUT("/api/prompts/" + operators, "application/json",
                "{\"content\":\"edited\"}"));
        assertStatus(200, editedOperators);
        assertEquals(Arrays.asList(null, null), owner(json(editedOperators)));
        assertEquals(Arrays.asList(marker + " operator", "edited", "CODING", null, null), storedRow(operators));
    }

    @Test
    void anEditThatNamesTheCurrentOwnerLeavesTheOwnerUnchanged() {
        long agents = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));
        long operators = createAsOperator(marker + " operator", "c", null);

        var same = asOperator(() -> PUT("/api/prompts/" + agents, "application/json",
                "{\"ownerAgentId\":" + own.agentId() + "}"));
        assertStatus(200, same);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(same)));
        assertEquals(own.agentId(), storedOwner(agents));

        var stillOperators = asOperator(() -> PUT("/api/prompts/" + operators, "application/json",
                "{\"ownerAgentId\":null}"));
        assertStatus(200, stillOperators);
        assertEquals(Arrays.asList(null, null), owner(json(stillOperators)));
        assertNull(storedOwner(operators));
    }

    @Test
    void theOperatorNamingAnIdNoAgentHasOnAnEditIsRefusedAndNothingIsApplied() {
        long id = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));

        var refused = asOperator(() -> PUT("/api/prompts/" + id, "application/json",
                editNaming(String.valueOf(NO_SUCH_AGENT))));

        assertStatus(400, refused);
        assertEquals(List.of(INVALID_REQUEST, "ownerAgentId 999999999 names no agent."), codeAndMessage(refused));
        assertEquals(Arrays.asList(marker + " for own", "c", "CODING", null, own.agentId()), storedRow(id));
    }

    @Test
    void theOperatorNamingAnOwnerThatIsNotANumberOnAnEditIsRefusedAndNothingIsApplied() {
        long id = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));

        for (var notANumber : List.of("\"research-bot\"", "{}", "true")) {
            var refused = asOperator(() -> PUT("/api/prompts/" + id, "application/json", editNaming(notANumber)));

            assertStatus(400, refused);
            assertEquals(List.of(INVALID_REQUEST, NOT_AN_ID), codeAndMessage(refused), notANumber);
            assertEquals(Arrays.asList(marker + " for own", "c", "CODING", null, own.agentId()), storedRow(id),
                    notANumber);
        }
    }

    // --- an edit changes the owner: an agent other than main (JF-308) -------------------

    @Test
    void anAgentOtherThanMainCannotGiveItsPromptAwayAndNothingIsApplied() {
        long id = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));

        // Another agent, an id no agent has, a word, and null, which would hand it to the operator:
        // one answer for all four, so the refusal never says which ids exist.
        for (var named : List.of(String.valueOf(alien.agentId()), String.valueOf(NO_SUCH_AGENT), "\"research-bot\"",
                "null")) {
            assertRefused(OWNER_NOT_CHANGED, asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id,
                    "application/json", editNaming(named))));
            assertEquals(Arrays.asList(marker + " for own", "c", "CODING", null, own.agentId()), storedRow(id), named);
        }

        assertRefused("Prompt " + id + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(alien.agentId()), "/api/prompts/" + id)));
    }

    @Test
    void anAgentOtherThanMainNamingItselfOnItsOwnPromptKeepsItAndTheRestOfTheEditApplies() {
        long id = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));

        var edited = asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id, "application/json",
                editNaming(String.valueOf(own.agentId()))));

        assertStatus(200, edited);
        assertEquals(List.of(String.valueOf(own.agentId()), own.name()), owner(json(edited)));
        assertEquals(Arrays.asList(marker + " retitled", "rewritten", "WRITING", "new", own.agentId()), storedRow(id));
    }

    @Test
    void anAgentOtherThanMainCannotTakeAPrompt() {
        long operators = createAsOperator(marker + " operator", "c", null);
        long aliens = createAsOperator(marker + " for alien", "c", new JsonPrimitive(alien.agentId()));

        assertRefused("Prompt " + operators + " is the operator's; an agent other than main reaches only its own prompts.",
                asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + operators, "application/json",
                        editNaming(String.valueOf(own.agentId())))));
        assertRefused("Prompt " + aliens + " belongs to another agent.",
                asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + aliens, "application/json",
                        editNaming(String.valueOf(own.agentId())))));

        assertEquals(Arrays.asList(marker + " operator", "c", "CODING", null, null), storedRow(operators));
        assertEquals(Arrays.asList(marker + " for alien", "c", "CODING", null, alien.agentId()), storedRow(aliens));
    }

    @Test
    void anUnstampedBearerNamingAnOwnerOnAnEditChangesNothing() {
        long id = createAsOperator(marker + " for own", "c", new JsonPrimitive(own.agentId()));

        assertRefused("Prompt " + id + " belongs to another agent.",
                asAgent(() -> PUT(bareBearerRequest(), "/api/prompts/" + id, "application/json",
                        editNaming(String.valueOf(alien.agentId())))));

        assertEquals(Arrays.asList(marker + " for own", "c", "CODING", null, own.agentId()), storedRow(id));
    }

    // --- after a change (JF-308) --------------------------------------------------------

    @Test
    void afterAChangeTheNewOwnerListsReadsEditsAndDeletesThePrompt() {
        long id = createAsOperator(marker + " operator", "operator wrote this", null);
        assertStatus(200, asOperator(() -> PUT("/api/prompts/" + id, "application/json",
                "{\"ownerAgentId\":" + own.agentId() + "}")));

        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals(List.of(id + ":" + marker + " operator"), idsAndTitles(list));

        var read = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/" + id));
        assertStatus(200, read);
        assertEquals(List.of(marker + " operator", "operator wrote this", String.valueOf(own.agentId()), own.name()),
                List.of(json(read).get("title").getAsString(), json(read).get("content").getAsString(),
                        owner(json(read)).get(0), owner(json(read)).get(1)));

        var edited = asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id, "application/json",
                "{\"content\":\"edited by its new owner\"}"));
        assertStatus(200, edited);
        assertEquals(Arrays.asList(marker + " operator", "edited by its new owner", "CODING", null, own.agentId()),
                storedRow(id));

        var deleted = asAgent(() -> DELETE(agentRequest(own.agentId()), "/api/prompts/" + id));
        assertStatus(200, deleted);
        assertEquals("{\"status\":\"ok\",\"deleted\":true}", getContent(deleted));
        assertNull(stored(id));
    }

    @Test
    void afterAChangeTheAgentThatOwnedThePromptNoLongerReachesIt() {
        long id = createAsOperator(marker + " moving", "c", new JsonPrimitive(own.agentId()));
        assertStatus(200, asOperator(() -> PUT("/api/prompts/" + id, "application/json",
                "{\"ownerAgentId\":" + alien.agentId() + "}")));
        var refusal = "Prompt " + id + " belongs to another agent.";

        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals("[]", getContent(list));
        assertRefused(refusal, asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/" + id)));
        assertRefused(refusal, asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id,
                "application/json", "{\"content\":\"hijacked\"}")));
        assertRefused(refusal, asAgent(() -> DELETE(agentRequest(own.agentId()), "/api/prompts/" + id)));
        assertEquals(Arrays.asList(marker + " moving", "c", "CODING", null, alien.agentId()), storedRow(id));

        // The same routes answer the new owner, so the refusals above are the scope and not a broken header.
        var newOwnersList = asAgent(() -> GET(agentRequest(alien.agentId()), "/api/prompts"));
        assertStatus(200, newOwnersList);
        assertEquals(List.of(id + ":" + marker + " moving"), idsAndTitles(newOwnersList));
        assertStatus(200, asAgent(() -> DELETE(agentRequest(alien.agentId()), "/api/prompts/" + id)));
        assertNull(stored(id));
    }

    @Test
    void afterAPromptGoesBackToTheOperatorTheAgentThatOwnedItNoLongerReachesIt() {
        long id = createAsOperator(marker + " returning", "c", new JsonPrimitive(own.agentId()));
        assertStatus(200, asOperator(() -> PUT("/api/prompts/" + id, "application/json", "{\"ownerAgentId\":null}")));
        var refusal = "Prompt " + id + " is the operator's; an agent other than main reaches only its own prompts.";

        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals("[]", getContent(list));
        assertRefused(refusal, asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts/" + id)));
        assertRefused(refusal, asAgent(() -> PUT(agentRequest(own.agentId()), "/api/prompts/" + id,
                "application/json", "{\"content\":\"hijacked\"}")));
        assertRefused(refusal, asAgent(() -> DELETE(agentRequest(own.agentId()), "/api/prompts/" + id)));
        assertEquals(Arrays.asList(marker + " returning", "c", "CODING", null, null), storedRow(id));

        var operatorsList = asOperator(() -> GET("/api/prompts?title=" + marker));
        assertStatus(200, operatorsList);
        assertEquals(List.of(id + ":" + marker + " returning"), idsAndTitles(operatorsList));
        var edited = asOperator(() -> PUT("/api/prompts/" + id, "application/json", "{\"content\":\"edited\"}"));
        assertStatus(200, edited);
        assertEquals(Arrays.asList("edited", null, null),
                Arrays.asList(json(edited).get("content").getAsString(), owner(json(edited)).get(0),
                        owner(json(edited)).get(1)));
        assertStatus(200, asOperator(() -> DELETE("/api/prompts/" + id)));
        assertNull(stored(id));
    }

    // --- an import ----------------------------------------------------------------------

    @Test
    void anImportedPromptThatNamesAnOwnerIsStillTheOperators() {
        var entry = JsonParser.parseString(promptBody(marker + " imported", "c", new JsonPrimitive(own.agentId())))
                .getAsJsonObject();
        entry.addProperty("ownerAgentName", own.name());
        var prompts = new JsonArray();
        prompts.add(entry);
        var doc = new JsonObject();
        doc.addProperty("mode", "merge");
        doc.add("prompts", prompts);

        login();
        var imported = POST("/api/prompts/import", "application/json", doc.toString());
        assertStatus(200, imported);
        assertEquals("{\"status\":\"ok\",\"imported\":1}", getContent(imported));
        clearCookies();

        assertEquals(Arrays.asList((Long) null), commit(() -> {
            List<Prompt> rows = Prompt.find("title = ?1", marker + " imported").fetch();
            var owners = new ArrayList<Long>();
            for (var row : rows) owners.add(row.ownerAgent == null ? null : row.ownerAgent.id);
            return owners;
        }));
        var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/prompts"));
        assertStatus(200, list);
        assertEquals("[]", getContent(list));
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

    private static List<String> idsAndTitles(Http.Response response) {
        var out = new ArrayList<String>();
        for (var row : JsonParser.parseString(getContent(response)).getAsJsonArray()) {
            var o = row.getAsJsonObject();
            out.add(o.get("id").getAsLong() + ":" + o.get("title").getAsString());
        }
        return out;
    }

    /** A create body; {@code ownerAgentId} is left out when {@code owner} is null and sent as given otherwise. */
    private static String promptBody(String title, String content, JsonElement owner) {
        var body = new JsonObject();
        body.addProperty("title", title);
        body.addProperty("content", content);
        body.addProperty("category", "CODING");
        if (owner != null) body.add("ownerAgentId", owner);
        return body.toString();
    }

    /** An edit that changes every field and names {@code ownerJson}, a JSON value, as the owner. */
    private String editNaming(String ownerJson) {
        return "{\"title\":\"%s retitled\",\"content\":\"rewritten\",\"category\":\"WRITING\",\"tags\":\"new\","
                .formatted(marker) + "\"ownerAgentId\":" + ownerJson + "}";
    }

    /** One call in the operator's session, which ends with it, so a bearer call after it is the agent's. */
    private Http.Response asOperator(Supplier<Http.Response> call) {
        login();
        try {
            return call.get();
        } finally {
            clearCookies();
        }
    }

    private long createAsOperator(String title, String content, JsonElement owner) {
        login();
        try {
            var created = POST("/api/prompts", "application/json", promptBody(title, content, owner));
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

    /** The stored title, content, category, tags and owner's id, the last null for the operator's. */
    private static List<Object> storedRow(long id) {
        return commit(() -> {
            Prompt row = Prompt.findById(id);
            return Arrays.asList(row.title, row.content, row.category.name(), row.tags,
                    row.ownerAgent == null ? null : row.ownerAgent.id);
        });
    }

    /** The stored owner's id, or null for the operator's. */
    private static Long storedOwner(long id) {
        return commit(() -> {
            Prompt row = Prompt.findById(id);
            return row.ownerAgent == null ? null : row.ownerAgent.id;
        });
    }

    /** How many prompts this test has saved. */
    private long written() {
        return commit(() -> Prompt.count("title LIKE ?1", marker + "%"));
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
