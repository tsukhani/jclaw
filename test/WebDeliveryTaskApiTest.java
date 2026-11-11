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
        assertEquals("web:" + own.conversationId(), deliveryIn(created));
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
        assertEquals("web:" + own.otherConversationId(), deliveryIn(patched));
    }

    @Test
    void theOperatorSavesAnyConversationAsATasksTarget() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));

        var created = POST("/api/tasks", "application/json",
                newTask(own.agentId(), "jf289-api-operator-" + suffix, "web:" + alien.conversationId()));
        assertStatus(200, created);
        assertEquals("web:" + alien.conversationId(), deliveryIn(created));

        var patched = patch(operatorRequest(), "/api/tasks/" + own.taskId(),
                "{\"delivery\":\"web:%d\"}".formatted(alien.conversationId()));
        assertStatus(200, patched);
        assertEquals("web:" + alien.conversationId(), deliveryIn(patched));
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

    private static String deliveryIn(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject().get("delivery").getAsString();
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
