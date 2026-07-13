import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.FunctionalTest;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SubagentRun;
import services.AgentService;
import services.ConversationService;
import services.Tx;
import services.search.LuceneIndexer;

import java.time.Instant;
import java.util.function.Supplier;

/**
 * JCLAW-328: acceptance coverage for the {@code q:} keyword path on
 * {@code GET /api/subagent-runs}. The most-interesting controller
 * because its q resolution UNIONs two Lucene scopes — direct
 * SUBAGENT_RUN hits (label + outcome virtual doc) plus
 * CONVERSATION_MESSAGE hits mapped back to runs via
 * {@code SubagentRun.childConversation.id} — before intersecting with
 * the equality predicates.
 *
 * <p>Three cases pinned, mirroring the ticket's AC:
 * <ul>
 *   <li>{@link #searchByQueryFindsLabelAndOutcome} — direct
 *       SUBAGENT_RUN hits on both fields of the virtual doc.</li>
 *   <li>{@link #searchUnionsLabelOutcomeWithChildTranscript} — the
 *       transcript-via-child-conversation half of the union: a run
 *       whose label and outcome have nothing to do with the keyword
 *       but whose child conversation has a message that does, must
 *       still surface.</li>
 *   <li>{@link #searchIntersectsWithStatusAndParentAgent} — the
 *       full equality stack stays AND-semantics on top of the union.</li>
 * </ul>
 */
class ApiSubagentRunsControllerSearchTest extends FunctionalTest {

    @BeforeEach
    void setup() throws Exception {
        // JCLAW-428: serialize against other Lucene tests and open a clean
        // index at the %test path (data/jclaw-lucene-test). openForTest()
        // replaces the old close + MessageSearch.init + wipeIndex dance.
        LuceneTestSync.openForTest();
        Fixtures.deleteDatabase();
        AuthFixture.seedAdminPassword("changeme");
        login();
        services.search.MessageSearch.init();
    }

    @AfterEach
    void luceneRelease() {
        LuceneTestSync.release();
    }

    private void login() {
        assertIsOk(POST("/api/auth/login", "application/json",
                "{\"username\":\"admin\",\"password\":\"changeme\"}"));
    }

    // ── tests ────────────────────────────────────────────────────────────

    @Test
    void searchByQueryFindsLabelAndOutcome() {
        // Three runs: keyword in label only, keyword in outcome only,
        // keyword in neither. SUBAGENT_RUN scope indexes label + space +
        // outcome as a single virtual doc, so rows 1 and 2 must match.
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("sub-search-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("sub-search-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var inLabel = persistRun(p, c, pc, cc, "radarrtoken-monitor", "import succeeded",
                    SubagentRun.Status.COMPLETED);
            var inOutcome = persistRun(p, c, pc, cc, "generic-monitor",
                    "IMPORT_SUCCEEDED radarrtoken", SubagentRun.Status.COMPLETED);
            var neither = persistRun(p, c, pc, cc, "unrelated-monitor",
                    "unrelated outcome text", SubagentRun.Status.COMPLETED);
            return new long[]{inLabel, inOutcome, neither};
        });

