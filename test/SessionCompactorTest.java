import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.Choice;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.Usage;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import org.junit.jupiter.api.*;
import play.db.jpa.JPA;
import play.test.*;
import services.ConfigService;
import services.ConversationService;
import services.SessionCompactor;
import services.SessionCompactor.CompactionResult;
import services.SessionCompactor.MessageSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link SessionCompactor} (JCLAW-38).
 *
 * <p>Boundary selection is tested against in-memory Message lists so we
 * don't pay the DB for pure-algorithm assertions. The end-to-end
 * compact() flow runs against the real H2 DB so the SessionCompaction
 * row and the compactionSince watermark bump are verified by reading
 * back through the JPA layer — same path production code takes.
 */
public class SessionCompactorTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        agent = new Agent();
        agent.name = "compaction-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "test-model";
        agent.save();
    }

    // ─── findSafeBoundary ────────────────────────────────────────────────

    @Test
    public void findSafeBoundary_returnsNegativeOne_whenBelowMinSize() {
        // total=8 < minCompactable (10 default): no boundary possible
        var msgs = buildFakeHistory(8, i -> i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(-1, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    public void findSafeBoundary_picksLatestUserInRange() {
        // total=25, keepMin=10 → maxBoundary=15, walk 15..10 looking for user.
        // Place user roles at 0,2,4,...,24 — so msg[14] is user (14 is even).
        var msgs = buildFakeHistory(25, i -> i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
        // Latest user in [10,15] is at 14.
        assertEquals(14, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    public void findSafeBoundary_walksBackward_whenTrailingTurnsLackUser() {
        // total=25; users at 0,5,10,15. keepMin=10 → maxBoundary=15.
        // msg[15] is user → boundary=15. (Verifies we don't miss the top of the range.)
        var msgs = buildFakeHistory(25, i -> i % 5 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(15, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    public void findSafeBoundary_returnsNegativeOne_whenNoUserInEligibleRange() {
        // total=25, only users at 0..5 (all below minCompactable=10). keepMin=10.
        var msgs = buildFakeHistory(25, i -> i < 6 ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(-1, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    public void findSafeBoundary_honorsConfigOverrides() {
        ConfigService.set("chat.compactionKeepMessages", "5");
        ConfigService.set("chat.compactionMinTurns", "3");
        // total=12, keepMin=5 → maxBoundary=7. minCompactable=3.
        // Users at 3 and 7. Walks back from 7 — finds user at 7.
        var msgs = buildFakeHistory(12, i -> (i == 3 || i == 7) ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(7, SessionCompactor.findSafeBoundary(msgs));
    }

    // ─── shouldCompact ──────────────────────────────────────────────────

    @Test
    public void shouldCompact_false_whenModelInfoNull() {
        assertFalse(SessionCompactor.shouldCompact(999_999, null));
    }

    @Test
    public void shouldCompact_false_whenContextWindowZero() {
        var mi = new ModelInfo("m", "M", 0, 0, false);
        assertFalse(SessionCompactor.shouldCompact(10_000, mi));
    }

    @Test
    public void shouldCompact_false_whenUnderBudget() {
        // contextWindow=200k, reserve=15k (default) → budget=185k. 100k is safely under.
        var mi = new ModelInfo("m", "M", 200_000, 8192, false);
        assertFalse(SessionCompactor.shouldCompact(100_000, mi));
    }

    @Test
    public void shouldCompact_true_whenOverBudget() {
        // budget=200k-15k=185k. 190k triggers.
        var mi = new ModelInfo("m", "M", 200_000, 8192, false);
        assertTrue(SessionCompactor.shouldCompact(190_000, mi));
    }

    @Test
    public void shouldCompact_reserveFloorClamps() {
        // If reserveTokens < floor, floor wins. Set reserve=1000, floor stays at default 9000.
        ConfigService.set("chat.compactionReserveTokens", "1000");
        // budget = 20000 - max(1000, 9000) = 11000. 12000 triggers.
        var mi = new ModelInfo("m", "M", 20_000, 4096, false);
        assertTrue(SessionCompactor.shouldCompact(12_000, mi));
        assertFalse(SessionCompactor.shouldCompact(10_000, mi));
    }

    // ─── compact() end-to-end ──────────────────────────────────────────

    @Test
    public void compact_persistsRowAndBumpsWatermark_onSuccess() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        // Seed 25 messages — enough to pass the default 10-turn minimum.
        seedMessages(conv, 25);
        // Commit so the compact() flow sees the rows through its own Tx.
        commitAndReopen();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                msgs -> "This is the canned summary of the prior conversation.");

        assertTrue(result.compacted(), "expected compaction success, got reason=" + result.skipReason());
        assertTrue(result.turnsCompacted() > 0);
        assertTrue(result.summaryChars() > 0);

        // Reload and verify the SessionCompaction row + watermark.
        commitAndReopen();
        var reloaded = Conversation.<Conversation>findById(conv.id);
        assertNotNull(reloaded.compactionSince, "compactionSince watermark should be set");

        var latest = SessionCompaction.findLatest(reloaded);
        assertNotNull(latest, "SessionCompaction row should exist");
        assertEquals("This is the canned summary of the prior conversation.", latest.summary);
        assertEquals("openrouter/test-model", latest.model);
        assertTrue(latest.turnCount > 0);
        assertTrue(latest.summaryTokens > 0);
    }

    @Test
    public void compact_skipsWhenSummarizerReturnsEmpty() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> "");
        assertFalse(result.compacted());
        assertEquals("empty summary", result.skipReason());

        commitAndReopen();
        assertNull(Conversation.<Conversation>findById(conv.id).compactionSince);
        assertEquals(0L, SessionCompaction.count());
    }

    @Test
    public void compact_skipsWhenSummarizerThrows() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                msgs -> { throw new RuntimeException("boom"); });
        assertFalse(result.compacted());
        assertEquals("llm error", result.skipReason());

        commitAndReopen();
        assertNull(Conversation.<Conversation>findById(conv.id).compactionSince);
        assertEquals(0L, SessionCompaction.count());
    }

    @Test
    public void compact_skipsWhenTooFewMessages() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 5); // below minCompactable
        commitAndReopen();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                msgs -> "would summarize");
        assertFalse(result.compacted());
        assertEquals("no safe boundary or below min-turns", result.skipReason());
    }

    @Test
    public void compactForced_succeedsWhereAutoSkipsDueToLowTurnCount() throws Exception {
        // 8 messages — well below auto-trigger's 10-turn minimum.
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 8);
        commitAndReopen();

        // Auto mode skips (below min-turns).
        var auto = SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> "s");
        assertFalse(auto.compacted(), "auto should skip");

        // Forced mode succeeds with relaxed thresholds (default 4 keep, 2 min).
        var forced = SessionCompactor.compact(conv.id, "openrouter/test-model",
                msgs -> "Forced summary.", /*force*/ true, null);
        assertTrue(forced.compacted(), "forced should compact; reason=" + forced.skipReason());

        commitAndReopen();
        assertNotNull(Conversation.<Conversation>findById(conv.id).compactionSince);
    }

    @Test
    public void compact_additionalInstructions_threadedIntoSystemPrompt() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();

        // Capture the system prompt the summarizer sees to verify the extra hint is present.
        var captured = new java.util.concurrent.atomic.AtomicReference<String>();
        SessionCompactor.Summarizer capturing = msgs -> {
            captured.set(msgs.get(0).content() instanceof String s ? s : "");
            return "Summary with hint applied.";
        };

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", capturing,
                /*force*/ false, "focus on the migration plan");
        assertTrue(result.compacted());
        assertNotNull(captured.get());
        assertTrue(captured.get().contains("focus on the migration plan"),
                "custom hint should be threaded into system prompt; got: " + captured.get());
    }

    @Test
    public void compact_keepsOriginalMessagesIntact_afterSuccess() throws Exception {
        // AC: original turns remain accessible in conversation history.
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();

        var before = Message.count("conversation", Conversation.<Conversation>findById(conv.id));

        SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> "Summary.");

        commitAndReopen();
        var after = Message.count("conversation", Conversation.<Conversation>findById(conv.id));
        assertEquals(before, after, "Compaction must not delete Message rows");
    }

    // ─── appendSummaryToPrompt ───────────────────────────────────────────

    @Test
    public void appendSummaryToPrompt_returnsUnchanged_whenNoCompactionRow() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        commitAndReopen();
        var reloaded = Conversation.<Conversation>findById(conv.id);

        var result = SessionCompactor.appendSummaryToPrompt("SYSTEM PROMPT", reloaded);
        assertEquals("SYSTEM PROMPT", result);
    }

    @Test
    public void appendSummaryToPrompt_appendsHeaderAndSummary_whenRowPresent() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        var sc = new SessionCompaction();
        sc.conversation = conv;
        sc.turnCount = 12;
        sc.summaryTokens = 100;
        sc.model = "openrouter/test-model";
        sc.summary = "User asked about X. Agent investigated and found Y.";
        sc.save();
        commitAndReopen();

        var reloaded = Conversation.<Conversation>findById(conv.id);
        var result = SessionCompactor.appendSummaryToPrompt("SYSTEM PROMPT", reloaded);
        assertTrue(result.startsWith("SYSTEM PROMPT"));
        assertTrue(result.contains(SessionCompactor.PRIOR_SUMMARY_HEADER),
                "should include prior-summary header");
        assertTrue(result.contains("User asked about X"), "should include summary text");
    }

    @Test
    public void appendSummaryToPrompt_picksMostRecentWhenMultiple() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        var older = new SessionCompaction();
        older.conversation = conv;
        older.turnCount = 10;
        older.summaryTokens = 50;
        older.model = "openrouter/test-model";
        older.summary = "OLDER SUMMARY";
        older.compactedAt = Instant.now().minusSeconds(600);
        older.save();

        var newer = new SessionCompaction();
        newer.conversation = conv;
        newer.turnCount = 15;
        newer.summaryTokens = 80;
        newer.model = "openrouter/test-model";
        newer.summary = "NEWER SUMMARY";
        newer.compactedAt = Instant.now();
        newer.save();
        commitAndReopen();

        var reloaded = Conversation.<Conversation>findById(conv.id);
        var result = SessionCompactor.appendSummaryToPrompt("SYS", reloaded);
        assertTrue(result.contains("NEWER SUMMARY"));
        assertFalse(result.contains("OLDER SUMMARY"));
    }

    // ─── renderTurns ────────────────────────────────────────────────────

    @Test
    public void renderTurns_includesRoleAndContent() {
        var snaps = List.of(
                new MessageSnapshot(MessageRole.USER.value, "Hello there", null, null, Instant.now()),
                new MessageSnapshot(MessageRole.ASSISTANT.value, "Hi, how can I help?", null, null, Instant.now())
        );
        var rendered = SessionCompactor.renderTurns(snaps);
        assertTrue(rendered.contains("[USER] Hello there"));
        assertTrue(rendered.contains("[ASSISTANT] Hi, how can I help?"));
    }

    @Test
    public void renderTurns_includesToolCallsAndResults() {
        var snaps = List.of(
                new MessageSnapshot(MessageRole.ASSISTANT.value, null,
                        "[{\"id\":\"c1\",\"fn\":\"search\"}]", null, Instant.now()),
                new MessageSnapshot(MessageRole.TOOL.value, "{\"hits\":3}", null, "c1", Instant.now())
        );
        var rendered = SessionCompactor.renderTurns(snaps);
        assertTrue(rendered.contains("TOOL_CALLS"));
        assertTrue(rendered.contains("\"fn\":\"search\""));
        assertTrue(rendered.contains("TOOL_RESULT_ID"));
        assertTrue(rendered.contains("c1"));
    }

    // ─── firstChoiceText ────────────────────────────────────────────────

    @Test
    public void firstChoiceText_returnsStringContent() {
        var msg = new ChatMessage("assistant", "The answer is 42", null, null);
        var resp = new ChatResponse("r1", "m",
                List.of(new Choice(0, msg, "stop")),
                new Usage(10, 5, 15, 0, 0, 0));
        assertEquals("The answer is 42", SessionCompactor.firstChoiceText(resp));
    }

    @Test
    public void firstChoiceText_returnsNullForEmptyChoices() {
        var resp = new ChatResponse("r1", "m", List.of(), null);
        assertNull(SessionCompactor.firstChoiceText(resp));
    }

    @Test
    public void firstChoiceText_returnsNullForNullResponse() {
        assertNull(SessionCompactor.firstChoiceText(null));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static List<Message> buildFakeHistory(int total, java.util.function.IntFunction<MessageRole> roleAt) {
        var out = new ArrayList<Message>(total);
        for (int i = 0; i < total; i++) {
            var m = new Message();
            m.role = roleAt.apply(i).value;
            m.content = "msg-" + i;
            out.add(m);
        }
        return out;
    }

    private static void seedMessages(Conversation conv, int count) {
        // Alternating user/assistant messages (starts with user) so the
        // prefix is always anchorable at a user boundary.
        for (int i = 0; i < count; i++) {
            var role = (i % 2 == 0) ? MessageRole.USER : MessageRole.ASSISTANT;
            ConversationService.appendMessage(conv, role, "Turn " + i + " content", null, null, null);
        }
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().clear();
        JPA.em().getTransaction().begin();
    }
}
