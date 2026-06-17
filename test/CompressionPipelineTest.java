import agents.CompressionPipeline;
import llm.LlmTypes.ChatMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.ContentHash;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JCLAW-465 (core): the compression pipeline's routing and guards. Exercises
 * the pure {@code compressMessages} seam — TOOL-role JSON, CODE and TEXT/LOG
 * above the token floor are compressed; everything else passes through untouched.
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
    void compressedJsonCarriesCcrRetrieveHandle() {
        // The compressed message must end with a ccr_retrieve handle keyed by
        // the hash of the ORIGINAL content, so the LLM can recover the elided data.
        var json = bigJsonArray();
        var original = ChatMessage.toolResult("call_1", "web_search", json);
        var out = CompressionPipeline.compressMessages(List.of(original), MODEL, 250);
        var content = (String) out.get(0).content();
        assertTrue(content.contains("ccr_retrieve(\"" + ContentHash.handle(json) + "\")"),
                "expected a ccr_retrieve handle in: " + content);
    }

    @Test
    void compressesStatusPrefixedJsonToolResult() {
        // The jclaw_api smoke-test case: a tool result of "HTTP 200\n" + big JSON.
        var sb = new StringBuilder("HTTP 200\n[");
        for (int i = 0; i < 300; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i).append(",\"channelType\":\"web\",\"peerId\":\"admin")
                    .append(i).append("\"}");
        }
        sb.append(']');
        var original = ChatMessage.toolResult("call_1", "jclaw_api", sb.toString());

        var out = CompressionPipeline.compressMessages(List.of(original), MODEL, 250);
        var content = (String) out.get(0).content();
        assertTrue(content.startsWith("HTTP 200\n"), "prefix preserved through the pipeline");
        assertTrue(content.contains("items elided"), "array elided");
        assertTrue(content.contains("ccr_retrieve(\"" + ContentHash.handle(sb.toString()) + "\")"),
                "ccr handle keyed by the full original");
    }

    @Test
    void neverCompressesCcrRetrieveOutput() {
        // ccr_retrieve un-compresses; recompressing its (large JSON) output
        // would loop the LLM back to the elided view it was escaping.
        var msg = ChatMessage.toolResult("call_1", "ccr_retrieve", bigJsonArray());
        var out = CompressionPipeline.compressMessages(List.of(msg), MODEL, 250);
        assertSame(msg, out.get(0), "ccr_retrieve output must pass through uncompressed");
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
    void compressesTextToolContent() {
        // JCLAW-464: TEXT/LOG now route to the statistical TextCompressor.
        var text = "The build step finished without issues. ".repeat(60);
        var msg = ChatMessage.toolResult("call_3", "shell", text);
        var out = CompressionPipeline.compressMessages(List.of(msg), MODEL, 250);
        var compressed = out.get(0);
        assertNotSame(msg, compressed, "repetitive TEXT should be compressed");
        assertTrue(((String) compressed.content()).contains(
                "ccr_retrieve(\"" + ContentHash.handle(text) + "\")"), "ccr handle present");
    }

    @Test
    void compressesCodeToolContent() {
        // JCLAW-463: CODE is now routed to the CodeCompressor (it passed through
        // untouched in the JSON-only MVP).
        var sb = new StringBuilder("package demo;\n\nimport java.util.List;\n\npublic class Service {\n");
        for (int i = 0; i < 8; i++) {
            sb.append("    public int compute").append(i).append("(int a, int b) {\n");
            for (int j = 0; j < 10; j++) {
                sb.append("        int temp").append(j).append(" = a * ").append(j).append(" + b;\n");
            }
            sb.append("        return a + b;\n    }\n");
        }
        sb.append("}\n");
        var original = ChatMessage.toolResult("call_c", "read_file", sb.toString());

        var out = CompressionPipeline.compressMessages(List.of(original), MODEL, 250);
        var compressed = out.get(0);
        assertNotSame(original, compressed, "code tool output should be compressed");
        var content = (String) compressed.content();
        assertTrue(content.contains("public class Service"), "signature preserved: " + content);
        assertTrue(content.contains("ccr_retrieve(\"" + ContentHash.handle(sb.toString()) + "\")"),
                "ccr handle present");
    }

    @Test
    void returnsSameListWhenNothingCompressed() {
        var msgs = List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi"));
        var out = CompressionPipeline.compressMessages(msgs, MODEL, 250);
        assertSame(msgs, out, "no-op should return the original list instance");
    }
}
