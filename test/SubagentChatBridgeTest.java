import agents.AgentRunner;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.SubagentSpawnTool;

import java.util.concurrent.atomic.AtomicInteger;

class SubagentChatBridgeTest extends UnitTest {

    private static final AtomicInteger IDS = new AtomicInteger();

    // Ids far from any row a concurrent class creates; the registry is process-wide.
    private static long freshConversationId() {
        return Long.MAX_VALUE - 1_000 - IDS.incrementAndGet();
    }

    private static AgentRunner.StreamingCallbacks callbacks(Object owner) {
        return new AgentRunner.StreamingCallbacks(_ -> owner.hashCode(), _ -> owner.hashCode(), _ -> owner.hashCode(),
                _ -> owner.hashCode(), _ -> owner.hashCode(), _ -> owner.hashCode(), _ -> owner.hashCode(),
                owner::hashCode);
    }

    @Test
    void aStoppedTurnClosingLateLeavesTheNextTurnsCallbacksRegistered() {
        long conversationId = freshConversationId();
        var stopped = callbacks("stopped turn");
        var next = callbacks("next turn");
        SubagentSpawnTool.registerChatCallbacks(conversationId, stopped);
        SubagentSpawnTool.registerChatCallbacks(conversationId, next);

        SubagentSpawnTool.unregisterChatCallbacks(conversationId, stopped);

        assertSame(next, SubagentSpawnTool.chatCallbacksForTest(conversationId));
        SubagentSpawnTool.unregisterChatCallbacks(conversationId, next);
        assertNull(SubagentSpawnTool.chatCallbacksForTest(conversationId));
    }

    @Test
    void anEqualButDistinctCallbackSetDoesNotUnregisterTheTurn() {
        long conversationId = freshConversationId();
        var registered = callbacks("same");
        SubagentSpawnTool.registerChatCallbacks(conversationId, registered);

        SubagentSpawnTool.unregisterChatCallbacks(conversationId,
                new AgentRunner.StreamingCallbacks(registered.onInit(), registered.onToken(), registered.onReasoning(),
                        registered.onStatus(), registered.onToolCall(), registered.onComplete(), registered.onError(),
                        registered.onCancel()));

        assertSame(registered, SubagentSpawnTool.chatCallbacksForTest(conversationId));
        SubagentSpawnTool.unregisterChatCallbacks(conversationId, registered);
    }
}
