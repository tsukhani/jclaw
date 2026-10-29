import agents.MessageHydrator;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ChatResponse;
import llm.LlmTypes.Choice;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolCall;
import llm.LlmTypes.ToolDef;
import llm.LlmTypes.Usage;
import models.Agent;
import models.Conversation;
import models.Message;
import models.MessageRole;
import models.SessionCompaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.db.jpa.JPA;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import services.SessionCompactor.MessageSnapshot;
import services.SessionCompactor.TurnRequest;
import services.Tx;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for {@link SessionCompactor} (JCLAW-38).
 *
 * <p>Boundary selection is tested against in-memory Message lists so we
 * don't pay the DB for pure-algorithm assertions. The end-to-end
 * compact() flow runs against the real H2 DB so the SessionCompaction
 * row and the compactionSince watermark bump are verified by reading
 * back through the JPA layer — same path production code takes.
 */
class SessionCompactorTest extends UnitTest {

    private Agent agent;

    @BeforeEach
    void setup() {
        CompactionTriggerSync.acquire();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        agent = new Agent();
        agent.name = "compaction-test-agent";
        agent.modelProvider = "openrouter";
        agent.modelId = "test-model";
        agent.save();
    }

    @AfterEach
    void teardown() {
        CompactionTriggerSync.release();
    }

    // ─── findSafeBoundary ────────────────────────────────────────────────

