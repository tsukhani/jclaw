import agents.CompressionPipeline;
import agents.ToolRegistry;
import llm.LlmTypes.ChatMessage;
import llm.LlmTypes.FunctionCall;
import llm.LlmTypes.ToolCall;
import models.Agent;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.compression.ContentHash;

import java.util.List;
import java.util.Map;

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

    // ---- first view: a result the model has not read yet stays whole ----

    private static final int FIRST_VIEW_CAP = 6_000;

    /** About 2,000 tokens: above the floor, under the first-view cap. */
    private static String mediumJsonArray() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i)
                    .append(",\"title\":\"Result number ").append(i)
                    .append(" with a fairly long descriptive title that repeats\"}");
        }
        return sb.append(']').toString();
    }

    private static ChatMessage call(String id, String tool, String args) {
        return ChatMessage.assistant("", List.of(new ToolCall(id, "function", new FunctionCall(tool, args))));
    }

    @Test
    void leavesAResultTheModelHasNotSeenWhole() {
        var msgs = List.of(ChatMessage.user("find it"), call("c1", "web_search", "{}"),
                ChatMessage.toolResult("c1", "web_search", mediumJsonArray()));
        var out = CompressionPipeline.compressMessages(msgs, MODEL, 250, FIRST_VIEW_CAP);
        assertSame(msgs, out, "a result that follows the last assistant message has not been read yet");
    }

    @Test
    void compressesAResultOnceTheModelHasSeenIt() {
        var result = ChatMessage.toolResult("c1", "web_search", mediumJsonArray());
        var msgs = List.of(ChatMessage.user("find it"), call("c1", "web_search", "{}"), result,
                call("c2", "web_search", "{}"), ChatMessage.toolResult("c2", "web_search", "{\"ok\":true}"));
        var out = CompressionPipeline.compressMessages(msgs, MODEL, 250, FIRST_VIEW_CAP);
        assertNotSame(result, out.get(2), "a later assistant message means the model has read it");
        assertTrue(((String) out.get(2).content()).contains(
                "ccr_retrieve(\"" + ContentHash.handle(mediumJsonArray()) + "\")"), "ccr handle present");
    }

    @Test
    void compressesAnUnseenResultAboveTheFirstViewCap() {
        var result = ChatMessage.toolResult("c1", "web_search", bigJsonArray());
        var out = CompressionPipeline.compressMessages(
                List.of(call("c1", "web_search", "{}"), result), MODEL, 250, FIRST_VIEW_CAP);
        assertNotSame(result, out.get(1), "an unseen result above the cap is compressed straight away");
    }

    @Test
    void aFirstViewCapOfZeroCompressesUnseenResults() {
        var result = ChatMessage.toolResult("c1", "web_search", mediumJsonArray());
        var out = CompressionPipeline.compressMessages(
                List.of(call("c1", "web_search", "{}"), result), MODEL, 250, 0);
        assertNotSame(result, out.get(1), "0 turns the first-view rule off");
    }

    // ---- verbatim: a call whose tool declares its result a catalog is never compressed ----

    /** Declares its result verbatim only for the {@code list} mode, the way an MCP server handle does. */
    private static final class CatalogStub implements ToolRegistry.Tool {
        @Override public String name() { return "catalog_stub"; }
        @Override public String description() { return "stub"; }
        @Override public Map<String, Object> parameters() { return Map.of("type", "object"); }
        @Override public String execute(String argsJson, Agent agent) { return ""; }
        @Override public boolean verbatimResult(String argsJson) { return argsJson.contains("\"list\""); }
    }

    @Test
    void neverCompressesAResultItsToolDeclaresVerbatim() {
        ToolRegistrySync.publishForTest(List.of(new CatalogStub()));
        try {
            var catalog = ChatMessage.toolResult("c1", "catalog_stub", mediumJsonArray());
            var data = ChatMessage.toolResult("c2", "catalog_stub", mediumJsonArray());
            var msgs = List.of(call("c1", "catalog_stub", "{\"mode\":\"list\"}"), catalog,
                    call("c2", "catalog_stub", "{\"mode\":\"call\"}"), data,
                    ChatMessage.assistant("done"));
            var out = CompressionPipeline.compressMessages(msgs, MODEL, 250, FIRST_VIEW_CAP);
            assertSame(catalog, out.get(1), "a catalog stays whole in every round, seen or not");
            assertNotSame(data, out.get(3), "the same tool's other calls are still compressed once seen");
        } finally {
            ToolRegistrySync.release();
        }
    }

    @Test
    void returnsSameListWhenNothingCompressed() {
        var msgs = List.of(ChatMessage.user("hello"), ChatMessage.assistant("hi"));
        var out = CompressionPipeline.compressMessages(msgs, MODEL, 250);
        assertSame(msgs, out, "no-op should return the original list instance");
    }
}
