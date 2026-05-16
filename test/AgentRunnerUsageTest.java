import org.junit.jupiter.api.*;
import play.test.*;
import agents.UsageMetricsBuilder;
import com.google.gson.JsonParser;
import llm.LlmProvider;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.Usage;

/**
 * JCLAW-76 — verifies that {@code UsageMetricsBuilder.buildUsageJson} surfaces
 * token counts summed across every LLM round in a turn, not just the
 * first round's values. Exercises the pure helper directly against a
 * {@link LlmProvider.TurnUsage} built up via {@link LlmProvider.TurnUsage#addRound}
 * so no streaming harness is needed.
 */
class AgentRunnerUsageTest extends UnitTest {

    // --- TurnUsage folding ---

    @Test
    void turnUsageSumsTokensAcrossRounds() {
        var t = new LlmProvider.TurnUsage();
        t.addRound(roundWithUsage(new Usage(100, 10, 110, 5, 20, 0)));
        t.addRound(roundWithUsage(new Usage(200, 50, 250, 15, 30, 2)));
        t.addRound(roundWithUsage(new Usage(150, 800, 950, 0, 25, 0)));

        assertEquals(450, t.promptTokens);
        assertEquals(860, t.completionTokens);
        assertEquals(1310, t.totalTokens);
        assertEquals(20, t.reasoningTokens);
        assertEquals(75, t.cachedTokens);
        assertEquals(2, t.cacheCreationTokens);
        assertTrue(t.hasProviderUsage);
    }

    @Test
    void turnUsageIsEmptyWhenNoRoundsReportUsage() {
        var t = new LlmProvider.TurnUsage();
        t.addRound(roundWithUsage(null));                 // provider returned no usage
        t.addRound(roundWithUsage(null));

        assertFalse(t.hasProviderUsage);
        assertEquals(0, t.promptTokens);
        assertEquals(0, t.completionTokens);
    }

    @Test
    void turnUsageAccumulatesReasoningCharsAndDetectedFlagAcrossRounds() {
        var t = new LlmProvider.TurnUsage();
        var round1 = new LlmProvider.StreamAccumulator();
        round1.reasoningDetected = true;
        round1.appendReasoningText("First round reasoning text");    // 26 chars
        t.addRound(round1);

        var round2 = new LlmProvider.StreamAccumulator();
        round2.reasoningDetected = true;
        round2.appendReasoningText("second round thinking");         // 21 chars
        t.addRound(round2);

        assertTrue(t.reasoningDetected);
        assertEquals(47, t.reasoningChars);
    }

    // --- buildUsageJson output ---

