import agents.AgentExecutionSink;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.AttachmentService;

import java.util.ArrayList;
import java.util.List;

/**
 * JCLAW-315: cover the default-method bodies on the {@link AgentExecutionSink}
 * interface. Only an interface, so we test against an anonymous implementer
 * that exposes the abstract surface; the default methods are exercised
 * directly to confirm:
 *
 * <ul>
 *   <li>{@code onStart/onComplete/onFailure} are no-ops by default
 *       (so {@link agents.ConversationSink} can leave them empty).</li>
 *   <li>{@code appendAssistantMessage(content, toolCalls)} forwards into the
 *       5-arg form with the canonical null/false defaults.</li>
 *   <li>{@code appendToolResult(toolCallId, result)} forwards into the
 *       3-arg form with structuredJson=null.</li>
 *   <li>{@code executionLabel()} defaults to {@code "unknown"}.</li>
 * </ul>
 *
 * <p>Note on AC drift: the JCLAW-315 ticket mentions
 * {@code AgentRunStartedEvent}/{@code AgentRunCompletedEvent} subscriptions
 * and an "event log forward" — none of that surface exists on
 * {@link AgentExecutionSink}, which is a write-target interface, not a
 * pub-sub subscriber. See the test report for the actual coverage.
 */
class AgentExecutionSinkTest extends UnitTest {

    /** Test double that records every call so we can assert delegation. */
    static final class Recorder implements AgentExecutionSink {
        record Assistant(String content, String toolCalls, String usageJson,
                         String reasoning, boolean truncated) {}
        record Tool(String toolCallId, String result, String structuredJson) {}

        final List<String> userMessages = new ArrayList<>();
        final List<List<AttachmentService.Input>> userAttachments = new ArrayList<>();
        final List<Assistant> assistants = new ArrayList<>();
        final List<Tool> tools = new ArrayList<>();

        @Override
        public void appendUserMessage(String content, List<AttachmentService.Input> attachments) {
            userMessages.add(content);
            userAttachments.add(attachments);
        }

        @Override
        public void appendAssistantMessage(String content, String toolCalls, String usageJson,
                                          String reasoning, boolean truncated) {
            assistants.add(new Assistant(content, toolCalls, usageJson, reasoning, truncated));
        }

        @Override
        public void appendToolResult(String toolCallId, String result, String structuredJson) {
            tools.add(new Tool(toolCallId, result, structuredJson));
        }
    }

    @Test
    void defaultOnStartIsNoOp() {
        var sink = new Recorder();
        // No exception, no observable effect on the recorder fields.
        sink.onStart();
        assertEquals(0, sink.userMessages.size());
        assertEquals(0, sink.assistants.size());
        assertEquals(0, sink.tools.size());
    }

    @Test
    void defaultOnCompleteIsNoOp() {
        var sink = new Recorder();
        sink.onComplete("final assistant summary");
        // ConversationSink ignores; nothing recorded.
        assertEquals(0, sink.assistants.size());
    }

    @Test
    void defaultOnFailureIsNoOp() {
        var sink = new Recorder();
        sink.onFailure("tool exploded");
        assertEquals(0, sink.assistants.size());
    }

    @Test
    void defaultExecutionLabelIsUnknown() {
        var sink = new Recorder();
        assertEquals("unknown", sink.executionLabel(),
                "default label is the literal 'unknown'");
    }

    @Test
    void shortAssistantOverloadForwardsToFullFormWithDefaults() {
        var sink = new Recorder();
        sink.appendAssistantMessage("hello", "[{\"name\":\"echo\"}]");
        assertEquals(1, sink.assistants.size());
        var got = sink.assistants.get(0);
        assertEquals("hello", got.content());
        assertEquals("[{\"name\":\"echo\"}]", got.toolCalls());
        assertNull(got.usageJson(), "default usageJson must be null");
        assertNull(got.reasoning(), "default reasoning must be null");
        assertFalse(got.truncated(), "default truncated must be false");
    }

    @Test
    void shortToolResultOverloadForwardsWithNullStructured() {
        var sink = new Recorder();
        sink.appendToolResult("call_abc123", "{\"ok\":true}");
        assertEquals(1, sink.tools.size());
        var got = sink.tools.get(0);
        assertEquals("call_abc123", got.toolCallId());
        assertEquals("{\"ok\":true}", got.result());
        assertNull(got.structuredJson(),
                "convenience overload must pass null for structuredJson");
    }

    @Test
    void abstractMethodsReachTheImplementer() {
        // Sanity: the 2-arg form ALSO ends up on the recorder via the
        // 5-arg override, and the user/tool methods land too.
        var sink = new Recorder();
        sink.appendUserMessage("user said", null);
        sink.appendAssistantMessage("model said", null, "{\"tokens\":5}", "reasoning trace", true);
        sink.appendToolResult("call_x", "tool out", "{\"chips\":[]}");

        assertEquals(1, sink.userMessages.size());
        assertEquals("user said", sink.userMessages.get(0));
        assertNull(sink.userAttachments.get(0), "null attachments must pass through unchanged");

        assertEquals(1, sink.assistants.size());
        var assistant = sink.assistants.get(0);
        assertTrue(assistant.truncated(), "truncated=true must propagate");
        assertEquals("reasoning trace", assistant.reasoning());

        assertEquals(1, sink.tools.size());
        assertEquals("{\"chips\":[]}", sink.tools.get(0).structuredJson(),
                "structuredJson must pass through when present");
    }
}
