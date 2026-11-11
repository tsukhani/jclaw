import agents.DangerousActionGate;
import agents.ToolContext;
import agents.ToolRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import models.Conversation;
import models.Message;
import models.Prompt;
import models.Task;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.Tx;
import tools.JClawApiTool;
import utils.AppClock;
import utils.HttpFactories;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * What a custom agent reaches through {@code jclaw_api} once the operator has switched it on (JF-293): its own
 * rows, and neither another agent's nor a route that answers only the operator and {@code main}.
 *
 * <p>Each call is the tool's own. The operator's grant goes through the grant route, the call is dispatched as a
 * turn dispatches it, against the tools that turn is offered, and {@link JClawApiTool} builds and stamps the
 * request; the transport then hands that request to this JVM's routes with the headers the tool put on it. Only
 * the bearer is replaced, with a token row of this class's own: the tool's is cached in a static, where it
 * outlives a sibling class's {@code Fixtures.deleteDatabase}.
 */
class JclawApiGrantedAgentScopeTest extends FunctionalTest {

    private static final String SCOPE_WHAT_BROKE =
            "An agent tried to reach another agent's row, or a route that answers only the operator and main.";

    private record Seed(Long agentId, String name, Long chatId, Long taskId) {}

    private Seed own;
    private Seed alien;
    private final List<Request> sent = new ArrayList<>();

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this
    // class's by its agent's unique name.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        own = seedAgent("jf293-own-");
        alien = seedAgent("jf293-alien-");
        ToolRegistrySync.canonicalForTest();
    }

    @AfterEach
    void release() {
        ToolRegistrySync.release();
        clearCookies();
    }

    @Test
    void aGrantedAgentReadsItsOwnRowsThroughTheTool() {
        grantJclawApi(own.agentId());

        assertEquals("HTTP 200\n{\"advisory\":null}",
                call("{\"method\":\"GET\",\"path\":\"/api/tasks/" + own.taskId() + "/delivery-advisory\"}"));

        var transcript = call("{\"method\":\"GET\",\"path\":\"/api/conversations/" + own.chatId() + "/messages\"}");
        assertEquals("HTTP 200", firstLine(transcript));
        assertEquals(List.of("user: the plan of " + own.name()), messages(body(transcript)));

        var agents = call("{\"method\":\"GET\",\"path\":\"/api/agents\"}");
        assertEquals("HTTP 200", firstLine(agents));
        assertEquals(List.of(own.name()), names(body(agents)));
    }

    @Test
    void aPromptTheGrantedAgentCreatesThroughTheToolNamesThatAgentAsItsOwner() {
        grantJclawApi(own.agentId());

        // A new prompt names its owner (JF-307), and an agent other than main may name only itself.
        var created = call("{\"method\":\"POST\",\"path\":\"/api/prompts\",\"body\":{\"title\":\"jf293 " + own.name()
                + "\",\"content\":\"Summarize the day.\",\"category\":\"CUSTOM\",\"ownerAgentId\":" + own.agentId() + "}}");

        assertEquals("HTTP 200", firstLine(created));
        var prompt = JsonParser.parseString(body(created)).getAsJsonObject();
        var promptId = prompt.get("id").getAsLong();
        try {
            assertEquals(List.of(String.valueOf(own.agentId()), own.name()),
                    List.of(prompt.get("ownerAgentId").getAsString(), prompt.get("ownerAgentName").getAsString()));
            assertEquals(own.agentId(), commit(() -> Prompt.<Prompt>findById(promptId).ownerAgent.id), "the stored row");
        } finally {
            commit(() -> Prompt.delete("id = ?1", promptId));
        }
    }

    @Test
    void aGrantedAgentIsRefusedAnotherAgentsRowsThroughTheTool() {
        grantJclawApi(own.agentId());

        assertRefused("Conversation " + alien.chatId() + " belongs to another agent.",
                call("{\"method\":\"GET\",\"path\":\"/api/conversations/" + alien.chatId() + "/messages\"}"));
        assertRefused("Task " + alien.taskId() + " belongs to another agent.",
                call("{\"method\":\"GET\",\"path\":\"/api/tasks/" + alien.taskId() + "\"}"));
        assertRefused("Agent " + alien.agentId() + " is not the calling agent; only main reaches another agent's settings.",
                call("{\"method\":\"GET\",\"path\":\"/api/agents/" + alien.agentId() + "\"}"));
    }

    @Test
    void aGrantedAgentIsRefusedWhatOnlyTheOperatorAndMainMayDoThroughTheTool() {
        grantJclawApi(own.agentId());

        for (var target : List.of(own.agentId(), alien.agentId())) {
            assertRefused("Only the operator or the main agent may change an agent's settings.",
                    call("{\"method\":\"PUT\",\"path\":\"/api/agents/" + target
                            + "\",\"body\":{\"description\":\"set through jclaw_api\"}}"));
            assertNull(commit(() -> Agent.<Agent>findById(target).description));
        }
        assertRefused("Only the operator or the main agent may read the configuration.",
                call("{\"method\":\"GET\",\"path\":\"/api/config\"}"));
    }

    @Test
    void theToolStampsTheCallingAgentAndItsTurnOnEveryRequest() {
        grantJclawApi(own.agentId());

        call("{\"method\":\"GET\",\"path\":\"/api/tasks/" + own.taskId() + "\"}");

        assertEquals(1, sent.size());
        var request = sent.getFirst();
        assertEquals(List.of(String.valueOf(own.agentId()), "web", String.valueOf(own.chatId()), "true"),
                List.of(String.valueOf(request.header(RequestPrincipal.AGENT_ID_HEADER)),
                        String.valueOf(request.header(RequestPrincipal.CALLER_ORIGIN_HEADER)),
                        String.valueOf(request.header(RequestPrincipal.CALLER_CONVERSATION_HEADER)),
                        String.valueOf(request.header(RequestPrincipal.CALLER_OWNER_READS_ALONE_HEADER))));
        assertEquals("Bearer", String.valueOf(request.header("Authorization")).split(" ")[0]);
    }

    @Test
    void withoutTheOperatorsGrantTheToolIsNotTheAgentsToCall() {
        assertEquals("Error: Tool 'jclaw_api' is not enabled for this agent. Do not retry it — "
                + "use only the tools listed in your request.",
                call("{\"method\":\"GET\",\"path\":\"/api/tasks/" + own.taskId() + "/delivery-advisory\"}"));
        assertEquals(List.of(), sent, "nothing was sent");
    }

    // --- helpers ------------------------------------------------------------------------

    /** The operator switches {@code jclaw_api} on for the agent, on the route the Agents page uses. */
    private void grantJclawApi(Long agentId) {
        clearCookies();
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
        var granted = PUT("/api/agents/" + agentId + "/tools/jclaw_api", "application/json", "{\"enabled\":true}");
        clearCookies();
        assertEquals("200 {\"name\":\"jclaw_api\",\"enabled\":true,\"status\":\"ok\"}",
                granted.status + " " + getContent(granted));
    }

    /** One {@code jclaw_api} call by the own agent from its web chat, dispatched as a turn dispatches it. */
    private String call(String argsJson) {
        try {
            return HttpFactories.callWith(inProcess(), () -> ToolContext.withConversation(own.chatId(), () -> {
                Agent agent = Agent.findById(own.agentId());
                return ToolRegistry.executeRich(JClawApiTool.TOOL_NAME, argsJson, agent, offeredTo(agent)).text();
            }));
        } finally {
            clearCookies();
        }
    }

    /** The tool names a turn in the own agent's web chat puts in front of the model. */
    private Set<String> offeredTo(Agent agent) {
        return ToolRegistry.forTurn(ToolRegistry.getToolDefsForAgent(agent), DangerousActionGate.ownerReadsAlone(own.chatId()))
                .stream().map(def -> def.function().name()).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Answers the tool's request from this JVM's routes, carrying the headers the tool stamped. */
    private OkHttpClient inProcess() {
        var token = AuthFixture.seedBearerToken();
        Interceptor dispatch = chain -> {
            var out = chain.request();
            sent.add(out);
            var request = newRequest();
            for (var name : out.headers().names()) {
                var key = name.toLowerCase(Locale.ROOT);
                request.headers.put(key, new Http.Header(key, "authorization".equals(key) ? "Bearer " + token : out.header(name)));
            }
            var query = out.url().encodedQuery();
            var url = out.url().encodedPath() + (query == null ? "" : "?" + query);
            var response = switch (out.method()) {
                case "GET" -> GET(request, url);
                case "PUT" -> PUT(request, url, "application/json", bodyOf(out));
                case "POST" -> POST(request, url, "application/json", bodyOf(out));
                default -> throw new AssertionError("this class sends no " + out.method());
            };
            return new Response.Builder()
                    .request(out)
                    .protocol(Protocol.HTTP_1_1)
                    .code(response.status)
                    .message("status " + response.status)
                    .body(ResponseBody.create(getContent(response), null))
                    .build();
        };
        return new OkHttpClient.Builder().addInterceptor(dispatch).build();
    }

    private static String bodyOf(Request request) throws IOException {
        var buffer = new Buffer();
        var body = request.body();
        if (body != null) body.writeTo(buffer);
        return buffer.readUtf8();
    }

    /** The refusal a route gives an agent out of its scope, as the tool hands it to the model. */
    private static void assertRefused(String message, String result) {
        assertEquals("HTTP 403", firstLine(result));
        var error = JsonParser.parseString(body(result)).getAsJsonObject();
        assertEquals(List.of("agent_scope", message, SCOPE_WHAT_BROKE),
                List.of(error.get("code").getAsString(), error.get("message").getAsString(),
                        error.getAsJsonObject("template").get("whatBroke").getAsString()));
    }

    private static String firstLine(String result) {
        return result.substring(0, Math.max(0, result.indexOf('\n')));
    }

    private static String body(String result) {
        return result.substring(result.indexOf('\n') + 1);
    }

    private static List<String> messages(String body) {
        var out = new ArrayList<String>();
        for (var row : JsonParser.parseString(body).getAsJsonArray()) {
            JsonObject message = row.getAsJsonObject();
            out.add(message.get("role").getAsString() + ": " + message.get("content").getAsString());
        }
        return out;
    }

    private static List<String> names(String body) {
        var out = new ArrayList<String>();
        JsonArray rows = JsonParser.parseString(body).getAsJsonArray();
        for (var row : rows) out.add(row.getAsJsonObject().get("name").getAsString());
        return out;
    }

    private static Seed seedAgent(String prefix) {
        var name = prefix + UUID.randomUUID().toString().substring(0, 8);
        return commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var chat = new Conversation();
            chat.agent = agent;
            chat.channelType = "web";
            chat.peerId = "jf293";
            chat.save();
            var message = new Message();
            message.conversation = chat;
            message.role = "user";
            message.content = "the plan of " + name;
            message.createdAt = AppClock.now();
            message.save();

            var task = new Task();
            task.agent = agent;
            task.name = "jf293-task-" + name;
            task.description = "the prompt of " + name;
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.initialStatusFor(Task.Type.IMMEDIATE);
            task.save();
            return new Seed(agent.id, name, chat.id, task.id);
        });
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