        var resp = GET("/api/subagent-runs?q=radarrtoken");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + ids[0]),
                "run with keyword in label must match: " + body);
        assertTrue(body.contains("\"id\":" + ids[1]),
                "run with keyword in outcome must match: " + body);
        assertFalse(body.contains("\"id\":" + ids[2]),
                "run with keyword in neither must be excluded: " + body);
    }

    @Test
    void searchUnionsLabelOutcomeWithChildTranscript() {
        // The cross-scope union path: a run whose own narrative content
        // (label + outcome) does NOT contain the keyword, but whose
        // child conversation has a message body that does, must still
        // appear in the result set. Proves the UNION resolves via
        // CONVERSATION_MESSAGE → distinct childConversation.id →
        // SubagentRun.id.
        var data = commitInFreshTx(() -> {
            var p = AgentService.create("sub-union-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("sub-union-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);

            // Append a child-transcript message containing the keyword.
            // The Message @PostPersist hook commits the doc to the
            // CONVERSATION_MESSAGE scope; the controller's q resolver
            // walks that hit set back to {@code cc.id} → runs whose
            // childConversation matches.
            var msg = new Message();
            msg.conversation = cc;
            msg.role = MessageRole.ASSISTANT.value;
            msg.content = "the operator hit a diskfullunionword condition mid-run";
            msg.save();

            // The run row itself has nothing in label/outcome matching
            // the keyword — it's only the transcript that does.
            var runId = persistRun(p, c, pc, cc, "uneventful-run",
                    "outcome with nothing matching", SubagentRun.Status.COMPLETED);
            return new long[]{runId};
        });

        var resp = GET("/api/subagent-runs?q=diskfullunionword");
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + data[0]),
                "run must surface via child-conversation-transcript match: " + body);
    }

    @Test
    void searchIntersectsWithStatusAndParentAgent() {
        // Three matching runs: COMPLETED under agent 42, FAILED under
        // agent 42, COMPLETED under agent 99. q + status=COMPLETED +
        // parentAgentId=42 must intersect down to only the first.
        var data = commitInFreshTx(() -> {
            var p42 = AgentService.create("sub-intersect-p42", "openrouter", "gpt-4.1");
            var p99 = AgentService.create("sub-intersect-p99", "openrouter", "gpt-4.1");
            var c = AgentService.create("sub-intersect-c", "openrouter", "gpt-4.1");
            var pc42 = ConversationService.create(p42, "web", "u-42");
            var pc99 = ConversationService.create(p99, "web", "u-99");
            var cc = ConversationService.create(c, "subagent", null);
            var keep = persistRun(p42, c, pc42, cc, "intersectruntoken-a", "outcome a",
                    SubagentRun.Status.COMPLETED);
            var wrongStatus = persistRun(p42, c, pc42, cc, "intersectruntoken-b", "outcome b",
                    SubagentRun.Status.FAILED);
            var wrongAgent = persistRun(p99, c, pc99, cc, "intersectruntoken-c", "outcome c",
                    SubagentRun.Status.COMPLETED);
            return new long[]{p42.id, keep, wrongStatus, wrongAgent};
        });

        var resp = GET("/api/subagent-runs?q=intersectruntoken&status=COMPLETED&parentAgentId=" + data[0]);
        assertIsOk(resp);
        var body = getContent(resp);
        assertTrue(body.contains("\"id\":" + data[1]),
                "matching COMPLETED+agent42 run must be present: " + body);
        assertFalse(body.contains("\"id\":" + data[2]),
                "FAILED run must be filtered out by status=COMPLETED: " + body);
        assertFalse(body.contains("\"id\":" + data[3]),
                "agent-99 run must be filtered out by parentAgentId=42: " + body);
    }

    @Test
    void searchWithQueryMatchingNothingShortCircuitsToZeroRows() {
        // A run exists, but the keyword appears in neither its label/outcome
        // nor any child-transcript message. Both Lucene scopes return empty, so
        // ftsSubagentRunIds yields an empty list and the controller
        // short-circuits to a zero-row response with X-Total-Count=0 rather than
        // falling through and listing the row.
        var ids = commitInFreshTx(() -> {
            var p = AgentService.create("sub-nomatch-p", "openrouter", "gpt-4.1");
            var c = AgentService.create("sub-nomatch-c", "openrouter", "gpt-4.1");
            var pc = ConversationService.create(p, "web", "u");
            var cc = ConversationService.create(c, "subagent", null);
            var runId = persistRun(p, c, pc, cc, "ordinary-label", "ordinary outcome",
                    SubagentRun.Status.COMPLETED);
            return new long[]{runId};
        });

        var resp = GET("/api/subagent-runs?q=zzznosuchtokenzzz");
        assertIsOk(resp);
        assertEquals("[]", getContent(resp),
                "no FTS match must render an empty array: " + getContent(resp));
        assertEquals("0", resp.getHeader("X-Total-Count"),
                "empty FTS result must report total=0");
        assertFalse(getContent(resp).contains("\"id\":" + ids[0]),
                "the non-matching seeded run must be absent from the zero-row response");
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private static long persistRun(Agent p, Agent c, Conversation pc, Conversation cc,
                                     String label, String outcome, SubagentRun.Status status) {
        var run = new SubagentRun();
        run.parentAgent = p;
        run.childAgent = c;
        run.parentConversation = pc;
        run.childConversation = cc;
        run.label = label;
        run.outcome = outcome;
        run.status = status;
        if (status != SubagentRun.Status.RUNNING) {
            run.endedAt = Instant.now();
        }
        run.save();
        return run.id;
    }

    private static long[] commitInFreshTx(Supplier<long[]> block) {
        var ref = new java.util.concurrent.atomic.AtomicReference<long[]>();
        var err = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        var t = Thread.ofPlatform().start(() -> {
            try {
                ref.set(Tx.run(block::get));
            } catch (Throwable ex) {
                err.set(ex);
            }
        });
        try { t.join(); } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(ie);
        }
        if (err.get() != null) throw new RuntimeException(err.get());
        return ref.get();
    }
}