    @Test
    void buildUsageJsonSumsAllDimensionsAcrossRounds() {
        // Simulates a two-round tool-using turn on Claude 3.7 Sonnet where
        // round 1 was the brief "call the tool" thinking and round 2 was
        // the 800-token synthesis. Pre-JCLAW-76 the emitted JSON reported
        // only round 1's numbers (10 prompt, 5 reasoning, 10 completion).
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(new Usage(100, 10, 110, 5, 0, 0)));
        turn.addRound(roundWithUsage(new Usage(200, 800, 1000, 15, 0, 0)));

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), null, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(300, obj.get("prompt").getAsInt());
        assertEquals(810, obj.get("completion").getAsInt());
        assertEquals(1110, obj.get("total").getAsInt());
        assertEquals(20, obj.get("reasoning").getAsInt());
    }

    @Test
    void buildUsageJsonSingleRoundIsUnchangedFromPreFixBehaviour() {
        // AC5 regression guard: a turn with zero tool rounds must produce
        // byte-equivalent numbers to pre-fix behaviour. Everything lines up
        // with round 1's numbers because there are no later rounds to fold.
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(new Usage(500, 300, 800, 50, 100, 0)));

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), null, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(500, obj.get("prompt").getAsInt());
        assertEquals(300, obj.get("completion").getAsInt());
        assertEquals(800, obj.get("total").getAsInt());
        assertEquals(50, obj.get("reasoning").getAsInt());
        assertEquals(100, obj.get("cached").getAsInt());
    }

    @Test
    void buildUsageJsonOmitsTokenFieldsWhenNoProviderUsage() {
        // When no round returned Usage (e.g. cancelled pre-first-chunk), the
        // emitted JSON drops to a compact durationMs-only shape so the
        // frontend stats pills stay in a valid "no usage info" state instead
        // of misleading zero counts.
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(null));

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), null, null);
        assertFalse(json.contains("\"prompt\""), "no prompt field when zero usage: " + json);
        assertFalse(json.contains("\"completion\""), "no completion field when zero usage: " + json);
        assertTrue(json.contains("\"durationMs\""), "durationMs always present: " + json);
    }

    @Test
    void buildUsageJsonFallsBackToReasoningCharsWhenReasoningTokensZero() {
        // Several providers (Ollama Cloud on glm-5.1, some OpenRouter routes)
        // stream reasoning text but omit reasoning_tokens in the usage block.
        // The helper falls back to a char-count estimate (~4 chars per token)
        // so the reasoning badge stays truthy. This case also exercises the
        // cumulative reasoningChars across rounds.
        var turn = new LlmProvider.TurnUsage();

        var round1 = new LlmProvider.StreamAccumulator();
        round1.usage = new Usage(100, 10, 110, 0, 0, 0);  // reasoning_tokens = 0
        round1.reasoningDetected = true;
        round1.appendReasoningText("A".repeat(200));
        turn.addRound(round1);

        var round2 = new LlmProvider.StreamAccumulator();
        round2.usage = new Usage(150, 400, 550, 0, 0, 0);  // reasoning_tokens = 0
        round2.reasoningDetected = true;
        round2.appendReasoningText("B".repeat(100));
        turn.addRound(round2);

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), null, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        // 300 chars / 4 chars-per-token, rounded up = 75
        assertEquals(75, obj.get("reasoning").getAsInt());
    }

    @Test
    void reasoningDurationMatchesFrontendLiveSemantics_singleRound() {
        // The persisted "Thought for X seconds" must equal what the user saw
        // streaming live — first reasoning chunk → first content chunk of the
        // turn. For a single-round turn that's just the round's own
        // appendReasoningText → noteFirstContentChunk span.
        var round = new LlmProvider.StreamAccumulator();
        round.usage = new Usage(100, 10, 110, 5, 0, 0);
        round.appendReasoningText("thinking");
        try { Thread.sleep(2); } catch (InterruptedException _) {}
        round.noteFirstContentChunk();

        var turn = new LlmProvider.TurnUsage();
        turn.addRound(round);

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), null, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertTrue(obj.has("reasoningDurationMs"), "duration field present for single-round turn: " + json);
        var persisted = obj.get("reasoningDurationMs").getAsLong();
        assertTrue(persisted >= 1L, "reasoning span must be >= 1ms, got " + persisted);
    }

    @Test
    void reasoningDurationSpansToolGapBetweenRounds() {
        // The actual bug: tool-using turns showed 1.23s persisted vs 9.60s
        // live because the persisted value was anchored to round 1's
        // reasoning span only, missing the tool-execution gap and any
        // subsequent rounds. Now the turn-level measurement runs from the
        // first reasoning chunk of round 1 to the first content chunk of
        // whatever round eventually emits content.
        var round1 = new LlmProvider.StreamAccumulator();
        round1.usage = new Usage(100, 0, 100, 5, 0, 0);
        round1.appendReasoningText("planning the search");
        // round 1 emits no content (only tool calls fire in real flows)

        var turn = new LlmProvider.TurnUsage();
        turn.addRound(round1);
        var round1ReasoningEnd = round1.reasoningEndNanos;

        // Simulate tool execution gap — far longer than the in-round
        // reasoning span, so round-1-anchored timing would massively
        // underreport the user-perceived wait.
        try { Thread.sleep(20); } catch (InterruptedException _) {}

        var round2 = new LlmProvider.StreamAccumulator();
        round2.usage = new Usage(150, 50, 200, 0, 0, 0);
        round2.noteFirstContentChunk();   // round 2 streams content directly
        turn.addRound(round2);

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), null, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertTrue(obj.has("reasoningDurationMs"), "duration field present: " + json);
        var persisted = obj.get("reasoningDurationMs").getAsLong();
        var roundLocal = (round1ReasoningEnd - round1.reasoningStartNanos) / 1_000_000L;
        assertTrue(persisted >= 20L,
                "turn-level duration must include the inter-round gap (>= 20ms), got " + persisted);
        assertTrue(persisted > roundLocal + 10L,
                "turn-level duration (" + persisted + "ms) must exceed round-1-only duration ("
                        + roundLocal + "ms) by at least the gap");
    }

    @Test
    void reasoningDurationFallsBackToTurnEndWhenNoContentEverStreams() {
        // Reasoning-only response (some providers, or tool-only turns where
        // no content ever streams). Per-frontend convention the timer stops
        // at stream completion; backend equivalent uses the turnEndNanos
        // passed by buildUsageJson at end-of-turn.
        var round = new LlmProvider.StreamAccumulator();
        round.usage = new Usage(50, 0, 50, 5, 0, 0);
        round.appendReasoningText("thought but never emitted content");

        var turn = new LlmProvider.TurnUsage();
        turn.addRound(round);

        try { Thread.sleep(5); } catch (InterruptedException _) {}
        var duration = turn.reasoningDurationMs(System.nanoTime());

        assertTrue(duration >= 5L,
                "reasoning-only turn must use turnEndNanos as fallback; got " + duration);
    }

    @Test
    void noteFirstContentChunkIsIdempotentSoEmptyContentChunksDontCollapseDuration() {
        // Regression for the v0.10.30 bug: OpenAI-compatible providers emit
        // chunks where the `content` field is always present in the schema
        // and defaults to "" when the chunk only carries reasoning. The
        // streaming callback's outer guard now skips empty-content chunks,
        // but verify here that even if noteFirstContentChunk DID fire on
        // the same instant as appendReasoningText, the idempotency check
        // protects subsequent (real) content chunks from re-stamping
        // firstContentNanos. This ensures the actual "first non-empty
        // content" instant wins.
        var acc = new LlmProvider.StreamAccumulator();
        // Simulate the racy pattern: reasoning chunk and a hypothetical
        // empty-content stamp landing in the same nanosecond.
        acc.appendReasoningText("reasoning");
        acc.noteFirstContentChunk();   // first call records this instant
        var earlyContentNanos = acc.firstContentNanos;

        try { Thread.sleep(5); } catch (InterruptedException _) {}
        acc.noteFirstContentChunk();   // second call MUST be idempotent

        assertEquals(earlyContentNanos, acc.firstContentNanos,
                "noteFirstContentChunk must record only the first call's instant");
    }

    @Test
    void roundLocalReasoningDurationStillUsesLastReasoningChunkForMultiChunk() {
        // Defensive: the per-round reasoningDurationMs (kept for diagnostic
        // use) anchors to the last appendReasoningText call. noteFirstContentChunk
        // must not push reasoningEndNanos forward when reasoning was already
        // multi-chunk (would over-report by the gap to first content).
        var acc = new LlmProvider.StreamAccumulator();
        acc.appendReasoningText("first");
        try { Thread.sleep(2); } catch (InterruptedException _) {}
        acc.appendReasoningText("second");
        var durationBeforeBookend = acc.reasoningDurationMs();
        assertTrue(durationBeforeBookend >= 1L, "setup sanity: multi-chunk reasoning >= 1ms");

        try { Thread.sleep(5); } catch (InterruptedException _) {}
        acc.noteFirstContentChunk();

        assertEquals(durationBeforeBookend, acc.reasoningDurationMs(),
                "bookend must not extend reasoningEndNanos when reasoning was multi-chunk");
    }

    @Test
    void buildUsageJsonIncludesModelPricingWhenProvided() {
        // ModelInfo carries per-token pricing that the frontend multiplies
        // against token counts to render the $ badge. Pricing fields ride
        // into the same usage JSON object so the frontend gets everything
        // it needs in one payload.
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(new Usage(1000, 500, 1500, 0, 0, 0)));

        var model = new ModelInfo("test-model", "Test", 128000, 4096, false,
                3.0, 15.0, 0.30, 3.75);

        var json = UsageMetricsBuilder.buildUsageJson(turn, model, System.currentTimeMillis(), null, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals(3.0, obj.get("promptPrice").getAsDouble(), 0.001);
        assertEquals(15.0, obj.get("completionPrice").getAsDouble(), 0.001);
    }

    @Test
    void buildUsageJsonPersistsModelIdentityAndContextWindow() {
        // JCLAW-107: each emitted message carries the agent's modelProvider +
        // modelId plus the model's contextWindow so JCLAW-108's per-conversation
        // cost aggregator can attribute every turn's cost to the model that
        // actually ran it — without re-resolving provider config at read time.
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(new Usage(1000, 500, 1500, 0, 0, 0)));
        var model = new ModelInfo("flash-preview", "Google Flash Preview", 128000, 8192, false,
                0.30, 2.50, 0.08, 0.38);

        var agent = new models.Agent();
        agent.modelProvider = "openrouter";
        agent.modelId = "flash-preview";

        var json = UsageMetricsBuilder.buildUsageJson(turn, model, System.currentTimeMillis(), agent, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("openrouter", obj.get("modelProvider").getAsString());
        assertEquals("flash-preview", obj.get("modelId").getAsString());
        assertEquals(128000, obj.get("contextWindow").getAsInt());
        // Pricing fields remain alongside the new identity fields.
        assertEquals(0.30, obj.get("promptPrice").getAsDouble(), 0.001);
    }

    @Test
    void buildUsageJsonWritesResolvedOverrideValuesWhenConversationOverridesSet() {
        // JCLAW-108: when the conversation has a model override, the emitted
        // usageJson must attribute the turn to the OVERRIDE's modelProvider +
        // modelId, not the agent's underlying fields. This is what makes
        // per-turn cost attribution correct across mid-conversation switches.
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(new Usage(1000, 500, 1500, 0, 0, 0)));
        var model = new ModelInfo("override-model", "Override", 100000, 4096, false,
                1.0, 2.0, 0.1, 0.2);

        var agent = new models.Agent();
        agent.modelProvider = "agent-provider";
        agent.modelId = "agent-model";

        var conversation = new models.Conversation();
        conversation.modelProviderOverride = "override-provider";
        conversation.modelIdOverride = "override-model";

        var json = UsageMetricsBuilder.buildUsageJson(turn, model, System.currentTimeMillis(), agent, conversation);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("override-provider", obj.get("modelProvider").getAsString(),
                "override provider wins over agent's: " + json);
        assertEquals("override-model", obj.get("modelId").getAsString(),
                "override model wins over agent's: " + json);
    }

    @Test
    void buildUsageJsonFallsBackToAgentWhenOverrideIsHalfSet() {
        // Defensive: if only one of the two override columns is non-null,
        // treat it as no-override (writing a half-override is undefined per
        // ConversationService.setModelOverride). The resolved identity comes
        // from the agent.
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(new Usage(100, 50, 150, 0, 0, 0)));

        var agent = new models.Agent();
        agent.modelProvider = "agent-provider";
        agent.modelId = "agent-model";

        var conversation = new models.Conversation();
        conversation.modelProviderOverride = "only-provider-set";
        // modelIdOverride intentionally null

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), agent, conversation);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertEquals("agent-provider", obj.get("modelProvider").getAsString());
        assertEquals("agent-model", obj.get("modelId").getAsString());
    }

    @Test
    void buildUsageJsonOmitsIdentityFieldsWhenAgentIsNull() {
        // Defensive: AgentRunner passes a non-null agent in practice, but the
        // helper must tolerate a null agent (e.g. pure-logic tests) without
        // NPE. Missing modelProvider/modelId in the output is a clearer
        // signal than a synthesized default.
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(roundWithUsage(new Usage(100, 50, 150, 0, 0, 0)));

        var json = UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), null, null);
        var obj = JsonParser.parseString(json).getAsJsonObject();

        assertFalse(obj.has("modelProvider"), "no modelProvider field when agent is null: " + json);
        assertFalse(obj.has("modelId"), "no modelId field when agent is null: " + json);
    }

    // --- Helpers ---

    private static LlmProvider.StreamAccumulator roundWithUsage(Usage u) {
        var acc = new LlmProvider.StreamAccumulator();
        acc.usage = u;
        return acc;
    }
}
