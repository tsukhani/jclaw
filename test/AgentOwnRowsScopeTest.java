import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import controllers.RequestPrincipal;
import models.Agent;
import models.CompressionMetric;
import models.Conversation;
import models.LatencyMetric;
import models.Message;
import models.MessageRole;
import models.Notification;
import models.Task;
import models.TaskRun;
import models.TaskRunMessage;
import models.VideoGenerationJob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.mvc.Http;
import play.test.FunctionalTest;
import services.AgentService;
import services.AttachmentService;
import services.ConversationService;
import services.Tx;
import utils.AppClock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * An agent reaches only its own notifications, video-generation jobs, hosted-app files and cost,
 * latency and compression rows (JF-292), driven end to end with the bearer and agent header
 * {@code jclaw_api} sends.
 *
 * <p>Each case pairs a refusal or a pinned list with the operator's view of the same route, since a
 * broken header would 403 or empty every caller. {@code main}'s half is in
 * {@code RequestPrincipalPolicyTest}: every route here asks {@code mayReachRow} or
 * {@code scopedAgentId}, and {@code main} is provisioned at boot, where a sibling's
 * {@code Fixtures.deleteDatabase} can remove it mid-suite.
 */
class AgentOwnRowsScopeTest extends FunctionalTest {

    private static final String AGENT_SCOPE = "agent_scope";
    private static final String UNSCOPED = "This request is agent-originated but names no agent, so it cannot be scoped.";

    private record Seed(Long agentId, String name, Long conversationId, Long notificationId, Long jobId,
                        String channel) {}

    private Seed own;
    private Seed alien;

    // No Fixtures.deleteDatabase(): test classes run concurrently, and every row here is this
    // class's by its agent's unique name.
    @BeforeEach
    void seed() {
        AuthFixture.seedAdminPassword("changeme");
        // The other agent's rows are the newer ones, so a limit applied before the pin would return them.
        own = seedAgent("jf292-rows-own-", 120);
        alien = seedAgent("jf292-rows-alien-", 60);
    }

