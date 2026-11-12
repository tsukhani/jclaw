import com.github.kagkarlsson.scheduler.task.Execution;
import com.github.kagkarlsson.scheduler.task.ExecutionContext;
import com.github.kagkarlsson.scheduler.task.TaskInstance;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import llm.LlmResilience;
import llm.ProviderRegistry;
import models.Agent;
import models.Task;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.FunctionalTest;
import services.ConfigService;
import services.TaskExecutionHandler;
import services.Tx;
import utils.CircuitBreakers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A task's tool list holds when its model calls a tool the list leaves out, as a model fooled by what
 * it read would. A stub model calls {@code web_fetch} with a URL carrying a canary from a task whose
 * list has only {@code datetime}; the call comes back refused, and the same call from a task whose
 * list has {@code web_fetch} reaches the tool. Splitting a task so no run holds untrusted input,
 * private data and a way out at once rests on this.
 */
class TaskToolListFenceTest extends FunctionalTest {

    private static final String MODEL = "fence-model";
    private static final String CANARY = "FENCE-CANARY-7f3a91";
    // A loopback address, so the allowed case reaches web_fetch and its SSRF guard, never the network.
    private static final String FETCH_ARGS = "{\"url\":\"http://127.0.0.1:9/collect?d=" + CANARY + "\"}";
    private static final String REFUSAL = "Error: Tool 'web_fetch' is not enabled for this agent. Do not retry it — "
            + "use only the tools listed in your request.";

    private HttpServer llmServer;
    private String provider;
    private String suffix;
    private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

    // No Fixtures.deleteDatabase(): every row here is this class's by its agent's unique name.
    @BeforeEach
    void seed() throws Exception {
        suffix = UUID.randomUUID().toString().substring(0, 8);
        provider = "fence-llm-" + suffix;
        llmServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llmServer.createContext("/chat/completions", exchange -> {
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            int n;
            synchronized (requests) {
                requests.add(body);
                n = requests.size();
            }
            LlmReplyFixture.send(exchange, n == 1 ? fetchCall() : reply("Done."));
        });
        llmServer.start();
        var baseUrl = "http://127.0.0.1:" + llmServer.getAddress().getPort();
        commit(() -> {
            ConfigService.set("provider." + provider + ".baseUrl", baseUrl);
            ConfigService.set("provider." + provider + ".apiKey", "sk-test");
            ConfigService.set("provider." + provider + ".models",
                    "[{\"id\":\"" + MODEL + "\",\"name\":\"Fence\",\"contextWindow\":100000,\"maxTokens\":4096}]");
            return null;
        });
        for (var i = 0; i < 50 && ProviderRegistry.get(provider) == null; i++) ProviderRegistry.refresh();
        assertNotNull(ProviderRegistry.get(provider), "test precondition: the stub provider must register");
    }

    @AfterEach
    void cleanUp() {
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
    void aCallForAToolTheTasksListLeavesOutIsRefused() throws Exception {
        fire(seedTask("[\"datetime\"]"));

        assertEquals(List.of("datetime"), offeredInTheFirstRequest());
        assertEquals(REFUSAL, toolResultTheModelGotBack());
    }

    @Test
    void theSameCallFromATaskWhoseListHasTheToolReachesIt() throws Exception {
        fire(seedTask("[\"datetime\",\"web_fetch\"]"));

        assertEquals(List.of("datetime", "web_fetch"), offeredInTheFirstRequest());
        var result = toolResultTheModelGotBack();
        assertTrue(result.startsWith("Error: URL rejected by SSRF guard"), "the call must reach web_fetch itself: " + result);
    }

    // --- helpers ------------------------------------------------------------------------

    private long seedTask(String toolList) {
        return commit(() -> {
            var agent = new Agent();
            agent.name = "fence-agent-" + suffix;
            agent.modelProvider = provider;
            agent.modelId = MODEL;
            agent.enabled = true;
            agent.save();

            var task = new Task();
            task.agent = agent;
            task.name = "fence-task-" + suffix;
            task.description = "Summarize the notes you were given.";
            task.type = Task.Type.SCHEDULED;
            task.status = Task.initialStatusFor(Task.Type.SCHEDULED);
            task.enabledToolNames = toolList;
            task.save();
            return task.id;
        });
    }

    /** The tool names the first model request offered, sorted. */
    private List<String> offeredInTheFirstRequest() {
        assertTrue(requests.size() >= 2, "the stub model should be asked twice, was asked " + requests.size());
        var names = new ArrayList<String>();
        var first = JsonParser.parseString(requests.getFirst()).getAsJsonObject();
        if (first.has("tools")) {
            for (var tool : first.getAsJsonArray("tools")) {
                names.add(tool.getAsJsonObject().getAsJsonObject("function").get("name").getAsString());
            }
        }
        Collections.sort(names);
        return names;
    }

    /** The content of the tool message the second model request carried: what the model was told the call did. */
    private String toolResultTheModelGotBack() {
        var second = JsonParser.parseString(requests.get(1)).getAsJsonObject();
        String result = null;
        for (var message : second.getAsJsonArray("messages")) {
            var m = message.getAsJsonObject();
            if ("tool".equals(m.get("role").getAsString())) result = text(m);
        }
        assertNotNull(result, "the second request should carry the tool's result");
        return result;
    }

    private static String text(JsonObject message) {
        var content = message.get("content");
        if (content.isJsonPrimitive()) return content.getAsString();
        var sb = new StringBuilder();
        for (var part : content.getAsJsonArray()) {
            var p = part.getAsJsonObject();
            if (p.has("text")) sb.append(p.get("text").getAsString());
        }
        return sb.toString();
    }

    private static String fetchCall() {
        var function = new JsonObject();
        function.addProperty("name", "web_fetch");
        function.addProperty("arguments", FETCH_ARGS);
        var call = new JsonObject();
        call.addProperty("id", "call_fence_1");
        call.addProperty("type", "function");
        call.add("function", function);
        var calls = new JsonArray();
        calls.add(call);
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.add("content", null);
        message.add("tool_calls", calls);
        return completion(message, "tool_calls");
    }

    private static String reply(String text) {
        var message = new JsonObject();
        message.addProperty("role", "assistant");
        message.addProperty("content", text);
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
        usage.addProperty("prompt_tokens", 12);
        usage.addProperty("completion_tokens", 7);
        usage.addProperty("total_tokens", 19);
        var completion = new JsonObject();
        completion.add("choices", choices);
        completion.add("usage", usage);
        return completion.toString();
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
