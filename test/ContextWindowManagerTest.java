import agents.ContextWindowManager;
import llm.LlmProvider;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ModelInfo;
import llm.LlmTypes.ProviderConfig;
import llm.LlmTypes.ToolDef;
import llm.OpenAiProvider;
import llm.TokenUsageEstimator;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

    @SuppressWarnings("unchecked")
    private static List<ChatMessage> trim(List<ChatMessage> messages, Agent agent,
                                          LlmProvider provider) throws Exception {
        var m = ContextWindowManager.class.getDeclaredMethod("trimToContextWindow",
                List.class, Agent.class, Conversation.class, LlmProvider.class);
        m.setAccessible(true);
        return (List<ChatMessage>) m.invoke(null, messages, agent, null, provider);
    }

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
        var nameOnly = ToolDef.of("aaaaaaaa", null, null);
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
}