    @AfterEach
    void cleanUp() throws Exception {
        clearCookies();
        for (var seed : List.of(own, alien)) {
            commit(() -> {
                LatencyMetric.delete("agentId = ?1", String.valueOf(seed.agentId()));
                CompressionMetric.delete("agentId = ?1", String.valueOf(seed.agentId()));
                return null;
            });
            var dir = AgentService.workspaceRoot().resolve(seed.name());
            if (!Files.exists(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                for (var p : walk.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(p);
            }
        }
    }

    // --- notifications ------------------------------------------------------------------

    @Test
    void anAgentListsOnlyItsOwnNotificationsWhateverTheLimit() {
        for (var query : List.of("?status=all", "?status=unread", "?status=all&limit=1", "")) {
            var list = asAgent(() -> GET(agentRequest(own.agentId()), "/api/notifications" + query));
            assertStatus(200, list);
            assertEquals(List.of(own.notificationId()), ids(rows(list)), query);
        }
    }

    @Test
    void theOperatorListsEveryAgentsNotifications() {
        login();
        var list = GET("/api/notifications?status=all&limit=500");
        assertStatus(200, list);
        var mine = new ArrayList<Long>();
        for (var row : rows(list)) {
            long agentId = row.getAsJsonObject().get("agentId").getAsLong();
            if (agentId == own.agentId() || agentId == alien.agentId()) mine.add(row.getAsJsonObject().get("id").getAsLong());
        }
        assertEquals(List.of(alien.notificationId(), own.notificationId()), mine);
    }

    @Test
    void anAgentAcknowledgesItsOwnNotificationAndNotAnotherAgents() {
        var theirs = asAgent(() -> POST(agentRequest(own.agentId()),
                "/api/notifications/" + alien.notificationId() + "/ack", "application/json", "{}"));
        assertRefused("Notification " + alien.notificationId() + " belongs to another agent.", theirs);
        assertNull(commit(() -> Notification.<Notification>findById(alien.notificationId()).acknowledgedAt));

        var mine = asAgent(() -> POST(agentRequest(own.agentId()),
                "/api/notifications/" + own.notificationId() + "/ack", "application/json", "{}"));
        assertStatus(200, mine);
        assertEquals(own.notificationId(), (Long) json(mine).get("id").getAsLong());
        assertNotNull(commit(() -> Notification.<Notification>findById(own.notificationId()).acknowledgedAt));

        assertStatus(404, asAgent(() -> POST(agentRequest(own.agentId()), "/api/notifications/999999999/ack",
                "application/json", "{}")));
    }

    @Test
    void theOperatorAcknowledgesAnyAgentsNotification() {
        login();
        var acked = POST("/api/notifications/" + alien.notificationId() + "/ack", "application/json", "{}");
        assertStatus(200, acked);
        assertNotNull(commit(() -> Notification.<Notification>findById(alien.notificationId()).acknowledgedAt));
    }

    @Test
    void anUnstampedBearerReachesNoNotification() {
        var list = asAgent(() -> GET(bareBearerRequest(), "/api/notifications?status=all"));
        assertStatus(403, list);
        assertEquals(List.of(AGENT_SCOPE, UNSCOPED), codeAndMessage(list));
        assertRefused("Notification " + own.notificationId() + " belongs to another agent.",
                asAgent(() -> POST(bareBearerRequest(), "/api/notifications/" + own.notificationId() + "/ack",
                        "application/json", "{}")));
    }

    // --- video-generation jobs ----------------------------------------------------------

    @Test
    void anAgentSeesOnlyItsOwnVideoJobsByIdAndAnotherAgentsAreLeftOut() {
        var ids = own.jobId() + "," + alien.jobId() + ",999999999";
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/videogen/jobs?ids=" + ids));
        assertStatus(200, mine);
        assertEquals(List.of(own.jobId()), ids(rows(mine)));

        login();
        var all = GET("/api/videogen/jobs?ids=" + ids);
        assertStatus(200, all);
        assertEquals(List.of(own.jobId(), alien.jobId()), ids(rows(all)));
    }

    @Test
    void anAgentsRecentVideoJobsAreItsOwn() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/videogen/jobs/recent"));
        assertStatus(200, mine);
        assertEquals(List.of(own.jobId()), ids(rows(mine)));

        login();
        var all = GET("/api/videogen/jobs/recent");
        assertStatus(200, all);
        var seen = new ArrayList<Long>();
        for (var id : ids(rows(all))) {
            if (id.equals(own.jobId()) || id.equals(alien.jobId())) seen.add(id);
        }
        // Newest first, and the other agent's job is the newer one.
        assertEquals(List.of(alien.jobId(), own.jobId()), seen);
    }

    @Test
    void aVideoJobIsItsConversationsAgentsWhenItNamesNoAgentAndTheOperatorsWhenItNamesNeither() {
        var seeded = commit(() -> {
            var byConversation = new VideoGenerationJob();
            byConversation.conversation = Conversation.findById(own.conversationId());
            byConversation.provider = "jf292";
            byConversation.prompt = "by conversation";
            byConversation.save();
            var ownerless = new VideoGenerationJob();
            ownerless.provider = "jf292";
            ownerless.prompt = "ownerless";
            ownerless.save();
            return new Long[]{byConversation.id, ownerless.id};
        });
        try {
            var ids = seeded[0] + "," + seeded[1];
            var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/videogen/jobs?ids=" + ids));
            assertStatus(200, mine);
            assertEquals(List.of(seeded[0]), ids(rows(mine)));

            var theirs = asAgent(() -> GET(agentRequest(alien.agentId()), "/api/videogen/jobs?ids=" + ids));
            assertStatus(200, theirs);
            assertEquals("[]", getContent(theirs));

            login();
            var all = GET("/api/videogen/jobs?ids=" + ids);
            assertStatus(200, all);
            assertEquals(List.of(seeded[0], seeded[1]), ids(rows(all)));
        } finally {
            commit(() -> VideoGenerationJob.delete("id = ?1", seeded[1]));
        }
    }

    @Test
    void anUnstampedBearerSeesNoVideoJob() {
        for (var path : List.of("/api/videogen/jobs?ids=" + own.jobId(), "/api/videogen/jobs/recent")) {
            var resp = asAgent(() -> GET(bareBearerRequest(), path));
            assertStatus(403, resp);
            assertEquals(List.of(AGENT_SCOPE, UNSCOPED), codeAndMessage(resp), path);
        }
    }

    // --- hosted-app files ---------------------------------------------------------------

    @Test
    void anAppsFileIsItsAgentsAndNotAnotherAgents() {
        var slug = "jf292" + UUID.randomUUID().toString().substring(0, 8);
        var uuid = commit(() -> {
            Agent agent = Agent.findById(own.agentId());
            var conversation = ConversationService.create(agent, "app", slug);
            var message = ConversationService.appendAssistantMessage(conversation, "here is your file", null);
            return AttachmentService.persistGeneratedAttachment(agent, message,
                    "%PDF-1.4 jf292".getBytes(StandardCharsets.ISO_8859_1), "application/pdf", null, "report.pdf").uuid;
        });
        var path = "/api/apps/" + slug + "/files/" + uuid;

        var mine = asAgent(() -> GET(agentRequest(own.agentId()), path));
        assertStatus(200, mine);
        assertEquals("attachment; filename=\"report.pdf\"", mine.headers.get("Content-Disposition").value());

        assertRefused("Attachment " + uuid + " belongs to another agent.",
                asAgent(() -> GET(agentRequest(alien.agentId()), path)));
        assertRefused("Attachment " + uuid + " belongs to another agent.", asAgent(() -> GET(bareBearerRequest(), path)));
        assertStatus(404, asAgent(() -> GET(agentRequest(own.agentId()),
                "/api/apps/" + slug + "/files/" + UUID.randomUUID())));

        login();
        var operators = GET(path);
        assertStatus(200, operators);
        assertEquals("attachment; filename=\"report.pdf\"", operators.headers.get("Content-Disposition").value());
    }

    // --- cost, latency and compression rows ---------------------------------------------

    @Test
    void anAgentsCostRowsAreItsOwnWhicheverAgentItAsksFor() {
        for (var query : List.of("", "?agentId=" + alien.agentId(), "?agentId=" + own.agentId())) {
            var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/metrics/cost" + query));
            assertStatus(200, mine);
            assertEquals(List.of(own.agentId() + ":task", own.agentId() + ":" + own.channel()), costRows(mine), query);
        }

        login();
        var theirs = GET("/api/metrics/cost?agentId=" + alien.agentId());
        assertStatus(200, theirs);
        assertEquals(List.of(alien.agentId() + ":task", alien.agentId() + ":" + alien.channel()), costRows(theirs));
    }

    @Test
    void anAgentsLatencyRowsAreItsOwnWhicheverAgentItAsksFor() {
        for (var query : List.of("", "?agentId=" + alien.agentId())) {
            var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/metrics/latency/rows" + query));
            assertStatus(200, mine);
            assertEquals(List.of(List.of(own.channel()), List.of("seg-" + own.channel())),
                    List.of(strings(json(mine).getAsJsonArray("channels")), List.copyOf(json(mine).getAsJsonObject("segments").keySet())),
                    query);
        }

        login();
        var theirs = GET("/api/metrics/latency/rows?agentId=" + alien.agentId());
        assertStatus(200, theirs);
        assertEquals(List.of(List.of(alien.channel()), List.of("seg-" + alien.channel())),
                List.of(strings(json(theirs).getAsJsonArray("channels")), List.copyOf(json(theirs).getAsJsonObject("segments").keySet())));
    }

    @Test
    void anAgentsCompressionTotalsAreItsOwnWhicheverAgentItAsksFor() {
        for (var query : List.of("", "?agentId=" + alien.agentId())) {
            var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/metrics/compression" + query));
            assertStatus(200, mine);
            var body = json(mine);
            assertEquals(List.of(String.valueOf(own.agentId()), own.channel(), "1", "1000", "400"),
                    List.of(String.join(",", strings(body.getAsJsonArray("agents"))),
                            String.join(",", strings(body.getAsJsonArray("channels"))),
                            body.get("compressions").getAsString(), body.get("tokensBefore").getAsString(),
                            body.get("tokensAfter").getAsString()),
                    query);
        }

        login();
        var all = json(GET("/api/metrics/compression"));
        var agents = new TreeSet<>(strings(all.getAsJsonArray("agents")));
        agents.retainAll(List.of(String.valueOf(own.agentId()), String.valueOf(alien.agentId())));
        assertEquals(new TreeSet<>(List.of(String.valueOf(own.agentId()), String.valueOf(alien.agentId()))), agents);
        var theirs = json(GET("/api/metrics/compression?agentId=" + alien.agentId()));
        assertEquals(List.of("1", "2000", "900"), List.of(theirs.get("compressions").getAsString(),
                theirs.get("tokensBefore").getAsString(), theirs.get("tokensAfter").getAsString()));
    }

    @Test
    void anAgentsCompressionEventsAreItsOwn() {
        var mine = asAgent(() -> GET(agentRequest(own.agentId()), "/api/metrics/compression/rows?limit=1"));
        assertStatus(200, mine);
        var rows = json(mine).getAsJsonArray("rows");
        assertEquals(List.of(own.agentId() + ":" + own.channel() + ":1000"), compressionRows(rows));
        assertTrue(json(mine).get("nextBefore").isJsonNull(), getContent(mine));

        login();
        var all = GET("/api/metrics/compression/rows?limit=50");
        assertStatus(200, all);
        var seen = new ArrayList<String>();
        for (var row : compressionRows(json(all).getAsJsonArray("rows"))) {
            if (row.startsWith(own.agentId() + ":") || row.startsWith(alien.agentId() + ":")) seen.add(row);
        }
        assertEquals(List.of(alien.agentId() + ":" + alien.channel() + ":2000", own.agentId() + ":" + own.channel() + ":1000"),
                seen);
    }

    @Test
    void anUnstampedBearerSeesNoMetricRows() {
        for (var path : List.of("/api/metrics/cost", "/api/metrics/latency/rows", "/api/metrics/compression",
                "/api/metrics/compression/rows")) {
            var resp = asAgent(() -> GET(bareBearerRequest(), path));
            assertStatus(403, resp);
            assertEquals(List.of(AGENT_SCOPE, UNSCOPED), codeAndMessage(resp), path);
        }
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

    private static JsonObject json(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonObject();
    }

    private static JsonArray rows(Http.Response response) {
        return JsonParser.parseString(getContent(response)).getAsJsonArray();
    }

    private static List<Long> ids(JsonArray rows) {
        var out = new ArrayList<Long>();
        for (var row : rows) out.add(row.getAsJsonObject().get("id").getAsLong());
        return out;
    }

    private static List<String> strings(JsonArray values) {
        var out = new ArrayList<String>();
        for (var value : values) out.add(value.getAsString());
        return out;
    }

    /** Each cost row as {@code agentId:channelType}, in the order the route returns them. */
    private static List<String> costRows(Http.Response response) {
        var out = new ArrayList<String>();
        for (var row : json(response).getAsJsonArray("rows")) {
            var o = row.getAsJsonObject();
            out.add(o.get("agentId").getAsLong() + ":" + o.get("channelType").getAsString());
        }
        return out;
    }

    /** Each compression event as {@code agentId:channel:tokensBefore}. */
    private static List<String> compressionRows(JsonArray rows) {
        var out = new ArrayList<String>();
        for (var row : rows) {
            var o = row.getAsJsonObject();
            out.add((o.get("agentId").isJsonNull() ? "null" : o.get("agentId").getAsString()) + ":"
                    + (o.get("channel").isJsonNull() ? "null" : o.get("channel").getAsString()) + ":"
                    + o.get("tokensBefore").getAsInt());
        }
        return out;
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json", "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    /**
     * An agent with a conversation holding one costed reply, a task run holding another, one unread
     * notification, one video job, one latency sample and one compression event, each stamped
     * {@code secondsAgo} back so the two agents' rows have a known order.
     */
    private static Seed seedAgent(String prefix, int secondsAgo) {
        var suffix = UUID.randomUUID().toString().substring(0, 8);
        var name = prefix + suffix;
        var channel = "c" + suffix;
        var at = AppClock.now().minus(secondsAgo, ChronoUnit.SECONDS);
        boolean first = secondsAgo > 60;
        var ids = commit(() -> {
            var agent = new Agent();
            agent.name = name;
            agent.modelProvider = "openrouter";
            agent.modelId = "gpt-4.1";
            agent.enabled = true;
            agent.save();

            var conversation = new Conversation();
            conversation.agent = agent;
            conversation.channelType = channel;
            conversation.peerId = "jf292";
            conversation.save();
            var reply = new Message();
            reply.conversation = conversation;
            reply.role = "assistant";
            reply.content = "a costed reply";
            reply.usageJson = "{\"promptTokens\":10}";
            reply.save();   // stamped now by Message.onCreate, so it follows the task reply below

            var task = new Task();
            task.agent = agent;
            task.name = "jf292-task-" + suffix;
            task.description = "the prompt of " + name;
            task.type = Task.Type.IMMEDIATE;
            task.status = Task.initialStatusFor(Task.Type.IMMEDIATE);
            task.save();
            var run = new TaskRun();
            run.task = task;
            run.startedAt = at;
            run.status = TaskRun.Status.COMPLETED;
            run.completedAt = at;
            run.durationMs = 100L;
            run.save();
            var runReply = new TaskRunMessage();
            runReply.taskRun = run;
            runReply.role = MessageRole.ASSISTANT;
            runReply.content = "a costed task reply";
            runReply.usageJson = "{\"promptTokens\":20}";
            runReply.createdAt = at;
            runReply.save();

            var notification = new Notification();
            notification.agent = agent;
            notification.content = "a reminder for " + name;
            notification.createdAt = at;
            notification.save();

            var job = new VideoGenerationJob();
            job.agent = agent;
            job.provider = "jf292";
            job.prompt = "a video for " + name;
            job.createdAt = at;
            job.save();

            var latency = new LatencyMetric();
            latency.agentId = String.valueOf(agent.id);
            latency.channel = channel;
            latency.segment = "seg-" + channel;
            latency.latencyMs = 42;
            latency.createdAt = at;
            latency.save();

            var compression = new CompressionMetric();
            compression.kind = CompressionMetric.Kind.COMPRESSION;
            compression.agentId = String.valueOf(agent.id);
            compression.channel = channel;
            compression.contentType = "TEXT";
            compression.algorithm = "jf292";
            compression.tokensBefore = first ? 1000 : 2000;
            compression.tokensAfter = first ? 400 : 900;
            compression.createdAt = at;
            compression.save();

            return new Long[]{agent.id, conversation.id, notification.id, job.id};
        });
        return new Seed(ids[0], name, ids[1], ids[2], ids[3], channel);
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
