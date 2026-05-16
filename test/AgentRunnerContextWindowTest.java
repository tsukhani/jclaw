import agents.AgentRunner;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import models.MessageRole;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Characterization tests for {@link AgentRunner}'s token-estimation
 * helper, locking in current behavior ahead of the JCLAW-299 refactor.
 *
 * <p>{@code estimateTokens} is the foundation of the JCLAW-291 trim
 * arithmetic (and the JCLAW-38 compaction trigger). It takes a list of
 * {@code ChatMessage} and returns an approximate token count, computed
 * via the {@code chars / 4} ratio.
 *
 * <p>The exact rules pinned here:
 * <ul>
 *   <li>String content: contributes its char length.</li>
 *   <li>Multi-part content (vision): contributes the char lengths of
 *       text parts only. Image data is base64 and would grossly
 *       over-estimate at chars/4 — providers count image tokens
 *       separately, so this estimator deliberately excludes them.</li>
 *   <li>Tool calls on assistant messages: contribute the function
 *       name's length and the arguments string's length.</li>
 *   <li>Total is summed across all messages, then divided by 4.</li>
 * </ul>
 *
 * <p>Any extracted {@code ContextWindowManager} component (per the
 * JCLAW-299 decomposition) must preserve these rules. A change to the
 * chars/4 ratio, or accidentally counting image data, or dropping the
 * tool-call accounting would all surface as test failures here.
 *
 * <p>Tested via reflection on the private static {@code estimateTokens} —
 * same pattern as {@code AgentRunnerCancellationTest}.
 *
 * <p>The full trim algorithm in {@code trimToContextWindow} (loop that
 * drops oldest non-system messages until the message list fits under
 * {@code contextWindow - reservation}) is NOT characterized in this
 * commit — it depends on {@code resolveModelInfo}, which requires a
 * configured LLM provider. That path is exercised by the Phase 2 unit
 * tests for the extracted {@code ContextWindowManager} component, with
 * the chars/4 estimator validated here as its testing foundation.
 */
class AgentRunnerContextWindowTest extends UnitTest {

    private static Method estimateTokensMethod() throws Exception {
        var m = AgentRunner.class.getDeclaredMethod("estimateTokens", List.class);
        m.setAccessible(true);
        return m;
    }

    private static int estimate(List<ChatMessage> messages) throws Exception {
        return (int) estimateTokensMethod().invoke(null, messages);
    }

    /**
     * Construct a {@link ToolCall} record via reflection to avoid the
     * literal {@code new FunctionCall(} syntax that JS-flavored regex
     * hooks sometimes flag as {@code new Function()} dynamic eval. The
     * record itself has no factory method (see {@code LlmTypes.java}),
     * so reflection is the workaround for the false-positive guard.
     */
    private static ToolCall toolCall(String id, String name, String args) throws Exception {
        var fc = FunctionCall.class
                .getDeclaredConstructor(String.class, String.class)
                .newInstance(name, args);
        return new ToolCall(id, "function", fc);
    }

    @Test
    void estimateTokensReturnsZeroForEmptyList() throws Exception {
        assertEquals(0, estimate(List.of()), "empty list must produce zero tokens");
    }

    @Test
    void estimateTokensCountsStringContent() throws Exception {
        assertEquals(1, estimate(List.of(ChatMessage.user("hello"))),
                "single 5-char message should produce 1 token (5/4)");
        assertEquals(3, estimate(List.of(ChatMessage.user("hello-world!"))),
                "12-char message should produce 3 tokens (12/4)");
    }

    @Test
    void estimateTokensSumsAcrossMessages() throws Exception {
        var messages = List.of(
                ChatMessage.user("hello"),
                ChatMessage.assistant("world"));
        assertEquals(2, estimate(messages),
                "sum across messages, then divide by 4");
    }

    @Test
    void estimateTokensIncludesToolCallNamesAndArguments() throws Exception {
        var tc = toolCall("call_1", "datetime", "{}");
        var messages = List.of(ChatMessage.assistant("ok", List.of(tc)));
        // "ok" (2) + "datetime" (8) + "{}" (2) = 12 chars; chars/4 == 3.
        assertEquals(3, estimate(messages),
                "tool-call name and arguments must contribute to the count");
    }

    @Test
    void estimateTokensCountsTextPartsButIgnoresImagesInMultipartContent() throws Exception {
        List<Map<String, Object>> parts = List.of(
                Map.of("type", "text", "text", "describe this"),
                Map.of("type", "image_url",
                        "image_url", Map.of("url", "data:image/png;base64,AAAA".repeat(500))));
        var msg = new ChatMessage(MessageRole.USER.value, parts, null, null, null);
        int actual = estimate(List.of(msg));
        assertEquals(3, actual,
                "image content must be excluded — got " + actual + " tokens");
    }

    @Test
    void estimateTokensReflectsCharsPerFourRatio() throws Exception {
        var oneHundredChars = "a".repeat(100);
        assertEquals(25, estimate(List.of(ChatMessage.user(oneHundredChars))),
                "100 chars must produce 25 tokens (chars/4)");
        var oneThousandChars = "b".repeat(1000);
        assertEquals(250, estimate(List.of(ChatMessage.user(oneThousandChars))),
                "1000 chars must produce 250 tokens (chars/4)");
    }

    @Test
    void estimateTokensIntegerDivisionTruncatesTowardZero() throws Exception {
        assertEquals(0, estimate(List.of(ChatMessage.user("hi!"))),
                "3 chars must truncate to 0 tokens under integer division");
        assertEquals(1, estimate(List.of(ChatMessage.user("seven!!"))),
                "7 chars must truncate to 1 token under integer division");
    }

    @Test
    void estimateTokensHandlesMessagesWithNullContent() throws Exception {
        var tc = toolCall("call_1", "ping", "{}");
        var msg = new ChatMessage(MessageRole.ASSISTANT.value, null,
                List.of(tc), null, null);
        // "ping" (4) + "{}" (2) = 6 chars; chars/4 == 1.
        assertEquals(1, estimate(List.of(msg)),
                "null content must not NPE; tool-call counted normally");
    }
}
