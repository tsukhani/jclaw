import agents.ToolRegistry;
import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.NotificationBus;
import services.Tx;
import tools.HarnessEvent;
import tools.SubagentSpawnTool;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * JCLAW-662 streaming test: drives the {@code runtime:"acp"} coding-harness path
 * in STREAMING mode ({@code subagent.acp.mode=json}) end to end against a real
 * on-disk fake harness that emits multiple stdout lines, then exits 0.
 *
 * <p>The {@code generic} adapter turns every stdout line into a
 * {@link HarnessEvent#STEP} regardless of the line's shape, so the harness can
 * print plain deterministic lines (no JSON protocol to mimic) and each one is
 * guaranteed to fan out as exactly one step. Each step is (a) persisted as a
 * {@code codingrun_step} {@link Message} on the run's child {@link Conversation}
 * (Rail B replay) and (b) published on the {@link NotificationBus} as a
 * {@link NotificationBus#BUS_CODINGRUN_STEP} event (Rail B live), with a
 * {@link NotificationBus#BUS_CODINGRUN_DONE} on the terminal. No chat SSE turn is
 * ever registered here, so {@code callbacksFor(runId)} is null throughout — the
 * tab-closed / Rail-B-only case — and both rails must still fire.
 *
 * <p>Each test uses a unique {@code nonce} embedded in every harness line so the
 * process-global {@link NotificationBus} assertions isolate this run's events
 * from any cross-talk the concurrent test lane publishes.
 */
class HarnessStreamingRunTest extends UnitTest {

    /** Distinct stdout lines the fake harness emits per run. */
    private static final int STEP_COUNT = 5;

    private final List<Path> tempScripts = new ArrayList<>();
    private final List<Runnable> unsubscribers = new ArrayList<>();

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        EventLogger.clear();
        ConfigService.clearCache();
        llm.ProviderRegistry.refresh();
        new jobs.ToolRegistrationJob().doJob();
    }

    @AfterEach
    void teardown() throws Exception {
        for (var unsub : unsubscribers) unsub.run();
        unsubscribers.clear();
        EventLogger.clear();
        for (var script : tempScripts) Files.deleteIfExists(script);
        tempScripts.clear();
    }

    @Test
    void persistsStreamedSteps() throws Exception {
        var nonce = "streamsteps-" + System.nanoTime();
        var harness = writeStreamingHarness(nonce, STEP_COUNT);
        configureGenericJsonHarness(harness);
        var parent = createAcpAgent("p-stream-persist");
        commitAndReopen();

        var json = spawnAcp(parent.id);
        assertEquals("COMPLETED", json.get("status").getAsString(),
                "runtime=acp/json against a working harness must report COMPLETED");
        long runId = Long.parseLong(json.get("run_id").getAsString());

        assertPersistedSteps(runId, nonce);
    }

    @Test
    void publishesBusSteps() throws Exception {
        var nonce = "streambus-" + System.nanoTime();
        var harness = writeStreamingHarness(nonce, STEP_COUNT);
        configureGenericJsonHarness(harness);
        var parent = createAcpAgent("p-stream-bus");
        commitAndReopen();

        // Subscribe BEFORE spawning: the harness reader publishes each step (and the
        // terminal DONE) synchronously within the spawn, so by the time execute
        // returns every event has been delivered to this listener.
        var received = new CopyOnWriteArrayList<String>();
        subscribe(received::add);

        var json = spawnAcp(parent.id);
        assertEquals("COMPLETED", json.get("status").getAsString());

        long stepEvents = received.stream()
                .filter(s -> s.contains(NotificationBus.BUS_CODINGRUN_STEP) && s.contains(nonce))
                .count();
        assertEquals(STEP_COUNT, stepEvents,
                "each streamed line must publish exactly one BUS_CODINGRUN_STEP event for this run");

        boolean done = received.stream()
                .anyMatch(s -> s.contains(NotificationBus.BUS_CODINGRUN_DONE) && s.contains(nonce));
        assertTrue(done, "the run's terminal must publish a BUS_CODINGRUN_DONE event");
    }

    @Test
    void nullChatCallbacksStillPersist() throws Exception {
        // No chat SSE turn is registered (no ToolContext.conversationId bound on the
        // spawn VT, no registerChatCallbacks), so callbacksFor(runId) is null — the
        // tab-closed / Rail-B-only case. Rail B (persist + bus) must still fire.
        var nonce = "streamnocb-" + System.nanoTime();
        var harness = writeStreamingHarness(nonce, STEP_COUNT);
        configureGenericJsonHarness(harness);
        var parent = createAcpAgent("p-stream-nocb");
        commitAndReopen();

        var received = new CopyOnWriteArrayList<String>();
        subscribe(received::add);

        var json = spawnAcp(parent.id);
        assertEquals("COMPLETED", json.get("status").getAsString());
        long runId = Long.parseLong(json.get("run_id").getAsString());

        // Persistence (Rail B replay) holds despite the absent chat callbacks.
        assertPersistedSteps(runId, nonce);

        // The live bus (Rail B) fired too.
        long stepEvents = received.stream()
                .filter(s -> s.contains(NotificationBus.BUS_CODINGRUN_STEP) && s.contains(nonce))
                .count();
        assertEquals(STEP_COUNT, stepEvents,
                "with no chat SSE watching, the bus must still carry every streamed step");
    }

    // --- assertions --------------------------------------------------------

    /** Assert the run is COMPLETED and exactly {@link #STEP_COUNT} codingrun_step
     *  rows are persisted on the child Conversation in order, with strictly
     *  increasing metadata {@code seq} and the harness line as content. */
    private void assertPersistedSteps(long runId, String nonce) {
        JPA.em().clear();
        SubagentRun run = SubagentRun.findById(runId);
        assertNotNull(run, "the acp streaming spawn must persist a SubagentRun row");
        assertEquals(SubagentRun.Status.COMPLETED, run.status,
                "the persisted acp streaming run must be COMPLETED");

        java.util.List<Message> steps = Message.find(
                "conversation = ?1 AND messageKind = ?2 ORDER BY id ASC",
                run.childConversation, SubagentSpawnTool.MESSAGE_KIND_CODINGRUN_STEP).fetch();
        assertEquals(STEP_COUNT, steps.size(),
                "each streamed harness line must persist exactly one codingrun_step Message");

        int prevSeq = -1;
        for (int i = 0; i < STEP_COUNT; i++) {
            Message m = steps.get(i);
            assertEquals(nonce + "-" + i, m.content,
                    "step " + i + " content must be the verbatim harness line");
            var meta = JsonParser.parseString(m.metadata).getAsJsonObject();
            assertEquals(HarnessEvent.STEP, meta.get("kind").getAsString(),
                    "generic-adapter steps carry kind=step in their metadata");
            int seq = meta.get("seq").getAsInt();
            assertTrue(seq > prevSeq,
                    "metadata seq must strictly increase (row " + i + " seq=" + seq
                            + " prev=" + prevSeq + ")");
            prevSeq = seq;
        }
    }

    // --- fixtures / helpers ------------------------------------------------

    /** Point the acp runtime at {@code harness} and select the generic adapter in
     *  streaming (json) mode. */
    private static void configureGenericJsonHarness(Path harness) {
        ConfigService.set(SubagentSpawnTool.ACP_COMMAND_KEY, harness.toString());
        ConfigService.set(SubagentSpawnTool.ACP_HARNESS_KEY, "generic");
        ConfigService.set(SubagentSpawnTool.ACP_MODE_KEY, "json");
    }

    /** A non-main agent granted the acp capability, with a resolvable web conversation. */
    private Agent createAcpAgent(String name) {
        var agent = AgentService.create(name, "test-provider", "test-model");
        agent.enabled = true;
        agent.acpAllowed = true;
        agent.save();
        ConversationService.create(agent, "web", "u-" + name);
        return agent;
    }

    /** Fake streaming harness: drain the task off stdin, then print {@code n}
     *  distinct nonce-tagged lines and exit 0. No sleeps — the reader sees all
     *  {@code n} lines then EOF. */
    private Path writeStreamingHarness(String nonce, int n) throws Exception {
        var sb = new StringBuilder("#!/bin/sh\ncat >/dev/null\n");
        for (int i = 0; i < n; i++) {
            sb.append("printf '%s\\n' '").append(nonce).append('-').append(i).append("'\n");
        }
        var script = Files.createTempFile("jclaw-stream-harness-", ".sh");
        Files.writeString(script, sb.toString());
        script.toFile().setExecutable(true, false);
        tempScripts.add(script);
        return script;
    }

    /** Spawn synchronously on a VT with runtime=acp and parse the tool's JSON reply. */
    private com.google.gson.JsonObject spawnAcp(Long parentAgentId) throws Exception {
        var reply = invokeOnVirtualThread(parentAgentId,
                "{\"task\":\"stream-check\",\"runtime\":\"acp\"}");
        return JsonParser.parseString(reply).getAsJsonObject();
    }

    private Runnable subscribe(Consumer<String> listener) {
        var unsub = NotificationBus.subscribe(listener);
        unsubscribers.add(unsub);
        return unsub;
    }

    /** Commit pending setup rows so the VT-dispatched child run sees them. */
    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    /** Drive SubagentSpawnTool.execute on a VT so its child run observes committed
     *  rows through its own persistence context. */
    private String invokeOnVirtualThread(Long parentAgentId, String argsJson) throws Exception {
        var resultRef = new AtomicReference<String>();
        var errorRef = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                var parent = Tx.run(() -> (Agent) Agent.findById(parentAgentId));
                var tool = ToolRegistry.lookupTool(SubagentSpawnTool.TOOL_NAME);
                resultRef.set(tool.execute(argsJson, parent));
            } catch (Exception e) {
                errorRef.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "acp streaming spawn must complete within 30s");
        if (errorRef.get() != null) throw errorRef.get();
        return resultRef.get();
    }
}
