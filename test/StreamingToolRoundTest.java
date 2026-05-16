import org.junit.jupiter.api.*;
import play.test.*;
import agents.AgentRunner;
import llm.LlmProvider;
import llm.OpenAiProvider;
import llm.LlmTypes.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests that streaming tool call recursion is capped at MAX_TOOL_ROUNDS.
 */
class StreamingToolRoundTest extends UnitTest {

    @Test
    void estimateTokensCalculatesApproximately() throws Exception {
        // Use reflection to test the private estimateTokens method
        var method = agents.ContextWindowManager.class.getDeclaredMethod("estimateTokens", List.class);
        method.setAccessible(true);

        var messages = List.of(
                ChatMessage.system("You are helpful"),    // 15 chars
                ChatMessage.user("Hello world")           // 11 chars
        );
        var tokens = (int) method.invoke(null, messages);
        // 26 chars / 4 = 6 tokens
        assertEquals(6, tokens);
    }

    @Test
    void estimateTokensHandlesEmptyMessages() throws Exception {
        var method = agents.ContextWindowManager.class.getDeclaredMethod("estimateTokens", List.class);
        method.setAccessible(true);

        var messages = List.<ChatMessage>of();
        var tokens = (int) method.invoke(null, messages);
        assertEquals(0, tokens);
    }

    @Test
    void estimateTokensHandlesNullContent() throws Exception {
        var method = agents.ContextWindowManager.class.getDeclaredMethod("estimateTokens", List.class);
        method.setAccessible(true);

        // Assistant message with null content (tool call record). The tool call
        // name ("test" = 4 chars) and arguments ("{}" = 2 chars) still count
        // toward the estimate because they consume input tokens when replayed.
        var messages = List.of(
                ChatMessage.assistant(null, List.of(
                        new ToolCall("call-1", "function", new FunctionCall("test", "{}"))
                ))
        );
        var tokens = (int) method.invoke(null, messages);
        // 6 chars / 4 = 1 token
        assertEquals(1, tokens);
    }

    @Test
    void trimToContextWindowPreservesSystemPrompt() throws Exception {
        // JCLAW-108: trimToContextWindow gained a Conversation parameter to
        // honor conversation-scoped model overrides. Pass null here — tests
        // that care about override behavior use the AgentRunnerUsageTest
        // suite instead.
        var method = agents.ContextWindowManager.class.getDeclaredMethod("trimToContextWindow",
                List.class, models.Agent.class, models.Conversation.class, LlmProvider.class);
        method.setAccessible(true);

        // Create a provider with a very small context window
        var provider = new OpenAiProvider(new ProviderConfig("test", "http://test", "key",
                List.of(new ModelInfo("model-1", "Model 1", 10, 100, false)))); // 10 tokens max

        var agent = new models.Agent();
        agent.name = "trim-test";
        agent.modelId = "model-1";

        // Create messages that exceed the context window
        // System prompt: 20 chars = 5 tokens, each user message: 40 chars = 10 tokens
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("System prompt text..")); // 20 chars = 5 tokens
        messages.add(ChatMessage.user("This is a long user message number one.")); // 39 chars = ~10 tokens
        messages.add(ChatMessage.user("This is a long user message number two.")); // 39 chars = ~10 tokens

        @SuppressWarnings("unchecked")
        var trimmed = (List<ChatMessage>) method.invoke(null, messages, agent, null, provider);

