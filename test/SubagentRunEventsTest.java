import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jobs.SubagentOrphanRecoveryJob;
import models.Agent;
import models.Conversation;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.NotificationBus;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool.SyncRunOutcome;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JCLAW-1206: every SubagentRun insert and terminal write announces itself on the
 * {@link NotificationBus}, and only once its transaction has committed. The bus is
 * process-global and test classes run concurrently, so assertions match this class's run ids.
 */
class SubagentRunEventsTest extends UnitTest {

    private static final String STARTED = NotificationBus.BUS_SUBAGENT_RUN_STARTED;
    private static final String ENDED = NotificationBus.BUS_SUBAGENT_RUN_ENDED;

    private final List<String> received = new CopyOnWriteArrayList<>();
    private Runnable unsubscribe = () -> {};

    private record Seed(Long parentAgentId, Long childAgentId, Long parentConvId, Long childConvId) {}

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    @BeforeEach
    void subscribe() {
        unsubscribe = NotificationBus.subscribe(received::add);
    }

    @AfterEach
    void unsubscribeFromBus() {
        unsubscribe.run();
    }

    @Test
    void insertAnnouncesStartedOnlyOnceTheRowCommits() throws Exception {
        var seed = seed("start");
        long runId = insert(seed, "research notes");

        assertTrue(events(STARTED, runId).isEmpty(), "an uncommitted row must not be announced");
        commitAndReopen();

        var data = single(STARTED, runId);
        assertEquals((long) seed.parentConvId(), data.get("parentConversationId").getAsLong());
        assertEquals((long) seed.childConvId(), data.get("childConversationId").getAsLong());
        assertEquals((long) seed.childAgentId(), data.get("childAgentId").getAsLong());
        assertEquals("RUNNING", data.get("status").getAsString());
        assertEquals("research notes", data.get("label").getAsString());
    }

    @Test
    void terminalWriteAnnouncesEnded() throws Exception {
        var seed = seed("done");
        long runId = insert(seed, null);
        commitAndReopen();

        onVirtualThread(() -> persistTerminalRun(runId, SubagentRun.Status.COMPLETED));

        var data = single(ENDED, runId);
        assertEquals("COMPLETED", data.get("status").getAsString());
        assertEquals((long) seed.parentConvId(), data.get("parentConversationId").getAsLong());
        assertEquals((long) seed.childConvId(), data.get("childConversationId").getAsLong());
        assertEquals((long) seed.childAgentId(), data.get("childAgentId").getAsLong());
        assertTrue(data.has("label") && data.get("label").isJsonNull(), "an unlabelled run sends label null: " + data);
    }

    @Test
    void asyncTerminalWriteAnnouncesEnded() throws Exception {
        long runId = insert(seed("async"), "slow");
        commitAndReopen();
        var outcome = new SyncRunOutcome("", SubagentRun.Status.TIMEOUT, "budget spent", "budget spent", false, false);

        onVirtualThread(() -> invokeStore("persistAsyncTerminalRun",
                new Class<?>[]{Long.class, SyncRunOutcome.class}, runId, outcome));

        var data = single(ENDED, runId);
        assertEquals("TIMEOUT", data.get("status").getAsString());
        assertEquals("slow", data.get("label").getAsString());
    }

    @Test
    void killAnnouncesEndedOnCommitAndALaterTerminalWriteStaysSilent() throws Exception {
        long runId = insert(seed("kill"), "doomed");
        commitAndReopen();

        // Runs inside this test's transaction, as the kill endpoint runs inside its request's.
        assertTrue(SubagentRegistry.kill(runId, "operator stop").killed());
        assertTrue(events(ENDED, runId).isEmpty(), "a kill must not be announced before it commits");
        commitAndReopen();
        assertEquals("KILLED", single(ENDED, runId).get("status").getAsString());

        onVirtualThread(() -> persistTerminalRun(runId, SubagentRun.Status.COMPLETED));
        assertEquals(1, events(ENDED, runId).size(), "a terminal write that skips a KILLED row announces nothing");
    }

    @Test
    void orphanSweepAnnouncesFailedOnCommit() {
        var seed = seed("orphan");
        // Left uncommitted until the sweep has run, so a concurrent class's sweep cannot claim it first.
        long runId = Tx.run(() -> {
            var run = new SubagentRun();
            run.parentAgent = Agent.findById(seed.parentAgentId());
            run.childAgent = Agent.findById(seed.childAgentId());
            run.parentConversation = Conversation.findById(seed.parentConvId());
            run.childConversation = Conversation.findById(seed.childConvId());
            run.startedAt = Instant.now().minusSeconds(3600);
            run.save();
            return run.id;
        });

        new SubagentOrphanRecoveryJob().doJob();
        assertTrue(events(ENDED, runId).isEmpty(), "the sweep must not announce before its transaction commits");
        commitAndReopen();

        var data = single(ENDED, runId);
        assertEquals("FAILED", data.get("status").getAsString());
        assertEquals((long) seed.childAgentId(), data.get("childAgentId").getAsLong());
    }

    private static Seed seed(String tag) {
        var nonce = tag + System.nanoTime();
        return Tx.run(() -> {
            Agent parent = AgentService.create("ev-p-" + nonce, "openrouter", "gpt-4.1");
            Agent child = AgentService.create("ev-c-" + nonce, "openrouter", "gpt-4.1");
            Conversation pc = ConversationService.create(parent, "web", "u");
            Conversation cc = ConversationService.create(child, "subagent", null);
            return new Seed(parent.id, child.id, pc.id, cc.id);
        });
    }

    private static long insert(Seed seed, String label) throws Exception {
        return (Long) invokeStore("insertSubagentRun",
                new Class<?>[]{Long.class, Long.class, Long.class, Long.class, String.class},
                seed.parentAgentId(), seed.childAgentId(), seed.parentConvId(), seed.childConvId(), label);
    }

    private static void persistTerminalRun(long runId, SubagentRun.Status status) throws Exception {
        invokeStore("persistTerminalRun",
                new Class<?>[]{Long.class, SubagentRun.Status.class, String.class}, runId, status, "done");
    }

    // SubagentRunStore is package-private in tools; SubagentAsyncRunnerTest reaches its sibling the same way.
    private static Object invokeStore(String name, Class<?>[] types, Object... args) throws Exception {
        var method = Class.forName("tools.SubagentRunStore").getDeclaredMethod(name, types);
        method.setAccessible(true);
        try {
            return method.invoke(null, args);
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof Exception cause ? cause : e;
        }
    }

    /** Terminal writes run on spawn virtual threads, where no transaction is open to join. */
    private static void onVirtualThread(Body body) throws Exception {
        var error = new AtomicReference<Exception>();
        var thread = Thread.ofVirtual().start(() -> {
            try {
                body.run();
            } catch (Exception e) {
                error.set(e);
            }
        });
        thread.join(30_000);
        assertFalse(thread.isAlive(), "the terminal write must finish within 30s");
        if (error.get() != null) throw error.get();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().getTransaction().begin();
    }

    private List<JsonObject> events(String type, long runId) {
        return received.stream()
                .map(sse -> JsonParser.parseString(sse.substring("data: ".length())).getAsJsonObject())
                .filter(event -> type.equals(event.get("type").getAsString()))
                .map(event -> event.getAsJsonObject("data"))
                .filter(data -> data.has("runId") && data.get("runId").getAsLong() == runId)
                .toList();
    }

    private JsonObject single(String type, long runId) {
        var matches = events(type, runId);
        assertEquals(1, matches.size(), () -> type + " events for run " + runId + ": " + matches);
        return matches.getFirst();
    }
}
