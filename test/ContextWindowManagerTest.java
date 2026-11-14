import agents.CompactionGate;
import agents.ContextWindowManager;
import agents.PromptAnchor;
import agents.UsageMetricsBuilder;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionDef;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolDef;
import llm.LlmTypes.Usage;
import llm.OpenAiProvider;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.CompactionProgress;
import services.ConfigService;
import services.ConversationService;
import services.EventLogger;
import services.SessionCompactor;
import tools.ConversationSearchTool;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Branch coverage for {@link ContextWindowManager}'s context-window
 * arithmetic and tool-result truncation decisions (JCLAW-707) — the
 * clamp/floor/cap branches of {@link ContextWindowManager#effectiveMaxTokens},
 * the no-op / drop / truncate branches of {@code trimToContextWindow}, the
 * tool-schema token estimator, and the safety-multiplier fall-through arms not
 * reached by {@code ContextWindowSafetyMultiplierTest} (which always passes
 * both provider and model non-null and never exercises a blank config value).
 *
 * <p>The provider-facing methods are package-private and depend on
 * {@code ModelResolver}, so they are driven through reflection with an inline
 * {@link OpenAiProvider} — the same pattern {@code StreamingToolRoundTest}
 * uses. Assertions are on observable outcomes (returned budget, list identity /
 * size, in-place elision marker), not on exact tokenizer counts, which vary by
 * encoding.
 */
class ContextWindowManagerTest extends UnitTest {

    private static final String PROV = "cwm-prov";
    private static final String MODEL = "cwm-model";

    @AfterEach
    void cleanup() {
        ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROV + "." + MODEL);
        ConfigService.delete(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROV);
    }

    // ─── reflection helpers ──────────────────────────────────────────────────

    private static int estimateToolTokens(List<ToolDef> tools) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("estimateToolTokens", List.class);
        m.setAccessible(true);
        return (int) m.invoke(null, tools);
    }

    private static Integer effectiveMaxTokens(Agent agent, LlmProvider provider,
                                              List<ChatMessage> messages) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("effectiveMaxTokens",
                Agent.class, Conversation.class, LlmProvider.class, List.class, List.class);
        m.setAccessible(true);
        return (Integer) m.invoke(null, agent, null, provider, messages, List.of());
    }

    private static List<ChatMessage> trim(List<ChatMessage> messages, Agent agent,
                                          LlmProvider provider) throws Exception {
        return trim(messages, agent, null, provider);
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> trim(List<ChatMessage> messages, Agent agent, Conversation conv,
                                          LlmProvider provider) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("trimToContextWindow",
                List.class, Agent.class, Conversation.class, LlmProvider.class);
        m.setAccessible(true);
        return (List<ChatMessage>) m.invoke(null, messages, agent, conv, provider);
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> trim(List<ChatMessage> messages, Agent agent, LlmProvider provider,
                                          List<ToolDef> tools) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("trimToContextWindow",
                List.class, Agent.class, Conversation.class, LlmProvider.class, List.class);
        m.setAccessible(true);
        return (List<ChatMessage>) m.invoke(null, messages, agent, null, provider, tools);
    }

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> trimToBudget(List<ChatMessage> messages, LlmProvider provider,
                                                  List<ToolDef> tools, int budget) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("trimToBudget",
                List.class, Agent.class, Conversation.class, LlmProvider.class, List.class, int.class);
        m.setAccessible(true);
        return (List<ChatMessage>) m.invoke(null, messages, agentUsing("model-1"), null, provider, tools, budget);
    }

    private static String trimNotice(int dropped, boolean searchable) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("trimNotice", int.class, boolean.class);
        m.setAccessible(true);
        return (String) m.invoke(null, dropped, searchable);
    }

    private static ChatMessage withTrimNotice(ChatMessage system, int dropped, List<ToolDef> tools) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("withTrimNotice", ChatMessage.class, int.class, List.class);
        m.setAccessible(true);
        return (ChatMessage) m.invoke(null, system, dropped, tools);
    }

    /** What the trim logs and sends a bound sink while {@code body} runs. */
    private record Reported(List<EventLogger.Captured> logged, List<String> frames) {}

    private interface TrimCall {
        void run() throws Exception;
    }

    private static Reported reported(TrimCall body) {
        var frames = new ArrayList<String>();
        var logged = EventLogger.captureForTest(() -> ScopedValue.where(CompactionProgress.SINK,
                (Consumer<String>) frames::add).run(() -> {
                    try {
                        body.run();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }));
        return new Reported(logged, frames);
    }

    /** Forty long turns over a 4,000-token window, whose trim target is 2,000: the drop-oldest branch must cut. */
    private static List<ChatMessage> longHistory() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("System prompt."));
        for (int i = 0; i < 20; i++) {
            messages.add(ChatMessage.user("Question " + i + ": " + "what did the ledger show for the harbour account? ".repeat(12)));
            messages.add(ChatMessage.assistant("Answer " + i + ": " + "the ledger balanced, with two late payments. ".repeat(12)));
        }
        messages.add(ChatMessage.user("And today?"));
        return messages;
    }

    private static final List<ToolDef> SEARCH_TOOL = List.of(ToolDef.of(ConversationSearchTool.TOOL_NAME,
            "Search conversation history by keyword.", Map.of("type", "object")));

    private static int intConst(String name) throws Exception {
        var f = ContextWindowManager.class.getDeclaredField(name);
        f.setAccessible(true);
        return f.getInt(null);
    }

    private static LlmProvider providerWith(int contextWindow, int maxTokens) {
        return new OpenAiProvider(new ProviderConfig("test", "http://test", "key",
                List.of(new ModelInfo("model-1", "Model 1", contextWindow, maxTokens, false))));
    }

    private static Agent agentUsing(String modelId) {
        var a = new Agent();
        a.name = "cwm-test";
        a.modelId = modelId;
        return a;
    }

    // ─── safety multiplier fall-through arms ─────────────────────────────────

    @Test
    void adjustedPromptTokensOneArgReturnsRawWhenMatched() {
        var matched = new TokenUsageEstimator.ChatRequestTokens(100, 0, 100, "o200k_base", true);
        // 1-arg overload delegates to (null, null, estimate); the matched flag
        // short-circuits the multiplier so the raw count survives untouched —
        // deterministic regardless of any process-global config.
        assertEquals(100, ContextWindowManager.adjustedPromptTokens(matched));
    }

    @Test
    void adjustedMessageTokensAppliesMultiplierWhenUnmatched() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROV + "." + MODEL, "1.5");
        // Unmatched → per-message estimate scaled by the resolved multiplier
        // (ceil). Existing suite only covers the matched=true short-circuit.
        assertEquals(150, ContextWindowManager.adjustedMessageTokens(100, false, PROV, MODEL));
    }

    @Test
    void resolveSafetyMultiplierUsesPerProviderWhenModelNull() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROV, "1.8");
        // modelId null → the per-(provider,model) tier is skipped; the
        // per-provider tier resolves. Proves the `provider != null && model !=
        // null` false-arm falls into the `provider != null` true-arm.
        assertEquals(1.8, ContextWindowManager.resolveSafetyMultiplier(PROV, null), 1e-9);
    }

    @Test
    void resolveSafetyMultiplierIgnoresBlankValueAndFallsThrough() {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROV + "." + MODEL, "   ");
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROV, "1.3");
        // Blank specific value → parseMultiplier returns null → the lookup
        // falls through to the per-provider tier rather than treating "   " as 0.
        assertEquals(1.3, ContextWindowManager.resolveSafetyMultiplier(PROV, MODEL), 1e-9);
    }

    // ─── estimateToolTokens ──────────────────────────────────────────────────

    @Test
    void estimateToolTokensNullAndEmptyReturnZero() throws Exception {
        assertEquals(0, estimateToolTokens(null), "null tools → 0");
        assertEquals(0, estimateToolTokens(List.of()), "empty tools → 0");
    }

    @Test
    void estimateToolTokensSkipsNullFunction() throws Exception {
        var toolNoFn = new ToolDef("function", null);
        assertEquals(0, estimateToolTokens(List.of(toolNoFn)),
                "a ToolDef with no FunctionDef contributes nothing");
    }

    @Test
    void estimateToolTokensSumsFieldsAndSkipsNulls() throws Exception {
        // name(4) + description(4) + params "{k=v}"(5) = 13 chars; 13/4 == 3.
        var full = ToolDef.of("abcd", "efgh", Map.of("k", "v"));
        assertEquals(3, estimateToolTokens(List.of(full)));
        // name(8) only — null description and null parameters are skipped.
        var nameOnly = new ToolDef("function", new FunctionDef("aaaaaaaa", null, null));
        assertEquals(2, estimateToolTokens(List.of(nameOnly)));
    }

    // ─── effectiveMaxTokens ──────────────────────────────────────────────────

    private static final List<ChatMessage> SMALL_PROMPT = List.of(
            ChatMessage.system("You are helpful."),
            ChatMessage.user("Hi"));

    @Test
    void effectiveMaxTokensNullWhenModelNotConfigured() throws Exception {
        // agent points at a model the provider doesn't list → no ModelInfo.
        var r = effectiveMaxTokens(agentUsing("ghost-model"), providerWith(8000, 1000), SMALL_PROMPT);
        assertNull(r, "unresolvable model → no max_tokens (provider default)");
    }

    @Test
    void effectiveMaxTokensNullWhenMaxTokensNonPositive() throws Exception {
        var r = effectiveMaxTokens(agentUsing("model-1"), providerWith(8000, 0), SMALL_PROMPT);
        assertNull(r, "maxTokens<=0 → no configured cap → null");
    }

    @Test
    void effectiveMaxTokensReturnsConfiguredWhenNoContextWindow() throws Exception {
        var r = effectiveMaxTokens(agentUsing("model-1"), providerWith(0, 777), SMALL_PROMPT);
        assertEquals(Integer.valueOf(777), r,
                "contextWindow<=0 → skip context-fit, return the configured cap");
    }

    @Test
    void effectiveMaxTokensFloorsAtMinWhenPromptFillsWindow() throws Exception {
        int min = intConst("MIN_OUTPUT_TOKENS");
        // window 100 is smaller than the safety margin alone → headroom goes
        // negative → clamped up to the floor rather than shipping a useless cap.
        var r = effectiveMaxTokens(agentUsing("model-1"), providerWith(100, 5000), SMALL_PROMPT);
        assertEquals(Integer.valueOf(min), r);
    }

    @Test
    void effectiveMaxTokensCappedByConfiguredMax() throws Exception {
        // Huge window, small configured cap, tiny prompt → the configured cap
        // wins (min(configured, headroom) == configured).
        var r = effectiveMaxTokens(agentUsing("model-1"), providerWith(100_000, 1000), SMALL_PROMPT);
        assertEquals(Integer.valueOf(1000), r);
    }

    @Test
    void effectiveMaxTokensBoundedByContextFit() throws Exception {
        int min = intConst("MIN_OUTPUT_TOKENS");
        // Small window, huge configured cap → the context-fit bound wins, so the
        // returned budget sits well below the configured cap yet above the floor.
        var r = effectiveMaxTokens(agentUsing("model-1"), providerWith(2000, 100_000), SMALL_PROMPT);
        assertNotNull(r);
        assertTrue(r < 100_000, "context-fit must clamp below the configured cap: " + r);
        assertTrue(r >= min && r <= 2000, "budget bounded by the window: " + r);
    }

    // ─── trimToContextWindow ─────────────────────────────────────────────────

    @Test
    void trimReturnsSameListWhenModelInfoMissing() throws Exception {
        var messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
        var out = trim(messages, agentUsing("ghost-model"), providerWith(4000, 1000));
        assertSame(messages, out, "no resolvable model → input list returned unchanged");
    }

    @Test
    void trimReturnsSameListWhenContextWindowNonPositive() throws Exception {
        var messages = List.of(ChatMessage.system("s"), ChatMessage.user("u"));
        var out = trim(messages, agentUsing("model-1"), providerWith(0, 1000));
        assertSame(messages, out, "contextWindow<=0 → no trimming");
    }

    @Test
    void trimTruncatesOversizedToolResultInPlace() throws Exception {
        // A single huge tool result dominates the prompt; four trailing messages
        // keep it outside the preserve-recent window so it is a truncation
        // candidate. Head/tail truncation alone should bring the prompt under
        // target — Stage 1 wins, no whole turns are dropped.
        // Varied text so the tokenizer counts it densely (a single repeated
        // char can BPE-compress far below the trim target and skip Stage 1).
        var big = "The quick brown fox jumps over the lazy dog. ".repeat(450); // ~20k chars
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("System prompt."));
        messages.add(ChatMessage.toolResult("call-1", "grep", big));
        messages.add(ChatMessage.user("follow up one"));
        messages.add(ChatMessage.user("follow up two"));
        messages.add(ChatMessage.user("follow up three"));
        messages.add(ChatMessage.user("follow up four"));

        var out = trim(messages, agentUsing("model-1"), providerWith(4000, 8000));

        assertEquals(messages.size(), out.size(), "truncation must not drop turns");
        assertEquals("system", out.getFirst().role(), "system prompt preserved");
        var trimmedTool = (String) out.get(1).content();
        assertTrue(trimmedTool.length() < big.length(), "oversized tool result must shrink");
        assertTrue(trimmedTool.contains("JClaw elided"), "elision marker must be present");
    }

    // ─── what the trim tells the agent and the operator (JF-419) ────────────

    @Test
    void theNoticeCountsOneMessageOrSeveralAndNamesTheSearchOnlyWhenOffered() throws Exception {
        assertEquals("[1 earlier message of this conversation is left out of this request to fit the model's context "
                + "window. It is still stored.]", trimNotice(1, false));
        assertEquals("[2 earlier messages of this conversation are left out of this request to fit the model's context "
                + "window. They are still stored, and conversation_search with includeCurrent set to true can find "
                + "them.]", trimNotice(2, true));
        assertEquals("[1 earlier message of this conversation is left out of this request to fit the model's context "
                + "window. It is still stored, and conversation_search with includeCurrent set to true can find it.]",
                trimNotice(1, true));
    }

    @Test
    void aDropThatLeavesMessagesOutTellsTheAgentHowManyInTheSystemMessage() throws Exception {
        var messages = longHistory();
        var out = new ArrayList<ChatMessage>();

        var reported = reported(() -> out.addAll(trim(messages, agentUsing("model-1"), providerWith(4000, 8000), List.of())));

        int dropped = messages.size() - out.size();
        assertTrue(dropped > 0, "the history must not fit the 2,000-token target");
        assertEquals("System prompt.\n\n" + trimNotice(dropped, false), out.getFirst().content());
        assertEquals("system", out.getFirst().role());
        assertEquals(messages.subList(1 + dropped, messages.size()), out.subList(1, out.size()),
                "every later message keeps its place");
        assertEquals(List.of("{\"contextTrim\":{\"dropped\":" + dropped + "}}"), reported.frames());
        var trimLines = reported.logged().stream().filter(e -> e.message().startsWith("Left the ")).toList();
        assertEquals(1, trimLines.size(), () -> "one trim line: " + reported.logged());
        var line = trimLines.getFirst();
        assertEquals("WARN", line.level());
        assertEquals("llm", line.category());
        assertTrue(line.message().matches("Left the " + dropped + " oldest messages out of the request to fit the "
                        + "model's context window of 4000 tokens: it came to about \\d+ tokens, over the 2000 it may "
                        + "use after 2000 kept for the reply and 0 for recalled memories and skills, and to about \\d+ "
                        + "without them\\. They are still stored\\."),
                () -> "the operator's line: " + line.message());
    }

    @Test
    void aDropTellsAnAgentOfferedConversationSearchHowToFindWhatWentOut() throws Exception {
        var messages = longHistory();

        var out = trim(messages, agentUsing("model-1"), providerWith(4000, 8000), SEARCH_TOOL);

        int dropped = messages.size() - out.size();
        assertTrue(dropped > 0, "the history must not fit the 2,000-token target");
        assertEquals("System prompt.\n\n" + trimNotice(dropped, true), out.getFirst().content());
    }

    @Test
    void aRequestThatFitsCarriesNoNoticeAndSendsNoFrame() throws Exception {
        var messages = List.of(ChatMessage.system("System prompt."), ChatMessage.user("Hi"));
        var out = new ArrayList<List<ChatMessage>>();

        var reported = reported(() -> out.add(trim(messages, agentUsing("model-1"), providerWith(100_000, 8000), SEARCH_TOOL)));

        assertSame(messages, out.getFirst());
        assertEquals(List.of(), reported.frames());
        assertEquals(List.of(), reported.logged().stream().filter(e -> e.message().startsWith("Left ")).toList());
    }

    @Test
    void aTrimThatOnlyTruncatesAToolResultCarriesNoNotice() throws Exception {
        var big = "The quick brown fox jumps over the lazy dog. ".repeat(450);
        var messages = List.of(ChatMessage.system("System prompt."), ChatMessage.toolResult("call-1", "grep", big),
                ChatMessage.user("follow up one"), ChatMessage.user("follow up two"),
                ChatMessage.user("follow up three"), ChatMessage.user("follow up four"));
        var out = new ArrayList<ChatMessage>();

        var reported = reported(() -> out.addAll(trim(messages, agentUsing("model-1"), providerWith(4000, 8000), List.of())));

        assertEquals(messages.size(), out.size(), "truncation alone must fit the target");
        assertEquals("System prompt.", out.getFirst().content());
        assertEquals(List.of(), reported.frames());
    }

    @Test
    void aResendTrimAddsItsDropsToTheNoticeThePrologueLeft() throws Exception {
        var prologue = withTrimNotice(ChatMessage.system("System prompt."), 3, List.of());
        var messages = List.of(prologue, ChatMessage.user("Old question."), ChatMessage.assistant("Old answer."),
                ChatMessage.user("Current question?"));
        var out = new ArrayList<ChatMessage>();

        var reported = reported(() -> out.addAll(trimToBudget(messages, providerWith(100_000, 8000), SEARCH_TOOL, 1)));

        assertEquals(List.of(ChatMessage.system("System prompt.\n\n" + trimNotice(5, true)), messages.get(3)), out);
        assertEquals(List.of("{\"contextTrim\":{\"dropped\":5}}"), reported.frames(),
                "the frame counts every message the request leaves out");
        var line = reported.logged().stream().filter(e -> e.message().startsWith("Left ")).toList();
        assertEquals(1, line.size(), () -> "one resend line: " + reported.logged());
        assertTrue(line.getFirst().message().matches("Left 2 earlier messages out of the resend of a request the "
                        + "provider refused as longer than its context window: it came to about \\d+ tokens, over the 1 "
                        + "the resend aims at, and to about \\d+ without them\\. They are still stored\\."),
                () -> "the operator's line: " + line.getFirst().message());
    }

    @Test
    void aResendTrimOfAnUntrimmedRequestStartsTheCountAtWhatItDrops() throws Exception {
        var messages = List.of(ChatMessage.system("System prompt."), ChatMessage.assistant("Old answer."),
                ChatMessage.user("Current question?"));

        var out = trimToBudget(messages, providerWith(100_000, 8000), List.of(), 1);

        assertEquals(List.of(ChatMessage.system("System prompt.\n\n" + trimNotice(1, false)), messages.get(2)), out);
    }

    @Test
    void theNoticeLeavesAMessageThatIsNoSystemTextAsItIs() throws Exception {
        var user = ChatMessage.user("Hi");
        assertSame(user, withTrimNotice(user, 2, List.of()));
        var parts = new ChatMessage("system", List.of(Map.of("type", "text", "text", "System prompt.")), null, null, null);
        assertSame(parts, withTrimNotice(parts, 2, List.of()));
    }

    @Test
    void aSystemPromptEndingOnOtherBracketedTextGetsANoticeOfItsOwn() throws Exception {
        var system = ChatMessage.system("System prompt.\n\n[Reply in English.]");

        var noticed = withTrimNotice(system, 2, List.of());

        assertEquals("System prompt.\n\n[Reply in English.]\n\n" + trimNotice(2, false), noticed.content());
    }

    @Test
    void aSecondNoticeReplacesTheFirstAndTakesTheToolsOfTheLatestRequest() throws Exception {
        var once = withTrimNotice(ChatMessage.system("System prompt."), 1, SEARCH_TOOL);

        var twice = withTrimNotice(once, 1, List.of());

        assertEquals("System prompt.\n\n" + trimNotice(2, false), twice.content());
    }

    @Test
    void aPromptAnchorThatCountsTheRequestSmallLeavesTheTrimItsOwnEstimate() throws Exception {
        ConfigService.set(ContextWindowManager.SAFETY_MULTIPLIER_PREFIX + PROV + "." + MODEL, "1.4");
        var provider = new OpenAiProvider(new ProviderConfig(PROV, "http://test", "key",
                List.of(new ModelInfo(MODEL, MODEL, 4000, 8000, false))));
        var agent = agentUsing(MODEL);
        agent.name = "cwm-anchor-" + UUID.randomUUID().toString().substring(0, 8);
        agent.modelProvider = PROV;
        agent.enabled = true;
        agent.save();
        var conv = ConversationService.create(agent, "web", "u-cwm-anchor");
        var asked = new ArrayList<ChatMessage>();
        asked.add(ChatMessage.system("System prompt."));
        for (int i = 0; i < 30; i++) {
            asked.add(ChatMessage.user("Question " + i + ": what did the ledger for account " + (4_000 + i)
                    + " show about invoices, refunds and adjustments over the quarter, line by line?"));
            asked.add(ChatMessage.assistant("Answer " + i + ": invoices, refunds and adjustments for account "
                    + (4_000 + i) + " balanced each month, with two late payments noted in the second month."));
        }
        // The provider counted the request this reply answered at 100 tokens.
        var call = LlmProvider.StreamAccumulator.builder().usage(new Usage(100, 5, 105, 0, 0, 0))
                .promptTokenEstimate(TokenUsageEstimator.estimateChatRequest(MODEL, asked, List.of())).build();
        var turn = new LlmProvider.TurnUsage();
        turn.addRound(call);
        ConversationService.appendAssistantMessage(conv, "Done.", null,
                UsageMetricsBuilder.buildUsageJson(turn, null, System.currentTimeMillis(), agent, conv), null);
        var next = new ArrayList<>(asked);
        next.add(ChatMessage.assistant("Done."));
        next.add(ChatMessage.user("And the next quarter?"));
        var anchor = PromptAnchor.find(conv, PROV, MODEL);
        assertNotNull(anchor);
        int anchored = CompactionGate.measure(anchor, PROV, MODEL, new SessionCompactor.TurnRequest(next, List.of()))
                .estimatedTokens();
        assertTrue(anchored < 2000, "the anchor counts the request well inside the 2,000 the trim targets: " + anchored);

        var trimmed = trim(next, agent, conv, provider);

        assertTrue(trimmed.size() < next.size(), "the trim still cuts by the whole estimate");
        assertEquals(trim(next, agent, null, provider), trimmed, "the same cut as with no conversation to anchor on");
    }
}
