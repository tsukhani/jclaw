import agents.ToolContext;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;
import tools.AgentSpawnTool;
import utils.CircuitBreakers;
import utils.GsonHolder;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * JF-164: what the operator sees of an {@code agent_spawn} hand-off over HTTP — its row on the run list, the file the
 * callee saved at the {@code files_url} it returns, and a kill of a running hand-off.
 */
class AgentSpawnRunsTest extends FunctionalTest {

    private static final String MODEL = "agent-spawn-runs-model";
    private static final String FILE_TASK = "Write the report file.";
    private static final String HELD_TASK = "Take your time on this one.";
    private static final String REPORT = "Quarterly report\nline two\n";

    private HttpServer llmServer;
    private ExecutorService llmThreads;
    private String provider;
    private final CountDownLatch releaseHeld = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        ToolRegistrySync.canonicalForTest();
        Fixtures.deleteDatabase();
        SubagentRegistry.clear();
        EventLogger.clear();
        AuthFixture.seedAdminPassword("changeme");
        provider = "agent-spawn-runs-" + UUID.randomUUID().toString().substring(0, 8);
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmThreads = Executors.newVirtualThreadPerTaskExecutor();
        llmServer.setExecutor(llmThreads);
        llmServer.createContext("/chat/completions", this::answer);
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commitInFreshTx(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Runs\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) {
            ProviderRegistry.refresh();
            if (ProviderRegistry.get(provider) == null) Thread.sleep(20);
        }
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
        var login = POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}");
        assertIsOk(login);
    }

    @AfterEach
    void tearDown() {
        releaseHeld.countDown();
        llmServer.stop(0);
        llmThreads.shutdownNow();
        commitInFreshTx(() -> {
            for (var suffix : new String[] {".baseUrl", ".apiKey", ".models"}) {
                ConfigService.delete("provider." + provider + suffix);
            }
            return null;
        });
        ProviderRegistry.refresh();
        CircuitBreakers.remove(LlmResilience.breakerName(provider));
        SubagentRegistry.clear();
        EventLogger.clear();
        ToolRegistrySync.release();
    }

    @Test
    void aCompletedHandOffIsListedWithItsCallerAndCalleeAndItsFileIsServedAtFilesUrl() throws Exception {
        var ids = seed("reporter-" + provider);

        var result = JsonParser.parseString(handOff(ids, FILE_TASK).get(60, TimeUnit.SECONDS)).getAsJsonObject();

        assertEquals("COMPLETED", result.get("status").getAsString(), result.toString());
        assertEquals("/api/agents/" + ids.callee() + "/files/", result.get("files_url").getAsString());
        var file = GET(result.get("files_url").getAsString() + "out/report.txt");
        assertIsOk(file);
        // renderBinary hands the file to the server as response.direct; nothing lands in the captured body.
        assertTrue(file.direct instanceof File, "the file is served from disk: " + file.direct);
        assertEquals(REPORT, Files.readString(((File) file.direct).toPath()));

        var list = GET("/api/subagent-runs");
        assertIsOk(list);
        var rows = JsonParser.parseString(getContent(list)).getAsJsonArray();
        assertEquals(1, rows.size(), rows.toString());
        var row = rows.get(0).getAsJsonObject();
        assertEquals(result.get("run_id").getAsLong(), row.get("id").getAsLong());
        assertEquals("main", row.get("parentAgentName").getAsString());
        assertEquals(ids.calleeName(), row.get("childAgentName").getAsString());
        assertEquals("COMPLETED", row.get("status").getAsString());
    }

    @Test
    void killingARunningHandOffEndsItKilledAndDeletingTheRunKeepsTheCallee() throws Exception {
        var ids = seed("slowpoke-" + provider);

        var pending = handOff(ids, HELD_TASK);
        var runId = awaitRunningRun(ids.callee());
        var kill = POST("/api/subagent-runs/" + runId + "/kill", "application/json", "{}");
        assertIsOk(kill);
        var result = JsonParser.parseString(pending.get(60, TimeUnit.SECONDS)).getAsJsonObject();
        releaseHeld.countDown();

        assertEquals("KILLED", result.get("status").getAsString(), result.toString());
        assertIsOk(DELETE("/api/subagent-runs/" + runId));
        assertEquals(Boolean.TRUE, commitInFreshTx(() -> Agent.findById(ids.callee()) != null),
                "the callee is the operator's agent, never the run's to delete");
        assertEquals(Boolean.TRUE, commitInFreshTx(() -> SubagentRun.findById(runId) == null));
    }

    private record Ids(Long main, Long conversation, Long callee, String calleeName) {}

    private Ids seed(String calleeName) {
        return commitInFreshTx(() -> {
            var main = AgentService.create("main", provider, MODEL);
            var callee = AgentService.create(calleeName, provider, MODEL);
            var conv = ConversationService.create(main, "web", "u-runs");
            return new Ids(main.id, conv.id, callee.id, callee.name);
        });
    }

    /** {@code agent_spawn} from main's web chat on a thread of its own, as a turn's tool call runs. */
    private static CompletableFuture<String> handOff(Ids ids, String task) {
        var result = new CompletableFuture<String>();
        Thread.ofVirtual().start(() -> {
            try {
                var main = Tx.run(() -> (Agent) Agent.findById(ids.main()));
                result.complete(ToolContext.withConversation(ids.conversation(), () -> new AgentSpawnTool().execute(
                        GsonHolder.GSON.toJson(Map.of("agent", ids.calleeName(), "task", task)), main)));
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    private static Long awaitRunningRun(Long calleeId) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            Long id = commitInFreshTx(() -> {
                SubagentRun run = SubagentRun.find("childAgent.id = ?1 AND status = ?2",
                        calleeId, SubagentRun.Status.RUNNING).first();
                return run == null ? null : run.id;
            });
            if (id != null) return id;
            Thread.sleep(50);
        }
        fail("no running hand-off within 30s");
        return null;
    }

    private void answer(HttpExchange exchange) throws IOException {
        var messages = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject().getAsJsonArray("messages");
        var userText = new StringBuilder();
        for (var m : messages) {
            var message = m.getAsJsonObject();
            if ("user".equals(message.get("role").getAsString())) userText.append(message.get("content"));
        }
        var last = messages.get(messages.size() - 1).getAsJsonObject();
        if (userText.toString().contains(HELD_TASK)) {
            try {
                releaseHeld.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
            }
            LlmReplyFixture.send(exchange, reply("TOO_LATE"));
        } else if ("tool".equals(last.get("role").getAsString())) {
            LlmReplyFixture.send(exchange, reply("WROTE"));
        } else {
            LlmReplyFixture.send(exchange, writeReportCall());
        }
    }

    private static String writeReportCall() {
        var function = new JsonObject();
        function.addProperty("name", "filesystem");
        function.addProperty("arguments", GsonHolder.GSON.toJson(
                Map.of("action", "writeFile", "path", "out/report.txt", "content", REPORT)));
        var call = new JsonObject();
        call.addProperty("id", "call_write");
        call.addProperty("type", "function");
        call.add("function", function);
        var calls = new JsonArray();
        calls.add(call);
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", "");
        message.add("tool_calls", calls);
        return completion(message, "tool_calls");
    }

    private static String reply(String content) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", content);
        return completion(message, "stop");
    }

    private static String completion(JsonObject message, String finishReason) {
        var choice = new JsonObject();
        choice.addProperty("index", 0);
        choice.add("message", message);
        choice.addProperty("finish_reason", finishReason);
        var choices = new JsonArray();
        choices.add(choice);
        var usage = new JsonObject();
        usage.addProperty("prompt_tokens", 10);
        usage.addProperty("completion_tokens", 5);
        var completion = new JsonObject();
        completion.add("choices", choices);
        completion.add("usage", usage);
        return completion.toString();
    }

    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
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