        // Should have trimmed some messages but kept the system prompt
        assertTrue(trimmed.size() < messages.size(), "Should have trimmed messages");
        assertEquals("system", trimmed.getFirst().role(), "System prompt should be preserved");
    }

    @Test
    void trimToContextWindowNoOpWhenFits() throws Exception {
        var method = agents.ContextWindowManager.class.getDeclaredMethod("trimToContextWindow",
                List.class, models.Agent.class, models.Conversation.class, LlmProvider.class);
        method.setAccessible(true);

        // Large context window — nothing should be trimmed
        var provider = new OpenAiProvider(new ProviderConfig("test", "http://test", "key",
                List.of(new ModelInfo("model-1", "Model 1", 100000, 100, false))));

        var agent = new models.Agent();
        agent.name = "no-trim-test";
        agent.modelId = "model-1";

        var messages = List.of(
                ChatMessage.system("Short prompt"),
                ChatMessage.user("Hello")
        );

        @SuppressWarnings("unchecked")
        var result = (List<ChatMessage>) method.invoke(null, messages, agent, null, provider);

        assertEquals(messages.size(), result.size(), "No messages should be trimmed");
    }

    @Test
    void maxToolRoundsConstantExists() throws Exception {
        // Verify MAX_TOOL_ROUNDS is accessible and reasonable
        var field = AgentRunner.class.getDeclaredField("DEFAULT_MAX_TOOL_ROUNDS");
        field.setAccessible(true);
        var maxRounds = (int) field.get(null);
        assertTrue(maxRounds > 0 && maxRounds <= 20,
                "MAX_TOOL_ROUNDS should be between 1 and 20, got: " + maxRounds);
    }

    // === cancelledReturn: cancellation early-exit fallback resolution ===

    private static java.lang.reflect.Method cancelledReturnMethod() throws Exception {
        // JCLAW-299 Phase 2: cancelledReturn lives on agents.CancellationManager
        // now. Reflection target moved here; signature and semantics preserved.
        var m = agents.CancellationManager.class.getDeclaredMethod("cancelledReturn",
                String.class, List.class, String.class,
                AgentRunner.StreamingCallbacks.class, models.Agent.class, int.class);
        m.setAccessible(true);
        return m;
    }

    private static AgentRunner.StreamingCallbacks recordingCallbacks(java.util.concurrent.atomic.AtomicReference<String> emitted) {
        return new AgentRunner.StreamingCallbacks(
                _ -> {}, emitted::set, _ -> {}, _ -> {}, _ -> {}, _ -> {}, _ -> {}, () -> {});
    }

    @Test
    void cancelledReturnPreservesNonBlankPriorContent() throws Exception {
        // When the round-1 model emitted a preamble before the tool calls
        // ("Looking that up for you…"), the user already saw it streamed.
        // A cancellation must not replace it with a "(cancelled)" marker.
        var emitted = new java.util.concurrent.atomic.AtomicReference<String>();
        var cb = recordingCallbacks(emitted);
        var agent = new models.Agent();
        agent.name = "test-agent";

        var result = (String) cancelledReturnMethod().invoke(null,
                "Looking that up for you...", List.of(), "web", cb, agent, 0);

        assertEquals("Looking that up for you...", result);
        assertNull(emitted.get(), "no fallback token must be emitted when prior content is preserved");
    }

    @Test
    void cancelledReturnEmitsLabeledFallbackForEmptyPriorContent() throws Exception {
        // The bug fix: when priorContent="" (round 1 emitted only tool_calls),
        // returning "" was the silent-data-loss path. Emit a labeled fallback
        // instead so the persisted assistant row is non-empty and the user
        // sees what happened.
        var emitted = new java.util.concurrent.atomic.AtomicReference<String>();
        var cb = recordingCallbacks(emitted);
        var agent = new models.Agent();
        agent.name = "test-agent";

        var result = (String) cancelledReturnMethod().invoke(null,
                "", List.of(), "web", cb, agent, 0);

        assertTrue(result.contains("Synthesis was cancelled"),
                "fallback message must be returned, got: " + result);
        assertEquals(result, emitted.get(),
                "fallback must also be pushed via onToken so streamContent has it");
    }

    @Test
    void cancelledReturnTreatsNullAndBlankAsEmpty() throws Exception {
        var emitted1 = new java.util.concurrent.atomic.AtomicReference<String>();
        var emitted2 = new java.util.concurrent.atomic.AtomicReference<String>();
        var agent = new models.Agent();
        agent.name = "test-agent";

        var nullResult = (String) cancelledReturnMethod().invoke(null,
                null, List.of(), "web", recordingCallbacks(emitted1), agent, 0);
        var blankResult = (String) cancelledReturnMethod().invoke(null,
                "   \n\t  ", List.of(), "web", recordingCallbacks(emitted2), agent, 0);

        assertTrue(nullResult.contains("Synthesis was cancelled"));
        assertTrue(blankResult.contains("Synthesis was cancelled"));
        assertNotNull(emitted1.get());
        assertNotNull(emitted2.get());
    }

    @Test
    void cancelledReturnPrependsCollectedImagesOnFallback() throws Exception {
        // Tool calls that produced images (screenshots, QR codes) before
        // cancellation must not have those images dropped — they're already
        // committed to the conversation history and the user expects to see
        // them in the assistant bubble.
        var emitted = new java.util.concurrent.atomic.AtomicReference<String>();
        var cb = recordingCallbacks(emitted);
        var agent = new models.Agent();
        agent.name = "test-agent";

        var images = List.of("![screenshot](attachments/abc123.png)");
        var result = (String) cancelledReturnMethod().invoke(null,
                "", images, "web", cb, agent, 1);

        assertTrue(result.startsWith("![screenshot](attachments/abc123.png)"),
                "collected images must prefix the fallback, got: " + result);
        assertTrue(result.contains("Synthesis was cancelled"),
                "fallback message must still be present alongside images");
    }
}