    @Test
    void findSafeBoundary_returnsNegativeOne_whenBelowMinSize() {
        // total=8 < minCompactable (10 default): no boundary possible
        var msgs = buildFakeHistory(8, i -> i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(-1, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    void findSafeBoundary_picksLatestUserInRange() {
        // total=25, keepMin=10 → maxBoundary=15, walk 15..10 looking for user.
        // Place user roles at 0,2,4,...,24 — so msg[14] is user (14 is even).
        var msgs = buildFakeHistory(25, i -> i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
        // Latest user in [10,15] is at 14.
        assertEquals(14, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    void findSafeBoundary_walksBackward_whenTrailingTurnsLackUser() {
        // total=25; users at 0,5,10,15. keepMin=10 → maxBoundary=15.
        // msg[15] is user → boundary=15. (Verifies we don't miss the top of the range.)
        var msgs = buildFakeHistory(25, i -> i % 5 == 0 ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(15, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    void findSafeBoundary_returnsNegativeOne_whenNoUserInEligibleRange() {
        // total=25, only users at 0..5 (all below minCompactable=10). keepMin=10.
        var msgs = buildFakeHistory(25, i -> i < 6 ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(-1, SessionCompactor.findSafeBoundary(msgs));
    }

    @Test
    void findSafeBoundary_honorsConfigOverrides() {
        ConfigService.set("chat.compactionKeepMessages", "5");
        ConfigService.set("chat.compactionMinTurns", "3");
        // total=12, keepMin=5 → maxBoundary=7. minCompactable=3.
        // Users at 3 and 7. Walks back from 7 — finds user at 7.
        var msgs = buildFakeHistory(12, i -> (i == 3 || i == 7) ? MessageRole.USER : MessageRole.ASSISTANT);
        assertEquals(7, SessionCompactor.findSafeBoundary(msgs));
    }

    // ─── shouldCompact ──────────────────────────────────────────────────

    @Test
    void aModelWithNoKnownWindowUsesTheTokenCapAlone() {
        var noWindow = new ModelInfo("m", "M", 0, 0, false);
        for (var mi : java.util.Arrays.asList(noWindow, null)) {
            assertEquals(150_000, SessionCompactor.triggerThreshold(mi, 50, 150_000));
            assertTrue(SessionCompactor.shouldCompact(150_000, mi, 50, 150_000));
            assertFalse(SessionCompactor.shouldCompact(149_999, mi, 50, 150_000));
            assertFalse(SessionCompactor.shouldCompact(5_000_000, mi, 50, 0), "with the cap off nothing is left to fire");
        }
    }

    @Test
    void shouldCompact_false_whenUnderBudget() {
        // contextWindow=200k, reserve=15k (default) → budget=185k. 100k is safely under the window rule.
        var mi = new ModelInfo("m", "M", 200_000, 8192, false);
        assertFalse(SessionCompactor.shouldCompact(100_000, mi, 0, 0));
    }

    @Test
    void shouldCompact_true_whenOverBudget() {
        // Token trigger off, so only the window rule can fire: one token past the window less the reserve. The
        // reserve is read rather than assumed 15k, because other classes set it while this one runs.
        var mi = new ModelInfo("m", "M", 200_000, 8192, false);
        int budget = 200_000 - SessionCompactor.effectiveReserveTokens();
        assertTrue(SessionCompactor.shouldCompact(budget + 1, mi, 0, 0));
        assertFalse(SessionCompactor.shouldCompact(budget, mi, 0, 0));
    }

    @Test
    void shouldCompact_reserveFloorClamps() {
        // If reserveTokens < floor, floor wins. Set reserve=1000, floor stays at default 9000.
        ConfigService.set("chat.compactionReserveTokens", "1000");
        // budget = 20000 - max(1000, 9000) = 11000. 12000 triggers.
        var mi = new ModelInfo("m", "M", 20_000, 4096, false);
        assertTrue(SessionCompactor.shouldCompact(12_000, mi, 0, 0));
        assertFalse(SessionCompactor.shouldCompact(10_000, mi, 0, 0));
    }

    // ─── shouldCompact: token trigger (JF-154, JF-159) ──────────────────

    @Test
    void theTokenTriggerFiresAtTheSmallerOfHalfTheWindowAndTheCapAndNotOneTokenBelow() {
        // 32k: half the window comes first; 128k: still half; 1M: the 150,000 cap comes first.
        var thresholds = Map.of(32_768, 16_384, 128_000, 64_000, 1_000_000, 150_000);
        thresholds.forEach((window, threshold) -> {
            var mi = new ModelInfo("m", "M", window, 8192, false);
            assertEquals(threshold, SessionCompactor.triggerThreshold(mi, 50, 150_000), () -> "window " + window);
            assertTrue(SessionCompactor.shouldCompact(threshold, mi, 50, 150_000), () -> "window " + window);
            assertFalse(SessionCompactor.shouldCompact(threshold - 1, mi, 50, 150_000), () -> "window " + window);
        });
    }

    @Test
    void theShareIsTakenOfTheRoomLeftAfterTheFixedPartAndTheCapStillComesFirstOnALargeWindow() {
        // main sends ~39,000 tokens of system prompt and tools; the customer agent ~5,000 (measured 2026-10-28).
        record Case(int window, int fixed, int threshold) {}
        for (var c : List.of(new Case(64_000, 39_000, 51_500), new Case(128_000, 39_000, 83_500),
                new Case(256_000, 39_000, 147_500), new Case(1_000_000, 39_000, 150_000),
                new Case(32_768, 5_000, 18_884))) {
            var mi = new ModelInfo("m", "M", c.window(), 8192, false);
            assertEquals(c.threshold(), SessionCompactor.triggerThreshold(mi, 50, 150_000, c.fixed()), c::toString);
        }
        var small = new ModelInfo("m", "M", 32_768, 8192, false);
        assertEquals(150_000, SessionCompactor.triggerThreshold(small, 50, 150_000, 39_000),
                "a fixed part that fills the window leaves the cap, so the window rule decides");
        assertEquals(Integer.MAX_VALUE, SessionCompactor.triggerThreshold(small, 50, 0, 39_000));
    }

    @Test
    void theDefaultsAreHalfTheWindowAndAHundredAndFiftyThousandTokensWithEveryKeyAbsent() {
        var large = new ModelInfo("m", "M", 1_000_000, 8192, false);
        var mid = new ModelInfo("m", "M", 128_000, 8192, false);
        assertTrue(SessionCompactor.shouldCompact(150_000, large));
        assertFalse(SessionCompactor.shouldCompact(149_999, large));
        assertTrue(SessionCompactor.shouldCompact(64_000, mid));
        assertFalse(SessionCompactor.shouldCompact(63_999, mid));
    }

    @Test
    void theStoredHalvesAreRead() {
        var large = new ModelInfo("m", "M", 1_000_000, 8192, false);
        ConfigService.set(SessionCompactor.TRIGGER_PERCENT_KEY, "10");
        assertTrue(SessionCompactor.shouldCompact(100_000, large));
        assertFalse(SessionCompactor.shouldCompact(99_999, large));
        ConfigService.set(SessionCompactor.TRIGGER_TOKENS_KEY, "40000");
        assertTrue(SessionCompactor.shouldCompact(40_000, large));
        assertFalse(SessionCompactor.shouldCompact(39_999, large));
    }

    @Test
    void zeroTurnsEitherHalfOffAndBothLeaveOnlyTheWindowRule() {
        var large = new ModelInfo("m", "M", 1_000_000, 8192, false);
        assertEquals(150_000, SessionCompactor.triggerThreshold(large, 0, 150_000), "percent off: the cap alone");
        assertEquals(500_000, SessionCompactor.triggerThreshold(large, 50, 0), "cap off: half the window alone");
        assertEquals(500_000, SessionCompactor.triggerThreshold(large, 50, -1), "a negative cap is off too");
        assertFalse(SessionCompactor.shouldCompact(499_999, large, 50, 0));
        assertTrue(SessionCompactor.shouldCompact(500_000, large, 50, 0));
        int budget = 1_000_000 - SessionCompactor.effectiveReserveTokens();
        assertFalse(SessionCompactor.shouldCompact(budget, large, 0, 0), "both off: the window rule alone");
        assertTrue(SessionCompactor.shouldCompact(budget + 1, large, 0, 0));
    }

    @Test
    void theWindowRuleStillFiresWhenItComesFirst() {
        var small = new ModelInfo("m", "M", 20_000, 4096, false);
        assertTrue(SessionCompactor.shouldCompact(19_999, small, 0, 150_000));
        assertTrue(SessionCompactor.shouldCompact(19_999, small, 0, 0));
    }

    // ─── compact() on the turn's own request (JF-154) ──────────────────

    @Test
    void aSummaryOnTheTurnSendsItsPrefixAndToolsWithTheInstructionLast() {
        var conv = ConversationService.create(agent, "web", "user1");
        var written = Instant.now().minusSeconds(600);
        saveSummary(conv, "FIRST SUMMARY", written);
        seedMessages(conv, 25);
        commitAndReopen();
        var turn = turnMessages(conv.id);
        var calls = new ArrayList<List<ChatMessage>>();
        var toolLists = new ArrayList<List<ToolDef>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", new TurnRequest(turn, TOOLS),
                (msgs, defs) -> {
                    calls.add(msgs);
                    toolLists.add(defs);
                    return reply("TURN SUMMARY");
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        int summarized = result.turnsCompacted();
        assertEquals(1, calls.size());
        assertEquals(List.of(TOOLS), toolLists);
        var request = calls.getFirst();
        assertEquals(summarized + 2, request.size());
        assertEquals(turn.subList(0, summarized + 1), request.subList(0, summarized + 1));
        assertEquals(ChatMessage.system("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\n" + labelUpTo(written)
                + "\n\nFIRST SUMMARY"), request.getFirst());
        assertEquals("user", request.getLast().role());
        assertEquals(SECTIONS, headings(request.getLast().content()));
        commitAndReopen();
        assertEquals("TURN SUMMARY", SessionCompaction.findLatest(Conversation.findById(conv.id)).summary);
    }

    @Test
    void aToolCallReplyIsRetriedOnceOnTheTranscriptWithoutTools() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var rows = rowsOf(conv.id);
        var calls = new ArrayList<List<ChatMessage>>();
        var toolLists = new ArrayList<List<ToolDef>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                new TurnRequest(turnMessages(conv.id), TOOLS), (msgs, defs) -> {
                    calls.add(msgs);
                    toolLists.add(defs);
                    return calls.size() == 1 ? toolCallReply() : reply("RETRIED SUMMARY");
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(List.of(TOOLS, List.of()), toolLists);
        assertTranscript(rows.subList(0, result.turnsCompacted()), calls.get(1));
        commitAndReopen();
        assertEquals("RETRIED SUMMARY", SessionCompaction.findLatest(Conversation.findById(conv.id)).summary);
    }

    @Test
    void aReplyThatCallsAToolAgainAfterTheRetryStoresNoSummary() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                new TurnRequest(turnMessages(conv.id), TOOLS), (msgs, defs) -> {
                    calls.incrementAndGet();
                    return toolCallReply();
                });

        assertEquals(2, calls.get());
        assertFalse(result.compacted());
        assertEquals("empty summary", result.skipReason());
        commitAndReopen();
        assertNull(Conversation.<Conversation>findById(conv.id).compactionSince);
    }

    @Test
    void aTurnListThatNoLongerMatchesTheRowsSendsTheTranscript() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var rows = rowsOf(conv.id);
        var shifted = new ArrayList<>(turnMessages(conv.id));
        shifted.remove(1);
        var calls = new ArrayList<List<ChatMessage>>();
        var toolLists = new ArrayList<List<ToolDef>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", new TurnRequest(shifted, TOOLS),
                (msgs, defs) -> {
                    calls.add(msgs);
                    toolLists.add(defs);
                    return reply("TRANSCRIPT SUMMARY");
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(List.of(List.of()), toolLists);
        assertEquals(1, calls.size());
        assertTranscript(rows.subList(0, result.turnsCompacted()), calls.getFirst());
    }

    @Test
    void aKeptUserMessageWhoseTextDiffersFromItsRowSendsTheTranscript() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var rows = rowsOf(conv.id);
        // Every user slot rewritten, so whichever user row the boundary lands on, the slot after it is a user
        // message whose text is not that row's: the role check passes and only the text check can refuse.
        var shifted = new ArrayList<ChatMessage>();
        for (var m : turnMessages(conv.id)) shifted.add("user".equals(m.role()) ? ChatMessage.user("other text") : m);
        var calls = new ArrayList<List<ChatMessage>>();
        var toolLists = new ArrayList<List<ToolDef>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", new TurnRequest(shifted, TOOLS),
                (msgs, defs) -> {
                    calls.add(msgs);
                    toolLists.add(defs);
                    return reply("TRANSCRIPT SUMMARY");
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(List.of(List.of()), toolLists);
        assertTranscript(rows.subList(0, result.turnsCompacted()), calls.getFirst());
    }

    @Test
    void aFailedCallOnTheTurnsRequestIsRetriedOnceOnTheTranscript() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var rows = rowsOf(conv.id);
        var calls = new ArrayList<List<ChatMessage>>();
        var toolLists = new ArrayList<List<ToolDef>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                new TurnRequest(turnMessages(conv.id), TOOLS), (msgs, defs) -> {
                    calls.add(msgs);
                    toolLists.add(defs);
                    if (calls.size() == 1) throw new IllegalStateException("prompt plus max_tokens exceeds the window");
                    return reply("TRANSCRIPT AFTER FAILURE");
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(List.of(TOOLS, List.of()), toolLists);
        assertTranscript(rows.subList(0, result.turnsCompacted()), calls.get(1));
        commitAndReopen();
        assertEquals("TRANSCRIPT AFTER FAILURE", SessionCompaction.findLatest(Conversation.findById(conv.id)).summary);
    }

    @Test
    void aTranscriptRetryThatFailsTooStoresNoSummary() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var calls = new java.util.concurrent.atomic.AtomicInteger();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                new TurnRequest(turnMessages(conv.id), TOOLS), (msgs, defs) -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("provider down");
                });

        assertEquals(2, calls.get(), "the turn's request, then the transcript once");
        assertFalse(result.compacted());
        assertEquals("llm error", result.skipReason());
        commitAndReopen();
        assertNull(Conversation.<Conversation>findById(conv.id).compactionSince);
    }

    @Test
    void textPartsAloneKeepTheShapeTheTurnSendsThem() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var turn = new ArrayList<>(turnMessages(conv.id));
        // A user row with a document attachment hydrates as text parts and no media part.
        var withDocument = new ChatMessage("user", List.of(Map.of("type", "text", "text", "Turn 0 content"),
                Map.of("type", "text", "text", "[Attached file: notes.pdf]")), null, null, null);
        turn.set(1, withDocument);
        var request = new AtomicReference<List<ChatMessage>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", new TurnRequest(turn, TOOLS),
                (msgs, defs) -> {
                    request.set(msgs);
                    return reply("DOCUMENT SUMMARY");
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(withDocument, request.get().get(1));
    }

    @Test
    void mediaInTheSummarizedTurnsReachesTheSummaryAsItsText() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25);
        commitAndReopen();
        var turn = new ArrayList<>(turnMessages(conv.id));
        var image = Map.<String, Object>of("type", "image_url", "image_url", Map.of("url", "data:image/png;base64,AA=="));
        turn.set(1, new ChatMessage("user", List.of(Map.of("type", "text", "text", "Turn 0 with a photo"), image),
                null, null, null));
        turn.set(3, new ChatMessage("user", List.of(image), null, null, null));
        var request = new AtomicReference<List<ChatMessage>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", new TurnRequest(turn, TOOLS),
                (msgs, defs) -> {
                    request.set(msgs);
                    return reply("MEDIA SUMMARY");
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(ChatMessage.user("Turn 0 with a photo"), request.get().get(1));
        assertEquals(ChatMessage.user("[attachment]"), request.get().get(3));
        assertEquals(turn.get(2), request.get().get(2));
    }

    // ─── compact() end-to-end ──────────────────────────────────────────

    @Test
    void compact_persistsRowAndBumpsWatermark_onSuccess() {
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
    void compact_skipsWhenSummarizerReturnsEmpty() {
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
    void compact_skipsWhenSummarizerThrows() {
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
    void compact_skipsWhenTooFewMessages() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 5); // below minCompactable
        commitAndReopen();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                msgs -> "would summarize");
        assertFalse(result.compacted());
        assertEquals("no safe boundary or below min-turns", result.skipReason());
    }

    @Test
    void compactForced_succeedsWhereAutoSkipsDueToLowTurnCount() {
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
    void compact_additionalInstructions_threadedIntoSystemPrompt() {
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
    void compact_leavesOutASummaryThatAResetCleared() {
        var conv = ConversationService.create(agent, "web", "user1");
        saveSummary(conv, "PRE-RESET SUMMARY", Instant.now().minusSeconds(600));
        conv.contextSince = Instant.now().minusSeconds(300);
        conv.save();
        seedMessages(conv, 25, "After reset");
        commitAndReopen();

        var input = new AtomicReference<String>();
        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> {
            input.set((String) msgs.get(1).content());
            return "POST-RESET SUMMARY";
        });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertFalse(input.get().contains("PRE-RESET SUMMARY"), "summarizer input: " + input.get());
    }

    @Test
    void compact_keepsOriginalMessagesIntact_afterSuccess() {
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

    // ─── Catching up a chat past its history limit (JF-159) ─────────────

    @Test
    void aChatAtTwoAndAHalfTimesTheLimitIsSummarizedInChunksOldestFirst() {
        ConfigService.set("chat.maxContextMessages", "40");
        var conv = ConversationService.create(agent, "web", "user1");
        seedRows(conv, 100, i -> i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, 0);
        commitAndReopen();
        var rows = allRows(conv.id);
        var calls = new ArrayList<List<ChatMessage>>();
        var toolLists = new ArrayList<List<ToolDef>>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model",
                new TurnRequest(turnMessages(conv.id), TOOLS), (msgs, defs) -> {
                    calls.add(msgs);
                    toolLists.add(defs);
                    return reply("SUMMARY " + calls.size());
                });

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(List.of(List.of(), List.of(), List.of()), toolLists, "rows the turn never loaded go as the transcript");
        assertTranscript(rows.subList(0, 40), calls.get(0));
        assertTranscript(rows.subList(40, 80), calls.get(1));
        assertTranscript(rows.subList(80, 90), calls.get(2));
        assertEquals(90, result.turnsCompacted());
        assertEquals(3, result.calls());
        assertEquals(0, result.rowsLeft());
        commitAndReopen();
        var reloaded = Conversation.<Conversation>findById(conv.id);
        assertEquals(contents(rows.subList(90, 100)), ConversationService.loadRecentMessages(reloaded).stream()
                .map(m -> m.content).toList());
        assertEquals(List.of("SUMMARY 1", "SUMMARY 2", "SUMMARY 3"), storedSummaries(conv.id), "one segment a chunk");
    }

    @Test
    void eachChunkFitsTheTokensOneCallTakesAndTheNextCompactionResumesWhereTheCallLimitStopped() {
        ConfigService.set("chat.maxContextMessages", "40");
        int max = SessionCompactor.MAX_SUMMARY_CALLS;
        var conv = ConversationService.create(agent, "web", "user1");
        // Each row renders to 400 characters, 100 tokens, so 1,000 tokens hold 10 rows.
        seedRows(conv, 10 * max + 20, i -> i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, 400);
        commitAndReopen();
        var rows = allRows(conv.id);
        var calls = new ArrayList<List<ChatMessage>>();
        SessionCompactor.ModelCall call = (msgs, defs) -> {
            calls.add(msgs);
            return reply("SUMMARY " + calls.size());
        };

        var stopped = new AtomicReference<SessionCompactor.CompactionResult>();
        var events = EventLogger.captureForTest(() -> stopped.set(SessionCompactor.compact(conv.id,
                "openrouter/test-model", new TurnRequest(turnMessages(conv.id), TOOLS), call, false, null, 1_000,
                max)));

        assertEquals(10 * max, stopped.get().turnsCompacted());
        assertEquals(max, stopped.get().calls());
        assertEquals(10, stopped.get().rowsLeft(), "the 20 rows left, less the 10 a compaction keeps");
        assertEquals(max, calls.size());
        var expectedPrompt = new StringBuilder("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER);
        for (int k = 0; k < max; k++) {
            assertTranscript(rows.subList(10 * k, 10 * k + 10), calls.get(k));
            expectedPrompt.append("\n\n").append(label(rows.get(10 * k).createdAt(), rows.get(10 * k + 9).createdAt()))
                    .append("\n\nSUMMARY ").append(k + 1);
        }
        assertEquals(List.of("Compaction for conversation %d stopped after %d summary calls; the next turn continues it"
                .formatted(conv.id, max)), events.stream().filter(e -> "compaction".equals(e.category()))
                .map(EventLogger.Captured::message).toList());

        calls.clear();
        var resumed = SessionCompactor.compact(conv.id, "openrouter/test-model",
                new TurnRequest(turnMessages(conv.id), TOOLS), call, false, null, 1_000, max);

        assertEquals(10, resumed.turnsCompacted(), "the 20 rows left fit the limit: one call, keeping 10");
        assertEquals(1, calls.size());
        assertEquals(ChatMessage.system(expectedPrompt.toString()), calls.getFirst().getFirst(),
                "the turn's own request, carrying every segment the stopped compaction left");
        assertEquals(ChatMessage.user(rows.get(10 * max).content()), calls.getFirst().get(1));
    }

    @Test
    void oldestRowsWithNoUserMessageToSplitOnFallBackToTheNewestRowsAndSaySo() {
        ConfigService.set("chat.maxContextMessages", "40");
        var conv = ConversationService.create(agent, "web", "user1");
        // One user row, then 59 rows of a single tool loop, then 40 rows of ordinary turns.
        seedRows(conv, 100, i -> i == 0 || i >= 60 && i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, 0);
        commitAndReopen();
        var rows = allRows(conv.id);
        var inputs = new ArrayList<List<ChatMessage>>();

        var events = EventLogger.captureForTest(() -> assertTrue(SessionCompactor.compact(conv.id,
                "openrouter/test-model", msgs -> {
                    inputs.add(msgs);
                    return "NEWEST SUMMARY";
                }).compacted()));

        assertEquals(1, inputs.size());
        assertTranscript(rows.subList(60, 90), inputs.getFirst());
        assertEquals(List.of(("Compaction for conversation %d found no user message to end a summary on among its "
                + "oldest rows; the 60 rows before its newest 40 are not summarized").formatted(conv.id)),
                events.stream().filter(e -> "compaction".equals(e.category())).map(EventLogger.Captured::message).toList());
        commitAndReopen();
        assertEquals(rows.get(90).createdAt(), Conversation.<Conversation>findById(conv.id).compactionSince);
    }

    // ─── Summary segments (JF-161) ──────────────────────────────────────

    @Test
    void aThirdCompactionAddsASegmentAndLeavesTheFirstTwoAsTheyWere() {
        var conv = ConversationService.create(agent, "web", "user1");
        var inputs = new ArrayList<String>();
        var counts = new ArrayList<Integer>();
        var names = List.of("SEGMENT ONE", "SEGMENT TWO", "SEGMENT THREE");
        for (int b = 0; b < names.size(); b++) {
            var name = names.get(b);
            seedMinutes(Conversation.findById(conv.id), BASE.plusSeconds(b * 3_600L), 25, "Batch " + b);
            commitAndReopen();
            var result = SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> {
                inputs.add((String) msgs.get(1).content());
                return name;
            });
            assertTrue(result.compacted(), "reason=" + result.skipReason());
            counts.add(result.turnsCompacted());
            commitAndReopen();
        }

        var rows = allRows(conv.id);
        var expected = new StringBuilder("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER);
        int start = 0;
        for (int k = 0; k < names.size(); k++) {
            var covered = rows.subList(start, start + counts.get(k));
            assertEquals(SessionCompactor.renderTurns(covered), inputs.get(k), "each call sends its own rows alone");
            expected.append("\n\n").append(label(covered.getFirst().createdAt(), covered.getLast().createdAt()))
                    .append("\n\n").append(names.get(k));
            start += counts.get(k);
        }
        assertEquals(names, storedSummaries(conv.id));
        assertEquals(expected.toString(), SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id)));
    }

    @Test
    void pastTheBudgetExactlyTheOldestSegmentsMergeIntoOneThatKeepsTheCommitHashTheyName() {
        // Defaults: a 20,000-token budget and an 8,192-token output limit. Five 5,000-token segments pass the budget,
        // and the oldest three, merged into at most 8,192 tokens, are the fewest whose merge fits.
        var conv = ConversationService.create(agent, "web", "user1");
        var texts = new ArrayList<String>();
        for (int k = 0; k < 5; k++) {
            var head = k == 0 ? "LeaveFlow: pushed commit 76781a2 to the repo." : "Segment " + (k + 1) + ".";
            texts.add((head + " " + "x".repeat(20_000)).substring(0, 20_000));
        }
        var segmentInputs = new ArrayList<List<ChatMessage>>();
        var mergeInputs = new ArrayList<List<ChatMessage>>();
        SessionCompactor.Summarizer summarizer = msgs -> {
            if (((String) msgs.get(1).content()).startsWith("[Turns ")) {
                mergeInputs.add(msgs);
                return "MERGED: LeaveFlow commit " + (((String) msgs.get(1).content()).contains("76781a2") ? "76781a2" : "?");
            }
            segmentInputs.add(msgs);
            return texts.get(segmentInputs.size() - 1);
        };
        var counts = new ArrayList<Integer>();
        for (int b = 0; b < 5; b++) {
            seedMinutes(Conversation.findById(conv.id), BASE.plusSeconds(b * 3_600L), 25, "Batch " + b);
            commitAndReopen();
            var result = SessionCompactor.compact(conv.id, "openrouter/test-model", summarizer);
            assertTrue(result.compacted(), "reason=" + result.skipReason());
            counts.add(result.turnsCompacted());
            commitAndReopen();
            if (b == 3) assertEquals(List.of(), mergeInputs, "four segments sit exactly at the budget");
        }

        var rows = allRows(conv.id);
        var labels = new ArrayList<String>();
        int start = 0;
        for (int count : counts) {
            labels.add(label(rows.get(start).createdAt(), rows.get(start + count - 1).createdAt()));
            start += count;
        }
        assertEquals(1, mergeInputs.size());
        var merge = mergeInputs.getFirst();
        assertEquals(SECTIONS, headings(merge.getFirst().content()));
        assertTrue(((String) merge.getFirst().content()).lines().toList().contains(CARRY_FORWARD),
                () -> "merge instruction: " + merge.getFirst().content());
        assertEquals(ChatMessage.user(labels.get(0) + "\n\n" + texts.get(0) + "\n\n" + labels.get(1) + "\n\n"
                + texts.get(1) + "\n\n" + labels.get(2) + "\n\n" + texts.get(2)), merge.get(1));
        int mergedEnd = counts.get(0) + counts.get(1) + counts.get(2) - 1;
        assertEquals("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER
                        + "\n\n" + label(rows.getFirst().createdAt(), rows.get(mergedEnd).createdAt())
                        + "\n\nMERGED: LeaveFlow commit 76781a2"
                        + "\n\n" + labels.get(3) + "\n\n" + texts.get(3) + "\n\n" + labels.get(4) + "\n\n" + texts.get(4),
                SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id)));
        var stored = storedSummaries(conv.id);
        assertEquals(6, stored.size(), "the merged segments stay in the table");
        assertEquals(texts, stored.subList(0, 5));
        var segments = storedSegments(conv.id);
        var result = segments.getLast();
        assertEquals(java.util.Arrays.asList(result.id, result.id, result.id, null, null, null),
                segments.stream().map(sc -> sc.mergedIntoId).toList(), "exactly the oldest three are marked merged");
        assertEquals(rows.getFirst().createdAt(), result.coversFrom);
        assertEquals(rows.get(mergedEnd).createdAt(), result.coversTo);
        assertEquals(counts.get(0) + counts.get(1) + counts.get(2), result.turnCount);
    }

    @Test
    void aTranscriptCarriesEachToolResultCutToSixHundredCharactersWithItsLengthAndTheTurnsTextWhole() {
        var conv = ConversationService.create(agent, "web", "user1");
        var ask = "Fetch the LeaveFlow docs. " + "u".repeat(3_000);
        var reply = "Here is what the docs say. " + "a".repeat(3_000);
        var longArgs = "{\"url\":\"https://example.com/" + "p".repeat(2_000) + "\"}";
        var huge = "r".repeat(50_000);
        var exact = "s".repeat(600);
        var over = "t".repeat(601);
        ConversationService.appendMessage(conv, MessageRole.USER, ask, null, null, null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, null, toolCall("call_1", longArgs), null, null);
        ConversationService.appendMessage(conv, MessageRole.TOOL, huge, null, "call_1", null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, null, toolCall("call_2", "{}"), null, null);
        ConversationService.appendMessage(conv, MessageRole.TOOL, exact, null, "call_2", null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, null, toolCall("call_3", "{}"), null, null);
        ConversationService.appendMessage(conv, MessageRole.TOOL, over, null, "call_3", null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, reply, null, null, null);
        var tail = List.of("next", "ok", "more", "fine", "last", "done");
        for (int i = 0; i < tail.size(); i++) {
            ConversationService.appendMessage(conv, i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT, tail.get(i),
                    null, null, null);
        }
        commitAndReopen();
        var input = new AtomicReference<String>();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> {
            input.set((String) msgs.get(1).content());
            return "TRIMMED SUMMARY";
        }, true, null);

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        assertEquals(10, result.turnsCompacted(), "/compact keeps 4, from the user row 'more'");
        var shortArgs = longArgs.substring(0, 600) + "… [first 600 of " + longArgs.length() + " characters]";
        assertEquals("[USER] " + ask + "\n\n"
                + "[ASSISTANT]\n[TOOL_CALLS] " + toolCall("call_1", shortArgs) + "\n\n"
                + "[TOOL] " + "r".repeat(600) + "… [first 600 of 50000 characters]\n[TOOL_RESULT_ID] call_1\n\n"
                + "[ASSISTANT]\n[TOOL_CALLS] " + toolCall("call_2", "{}") + "\n\n"
                + "[TOOL] " + exact + "\n[TOOL_RESULT_ID] call_2\n\n"
                + "[ASSISTANT]\n[TOOL_CALLS] " + toolCall("call_3", "{}") + "\n\n"
                + "[TOOL] " + "t".repeat(600) + "… [first 600 of 601 characters]\n[TOOL_RESULT_ID] call_3\n\n"
                + "[ASSISTANT] " + reply + "\n\n"
                + "[USER] next\n\n"
                + "[ASSISTANT] ok\n\n", input.get());
    }

    @Test
    void theStoredToolResultLengthIsRead() {
        ConfigService.set(SessionCompactor.TOOL_RESULT_CHARS_KEY, "100");
        var conv = ConversationService.create(agent, "web", "user1");
        ConversationService.appendMessage(conv, MessageRole.USER, "fetch it", null, null, null);
        ConversationService.appendMessage(conv, MessageRole.ASSISTANT, null, toolCall("call_1", "{}"), null, null);
        ConversationService.appendMessage(conv, MessageRole.TOOL, "r".repeat(5_000), null, "call_1", null);
        var tail = List.of("ok", "next", "fine", "last", "done");
        for (int i = 0; i < tail.size(); i++) {
            ConversationService.appendMessage(conv, i % 2 == 0 ? MessageRole.ASSISTANT : MessageRole.USER, tail.get(i),
                    null, null, null);
        }
        commitAndReopen();
        var input = new AtomicReference<String>();

        assertTrue(SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> {
            input.set((String) msgs.get(1).content());
            return "S";
        }, true, null).compacted());

        assertTrue(input.get().contains("[TOOL] " + "r".repeat(100) + "… [first 100 of 5000 characters]\n"), input.get());
    }

    @Test
    void aRollingSummaryChainBecomesOneSegmentTheLatestAndTheUpgradeRunsOnce() throws Exception {
        var conv = ConversationService.create(agent, "web", "user1");
        var seeded = seedMinutes(conv, BASE, 30, "Turn");
        // Three cumulative rewrites as the code before JF-161 stored them; the last left rows 20 on.
        saveSummary(conv, "SUMMARY v1", BASE.plusSeconds(3_600));
        saveSummary(conv, "SUMMARY v1 v2", BASE.plusSeconds(7_200));
        saveSummary(conv, "SUMMARY v1 v2 v3", BASE.plusSeconds(10_800));
        conv.compactionSince = seeded.get(20).createdAt();
        conv.save();
        commitAndReopen();
        try {
            ConfigService.delete(jobs.DefaultConfigJob.SUMMARY_SEGMENTS_KEY);
            runSegmentUpgrade();
            commitAndReopen();

            assertEquals("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\n"
                            + label(seeded.getFirst().createdAt(), seeded.get(19).createdAt()) + "\n\nSUMMARY v1 v2 v3",
                    SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id)));
            assertEquals(List.of("SUMMARY v1", "SUMMARY v1 v2", "SUMMARY v1 v2 v3"), storedSummaries(conv.id));
            var chain = storedSegments(conv.id);
            var latest = chain.getLast();
            assertEquals(java.util.Arrays.asList(latest.id, latest.id, null),
                    chain.stream().map(sc -> sc.mergedIntoId).toList());
            assertEquals(seeded.getFirst().createdAt(), latest.coversFrom);
            assertEquals(seeded.get(19).createdAt(), latest.coversTo);
            assertEquals("true", ConfigService.get(jobs.DefaultConfigJob.SUMMARY_SEGMENTS_KEY));

            // A chain written after the marker stays as it is, which only a step that did not run again leaves.
            var later = ConversationService.create(agent, "web", "user2");
            saveSummary(later, "LATER v1", BASE.plusSeconds(3_600));
            saveSummary(later, "LATER v1 v2", BASE.plusSeconds(7_200));
            commitAndReopen();
            runSegmentUpgrade();
            commitAndReopen();
            assertEquals(java.util.Arrays.asList(null, null),
                    storedSegments(later.id).stream().map(sc -> sc.mergedIntoId).toList());
        } finally {
            ConfigService.set(jobs.DefaultConfigJob.SUMMARY_SEGMENTS_KEY, "true");
        }
    }

    @Test
    void segmentsBeforeAResetStayOutOfThePromptAndTheOnesAfterItAreShown() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMinutes(conv, BASE, 25, "Before");
        commitAndReopen();
        assertTrue(SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> "PRE-RESET SEGMENT").compacted());
        commitAndReopen();
        var reset = Conversation.<Conversation>findById(conv.id);
        reset.contextSince = AppClock.now();
        reset.save();
        commitAndReopen();
        var after = seedMinutes(Conversation.findById(conv.id), Instant.now().plusSeconds(60), 25, "After");
        commitAndReopen();

        var result = SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> "POST-RESET SEGMENT");

        assertTrue(result.compacted(), "reason=" + result.skipReason());
        commitAndReopen();
        var covered = after.subList(0, result.turnsCompacted());
        assertEquals("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\n"
                        + label(covered.getFirst().createdAt(), covered.getLast().createdAt()) + "\n\nPOST-RESET SEGMENT",
                SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id)));
    }

    // ─── appendSummaryToPrompt ───────────────────────────────────────────

    @Test
    void appendSummaryToPrompt_returnsUnchanged_whenNoCompactionRow() {
        var conv = ConversationService.create(agent, "web", "user1");
        commitAndReopen();
        var reloaded = Conversation.<Conversation>findById(conv.id);

        var result = SessionCompactor.appendSummaryToPrompt("SYSTEM PROMPT", reloaded);
        assertEquals("SYSTEM PROMPT", result);
    }

    @Test
    void appendSummaryToPrompt_appendsHeaderAndSummary_whenRowPresent() {
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
    void theStackTellsTheModelThatLaterSegmentsAndTheMessagesAfterThemWin() {
        // Each segment keeps the next steps of its own time, so an earlier one can still say to wait for work since done.
        var conv = ConversationService.create(agent, "web", "user1");
        var older = saveSummary(conv, "Next steps: wait for scrape job 702.", Instant.now().minusSeconds(600));
        var newer = saveSummary(conv, "Job 702 finished and its folder was deleted.", Instant.now().minusSeconds(300));
        commitAndReopen();

        assertEquals("SYS\n\n[Prior conversation summary — earlier turns were compacted to fit the context window. Segments "
                + "run oldest first; where they differ, a later segment wins, and the messages after the summary win over all "
                + "of them: an open item or next step in an earlier segment may already be settled.]\n\n"
                + labelUpTo(older.compactedAt) + "\n\nNext steps: wait for scrape job 702.\n\n"
                + labelUpTo(newer.compactedAt) + "\n\nJob 702 finished and its folder was deleted.",
                SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id)));
    }

    @Test
    void aMergedSegmentStaysOutOfThePrompt() {
        var conv = ConversationService.create(agent, "web", "user1");
        var merged = saveSummary(conv, "MERGED AWAY", Instant.now().minusSeconds(600));
        var kept = saveSummary(conv, "STILL LIVE", Instant.now().minusSeconds(300));
        merged.mergedIntoId = kept.id;
        merged.save();
        commitAndReopen();

        assertEquals("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\n" + labelUpTo(kept.compactedAt)
                + "\n\nSTILL LIVE", SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id)));
    }

    @Test
    void appendSummaryToPrompt_skipsASummaryThatAResetCleared() {
        var conv = ConversationService.create(agent, "web", "user1");
        saveSummary(conv, "PRE-RESET SUMMARY", Instant.now().minusSeconds(600));
        conv.contextSince = Instant.now().minusSeconds(300);
        conv.save();
        commitAndReopen();

        assertEquals("SYS", SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id)));
    }

    // ─── renderTurns ────────────────────────────────────────────────────

    @Test
    void renderTurns_includesRoleAndContent() {
        var snaps = List.of(
                new MessageSnapshot(MessageRole.USER.value, "Hello there", null, null, Instant.now()),
                new MessageSnapshot(MessageRole.ASSISTANT.value, "Hi, how can I help?", null, null, Instant.now())
        );
        var rendered = SessionCompactor.renderTurns(snaps);
        assertTrue(rendered.contains("[USER] Hello there"));
        assertTrue(rendered.contains("[ASSISTANT] Hi, how can I help?"));
    }

    @Test
    void renderTurns_includesToolCallsAndResults() {
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
    void firstChoiceText_returnsStringContent() {
        var msg = new ChatMessage("assistant", "The answer is 42", null, null, null);
        var resp = new ChatResponse("r1", "m",
                List.of(new Choice(0, msg, "stop")),
                new Usage(10, 5, 15, 0, 0, 0));
        assertEquals("The answer is 42", SessionCompactor.firstChoiceText(resp));
    }

    @Test
    void firstChoiceText_returnsNullForEmptyChoices() {
        var resp = new ChatResponse("r1", "m", List.of(), null);
        assertNull(SessionCompactor.firstChoiceText(resp));
    }

    @Test
    void firstChoiceText_returnsNullForNullResponse() {
        assertNull(SessionCompactor.firstChoiceText(null));
    }

    // ─── Helpers ────────────────────────────────────────────────────────

    private static final List<ToolDef> TOOLS = List.of(ToolDef.of("counter_bump", "Counts its runs",
            Map.of("type", "object", "properties", Map.of())));

    private static final List<String> SECTIONS = List.of("Goals and constraints", "Decisions",
            "Identifiers and facts", "Open questions and errors", "Next steps");

    private static final String CARRY_FORWARD = "Keep every file path, URL, commit hash, name, number and open item "
            + "they record, copied exactly as written, unless a later summary supersedes it. Finished work may shrink "
            + "to one line, but keep its identifiers.";

    /** Rows seeded at a fixed past instant, so a segment's label names minutes this test chose. */
    private static final Instant BASE = Instant.parse("2026-10-20T08:00:00Z");

    private static final java.time.format.DateTimeFormatter LABEL_TIME =
            java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** The label of a segment whose rows are undated: the time it was written, in the operator's zone. */
    private static String labelUpTo(Instant compactedAt) {
        var zone = services.TimezoneResolver.appZone();
        return "[Turns up to " + LABEL_TIME.format(compactedAt.atZone(zone)) + ", " + zone.getId() + "]";
    }

    /** The label a segment covering {@code from} to {@code to} carries, in the operator's zone. */
    private static String label(Instant from, Instant to) {
        var zone = services.TimezoneResolver.appZone();
        return "[Turns from " + LABEL_TIME.format(from.atZone(zone)) + " to " + LABEL_TIME.format(to.atZone(zone))
                + ", " + zone.getId() + "]";
    }

    /** Every stored summary of the conversation, merged or not, in the order they were written. */
    private static List<String> storedSummaries(Long conversationId) {
        var out = new ArrayList<String>();
        for (Object o : SessionCompaction.find("conversation.id = ?1 ORDER BY id", conversationId).fetch()) {
            out.add(((SessionCompaction) o).summary);
        }
        return out;
    }

    private static List<SessionCompaction> storedSegments(Long conversationId) {
        var out = new ArrayList<SessionCompaction>();
        for (Object o : SessionCompaction.find("conversation.id = ?1 ORDER BY id", conversationId).fetch()) {
            out.add((SessionCompaction) o);
        }
        return out;
    }

    private static void runSegmentUpgrade() throws Exception {
        var step = jobs.DefaultConfigJob.class.getDeclaredMethod("adoptRollingSummariesOnce");
        step.setAccessible(true);
        step.invoke(new jobs.DefaultConfigJob());
    }

    /** One stored tool call as {@code Message.toolCalls} holds it. */
    private static String toolCall(String id, String arguments) {
        var function = new com.google.gson.JsonObject();
        function.addProperty("name", "web_fetch");
        function.addProperty("arguments", arguments);
        var call = new com.google.gson.JsonObject();
        call.addProperty("id", id);
        call.addProperty("type", "function");
        call.add("function", function);
        return call.toString();
    }

    /** {@code count} alternating user and assistant rows {@code label i}, at least a minute apart from {@code from}. */
    private static List<MessageSnapshot> seedMinutes(Conversation conv, Instant from, int count, String label) {
        var tick = new AtomicLong(from.toEpochMilli() - 60_000);
        var ticking = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(tick.addAndGet(60_000)); }
        };
        var out = new ArrayList<MessageSnapshot>();
        AppClock.runWith(ticking, () -> {
            for (int i = 0; i < count; i++) {
                var m = ConversationService.appendMessage(conv, i % 2 == 0 ? MessageRole.USER : MessageRole.ASSISTANT,
                        label + " " + i, null, null, null);
                out.add(new MessageSnapshot(m.role, m.content, m.toolCalls, m.toolResults, m.createdAt));
            }
        });
        return out;
    }

    /** The list a turn builds from {@code conversationId}'s rows, under a system prompt carrying any summary. */
    private static List<ChatMessage> turnMessages(Long conversationId) {
        return Tx.run(() -> {
            var conv = Conversation.<Conversation>findById(conversationId);
            return MessageHydrator.buildMessages(SessionCompactor.appendSummaryToPrompt("SYS", conv), conv).messages();
        });
    }

    private static List<MessageSnapshot> rowsOf(Long conversationId) {
        return Tx.run(() -> SessionCompactor.snapshotParentMessages(Conversation.findById(conversationId)));
    }

    /**
     * {@code request} is the transcript shape: the instruction as the system prompt, then the summarized rows and no
     * earlier summary (JF-161).
     */
    private static void assertTranscript(List<MessageSnapshot> summarized, List<ChatMessage> request) {
        assertEquals(2, request.size());
        assertEquals("system", request.getFirst().role());
        assertEquals(SECTIONS, headings(request.getFirst().content()));
        assertEquals(ChatMessage.user(SessionCompactor.renderTurns(summarized)), request.get(1));
    }

    /** Every stored row of the conversation, oldest first, whatever its floor or history limit. */
    private static List<MessageSnapshot> allRows(Long conversationId) {
        return Tx.run(() -> {
            var out = new ArrayList<MessageSnapshot>();
            for (Object o : Message.find("conversation.id = ?1 ORDER BY createdAt, id", conversationId).fetch()) {
                var m = (Message) o;
                out.add(new MessageSnapshot(m.role, m.content, m.toolCalls, m.toolResults, m.createdAt));
            }
            return out;
        });
    }

    private static List<String> contents(List<MessageSnapshot> rows) {
        return rows.stream().map(MessageSnapshot::content).toList();
    }

    /**
     * {@code count} rows a millisecond apart, so no two share a timestamp a floor could split. With {@code rendered}
     * above 0, each row's content is padded so the row renders to exactly that many characters for a summary.
     */
    private static void seedRows(Conversation conv, int count, java.util.function.IntFunction<MessageRole> roleAt,
                                 int rendered) {
        var tick = new AtomicLong(Instant.now().minusSeconds(3_600).toEpochMilli());
        var ticking = new Clock() {
            @Override public ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(ZoneId zone) { return this; }
            @Override public Instant instant() { return Instant.ofEpochMilli(tick.incrementAndGet()); }
        };
        AppClock.runWith(ticking, () -> {
            for (int i = 0; i < count; i++) {
                var role = roleAt.apply(i);
                var content = "Row " + i;
                if (rendered > 0) {
                    int overhead = ("[" + role.value.toUpperCase() + "] ").length() + 2;
                    content = (content + " " + "x".repeat(rendered)).substring(0, rendered - overhead);
                }
                ConversationService.appendMessage(conv, role, content, null, null, null);
            }
        });
    }

    private static List<String> headings(Object instruction) {
        return ((String) instruction).lines().filter(l -> l.startsWith("### ")).map(l -> l.substring(4)).toList();
    }

    private static ChatResponse reply(String text) {
        return new ChatResponse("r", "m", List.of(new Choice(0, ChatMessage.assistant(text), "stop")), null);
    }

    private static ChatResponse toolCallReply() {
        var call = new ToolCall("call_1", "function", new FunctionCall("counter_bump", "{}"));
        return new ChatResponse("r", "m",
                List.of(new Choice(0, new ChatMessage("assistant", null, List.of(call), null, null), "tool_calls")), null);
    }

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
        seedMessages(conv, count, "Turn");
    }

    private static void seedMessages(Conversation conv, int count, String label) {
        // Alternating user/assistant messages (starts with user) so the
        // prefix is always anchorable at a user boundary.
        for (int i = 0; i < count; i++) {
            var role = (i % 2 == 0) ? MessageRole.USER : MessageRole.ASSISTANT;
            ConversationService.appendMessage(conv, role, label + " " + i + " content", null, null, null);
        }
    }

    /** A summary as the code before JF-161 stored it: no dates for the rows it covers. */
    private static SessionCompaction saveSummary(Conversation conv, String summary, Instant compactedAt) {
        var sc = new SessionCompaction();
        sc.conversation = conv;
        sc.turnCount = 10;
        sc.summaryTokens = 50;
        sc.model = "openrouter/test-model";
        sc.summary = summary;
        sc.compactedAt = compactedAt;
        sc.save();
        return sc;
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().clear();
        JPA.em().getTransaction().begin();
    }
}
