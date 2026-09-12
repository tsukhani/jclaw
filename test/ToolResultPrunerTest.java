import agents.ToolContext;
import agents.ToolResultPruner;
import llm.LlmTypes.ChatMessage;
import models.Agent;
import models.Conversation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.AgentService;
import services.ConfigService;
import services.ConversationService;
import services.compression.ContentHash;
import tools.CcrRetrieveTool;

import java.util.List;

/** JCLAW-1202: old large tool results become stubs that resolve through ccr_retrieve. */
class ToolResultPrunerTest extends UnitTest {

    private static final String BIG = "x".repeat(5_000);

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
        ConfigService.clearCache();
    }

    private static ChatMessage tool(String id, String name, String body) {
        return new ChatMessage("tool", body, null, id, name);
    }

    @Test
    void anEarlierTurnsLargeResultBecomesAStubCarryingItsHandleAndCallId() {
        var messages = List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("first ask"),
                ChatMessage.assistant("", List.of()),
                tool("c1", "web_fetch", BIG),
                ChatMessage.assistant("answered"),
                ChatMessage.user("second ask"),
                ChatMessage.assistant("", List.of()),
                tool("c2", "web_fetch", BIG));

        var pruned = ToolResultPruner.prune(messages, 4_000, 2, null, null);

        var stub = pruned.get(3);
        assertTrue(((String) stub.content()).startsWith(ToolResultPruner.STUB_MARKER), stub.content().toString());
        assertTrue(((String) stub.content()).contains("ccr_retrieve(\"" + ContentHash.handle(BIG) + "\")"),
                "the stub carries the hash of the original body");
        assertTrue(((String) stub.content()).contains("web_fetch returned 5000 chars"));
        assertEquals("c1", stub.toolCallId(), "the tool_call pairing survives");
        assertSame(BIG, pruned.get(7).content(), "the current turn's result is sent whole");
        assertSame(messages.get(3), messages.get(3), "the input list is not mutated");
        assertEquals(BIG, messages.get(3).content());
    }

    @Test
    void smallResultsStubsAndTheRetrieveToolsOwnOutputAreLeftAlone() {
        var messages = List.of(
                ChatMessage.user("first ask"),
                tool("c1", "web_fetch", "short"),
                tool("c2", "ccr_retrieve", BIG),
                tool("c3", "web_fetch", ToolResultPruner.STUB_MARKER + ": already stubbed]"),
                ChatMessage.user("second ask"),
                ChatMessage.assistant("done"));

        var pruned = ToolResultPruner.prune(messages, 4_000, 1, null, null);

        assertSame(messages, pruned, "nothing eligible: the same list instance comes back");
    }

    @Test
    void theCurrentTurnAndTheNewestMessagesAreNeverStubbed() {
        var messages = new java.util.ArrayList<ChatMessage>();
        messages.add(ChatMessage.user("one ask, many rounds"));
        for (int i = 0; i < 10; i++) messages.add(tool("c" + i, "web_search", BIG));
        assertSame(messages, ToolResultPruner.prune(messages, 4_000, 2, null, null),
                "everything after the last user message belongs to the turn in flight");

        var older = List.of(ChatMessage.user("a"), tool("c1", "t", BIG), ChatMessage.user("b"), tool("c2", "t", BIG),
                ChatMessage.user("c"));
        var pruned = ToolResultPruner.prune(older, 4_000, 3, null, null);
        assertTrue(((String) pruned.get(1).content()).startsWith(ToolResultPruner.STUB_MARKER));
        assertEquals(BIG, pruned.get(3).content(), "inside the protected tail");
    }

    @Test
    void aStubResolvesToTheOriginalThroughTheRetrieveTool() {
        new jobs.ToolRegistrationJob().doJob();
        Agent agent = AgentService.create("prune-agent", "openrouter", "gpt-4.1");
        Conversation conv = ConversationService.create(agent, "web", "u-prune");
        var body = "{\"rows\":[" + "\"r\",".repeat(2_000) + "\"end\"]}";
        ConversationService.appendToolResult(conv, "call_1", body);

        var pruned = ToolResultPruner.prune(List.of(
                ChatMessage.user("earlier"), tool("call_1", "documents", body), ChatMessage.user("now")),
                4_000, 1, agent, conv);
        var stub = (String) pruned.get(1).content();
        var hash = stub.substring(stub.indexOf("ccr_retrieve(\"") + 14, stub.indexOf("\")"));

        var result = ToolContext.withConversation(conv.id,
                () -> new CcrRetrieveTool().execute("{\"hash\":\"" + hash + "\"}", agent));
        assertEquals(body, result, "the handle in the stub returns the stored original verbatim");
    }
}
