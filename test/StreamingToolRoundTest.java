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
public class StreamingToolRoundTest extends UnitTest {

    @Test
    public void estimateTokensCalculatesApproximately() throws Exception {
        // Use reflection to test the private estimateTokens method
        var method = AgentRunner.class.getDeclaredMethod("estimateTokens", List.class);
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
    public void estimateTokensHandlesEmptyMessages() throws Exception {
        var method = AgentRunner.class.getDeclaredMethod("estimateTokens", List.class);
        method.setAccessible(true);

        var messages = List.<ChatMessage>of();
        var tokens = (int) method.invoke(null, messages);
        assertEquals(0, tokens);
    }

    @Test
    public void estimateTokensHandlesNullContent() throws Exception {
        var method = AgentRunner.class.getDeclaredMethod("estimateTokens", List.class);
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
    public void trimToContextWindowPreservesSystemPrompt() throws Exception {
        // JCLAW-108: trimToContextWindow gained a Conversation parameter to
        // honor conversation-scoped model overrides. Pass null here — tests
        // that care about override behavior use the AgentRunnerUsageTest
        // suite instead.
        var method = AgentRunner.class.getDeclaredMethod("trimToContextWindow",
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
    public void trimToContextWindowNoOpWhenFits() throws Exception {
        var method = AgentRunner.class.getDeclaredMethod("trimToContextWindow",
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
    public void maxToolRoundsConstantExists() throws Exception {
        // Verify MAX_TOOL_ROUNDS is accessible and reasonable
        var field = AgentRunner.class.getDeclaredField("DEFAULT_MAX_TOOL_ROUNDS");
        field.setAccessible(true);
        var maxRounds = (int) field.get(null);
        assertTrue(maxRounds > 0 && maxRounds <= 20,
                "MAX_TOOL_ROUNDS should be between 1 and 20, got: " + maxRounds);
    }
}
