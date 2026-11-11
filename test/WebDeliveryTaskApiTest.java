import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import models.Conversation;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@code POST} and {@code PATCH /api/tasks} save a {@code web:<id>} delivery for an agent only when the
 * conversation is one it may deliver into, and any for the operator (JF-289). The agent's requests carry
 * the bearer and agent header {@code jclaw_api} sends, as in {@code AgentTaskScopeTest}.
 *
 * <p>The task records who saved the target, and shows it as {@code deliverySetByOperator} (JF-305): an
 * agent's save of a delivery is the agent's, whoever saved the one before, and so is the target of a task
 * whose agent changed what a fire produces or when it fires. What a fire then does with each is in
 * {@code OperatorWebTargetFireTest}.
 *
 * <p>{@code main}'s half is in {@code WebDeliveryScopeTest}, against a detached entity: its row is
 * provisioned by code and a test may not seed one.
 */
class WebDeliveryTaskApiTest extends FunctionalTest {

    private record Seed(Long agentId, Long conversationId, Long otherConversationId, Long taskId, Long missingId) {}

    private String suffix;
    private Seed own;
    private Seed alien;

    // No Fixtures.deleteDatabase(): every row here is this class's by its agent's unique name.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        own = seedAgent("jf289-api-own-" + suffix);
        alien = seedAgent("jf289-api-alien-" + suffix);
    }

    @AfterEach
    void cleanUp() {
        clearCookies();
    }

    @Test
    void anAgentCannotCreateATaskThatDeliversIntoAnotherAgentsConversation() {
        var name = "jf289-api-create-" + suffix;
        var replies = new ArrayList<String>();
        for (var id : List.of(alien.conversationId(), own.missingId())) {
            replies.add(refusal(id, asAgent(() -> POST(agentRequest(own.agentId()), "/api/tasks", "application/json",
                    newTask(own.agentId(), name, "web:" + id)))));
        }
        assertEquals(replies.get(0), replies.get(1), "another agent's conversation and a missing one must read alike");
        assertEquals(0L, (long) commit(() -> Task.count("name = ?1", name)));

        var created = asAgent(() -> POST(agentRequest(own.agentId()), "/api/tasks", "application/json",
                newTask(own.agentId(), name, "web:" + own.conversationId())));
        assertStatus(200, created);
        assertEquals(new Saved("web:" + own.conversationId(), false), savedIn(created));
    }

    @Test
    void anAgentCannotRepointItsTaskAtAnotherAgentsConversation() {
        var path = "/api/tasks/" + own.taskId();
        var replies = new ArrayList<String>();
        for (var id : List.of(alien.conversationId(), own.missingId())) {
            replies.add(refusal(id, asAgent(() -> patch(agentRequest(own.agentId()), path,
                    "{\"description\":\"rewritten\",\"delivery\":\"web:%d\"}".formatted(id)))));
        }
        assertEquals(replies.get(0), replies.get(1), "another agent's conversation and a missing one must read alike");
        // Refused before any field is applied: the description in the same patch is not saved either.
        assertEquals(List.of("web:" + own.conversationId(), "the prompt"), commit(() -> {
            var task = (Task) Task.findById(own.taskId());
            return List.of(task.delivery, task.description);
        }));

        var patched = asAgent(() -> patch(agentRequest(own.agentId()), path,
                "{\"delivery\":\"web:%d\"}".formatted(own.otherConversationId())));
        assertStatus(200, patched);
        assertEquals(new Saved("web:" + own.otherConversationId(), false), savedIn(patched));
    }

    @Test
    void theOperatorSavesAnyConversationAsATasksTarget() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));

        var created = POST("/api/tasks", "application/json",
                newTask(own.agentId(), "jf289-api-operator-" + suffix, "web:" + alien.conversationId()));
        assertStatus(200, created);
        assertEquals(new Saved("web:" + alien.conversationId(), true), savedIn(created));

        var patched = patch(operatorRequest(), "/api/tasks/" + own.taskId(),
                "{\"delivery\":\"web:%d\"}".formatted(alien.conversationId()));
        assertStatus(200, patched);
        assertEquals(new Saved("web:" + alien.conversationId(), true), savedIn(patched));
    }

    @Test
    void anAgentsEditOfWhatAFireProducesOrWhenItFiresMakesTheOperatorsTargetTheAgents() {
        var path = "/api/tasks/" + own.taskId();
        var wider = "web:" + alien.conversationId();
        var edits = List.of(
                "{\"description\":\"rewritten\"}",
                "{\"schedule\":\"2d\"}",
                "{\"name\":\"renamed-" + suffix + "\"}",
                "{\"payloadType\":\"reminder\"}",
                "{\"enabledToolNames\":[\"web_search\"]}",
                "{\"timezone\":\"Asia/Tokyo\"}");

        for (var edit : edits) {
            assertEquals(new Saved(wider, true), operatorSaves(path, wider), edit);

            var edited = asAgent(() -> patch(agentRequest(own.agentId()), path, edit));

            assertStatus(200, edited);
            assertEquals(new Saved(wider, false), savedIn(edited), edit);
        }
    }

    @Test
    void anAgentThatSwitchesItsTaskOffAndOnKeepsTheOperatorsTarget() {
        var path = "/api/tasks/" + own.taskId();
        var wider = "web:" + alien.conversationId();
        assertEquals(new Saved(wider, true), operatorSaves(path, wider));

        for (var edit : List.of("{\"paused\":true}", "{\"paused\":false}", "{\"autoDeleteOnComplete\":true}")) {
            var edited = asAgent(() -> patch(agentRequest(own.agentId()), path, edit));

            assertStatus(200, edited);
            assertEquals(new Saved(wider, true), savedIn(edited), edit);
        }
    }

    @Test
    void theOperatorsOwnEditOfTheTaskKeepsTheTargetTheirs() {
        var path = "/api/tasks/" + own.taskId();
        var wider = "web:" + alien.conversationId();
        assertEquals(new Saved(wider, true), operatorSaves(path, wider));

        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
        var edited = patch(operatorRequest(), path, "{\"description\":\"rewritten\",\"schedule\":\"2d\"}");

        assertStatus(200, edited);
        assertEquals(new Saved(wider, true), savedIn(edited));
    }

    @Test
    void theOperatorsTargetSentBackByTheAgentIsRefusedAndNothingInThePatchIsSaved() {
        var path = "/api/tasks/" + own.taskId();
        var wider = "web:" + alien.conversationId();
        assertEquals(new Saved(wider, true), operatorSaves(path, wider));

        // Sent back unchanged it is still the agent's save, and out of the agent's reach.
        refusal(alien.conversationId(), asAgent(() -> patch(agentRequest(own.agentId()), path,
                "{\"description\":\"once more\",\"delivery\":\"%s\"}".formatted(wider))));

        assertEquals(List.of(wider, true, "the prompt"), stored(own.taskId()));
    }

    @Test
    void anAgentsChangeOfTheDeliveryMakesTheTargetTheAgents() {
        var path = "/api/tasks/" + own.taskId();
        var wider = "web:" + alien.conversationId();
        var ownOther = "web:" + own.otherConversationId();
        assertEquals(new Saved(wider, true), operatorSaves(path, wider));

        var changed = asAgent(() -> patch(agentRequest(own.agentId()), path, "{\"delivery\":\"%s\"}".formatted(ownOther)));
        assertStatus(200, changed);
        assertEquals(new Saved(ownOther, false), savedIn(changed));

        refusal(alien.conversationId(), asAgent(() -> patch(agentRequest(own.agentId()), path,
                "{\"delivery\":\"%s\"}".formatted(wider))));
        assertEquals(List.of(ownOther, false, "the prompt"), stored(own.taskId()));

        assertEquals(new Saved(wider, true), operatorSaves(path, wider));
    }

    @Test
    void anAgentThatClearsTheOperatorsTargetLeavesNoneBehind() {
        var path = "/api/tasks/" + own.taskId();
        assertEquals(new Saved("web:" + alien.conversationId(), true),
                operatorSaves(path, "web:" + alien.conversationId()));

        var cleared = asAgent(() -> patch(agentRequest(own.agentId()), path, "{\"delivery\":null}"));

        assertStatus(200, cleared);
        assertEquals(new Saved(null, false), savedIn(cleared));
    }

    // --- helpers -------------------------------------------------------------------------

    /** Asserts the refusal of {@code conversationId} and returns its whole body with the id masked, to compare two. */
    private static String refusal(Long conversationId, Http.Response response) {
        assertStatus(400, response);
        var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(List.of("invalid_request", "Web target conversation " + conversationId + " not found."),
                List.of(body.get("code").getAsString(), body.get("message").getAsString()), getContent(response));
        return getContent(response).replace(String.valueOf(conversationId), "<id>");
    }

    /** A task's delivery and whether the operator or main saved it, as the API shows them. */
    private record Saved(String delivery, boolean byOperatorOrMain) {}

    private static Saved savedIn(Http.Response response) {
        var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
        var delivery = body.get("delivery");
        assertNotNull(body.get("deliverySetByOperator"), "the task shows who saved its delivery");
        return new Saved(delivery.isJsonNull() ? null : delivery.getAsString(),
                body.get("deliverySetByOperator").getAsBoolean());
    }

    /** The operator signs in and saves {@code delivery} on the task at {@code path}; returns what the reply shows. */
    private Saved operatorSaves(String path, String delivery) {
        try {
            assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
            var patched = patch(operatorRequest(), path, "{\"delivery\":\"%s\"}".formatted(delivery));
            assertStatus(200, patched);
            return savedIn(patched);
        } finally {
            clearCookies();
        }
    }

    /** The task's delivery, who saved it and its description, as its agent reads them back. */
    private List<Object> stored(Long taskId) {
        var read = asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/" + taskId));
        assertStatus(200, read);
        var saved = savedIn(read);
        return List.of(saved.delivery(), saved.byOperatorOrMain(),
                JsonParser.parseString(getContent(read)).getAsJsonObject().get("description").getAsString());
    }

    private static String newTask(Long agentId, String name, String delivery) {
        return "{\"agentId\":%d,\"name\":\"%s\",\"schedule\":\"1d\",\"delivery\":\"%s\"}"
                .formatted(agentId, name, delivery);
    }

    /** An agent with two web conversations, a task delivering into the first, and an id that names none. */
    private static Seed seedAgent(String name) {
        return commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "test-provider";
            agent.modelId = "test-model";
            agent.enabled = true;
            agent.save();

            var conversation = conversation(agent);
            var other = conversation(agent);
            var gone = conversation(agent);
            var missingId = gone.id;
            gone.delete();

            var task = new Task();
            task.agent = agent;
            task.name = "task-of-" + name;
            task.description = "the prompt";
            task.type = Task.Type.SCHEDULED;
            task.status = Task.initialStatusFor(Task.Type.SCHEDULED);
            task.delivery = "web:" + conversation.id;
            task.save();
            return new Seed(agent.id, conversation.id, other.id, task.id, missingId);
        });
    }

    private static Conversation conversation(Agent agent) {
        var conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "jf289";
        conversation.save();
        return conversation;
    }

    /** Commit off-thread: the test body is already in a transaction this request would not see. */
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
        var request = newRequest();
        request.headers.put("authorization",
                new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** A request carrying the session cookies the login left, which {@code makeRequest} does not add itself. */
    @SuppressWarnings("unchecked")
    private static Http.Request operatorRequest() {
        var request = newRequest();
        try {
            var saved = FunctionalTest.class.getDeclaredField("savedCookies");
            saved.setAccessible(true);
            var cookies = (Map<String, Http.Cookie>) saved.get(null);
            if (cookies != null) request.cookies = cookies;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not read FunctionalTest.savedCookies", e);
        }
        return request;
    }

    /** FunctionalTest has no PATCH; this sends one on {@code request}. */
    private static Http.Response patch(Http.Request request, String url, String json) {
        request.method = "PATCH";
        request.contentType = "application/json";
        request.url = url;
        request.path = url;
        request.querystring = "";
        request.body = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return makeRequest(request);
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
