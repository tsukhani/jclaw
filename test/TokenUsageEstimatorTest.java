import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.ToolDef;
import llm.TokenUsageEstimator;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;
import java.util.Map;

class TokenUsageEstimatorTest extends UnitTest {

    @Test
    void chatPromptEstimateIncludesMessageFraming() {
        var message = ChatMessage.user("hello world");

        var messageOnly = TokenUsageEstimator.estimateMessage("gpt-4o", message);
        var request = TokenUsageEstimator.estimateChatRequest("gpt-4o", List.of(message), List.of());

        assertTrue(request.promptTokens() > messageOnly.tokens(),
                "request estimate must include assistant-primer framing, got " + request);
        assertEquals(0, request.toolTokens());
    }

    @Test
    void toolSchemasContributeToPromptEstimate() {
        var messages = List.of(ChatMessage.user("search for this"));
        var withoutTools = TokenUsageEstimator.estimateChatRequest("gpt-4o", messages, List.of());
        var tool = ToolDef.of("web_search", "Search the web",
                Map.of("type", "object", "properties",
                        Map.of("query", Map.of("type", "string"))));

        var withTools = TokenUsageEstimator.estimateChatRequest("gpt-4o", messages, List.of(tool));

        assertTrue(withTools.toolTokens() > 0, "tool schema must be counted");
        assertTrue(withTools.promptTokens() > withoutTools.promptTokens(),
                "tool schema should increase prompt tokens");
    }

    @Test
    void modernUnknownOpenAiModelFallsBackToO200KBase() {
        var estimate = TokenUsageEstimator.estimateChatRequest(
                "gpt-5.1-preview", List.of(ChatMessage.user("hello")), List.of());

        assertEquals("o200k_base", estimate.encodingName());
        assertFalse(estimate.modelMatched(),
                "jtokkit 1.1.0 does not know future model ids, so fallback should be explicit");
    }

    @Test
    void providerPrefixedOpenAiModelStillUsesOpenAiFamilyEncoding() {
        var estimate = TokenUsageEstimator.estimateChatRequest(
                "openai/gpt-5.1-preview", List.of(ChatMessage.user("hello")), List.of());

        assertEquals("o200k_base", estimate.encodingName());
    }

    @Test
    void completionEstimateIncludesReasoningText() {
        var contentOnly = TokenUsageEstimator.estimateCompletion(
                "gpt-4o", "final answer", List.of(), null);
        var withReasoning = TokenUsageEstimator.estimateCompletion(
                "gpt-4o", "final answer", List.of(), "private chain of thought");

        assertTrue(withReasoning.tokens() > contentOnly.tokens(),
                "reasoning text must contribute to fallback completion accounting");
    }
}
