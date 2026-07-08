import com.google.gson.JsonParser;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import services.AgentService;
import services.ConversationService;
import services.EventLogger;
import services.SubagentRegistry;
import services.Tx;
import tools.SubagentSpawnTool;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * JCLAW-662 functional coverage for {@code GET /api/subagent-runs/{id}/steps}
 * ({@link controllers.ApiSubagentRunsController#steps}). Exercises the replay
 * transcript over the real HTTP stack via {@link FunctionalTest}: the persisted
 * {@code codingrun_step} rows on a run's child {@link Conversation} must come
 * back as an ordered JSON array of {@code {seq,kind,text,createdAt}} in
 * insertion order (= {@code seq} order, since each step is written as it
 * streams in and the endpoint sorts by {@code id ASC}).
 */
class ApiSubagentRunStepsTest extends FunctionalTest {

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        SubagentRegistry.clear();
        EventLogger.clear();
        AuthFixture.seedAdminPassword("changeme");
    }

    @AfterEach
    void teardown() {
        SubagentRegistry.clear();
        EventLogger.clear();
    }

    private void login() {
        var resp = POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}");
        assertIsOk(resp);
    }

    // ── tests ─────────────────────────────────────────────────────────

    @Test
    void stepsReturnsOrderedTranscriptForCodingRun() {
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-steps-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-steps-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var id = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            // Persist in seq order; id ASC (insertion order) is what the
            // endpoint sorts by, so the wire order must equal this order.
            persistStep(cc, id, 1, "line 1");
            persistStep(cc, id, 2, "line 2");
            persistStep(cc, id, 3, "line 3");
            return id;
        });

        var resp = GET("/api/subagent-runs/" + runId + "/steps");
        assertIsOk(resp);
        var body = getContent(resp);
        var arr = JsonParser.parseString(body).getAsJsonArray();
        assertEquals(3, arr.size(), "one element per persisted step: " + body);
        for (int i = 0; i < arr.size(); i++) {
            var obj = arr.get(i).getAsJsonObject();
            int expectedSeq = i + 1;
            assertEquals(expectedSeq, obj.get("seq").getAsInt(),
                    "steps in insertion (seq) order at index " + i + ": " + body);
            assertEquals("step", obj.get("kind").getAsString(),
                    "kind projected from metadata at index " + i + ": " + body);
            assertEquals("line " + expectedSeq, obj.get("text").getAsString(),
                    "text projected from Message.content at index " + i + ": " + body);
            assertFalse(obj.get("createdAt").isJsonNull(),
                    "createdAt present at index " + i + ": " + body);
        }
    }

    @Test
    void stepsForUnknownRunReturns404() {
        login();
        var resp = GET("/api/subagent-runs/9999999/steps");
        assertEquals(404, resp.status.intValue());
    }

    @Test
    void stepsForRunWithoutCodingStepsReturnsEmptyArray() {
        // The child_conversation FK is NOT NULL, so a genuinely null
        // childConversation isn't persistable — this exercises the same
        // empty-array output through the seedable path: a run whose child
        // conversation carries a non-step message but no codingrun_step rows.
        // Proves the messageKind filter narrows correctly rather than the
        // conversation merely being empty.
        login();
        var runId = commitInFreshTx(() -> {
            var p = AgentService.create("api-steps-empty-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("api-steps-empty-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var id = persistRun(p, c, pc, cc, SubagentRun.Status.COMPLETED);
            // A regular assistant reply — no messageKind, so it must not be
            // surfaced by the codingrun_step filter.
            var other = new Message();
            other.conversation = cc;
            other.role = MessageRole.ASSISTANT.value;
            other.content = "not a step";
            other.save();
            return id;
        });

        var resp = GET("/api/subagent-runs/" + runId + "/steps");
        assertIsOk(resp);
        var body = getContent(resp);
        var arr = JsonParser.parseString(body).getAsJsonArray();
        assertEquals(0, arr.size(),
                "no codingrun_step rows means an empty array: " + body);
    }

    // ── helpers ───────────────────────────────────────────────────────

    private static long persistRun(Agent p, Agent c, Conversation pc, Conversation cc,
                                    SubagentRun.Status status) {
        var run = new SubagentRun();
        run.parentAgent = p;
        run.childAgent = c;
        run.parentConversation = pc;
        run.childConversation = cc;
        run.status = status;
        if (status != SubagentRun.Status.RUNNING) {
            run.endedAt = Instant.now();
            run.outcome = "seeded " + status.name().toLowerCase();
        }
        run.save();
        return run.id;
    }

    /** Persist one codingrun_step exactly as
     *  {@link SubagentSpawnTool}'s harness dispatch does: role assistant,
     *  messageKind codingrun_step, content = step text, metadata carrying
     *  {@code {"seq":n,"kind":"step"}}. */
    private static void persistStep(Conversation childConv, long runId, int seq, String text) {
        var m = new Message();
        m.conversation = childConv;
        m.subagentRunId = runId;
        m.role = MessageRole.ASSISTANT.value;
        m.messageKind = SubagentSpawnTool.MESSAGE_KIND_CODINGRUN_STEP;
        m.content = text;
        m.metadata = "{\"seq\":" + seq + ",\"kind\":\"step\"}";
        m.save();
    }

    /** Same as the sibling controller test: commit seed rows on a VT so the
     *  in-process FunctionalTest HTTP handler can observe them before
     *  responding. The shared FunctionalTest carrier is inside an ambient tx
     *  that doesn't commit until the test returns. */
    private static <T> T commitInFreshTx(Supplier<T> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<T>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofVirtual().start(() -> {
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
