import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import controllers.RequestPrincipal;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Conversation;
import models.Message;
import models.SubagentRun;
import models.Task;
import models.TaskRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.ConfigService;
import services.TaskExecutionHandler;
import services.Tx;
import utils.AppClock;
import utils.CircuitBreakers;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A {@code web:<id>} target the operator saves for a task of another agent is delivered to when the task
 * fires, a target the agent saved is judged as the agent's, and an agent's change of the target makes it
 * the agent's again (JF-305). Each test saves through {@code POST} or {@code PATCH /api/tasks} and then
 * runs the production fire body against a stub provider.
 *
 * <p>{@code main}'s save is in {@code WebDeliveryScopeTest}: its row is provisioned by code and a test may
 * not seed one.
 */
class OperatorWebTargetFireTest extends FunctionalTest {

    private static final String OUTPUT = "The nightly report.";
    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}""".formatted(OUTPUT);
    private static final String MODEL = "jf305-model";

    private record Seed(Long agentId, Long conversationId, Long taskId) {}

    private record Family(Long childId, Long taskId, Long startedConversationId, Long runId) {}

    private HttpServer llmServer;
    private String provider;
    private String suffix;
    private Seed worker;
    private Seed other;

    // No Fixtures.deleteDatabase(): every row here is this class's by its agent's unique name.
    @BeforeEach
    void seed() throws Exception {
        AuthFixture.seedAdminPassword("changeme");
        suffix = UUID.randomUUID().toString().substring(0, 8);
        provider = "jf305-llm-" + suffix;
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            LlmReplyFixture.send(exchange, REPLY);
        });
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commit(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Task\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");

        worker = seedAgent("jf305-worker-" + suffix);
        other = seedAgent("jf305-other-" + suffix);
    }

    @AfterEach
    void cleanUp() {
        clearCookies();
        llmServer.stop(0);
        commit(() -> {
            for (var key : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + key);
            }
            return null;
        });
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
    }

    @Test
    void aTargetTheOperatorSavesForAnAgentsTaskIsDeliveredToWhenItFires() throws Exception {
        login();
        assertStatus(200, patch(operatorRequest(), "/api/tasks/" + worker.taskId(), delivery(other.conversationId())));

        fire(worker.taskId());

        assertEquals(deliveredTo(other.conversationId()), lastRun(worker.taskId()));
        assertEquals(List.of(OUTPUT), deliveredInto(other.conversationId()));
        assertEquals(List.of(), deliveredInto(worker.conversationId()));
    }

    @Test
    void aTaskTheOperatorCreatesForAnAgentDeliversIntoTheConversationItNames() throws Exception {
        login();
        var created = POST("/api/tasks", "application/json",
                "{\"agentId\":%d,\"name\":\"jf305-created-%s\",\"schedule\":\"1d\",\"delivery\":\"web:%d\"}"
                        .formatted(worker.agentId(), suffix, other.conversationId()));
        assertStatus(200, created);
        var taskId = body(created).get("id").getAsLong();

        fire(taskId);

        assertEquals(deliveredTo(other.conversationId()), lastRun(taskId));
        assertEquals(List.of(OUTPUT), deliveredInto(other.conversationId()));
    }

    @Test
    void aTargetSavedBeforeTheRuleIsRefusedAtTheFireUntilTheOperatorSavesItAgain() throws Exception {
        // Written straight to the row: what a task saved before the column existed holds, at the column's default.
        commit(() -> {
            var task = (Task) Task.findById(worker.taskId());
            task.delivery = "web:" + other.conversationId();
            task.save();
            return null;
        });

        fire(worker.taskId());

        assertEquals(notDeliveredTo(other.conversationId()), lastRun(worker.taskId()));
        assertEquals(List.of(), deliveredInto(other.conversationId()));

        login();
        assertStatus(200, patch(operatorRequest(), "/api/tasks/" + worker.taskId(), delivery(other.conversationId())));
        fire(worker.taskId());

        assertEquals(deliveredTo(other.conversationId()), lastRun(worker.taskId()));
        assertEquals(List.of(OUTPUT), deliveredInto(other.conversationId()));
    }

    @Test
    void anAgentsEditThatLeavesTheTargetAloneKeepsTheOperatorsTarget() throws Exception {
        var path = "/api/tasks/" + worker.taskId();
        login();
        assertStatus(200, patch(operatorRequest(), path, delivery(other.conversationId())));
        clearCookies();

        var edited = asAgent(() -> patch(agentRequest(worker.agentId()), path, "{\"description\":\"rewritten\"}"));
        assertStatus(200, edited);
        assertEquals(List.of("rewritten", "web:" + other.conversationId()),
                List.of(body(edited).get("description").getAsString(), body(edited).get("delivery").getAsString()));

        fire(worker.taskId());

        assertEquals(deliveredTo(other.conversationId()), lastRun(worker.taskId()));
        assertEquals(List.of(OUTPUT), deliveredInto(other.conversationId()));
    }

    @Test
    void anAgentsChangeOfTheTargetIsJudgedAsTheAgentsFromThenOn() throws Exception {
        var family = seedFamily();
        var path = "/api/tasks/" + family.taskId();
        login();
        assertStatus(200, patch(operatorRequest(), path, delivery(other.conversationId())));
        clearCookies();

        // In the child's reach for as long as the run that chat started is on record.
        var changed = asAgent(() -> patch(agentRequest(family.childId()), path, delivery(family.startedConversationId())));
        assertStatus(200, changed);
        assertEquals("web:" + family.startedConversationId(), body(changed).get("delivery").getAsString());
        commit(() -> {
            ((SubagentRun) SubagentRun.findById(family.runId())).delete();
            return null;
        });

        fire(family.taskId());

        assertEquals(notDeliveredTo(family.startedConversationId()), lastRun(family.taskId()));
        assertEquals(List.of(), deliveredInto(family.startedConversationId()));
    }

    // --- helpers -------------------------------------------------------------------------

    private static String delivery(Long conversationId) {
        return "{\"delivery\":\"web:%d\"}".formatted(conversationId);
    }

    /** What {@link #lastRun} reads for a run whose output reached {@code conversationId}. */
    private static List<String> deliveredTo(Long conversationId) {
        return List.of("COMPLETED", "DELIVERED", "web:" + conversationId, "null");
    }

    /** What {@link #lastRun} reads for a run whose target the fire refused. */
    private static List<String> notDeliveredTo(Long conversationId) {
        return List.of("COMPLETED", "NOT_DELIVERED", "web:" + conversationId,
                "Web target conversation " + conversationId + " not found.");
    }

    /** The newest run of {@code taskId}: its status, delivery status, target and error. */
    private static List<String> lastRun(Long taskId) {
        return commit(() -> {
            TaskRun run = TaskRun.find("task.id = ?1 ORDER BY id DESC", taskId).first();
            assertNotNull(run, "the fire opened a run");
            return List.of(String.valueOf(run.status), String.valueOf(run.deliveryStatus),
                    String.valueOf(run.deliveryTarget), String.valueOf(run.deliveryError));
        });
    }

    /** What web delivery appended to the conversation, oldest first. */
    private static List<String> deliveredInto(Long conversationId) {
        return commit(() -> {
            var contents = new ArrayList<String>();
            for (var row : Message.find("conversation.id = ?1 AND messageKind = ?2 ORDER BY id ASC",
                    conversationId, "subagent_send").fetch()) {
                contents.add(((Message) row).content);
            }
            return contents;
        });
    }

    private static JsonObject body(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    /** An agent on the stub provider with one web conversation and a task that delivers into it. */
    private Seed seedAgent(String name) {
        return commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = provider;
            agent.modelId = MODEL;
            agent.enabled = true;
            agent.save();

            var conversation = conversation(agent, null);

            var task = new Task();
            task.agent = agent;
            task.name = "task-of-" + name;
            task.description = "Report.";
            task.type = Task.Type.SCHEDULED;
            task.status = Task.initialStatusFor(Task.Type.SCHEDULED);
            task.delivery = "web:" + conversation.id;
            task.save();
            return new Seed(agent.id, conversation.id, task.id);
        });
    }

    /** A parent's chat and a subagent, with a task of its own, whose session run that chat started. */
    private Family seedFamily() {
        var parent = seedAgent("jf305-parent-" + suffix);
        return commit(() -> {
            var parentAgent = (Agent) Agent.findById(parent.agentId());
            var started = (Conversation) Conversation.findById(parent.conversationId());
            var child = new Agent();
            child.name = "jf305-child-" + suffix;
            child.modelProvider = provider;
            child.modelId = MODEL;
            child.enabled = true;
            child.parentAgent = parentAgent;
            child.save();
            var childChat = conversation(child, started);

            var run = new SubagentRun();
            run.parentAgent = parentAgent;
            run.childAgent = child;
            run.parentConversation = started;
            run.childConversation = childChat;
            run.startedAt = AppClock.now();
            run.save();

            var task = new Task();
            task.agent = child;
            task.name = "task-of-" + child.name;
            task.description = "Report.";
            task.type = Task.Type.SCHEDULED;
            task.status = Task.initialStatusFor(Task.Type.SCHEDULED);
            task.save();
            return new Family(child.id, task.id, started.id, run.id);
        });
    }

    private static Conversation conversation(Agent agent, Conversation parent) {
        var conversation = new Conversation();
        conversation.agent = agent;
        conversation.channelType = "web";
        conversation.peerId = "jf305";
        conversation.parentConversation = parent;
        conversation.save();
        return conversation;
    }

    /** Runs the production fire body, as db-scheduler does, on a thread with no transaction. */
    private static void fire(long taskId) throws Exception {
        var instance = new TaskInstance<Void>(TaskExecutionHandler.TASK_NAME, Long.toString(taskId));
        var ctx = new ExecutionContext(null, new Execution(Instant.now(), instance), null, null);
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                TaskExecutionHandler.buildTask().execute(instance, ctx);
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the fire should finish within 30s");
        if (error.get() != null) throw error.get();
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
