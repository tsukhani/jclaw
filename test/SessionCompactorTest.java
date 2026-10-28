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
            assertEquals(c.threshold(), SessionCompactor.triggerThreshold(mi, 50, 150_000, c.fixed()), () -> c.toString());
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
        saveSummary(conv, "FIRST SUMMARY", Instant.now().minusSeconds(600));
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
        assertEquals(ChatMessage.system("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\nFIRST SUMMARY"),
                request.getFirst());
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
    void compact_secondCompactionCarriesTheFirstSummary() {
        var conv = ConversationService.create(agent, "web", "user1");
        seedMessages(conv, 25, "Early turn");
        commitAndReopen();
        assertTrue(SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> "FIRST SUMMARY").compacted());

        seedMessages(Conversation.findById(conv.id), 25, "Later turn");
        commitAndReopen();
        var input = new AtomicReference<String>();
        var second = SessionCompactor.compact(conv.id, "openrouter/test-model", msgs -> {
            input.set((String) msgs.get(1).content());
            return "SECOND SUMMARY";
        });

        assertTrue(second.compacted(), "reason=" + second.skipReason());
        assertTrue(input.get().contains("FIRST SUMMARY"), "summarizer input: " + input.get());
        assertTrue(input.get().contains("Later turn"), "summarizer input: " + input.get());
        commitAndReopen();
        var prompt = SessionCompactor.appendSummaryToPrompt("SYS", Conversation.findById(conv.id));
        assertTrue(prompt.endsWith("SECOND SUMMARY"), "prompt: " + prompt);
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
        assertTranscript(rows.subList(0, 40), null, calls.get(0));
        assertTranscript(rows.subList(40, 80), "SUMMARY 1", calls.get(1));
        assertTranscript(rows.subList(80, 90), "SUMMARY 2", calls.get(2));
        assertEquals(90, result.turnsCompacted());
        commitAndReopen();
        var reloaded = Conversation.<Conversation>findById(conv.id);
        assertEquals(contents(rows.subList(90, 100)), ConversationService.loadRecentMessages(reloaded).stream()
                .map(m -> m.content).toList());
        assertEquals("SUMMARY 3", SessionCompaction.findLatest(reloaded).summary);
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

        var events = EventLogger.captureForTest(() -> assertEquals(10 * max, SessionCompactor.compact(conv.id,
                "openrouter/test-model", new TurnRequest(turnMessages(conv.id), TOOLS), call, false, null, 1_000)
                .turnsCompacted()));

        assertEquals(max, calls.size());
        for (int k = 0; k < max; k++) {
            assertTranscript(rows.subList(10 * k, 10 * k + 10), k == 0 ? null : "SUMMARY " + k, calls.get(k));
        }
        assertEquals(List.of("Compaction for conversation %d stopped after %d summary calls; the next turn continues it"
                .formatted(conv.id, max)), events.stream().filter(e -> "compaction".equals(e.category()))
                .map(EventLogger.Captured::message).toList());

        calls.clear();
        var resumed = SessionCompactor.compact(conv.id, "openrouter/test-model",
                new TurnRequest(turnMessages(conv.id), TOOLS), call, false, null, 1_000);

        assertEquals(10, resumed.turnsCompacted(), "the 20 rows left fit the limit: one call, keeping 10");
        assertEquals(1, calls.size());
        assertEquals(ChatMessage.system("SYS\n\n" + SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\nSUMMARY " + max),
                calls.getFirst().getFirst(), "the turn's own request, carrying the summary the stopped compaction left");
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
        assertTranscript(rows.subList(60, 90), null, inputs.getFirst());
        assertEquals(List.of(("Compaction for conversation %d found no user message to end a summary on among its "
                + "oldest rows; the 60 rows before its newest 40 are not summarized").formatted(conv.id)),
                events.stream().filter(e -> "compaction".equals(e.category())).map(EventLogger.Captured::message).toList());
        commitAndReopen();
        assertEquals(rows.get(90).createdAt(), Conversation.<Conversation>findById(conv.id).compactionSince);
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
    void appendSummaryToPrompt_picksMostRecentWhenMultiple() {
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

    /** {@code request} is the transcript shape: the instruction as the system prompt, then the summarized rows. */
    private static void assertTranscript(List<MessageSnapshot> summarized, List<ChatMessage> request) {
        assertTranscript(summarized, null, request);
    }

    /** As {@link #assertTranscript(List, List)}, with {@code prior} folded in ahead of the rows when not null. */
    private static void assertTranscript(List<MessageSnapshot> summarized, String prior, List<ChatMessage> request) {
        assertEquals(2, request.size());
        assertEquals("system", request.getFirst().role());
        assertEquals(SECTIONS, headings(request.getFirst().content()));
        var turns = SessionCompactor.renderTurns(summarized);
        assertEquals(ChatMessage.user(prior == null ? turns : SessionCompactor.PRIOR_SUMMARY_HEADER + "\n\n" + prior
                + "\n\n[Turns since that summary]\n\n" + turns), request.get(1));
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

    private static void saveSummary(Conversation conv, String summary, Instant compactedAt) {
        var sc = new SessionCompaction();
        sc.conversation = conv;
        sc.turnCount = 10;
        sc.summaryTokens = 50;
        sc.model = "openrouter/test-model";
        sc.summary = summary;
        sc.compactedAt = compactedAt;
        sc.save();
    }

    private static void commitAndReopen() {
        JPA.em().getTransaction().commit();
        JPA.em().clear();
        JPA.em().getTransaction().begin();
    }
}
