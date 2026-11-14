import agents.CompactionGate;
import agents.ContextWindowManager;
import agents.PromptAnchor;
import agents.UsageMetricsBuilder;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ToolDef;
import llm.LlmTypes.Usage;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import models.Message;
import models.SessionCompaction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.ConfigService;
import services.ConversationService;
import services.SessionCompactor;
import utils.AppClock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * JF-420: the compaction trigger counts a request from the conversation's last provider-reported prompt and completion
 * plus the adjusted estimate of what changed since, and from the adjusted estimate of the whole request when there is
 * no such count, another model answered it, or a summary was stored meanwhile. The usage record's {@code compactAt} is
 * the same point, so the chat's meter fills toward where the gate fires.
 */
class PromptAnchorTest extends UnitTest {

    /** o200k_base is gpt-4o's own encoding, so its estimate is taken as it is. */
    private static final String MATCHED = "gpt-4o";
    /** Counted on the cl100k_base fallback, so its estimate is scaled by the safety multiplier, 1.5 here. */
    private static final String UNMATCHED = "jf420-model";
    private static final String OTHER = "jf420-other";
    private static final double MULTIPLIER = 1.5;
    private static final Instant T0 = Instant.parse("2026-11-01T10:00:00Z");
    private static final ModelInfo WINDOW = new ModelInfo(MATCHED, MATCHED, 200_000, 8192, false);
    private static final List<ToolDef> TOOLS = List.of(ToolDef.of("web_search", "Search the web",
            Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")))));

    private String provider;

    @BeforeEach
    void setUp() {
        CompactionTriggerSync.acquire();
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
        provider = "jf420-" + UUID.randomUUID().toString().substring(0, 8);
        ConfigService.set(multiplierKey(UNMATCHED), String.valueOf(MULTIPLIER));
        ConfigService.set(multiplierKey(OTHER), String.valueOf(MULTIPLIER));
    }

    @AfterEach
    void tearDown() {
        try {
            ConfigService.delete(multiplierKey(UNMATCHED));
            ConfigService.delete(multiplierKey(OTHER));
        } finally {
            CompactionTriggerSync.release();
        }
    }

    // ─── the anchored measure ───────────────────────────────────────────

    @Test
    void onAMatchedTokenizerTheMeasureIsTheLastPromptAndCompletionPlusTheRowsAddedSince() {
        var convo = conversation(MATCHED);
        var asked = history();
        // The provider counted a fifth fewer than the estimate: that error is the old prompt's, and is not counted again.
        int estimated = estimate(MATCHED, asked).promptTokens();
        int counted = estimated * 4 / 5;
        reply(convo, MATCHED, "Day one: Asakusa.", providerCall(counted, 5, estimate(MATCHED, asked)), T0);

        var next = plus(asked, ChatMessage.assistant("Day one: Asakusa."), ChatMessage.user("And day two?"));
        var measure = measure(convo, MATCHED, next);

        assertEquals(counted + 5 + rowTokens(MATCHED, ChatMessage.user("And day two?")), measure.estimatedTokens());
        assertEquals(estimate(MATCHED, next).promptTokens(), measure.windowTokens(),
                "the window rule keeps the whole estimate, the larger here");
        assertNotNull(measure.anchor());
    }

    @Test
    void onAnUnmatchedTokenizerOnlyTheRowsAddedSinceAreScaledByTheMultiplier() {
        var convo = conversation(UNMATCHED);
        var asked = history();
        int estimated = estimate(UNMATCHED, asked).promptTokens();
        reply(convo, UNMATCHED, "Day one: Asakusa.", providerCall(estimated, 40, estimate(UNMATCHED, asked)), T0);

        var next = plus(asked, ChatMessage.assistant("Day one: Asakusa."), ChatMessage.user("And day two?"));
        var measure = measure(convo, UNMATCHED, next);

        int added = rowTokens(UNMATCHED, ChatMessage.user("And day two?"));
        assertEquals(estimated + 40 + (int) Math.ceil(added * MULTIPLIER), measure.estimatedTokens());
        assertEquals(whole(UNMATCHED, next), measure.windowTokens(),
                "the whole prompt scaled by 1.5 is what the anchor saves: " + measure);
    }

    @Test
    void aRequestThatShrankWhatTheAnchoredOneCarriedMeasuresLowerByTheShrinkage() {
        var convo = conversation(MATCHED);
        var big = ChatMessage.toolResult("c1", "web_search", "Ledger entry for account 4471: invoice paid. ".repeat(200));
        var stub = ChatMessage.toolResult("c1", "web_search", "[tool result elided]");
        var asked = plus(history(), big);
        int estimated = estimate(MATCHED, asked).promptTokens();
        reply(convo, MATCHED, "Paid.", providerCall(estimated, 5, estimate(MATCHED, asked)), T0);

        var next = plus(history(), stub, ChatMessage.assistant("Paid."));
        var measure = measure(convo, MATCHED, next);

        assertEquals(estimated + 5 - rowTokens(MATCHED, big) + rowTokens(MATCHED, stub), measure.estimatedTokens());
    }

    @Test
    void rightAfterAReplyTheGateMeasuresWhatTheMeterShows() {
        var convo = conversation(UNMATCHED);
        var asked = history();
        reply(convo, UNMATCHED, "Day one: Asakusa.", providerCall(1_234, 56, estimate(UNMATCHED, asked)), T0);

        var measure = measure(convo, UNMATCHED, plus(asked, ChatMessage.assistant("Day one: Asakusa.")));

        assertEquals(1_234 + 56, measure.estimatedTokens(), "the meter's lastPrompt plus lastCompletion");
    }

    // ─── when the gate falls back to the whole estimate ─────────────────

    @Test
    void aConversationWithNoReplyCountedByTheProviderIsMeasuredWhole() {
        var convo = conversation(UNMATCHED);
        append(convo, ChatMessage.user("Plan day one."), T0);
        var unmeasured = ConversationService.appendAssistantMessage(convo, "Day one.", null);
        var next = plus(history(), ChatMessage.assistant("Day one."), ChatMessage.user("And day two?"));
        assertNull(unmeasured.usageJson);

        assertNull(PromptAnchor.find(convo, provider, UNMATCHED), "no reply carries usage");
        assertEquals(new CompactionGate.Measure(whole(UNMATCHED, next), fixed(UNMATCHED, next), whole(UNMATCHED, next),
                null), measure(convo, UNMATCHED, next));
    }

    @Test
    void aReplyTheLocalTokenizerCountedIsNoAnchor() {
        var convo = conversation(UNMATCHED);
        var estimated = LlmProvider.StreamAccumulator.builder().promptTokenEstimate(estimate(UNMATCHED, history()))
                .completionTokenEstimate(new TokenUsageEstimator.TokenCount(12, "cl100k_base", false)).build();
        reply(convo, UNMATCHED, "Day one.", turnOf(estimated), T0);

        assertEquals("jtokkit", usageOf(convo).get("usageSource").getAsString());
        assertNull(PromptAnchor.find(convo, provider, UNMATCHED));
    }

    @Test
    void aReplyRecordedWithoutItsCallsEstimateIsNoAnchor() {
        var convo = conversation(MATCHED);
        var unestimated = LlmProvider.StreamAccumulator.builder().usage(new Usage(900, 40, 940, 0, 0, 0)).build();
        reply(convo, MATCHED, "Day one.", turnOf(unestimated), T0);

        assertFalse(usageOf(convo).has(PromptAnchor.ESTIMATE_FIELD));
        assertNull(PromptAnchor.find(convo, provider, MATCHED));
    }

    @Test
    void aReplyFromAnotherModelOrProviderIsNoAnchor() {
        var convo = conversation(UNMATCHED);
        reply(convo, UNMATCHED, "Day one.", providerCall(900, 40, estimate(UNMATCHED, history())), T0);

        assertNotNull(PromptAnchor.find(convo, provider, UNMATCHED), "the reply's own model");
        assertNull(PromptAnchor.find(convo, provider, OTHER), "the conversation switched model since");
        assertNull(PromptAnchor.find(convo, provider + "-b", UNMATCHED), "or provider");
        var next = plus(history(), ChatMessage.assistant("Day one."));
        assertEquals(whole(OTHER, next), measure(convo, OTHER, next).estimatedTokens());
    }

    @Test
    void anAnchorCountedWithAnotherEncodingIsNotUsed() {
        var convo = conversation(MATCHED);
        reply(convo, MATCHED, "Day one.", providerCall(900, 40, estimate(UNMATCHED, history())), T0);
        var anchor = PromptAnchor.find(convo, provider, MATCHED);
        assertNotNull(anchor);
        var next = plus(history(), ChatMessage.assistant("Day one."));

        var measure = CompactionGate.measure(anchor, provider, MATCHED, new SessionCompactor.TurnRequest(next, TOOLS));

        assertFalse(anchor.measures(estimate(MATCHED, next)));
        assertNull(measure.anchor());
        assertEquals(whole(MATCHED, next), measure.estimatedTokens());
    }

    @Test
    void aSummaryStoredAfterTheReplyLeavesNoAnchor() {
        var convo = conversation(MATCHED);
        reply(convo, MATCHED, "Day one.", providerCall(800, 30, estimate(MATCHED, history())), T0.minusSeconds(20));
        reply(convo, MATCHED, "Day two.", providerCall(900, 40, estimate(MATCHED, history())), T0);
        summary(convo, T0.plusSeconds(5));

        assertNull(PromptAnchor.find(convo, provider, MATCHED));
    }

    @Test
    void aSummaryStoredWhileTheAnchoredTurnRanLeavesNoAnchor() {
        // A turn past the trigger is sent uncompacted beside its summary, which can be stored before the reply is.
        var convo = conversation(MATCHED);
        reply(convo, MATCHED, "Day one.", providerCall(800, 30, estimate(MATCHED, history())), T0);
        append(convo, ChatMessage.user("And day two?"), T0.plusSeconds(10));
        summary(convo, T0.plusSeconds(11));
        reply(convo, MATCHED, "Day two.", providerCall(900, 40, estimate(MATCHED, history())), T0.plusSeconds(12));

        assertNull(PromptAnchor.find(convo, provider, MATCHED));
    }

    @Test
    void aSummaryStoredBeforeThePreviousReplyLeavesTheAnchor() {
        var convo = conversation(MATCHED);
        summary(convo, T0.minusSeconds(1));
        reply(convo, MATCHED, "Day one.", providerCall(800, 30, estimate(MATCHED, history())), T0);
        append(convo, ChatMessage.user("And day two?"), T0.plusSeconds(10));
        reply(convo, MATCHED, "Day two.", providerCall(900, 40, estimate(MATCHED, history())), T0.plusSeconds(12));

        var anchor = PromptAnchor.find(convo, provider, MATCHED);

        assertNotNull(anchor);
        assertEquals(900, anchor.promptTokens());
        assertEquals(40, anchor.completionTokens());
    }

    @Test
    void aFirstReplyIsNoAnchorOnceAnySummaryWasStored() {
        var convo = conversation(MATCHED);
        summary(convo, T0.minusSeconds(60));
        reply(convo, MATCHED, "Day one.", providerCall(900, 40, estimate(MATCHED, history())), T0);

        assertNull(PromptAnchor.find(convo, provider, MATCHED), "no earlier reply bounds when that summary was stored");
    }

    @Test
    void aResetAfterTheReplyLeavesNoAnchorAndOneBeforeItLeavesIt() {
        var convo = conversation(MATCHED);
        reply(convo, MATCHED, "Day one.", providerCall(900, 40, estimate(MATCHED, history())), T0);

        convo.contextSince = T0.minusSeconds(1);
        convo.save();
        assertNotNull(PromptAnchor.find(convo, provider, MATCHED));

        convo.contextSince = T0.plusSeconds(1);
        convo.save();
        assertNull(PromptAnchor.find(convo, provider, MATCHED));
    }

    @Test
    void anInlineSubagentsReplyIsNotTheAnchorTheParentsOwnReplyIs() {
        var convo = conversation(MATCHED);
        reply(convo, MATCHED, "Parent reply.", providerCall(900, 40, estimate(MATCHED, history())), T0);
        var child = reply(convo, MATCHED, "Child reply.", providerCall(50, 5, estimate(MATCHED, history())),
                T0.plusSeconds(5));
        child.subagentRunId = 42L;
        child.save();

        var anchor = PromptAnchor.find(convo, provider, MATCHED);

        assertNotNull(anchor);
        assertEquals(900, anchor.promptTokens());
    }

    @Test
    void aBlankOrMalformedUsageRecordIsNoAnchor() {
        for (var usage : List.of("", "{not valid", "[1,2]")) {
            var convo = conversation(MATCHED);
            ConversationService.appendAssistantMessage(convo, "Day one.", null, usage, null);

            assertNull(PromptAnchor.find(convo, provider, MATCHED), "usage " + usage);
        }
    }

    // ─── the meter's point ──────────────────────────────────────────────

    @Test
    void theUsageRecordsCompactAtIsWhereTheGateFiresOnTheSameFixedPart() {
        var convo = conversation(UNMATCHED);
        var asked = history();
        var call = estimate(UNMATCHED, asked);
        reply(convo, UNMATCHED, "Day one.", providerCall(call.promptTokens() * 9 / 10, 40, call), T0);
        var measure = measure(convo, UNMATCHED, plus(asked, ChatMessage.assistant("Day one.")));
        assertNotNull(measure.anchor());

        int compactAt = usageOf(convo).get("compactAt").getAsInt();

        int fixed = (int) Math.ceil(call.fixedTokens() * MULTIPLIER);
        assertEquals(fixed, measure.fixedTokens(), "the gate's fixed part is the one the record took");
        // 200,000 window, half of what the fixed part leaves, beside the 150,000 cap.
        assertEquals(fixed + (200_000 - fixed) / 2, compactAt);
        assertTrue(SessionCompactor.shouldCompact(compactAt, measure.fixedTokens(), WINDOW));
        assertFalse(SessionCompactor.shouldCompact(compactAt - 1, measure.fixedTokens(), WINDOW));
    }

    @Test
    void aReplyTheProviderDidNotCountHasItsPointInTheMetersRawEstimate() {
        var convo = conversation(UNMATCHED);
        var call = estimate(UNMATCHED, history());
        var estimated = LlmProvider.StreamAccumulator.builder().promptTokenEstimate(call)
                .completionTokenEstimate(new TokenUsageEstimator.TokenCount(12, "cl100k_base", false)).build();
        reply(convo, UNMATCHED, "Day one.", turnOf(estimated), T0);

        int fixed = (int) Math.ceil(call.fixedTokens() * MULTIPLIER);
        // The gate counts the adjusted estimate, 1.5 times the raw one the meter shows.
        assertEquals((int) Math.round((fixed + (200_000 - fixed) / 2) / MULTIPLIER),
                usageOf(convo).get("compactAt").getAsInt());
    }

    @Test
    void theRecordedEstimateIsTheFinalCallsRawCount() {
        var convo = conversation(UNMATCHED);
        var call = estimate(UNMATCHED, history());
        reply(convo, UNMATCHED, "Day one.", providerCall(900, 40, call), T0);

        var recorded = usageOf(convo).getAsJsonObject(PromptAnchor.ESTIMATE_FIELD);

        assertEquals(call.promptTokens(), recorded.get("prompt").getAsInt());
        assertEquals(call.fixedTokens(), recorded.get("fixed").getAsInt());
        assertFalse(recorded.get("modelMatched").getAsBoolean());
    }

    @Test
    void aSizeWithNoAnchoredRequestOfItsOwnIsScaledByTheAnchorsRatio() {
        var anchor = new PromptAnchor(provider, UNMATCHED, 1_200, 40, 1_000, 3, false);

        // 1,000 estimated is 1,500 adjusted, which the provider counted as 1,200: 0.8 a token.
        assertEquals(400, anchor.toProviderUnits(500));
        assertEquals(400, new CompactionGate.Measure(0, 0, 0, anchor).toTriggerUnits(500));
        assertEquals(500, new CompactionGate.Measure(0, 0, 0, null).toTriggerUnits(500));
    }

    // ─── helpers ────────────────────────────────────────────────────────

    private String multiplierKey(String model) {
        return ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + provider + "." + model;
    }

    private Conversation conversation(String model) {
        var agent = new Agent();
        agent.name = "jf420-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = provider;
        agent.modelId = model;
        agent.enabled = true;
        agent.save();
        return ConversationService.create(agent, "web", "u-jf420");
    }

    /** The request a turn sent: a system prompt and two earlier rows, with {@link #TOOLS}. */
    private static List<ChatMessage> history() {
        return List.of(ChatMessage.system("You are a careful travel planner. Answer in plain English."),
                ChatMessage.user("We land in Tokyo on Monday."), ChatMessage.assistant("Noted: Monday arrival."),
                ChatMessage.user("Plan day one."));
    }

    private static List<ChatMessage> plus(List<ChatMessage> messages, ChatMessage... more) {
        var out = new ArrayList<>(messages);
        out.addAll(List.of(more));
        return out;
    }

    private static TokenUsageEstimator.ChatRequestTokens estimate(String model, List<ChatMessage> messages) {
        return TokenUsageEstimator.estimateChatRequest(model, messages, TOOLS);
    }

    private static int rowTokens(String model, ChatMessage message) {
        return TokenUsageEstimator.estimateMessage(model, message).tokens();
    }

    private int whole(String model, List<ChatMessage> messages) {
        return ContextWindowManager.adjustedPromptTokens(provider, model, estimate(model, messages));
    }

    private int fixed(String model, List<ChatMessage> messages) {
        var estimate = estimate(model, messages);
        return ContextWindowManager.adjustedMessageTokens(estimate.fixedTokens(), estimate.modelMatched(), provider, model);
    }

    private CompactionGate.Measure measure(Conversation convo, String model, List<ChatMessage> messages) {
        return CompactionGate.measure(PromptAnchor.find(convo, provider, model), provider, model,
                new SessionCompactor.TurnRequest(messages, TOOLS));
    }

    /** One final call the provider counted at {@code prompt} and {@code completion}, measured locally as {@code estimate}. */
    private static LlmProvider.TurnUsage providerCall(int prompt, int completion,
                                                      TokenUsageEstimator.ChatRequestTokens estimate) {
        return turnOf(LlmProvider.StreamAccumulator.builder()
                .usage(new Usage(prompt, completion, prompt + completion, 0, 0, 0))
                .promptTokenEstimate(estimate).build());
    }

    private static LlmProvider.TurnUsage turnOf(LlmProvider.StreamAccumulator call) {
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(call);
        return turn;
    }

    /** The reply row a turn on {@code model} stores at {@code at}, with the usage record the runner builds for it. */
    private Message reply(Conversation convo, String model, String content, LlmProvider.TurnUsage turn, Instant at) {
        var usage = UsageMetricsBuilder.buildUsageJson(turn, new ModelInfo(model, model, 200_000, 8192, false),
                System.currentTimeMillis(), convo.agent, convo);
        return AppClock.callWith(Clock.fixed(at, ZoneOffset.UTC),
                () -> ConversationService.appendAssistantMessage(convo, content, null, usage, null));
    }

    private static void append(Conversation convo, ChatMessage message, Instant at) {
        AppClock.runWith(Clock.fixed(at, ZoneOffset.UTC),
                () -> ConversationService.appendUserMessage(convo, (String) message.content()));
    }

    private static void summary(Conversation convo, Instant at) {
        AppClock.runWith(Clock.fixed(at, ZoneOffset.UTC), () -> {
            var row = new SessionCompaction();
            row.conversation = convo;
            row.turnCount = 10;
            row.summaryTokens = 100;
            row.model = "jf420/summary";
            row.summary = "Earlier: the trip starts Monday.";
            row.save();
        });
    }

    private static com.google.gson.JsonObject usageOf(Conversation convo) {
        Message newest = Message.find("conversation = ?1 AND usageJson IS NOT NULL ORDER BY createdAt DESC, id DESC",
                convo).first();
        assertNotNull(newest);
        return JsonParser.parseString(newest.usageJson).getAsJsonObject();
    }
}
