import agents.AgentRunner;
import agents.ToolContext;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.SubagentRegistry;
import tools.SubagentSpawnTool;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
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

    // The chat stream is closed after 12 minutes without a frame, and a turn waiting on a subagent sends none.
    @Test
    void aWaitingTurnHearsFromItsSubagentOnlyWhileTheSubagentWorks() throws Exception {
        long conversationId = freshConversationId();
        long runId = freshConversationId();
        var statuses = new CopyOnWriteArrayList<String>();
        var watching = new AgentRunner.StreamingCallbacks(_ -> { }, _ -> { }, _ -> { }, statuses::add, _ -> { },
                _ -> { }, _ -> { }, () -> { });
        SubagentSpawnTool.registerChatCallbacks(conversationId, watching);
        SubagentRegistry.register(runId, new CompletableFuture<Void>());
        var relay = ToolContext.withScope(conversationId, null,
                () -> SubagentSpawnTool.relayActivityForTest(List.of(runId), Duration.ofMillis(50)));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (statuses.isEmpty() && System.nanoTime() < deadline) {
                SubagentRegistry.touch(runId);
                Thread.sleep(10);
            }
            assertEquals("Subagent still working", statuses.isEmpty() ? null : statuses.getFirst());

            Thread.sleep(200);
            int whenIdle = statuses.size();
            Thread.sleep(300);
            assertEquals(whenIdle, statuses.size(), "a subagent that does nothing sends nothing");
        } finally {
            relay.cancel(false);
            SubagentRegistry.unregister(runId);
            SubagentSpawnTool.unregisterChatCallbacks(conversationId, watching);
        }
    }

    @Test
    void nothingIsRelayedOutsideAChatTurn() {
        assertTrue(SubagentSpawnTool.relayActivityForTest(List.of(freshConversationId()), Duration.ofMillis(50)).isDone());
    }
}
