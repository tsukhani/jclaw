import agents.CompressionPipeline;
import agents.ToolContext;
import llm.LlmTypes.ChatMessage;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConversationService;
import services.compression.ContentHash;
import tools.CcrRetrieveTool;

import java.util.List;

/**
 * JCLAW-468: cross-component integration + regression for the compression
 * pipeline. Proves the disabled-agent no-op (the compaction/trim stages see the
 * original untouched) and the compress -> ccr_retrieve round-trip (the marker
 * handle the pipeline writes resolves back to the byte-identical original).
 */
class CompressionIntegrationTest extends UnitTest {

    private Agent agent;
    private Conversation conv;

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        agent = AgentService.create("integ-agent", "openrouter", "gpt-4o");
        conv = ConversationService.create(agent, "web", "u-integ");
    }

    private static String bigJsonArray() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < 500; i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":").append(i)
                    .append(",\"title\":\"item ").append(i)
                    .append(" with a longish repeated description\"}");
        }
        return sb.append(']').toString();
    }

    @Test
    void pipelineIsANoOpWhenTheAgentHasCompressionDisabled() {
        // Regression: a master-off agent leaves the message list instance
        // untouched, so downstream compaction/trim see exactly the original.
        var off = AgentService.create("integ-off", "openrouter", "gpt-4o");
        off.compressionEnabled = false; // master off
        off.save();

        var messages = List.of(ChatMessage.toolResult("c1", "web_search", bigJsonArray()));
        var out = CompressionPipeline.compress(messages, off, conv);
        assertSame(messages, out, "a disabled agent makes the pipeline a no-op");
    }

    @Test
    void compressThenCcrRetrieveRestoresTheByteIdenticalOriginal() {
        var json = bigJsonArray();
        ConversationService.appendToolResult(conv, "c1", json); // the durable original

        // Compress (pure seam): the compressed view must carry the original's handle.
        var compressed = CompressionPipeline.compressMessages(
                List.of(ChatMessage.toolResult("c1", "web_search", json)), "gpt-4o", 250);
        var view = (String) compressed.get(0).content();
        assertTrue(view.length() < json.length(), "the view is shorter than the original");
        assertTrue(view.contains("ccr_retrieve(\"" + ContentHash.handle(json) + "\")"),
                "the compressed view carries the original's retrieval handle");

        // Retrieve: the handle resolves against the persisted Message row.
        var restored = ToolContext.withConversation(conv.id,
                () -> new CcrRetrieveTool().execute(
                        "{\"hash\":\"" + ContentHash.handle(json) + "\"}", agent));
        assertEquals(json, restored, "ccr_retrieve restores the byte-identical original");
    }
}
