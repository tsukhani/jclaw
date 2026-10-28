import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.MessageRole;
import models.Task;
import models.TaskRunMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.ConfigService;
import services.TaskExecutionHandler;
import services.Tx;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** A scheduled task's run saves its usage record, and the Cost panel's endpoint counts it (JF-150). */
class TaskRunUsageTest extends FunctionalTest {

    private static final String REPLY = """
            {"choices":[{"index":0,"message":{"role":"assistant","content":"Task done and counted."},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":12,"completion_tokens":7,"total_tokens":19}}""";
    private static final String MODEL = "task-model";

    private final AtomicBoolean refuseCalls = new AtomicBoolean(false);
    private HttpServer llmServer;
    private String provider;

    @BeforeEach
    void setUp() throws Exception {
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        provider = "task-usage-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (refuseCalls.get()) {
                var body = "{\"error\":{\"message\":\"bad request\"}}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
            LlmReplyFixture.send(exchange, REPLY);
        });
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commitInFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Task\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
    }

    @AfterEach
    void tearDown() {
        llmServer.stop(0);
        commitInFreshTx(() -> {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            return null;
        });
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
    }

    @Test
    void aTaskRunSavesItsUsageAndTheCostEndpointCountsItAsTask() throws Exception {
        var ids = seedAgentAndTask("task-usage-agent");

        fire(ids[1]);

        var usage = JsonParser.parseString(finalReplyUsage(ids[1])).getAsJsonObject();
        assertEquals(12, usage.get("prompt").getAsInt());
        assertEquals(7, usage.get("completion").getAsInt());
        assertEquals(19, usage.get("total").getAsInt());
        assertEquals("provider", usage.get("usageSource").getAsString());
        assertEquals(provider, usage.get("modelProvider").getAsString());
        assertEquals(MODEL, usage.get("modelId").getAsString());

        login();
        var rows = costRows("?agentId=" + ids[0]);
        assertEquals(1, rows.size(), rows::toString);
        var row = rows.get(0).getAsJsonObject();
        assertEquals("task", row.get("channelType").getAsString());
        assertEquals(ids[0], row.get("agentId").getAsLong());
        assertEquals(provider, row.get("modelProvider").getAsString());
        assertEquals(usage, JsonParser.parseString(row.get("usageJson").getAsString()));
        assertEquals(1, costRows("?channelType=task").size());
        assertEquals(0, costRows("?channelType=web").size());
    }

    @Test
    void aTaskRunWhoseCallFailsSavesNoUsage() throws Exception {
        refuseCalls.set(true);
        var ids = seedAgentAndTask("task-usage-failing-agent");

        fire(ids[1]);

        assertNull(finalReplyUsage(ids[1]));
        login();
        assertEquals(0, costRows("?agentId=" + ids[0]).size());
    }

    /** Agent and an immediate task, committed so the fire's own transactions see them; returns their ids. */
    private long[] seedAgentAndTask(String agentName) {
        return commitInFreshTx(() -> {
            var agent = new Agent();
            agent.name = agentName;
            agent.modelProvider = provider;
            agent.modelId = MODEL;
            agent.enabled = true;
            agent.save();
            var task = new Task();
            task.agent = agent;
            task.name = "Usage check";
            task.description = "Report on yesterday.";
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.Status.PENDING;
            task.nextRunAt = Instant.now();
            task.createdAt = Instant.now();
            task.updatedAt = Instant.now();
            task.save();
            return new long[] {agent.id, task.id};
        });
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

    private static String finalReplyUsage(long taskId) {
        return commitInFreshTx(() -> {
            TaskRunMessage reply = TaskRunMessage.find(
                    "taskRun.task.id = ?1 AND role = ?2 ORDER BY id DESC", taskId, MessageRole.ASSISTANT).first();
            assertNotNull(reply, "the run saved a final reply");
            return reply.usageJson;
        });
    }

    private JsonArray costRows(String query) {
        return JsonParser.parseString(getContent(GET("/api/metrics/cost" + query)))
                .getAsJsonObject().getAsJsonArray("rows");
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\": \"admin\", \"password\": \"changeme\"}"));
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
            throw new RuntimeException(e);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
