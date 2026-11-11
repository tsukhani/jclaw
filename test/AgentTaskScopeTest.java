import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageAttachment;
import models.MessageRole;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.Tx;
import utils.AppClock;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An agent reaches only its own tasks, task runs and attachments (JF-284), driven end to end with
 * the bearer and agent header {@code jclaw_api} sends.
 *
 * <p>Each case pairs a refusal with a permission on the same route, as {@code AgentScopedAccessTest}
 * does: a broken header would 403 every caller, which a refusal alone cannot tell apart. {@code main}'s
 * half lives in {@code RequestPrincipalPolicyTest}: {@code main} is provisioned at boot, and a sibling's
 * {@code Fixtures.deleteDatabase} can remove it mid-suite. Transcript search is in
 * {@code ApiTasksControllerSearchTest}, which holds the Lucene lock it needs.
 */
class AgentTaskScopeTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";
    private static final byte[] OWN_BYTES = "own attachment bytes".getBytes(StandardCharsets.UTF_8);

    private record Seed(Long agentId, String agentName, Long taskId, String taskName, String description,
                        Long runId, String messageText, String attachmentUuid) {}

    private Seed own;
    private Seed alien;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this
    // class's by its agent's unique name.
    @BeforeEach
    void seed() throws Exception {
        AuthFixture.seedAdminPassword("changeme");
        own = seedAgent("jf284-own-", TaskRun.Status.COMPLETED);
        alien = seedAgent("jf284-alien-", TaskRun.Status.RUNNING);
    }

    @AfterEach
    void cleanUp() throws Exception {
        clearCookies();
        for (var seed : List.of(own, alien)) {
            var dir = AgentService.workspaceRoot().resolve(seed.agentName());
            if (!Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    // --- one task ------------------------------------------------------------------------

    @Test
    void anAgentReadsItsOwnTaskAndNotAnotherAgents() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/" + own.taskId()));
        assertStatus(200, mine);
        assertEquals(own.taskName(), json(mine).get("name").getAsString());

        assertRefused("Task " + alien.taskId() + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/" + alien.taskId())));
        assertStatus(404, asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/999999999")));
    }

    @Test
    void anAgentCannotChangeRunOrDeleteAnotherAgentsTask() {
        var base = "/api/tasks/" + alien.taskId();
        var refused = new ArrayList<Http.Response>();
        refused.add(asAgent(() -> patch(agentRequest(own.agentId()), base, "{\"description\":\"hijacked\"}")));
        refused.add(asAgent(() -> DELETE(agentRequest(own.agentId()), base)));
        for (var action : List.of("cancel", "pause", "resume", "reenable", "run", "retry")) {
            refused.add(asAgent(() -> POST(agentRequest(own.agentId()), base + "/" + action, "application/json", "{}")));
        }
        for (var response : refused) {
            assertRefused("Task " + alien.taskId() + " belongs to another agent.", response);
        }

        var after = commit(() -> {
            var task = (Task) Task.findById(alien.taskId());
            return task == null ? null : List.of(task.description, String.valueOf(task.paused), task.status.name());
        });
        assertEquals(List.of(alien.description(), "false", "PENDING"), after);
    }

    @Test
    void anAgentPausesAndResumesItsOwnTask() {
        var paused = asAgent(() -> POST(agentRequest(own.agentId()), "/api/tasks/" + own.taskId() + "/pause",
                "application/json", "{}"));
        assertStatus(200, paused);
        assertTrue(json(paused).get("paused").getAsBoolean(), getContent(paused));

        var resumed = asAgent(() -> POST(agentRequest(own.agentId()), "/api/tasks/" + own.taskId() + "/resume",
                "application/json", "{}"));
        assertStatus(200, resumed);
        assertFalse(json(resumed).get("paused").getAsBoolean(), getContent(resumed));
    }

    @Test
    void theDeliveryAdvisoryIsTheOwnersOnly() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/" + own.taskId() + "/delivery-advisory"));
        assertStatus(200, mine);
        assertEquals("{\"advisory\":null}", getContent(mine));

        assertRefused("Task " + alien.taskId() + " belongs to another agent.", asAgent(() ->
                GET(agentRequest(own.agentId()), "/api/tasks/" + alien.taskId() + "/delivery-advisory")));
    }

    // --- creating and moving ------------------------------------------------------------

    @Test
    void anAgentCreatesATaskForItselfButNotForAnotherAgent() {
        var foreignName = "jf284-foreign-" + UUID.randomUUID();
        var foreign = asAgent(() -> POST(agentRequest(own.agentId()), "/api/tasks", "application/json",
                "{\"agentId\":%d,\"name\":\"%s\",\"schedule\":\"1d\"}".formatted(alien.agentId(), foreignName)));
        assertRefused("agentId names another agent; an agent creates tasks for itself only.", foreign);
        assertEquals(0L, (long) commit(() -> Task.count("name = ?1", foreignName)));

        var ownName = "jf284-created-" + UUID.randomUUID();
        var created = asAgent(() -> POST(agentRequest(own.agentId()), "/api/tasks", "application/json",
                "{\"agentId\":%d,\"name\":\"%s\",\"schedule\":\"1d\"}".formatted(own.agentId(), ownName)));
        assertStatus(200, created);
        assertEquals(own.agentId(), commit(() -> {
            var task = (Task) Task.find("name = ?1", ownName).first();
            return task.agent.id;
        }));
    }

    @Test
    void aPatchNamingAnotherAgentLeavesTheTaskWithItsOwner() {
        var response = asAgent(() -> patch(agentRequest(own.agentId()), "/api/tasks/" + own.taskId(),
                "{\"agentId\":%d,\"description\":\"rewritten by its owner\"}".formatted(alien.agentId())));
        assertStatus(200, response);

        assertEquals(List.of(String.valueOf(own.agentId()), "rewritten by its owner"), commit(() -> {
            var task = (Task) Task.findById(own.taskId());
            return List.of(String.valueOf(task.agent.id), task.description);
        }));
    }

    // --- lists --------------------------------------------------------------------------

    @Test
    void theTaskListHoldsOnlyTheCallersTasksWhateverAgentItNames() {
        for (var url : List.of("/api/tasks", "/api/tasks?agentId=" + alien.agentId())) {
            var response = asAgent(() -> GET(agentRequest(own.agentId()), url));
            assertStatus(200, response);
            assertEquals(List.of(own.taskName()), names(JsonParser.parseString(getContent(response)).getAsJsonArray()), url);
            assertEquals("1", response.getHeader("X-Total-Count"), url);
        }
    }

    @Test
    void recentRunsHoldOnlyTheCallersRuns() {
        var response = asAgent(() -> GET(agentRequest(own.agentId()), "/api/task-runs/recent?hours=1"));
        assertStatus(200, response);
        assertEquals(List.of(own.runId()), ids(JsonParser.parseString(getContent(response)).getAsJsonArray()));
    }

    @Test
    void theStatsCountOnlyTheCallersTasksAndRuns() {
        var response = asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/stats"));
        assertStatus(200, response);
        var stats = json(response);

        // The alien's RUNNING run and PENDING task are not counted.
        assertEquals(List.of(1L, 1L, 0L, 0L, 0L, 0L), List.of(stats.get("runsToday").getAsLong(),
                stats.get("pendingCount").getAsLong(), stats.get("runningCount").getAsLong(),
                stats.get("pausedCount").getAsLong(), stats.get("activeCount").getAsLong(),
                stats.get("failedCount").getAsLong()));
        assertEquals(1.0, stats.get("successRate").getAsDouble());
        assertEquals(100.0, stats.get("avgDurationMs").getAsDouble());
    }

    @Test
    void anUnstampedBearerIsRefusedTheTaskList() {
        var response = asAgent(() -> GET(bareBearerRequest(), "/api/tasks"));
        assertRefused("This request is agent-originated but names no agent, so it cannot be scoped.", response);
    }

    // --- runs ---------------------------------------------------------------------------

    @Test
    void aTasksRunsAreTheOwnersOnly() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/" + own.taskId() + "/runs"));
        assertStatus(200, mine);
        assertEquals(List.of(own.runId()), ids(JsonParser.parseString(getContent(mine)).getAsJsonArray()));

        assertRefused("Task " + alien.taskId() + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(own.agentId()), "/api/tasks/" + alien.taskId() + "/runs")));
    }

    @Test
    void aRunsMessagesAreTheOwnersOnly() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/task-runs/" + own.runId() + "/messages"));
        assertStatus(200, mine);
        var rows = JsonParser.parseString(getContent(mine)).getAsJsonArray();
        assertEquals(1, rows.size(), getContent(mine));
        assertEquals(own.messageText(), rows.get(0).getAsJsonObject().get("content").getAsString());

        assertRefused("Task run " + alien.runId() + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(own.agentId()), "/api/task-runs/" + alien.runId() + "/messages")));
        assertStatus(404, asAgent(() -> GET(agentRequest(own.agentId()), "/api/task-runs/999999999/messages")));
    }

    @Test
    void anAgentCannotCancelAnotherAgentsRun() {
        assertRefused("Task run " + alien.runId() + " belongs to another agent.", asAgent(() ->
                POST(agentRequest(own.agentId()), "/api/task-runs/" + alien.runId() + "/cancel", "application/json", "{}")));
        assertEquals(TaskRun.Status.RUNNING, commit(() -> ((TaskRun) TaskRun.findById(alien.runId())).status));

        // The owner passes the check and meets the next one: a settled run has nothing to cancel.
        assertStatus(400, asAgent(() ->
                POST(agentRequest(own.agentId()), "/api/task-runs/" + own.runId() + "/cancel", "application/json", "{}")));
    }

    // --- attachments --------------------------------------------------------------------

    @Test
    void anAgentDownloadsItsOwnAttachmentAndNotAnotherAgents() {
        // renderBinary streams the file outside the body FunctionalTest captures, so the header that
        // names this attachment is what shows the owner was served it.
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/attachments/" + own.attachmentUuid()));
        assertStatus(200, mine);
        assertEquals("attachment; filename=\"note.txt\"; filename*=UTF-8''note.txt",
                mine.headers.get("Content-Disposition").value());

        assertRefused("Attachment " + alien.attachmentUuid() + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(own.agentId()), "/api/attachments/" + alien.attachmentUuid())));
    }

    // --- helpers ------------------------------------------------------------------------

    private static void assertRefused(String message, Http.Response response) {
        assertStatus(403, response);
        var body = json(response);
        assertEquals(List.of(AGENT_SCOPE, message),
                List.of(body.get("code").getAsString(), body.get("message").getAsString()), getContent(response));
    }

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static List<String> names(JsonArray rows) {
        var out = new ArrayList<String>();
        for (var row : rows) out.add(row.getAsJsonObject().get("name").getAsString());
        return out;
    }

    private static List<Long> ids(JsonArray rows) {
        var out = new ArrayList<Long>();
        for (var row : rows) out.add(row.getAsJsonObject().get("id").getAsLong());
        return out;
    }

    /** An agent with one PENDING task, one run of it in {@code runStatus} with one message, and one
     *  attachment in a conversation of its own whose bytes are on disk. */
    private static Seed seedAgent(String prefix, TaskRun.Status runStatus) throws Exception {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var name = prefix + suffix;
        var uuid = UUID.randomUUID().toString();
        var seeded = commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var task = new Task();
            task.agent = agent;
            task.name = "jf284-task-" + suffix;
            task.description = "the prompt of " + name;
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.initialStatusFor(Task.Type.IMMEDIATE);
            task.save();

            var run = new TaskRun();
            run.task = task;
            run.startedAt = AppClock.now();
            run.status = runStatus;
            if (runStatus != TaskRun.Status.RUNNING) {
                run.completedAt = AppClock.now();
                run.durationMs = 100L;
            }
            run.save();

            var message = new TaskRunMessage();
            message.taskRun = run;
            message.role = MessageRole.ASSISTANT;
            message.content = "the transcript of " + name;
            message.save();

            var convo = new Conversation();
            convo.agent = agent;
            convo.channelType = "web";
            convo.peerId = "jf284";
            convo.save();
            var carrier = new Message();
            carrier.conversation = convo;
            carrier.role = "user";
            carrier.content = "attachment carrier";
            carrier.createdAt = AppClock.now();
            carrier.save();
            var att = new MessageAttachment();
            att.message = carrier;
            att.uuid = uuid;
            att.originalFilename = "note.txt";
            att.storagePath = name + "/attachments/" + convo.id + "/" + uuid + ".txt";
            att.mimeType = "text/plain";
            att.sizeBytes = OWN_BYTES.length;
            att.kind = MessageAttachment.KIND_FILE;
            att.save();

            return new Object[]{agent.id, task.id, task.name, task.description, run.id, message.content, convo.id};
        });
        var dest = AgentService.acquireWorkspacePath(name, "attachments/" + seeded[6] + "/" + uuid + ".txt");
        Files.createDirectories(dest.getParent());
        Files.write(dest, OWN_BYTES);
        return new Seed((Long) seeded[0], name, (Long) seeded[1], (String) seeded[2], (String) seeded[3],
                (Long) seeded[4], (String) seeded[5], uuid);
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
