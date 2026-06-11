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
    void repeatedTokenizationOfIdenticalContentHitsTheCache() {
        // A chat request re-includes the full history every turn, so the same
        // immutable message is tokenized again on the next turn. The memo must
        // serve that repeat from cache while returning an identical count.
        var msg = ChatMessage.user("memoization probe: the quick brown fox jumps "
                + "over the lazy dog, repeatedly, across many conversation turns");

        long hitsBefore = TokenUsageEstimator.tokenCacheHitCount();
        var first = TokenUsageEstimator.estimateMessage("gpt-4o", msg);
        var second = TokenUsageEstimator.estimateMessage("gpt-4o", msg);

        assertEquals(first.tokens(), second.tokens(),
                "memoized re-count must equal the first count");
        assertTrue(TokenUsageEstimator.tokenCacheHitCount() > hitsBefore,
                "re-tokenizing identical content must be served from the cache");
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
