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
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A custom agent cannot change the model or thinking level it runs on (JF-286), driven end to end with the
 * bearer and agent header {@code jclaw_api} sends. The operator's half is in {@code ApiConversationsControllerTest}
 * and the {@code ApiTasksController*} classes; {@code main}'s is the {@code isOperatorOrMain} matrix in
 * {@code RequestPrincipalPolicyTest}, since {@code main} cannot be seeded per test.
 */
class AgentModelChoiceTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";

    private Long agentId;
    private Long conversationId;
    private Long taskId;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this class's.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        var ids = commit(() -> {
            var agent = new Agent();
            agent.name = "jf286-" + UUID.randomUUID().toString().substring(0, 8);
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();
            var convo = new Conversation();
            convo.agent = agent;
            convo.channelType = "web";
            convo.peerId = "jf286";
            convo.modelProviderOverride = "openrouter";
            convo.modelIdOverride = "set-by-the-operator";
            convo.thinkingModeOverride = "low";
            convo.save();
            var task = new Task();
            task.agent = agent;
            task.name = "jf286-task-" + UUID.randomUUID();
            task.description = "before";
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.initialStatusFor(Task.Type.IMMEDIATE);
            task.save();
            return new Long[]{agent.id, convo.id, task.id};
        });
        agentId = ids[0];
        conversationId = ids[1];
        taskId = ids[2];
    }

    @AfterEach
    void dropCookieJar() {
        clearCookies();
    }

    @Test
    void aCustomAgentCannotSetOrClearItsConversationsModelOrThinkingLevel() {
        var base = "/api/conversations/" + conversationId;
        var refused = List.of(
                asAgent(() -> PUT(agentRequest(), base + "/model-override", "application/json",
                        "{\"modelProvider\":\"openrouter\",\"modelId\":\"gpt-4.1\"}")),
                asAgent(() -> DELETE(agentRequest(), base + "/model-override")),
                asAgent(() -> PUT(agentRequest(), base + "/thinking-override", "application/json",
                        "{\"thinkingMode\":\"high\"}")),
                asAgent(() -> DELETE(agentRequest(), base + "/thinking-override")));
        for (var response : refused) {
            assertRefused("Only the operator or the main agent may change a conversation's model or thinking level.",
                    response);
        }

        assertEquals(List.of("openrouter", "set-by-the-operator", "low"), commit(() -> {
            var convo = (Conversation) Conversation.findById(conversationId);
            return List.of(convo.modelProviderOverride, convo.modelIdOverride, convo.thinkingModeOverride);
        }));
    }

    @Test
    void aCustomAgentCannotCreateATaskOnAModelItChoseButMayCreateOneWithout() {
        var chosen = "jf286-chosen-" + UUID.randomUUID();
        assertRefused("Only the operator or the main agent may choose the model a task runs on.",
                asAgent(() -> POST(agentRequest(), "/api/tasks", "application/json",
                        "{\"agentId\":%d,\"name\":\"%s\",\"schedule\":\"1d\",\"modelProvider\":\"openrouter\",\"modelId\":\"gpt-4.1\"}"
                                .formatted(agentId, chosen))));
        assertEquals(0L, (long) commit(() -> Task.count("name = ?1", chosen)));

        var plain = "jf286-plain-" + UUID.randomUUID();
        assertStatus(200, asAgent(() -> POST(agentRequest(), "/api/tasks", "application/json",
                "{\"agentId\":%d,\"name\":\"%s\",\"schedule\":\"1d\"}".formatted(agentId, plain))));
        assertEquals(1L, (long) commit(() -> Task.count("name = ?1", plain)));
    }

    @Test
    void aCustomAgentCannotChangeItsTasksModelButAnUnchangedEchoIsNoChange() {
        assertRefused("Only the operator or the main agent may choose the model a task runs on.",
                asAgent(() -> patch("/api/tasks/" + taskId, "{\"modelId\":\"gpt-4.1\",\"description\":\"after\"}")));
        assertEquals(Arrays.asList(null, "before"), commit(() -> {
            var task = (Task) Task.findById(taskId);
            return Arrays.asList(task.modelId, task.description);
        }));

        assertStatus(200, asAgent(() -> patch("/api/tasks/" + taskId, "{\"modelId\":null,\"description\":\"after\"}")));
        assertEquals("after", commit(() -> ((Task) Task.findById(taskId)).description));
    }

    // --- helpers ------------------------------------------------------------------------

    private static void assertRefused(String message, Http.Response response) {
        assertStatus(403, response);
        var body = JsonParser.parseString(getContent(response)).getAsJsonObject();
        assertEquals(List.of(AGENT_SCOPE, message),
                List.of(body.get("code").getAsString(), body.get("message").getAsString()), getContent(response));
    }

    private Http.Request agentRequest() {
        var request = newRequest();
        request.headers.put("authorization", new Http.Header("authorization", "Bearer " + AuthFixture.seedBearerToken()));
        request.headers.put(RequestPrincipal.AGENT_ID_HEADER,
                new Http.Header(RequestPrincipal.AGENT_ID_HEADER, String.valueOf(agentId)));
        return request;
    }

    /** FunctionalTest has no PATCH; this sends one as the agent. */
    private Http.Response patch(String url, String json) {
        var request = agentRequest();
        request.method = "PATCH";
        request.contentType = "application/json";
        request.url = url;
        request.path = url;
        request.querystring = "";
        request.body = new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
        return makeRequest(request);
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
