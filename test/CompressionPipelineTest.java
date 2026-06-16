import agents.CompressionPipeline;
import llm.LlmTypes.ChatMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-465 (core): the compression pipeline's routing and guards. Exercises
 * the pure {@code compressMessages} seam — only TOOL-role JSON above the token
 * floor is compressed; everything else passes through untouched.
 */
class CompressionPipelineTest extends UnitTest {

    private static final String MODEL = "gpt-4o"; // OpenAI → matched encoding

    private static String bigJsonArray() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i)
                    .append(",\"title\":\"Result number ").append(i)
                    .append(" with a fairly long descriptive title that repeats\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void compressesLargeJsonToolMessage() {
        var original = ChatMessage.toolResult("call_1", "web_search", bigJsonArray());
        var out = CompressionPipeline.compressMessages(List.of(original), MODEL, 250);

        assertEquals(1, out.size());
        var compressed = out.get(0);
        assertNotSame(original, compressed, "tool message should have been compressed");
        assertEquals("tool", compressed.role());
        assertTrue(((String) compressed.content()).length() < bigJsonArray().length() / 2,
                "compressed content should be much shorter");
        assertTrue(((String) compressed.content()).contains("items elided"));
    }

    @Test
    void leavesNonToolRolesUntouched() {
        // Same big JSON, but as a USER turn — never compressed.
        var user = ChatMessage.user(bigJsonArray());
        var out = CompressionPipeline.compressMessages(List.of(user), MODEL, 250);
        assertSame(user, out.get(0), "user message must never be compressed");
    }

    @Test
    void skipsToolMessagesBelowMinTokens() {
        var small = ChatMessage.toolResult("call_2", "ping", "{\"ok\":true,\"n\":1}");
        var out = CompressionPipeline.compressMessages(List.of(small), MODEL, 250);
        assertSame(small, out.get(0), "below the token floor — left as-is");
    }

    @Test
    void skipsNonJsonToolContent() {
        // A large plain-text tool output (>250 tokens). TEXT isn't routed in the
        // MVP (JSON only), so it passes through untouched.
        var text = "The build completed successfully. ".repeat(80);
        var msg = ChatMessage.toolResult("call_3", "shell", text);
        var out = CompressionPipeline.compressMessages(List.of(msg), MODEL, 250);
        assertSame(msg, out.get(0), "non-JSON content is not compressed in the MVP");
    }

    @Test
    void returnsSameListWhenNothingCompressed() {
        var msgs = List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi"));
        var out = CompressionPipeline.compressMessages(msgs, MODEL, 250);
        assertSame(msgs, out, "no-op should return the original list instance");
    }
}
