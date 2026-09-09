import com.agentclientprotocol.sdk.spec.AcpSchema.AgentMessageChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.AgentThoughtChunk;
import com.agentclientprotocol.sdk.spec.AcpSchema.TextContent;
import com.agentclientprotocol.sdk.spec.AcpSchema.UsageUpdate;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.AcpThoughtCoalescer;
import tools.HarnessEvent;

import java.util.List;

/** One transcript step per reasoning block on the ACP path, instead of one per streamed fragment. */
class AcpThoughtCoalescerTest extends UnitTest {

    private static AgentThoughtChunk thought(String s) { return new AgentThoughtChunk("s", new TextContent(s)); }

    @Test
    void thoughtFragmentsAreHeldUntilTheNextNonThoughtUpdate() {
        var c = new AcpThoughtCoalescer();
        assertEquals(List.of(), c.accept(thought("The")));
        assertEquals(List.of(), c.accept(thought(" user")));
        assertEquals(List.of(), c.accept(thought(" wants files.")));

        var out = c.accept(new AgentMessageChunk("s", new TextContent("Created")));
        assertEquals(2, out.size(), out.toString());
        assertEquals(HarnessEvent.STEP, out.get(0).kind());
        assertEquals("The user wants files.", out.get(0).text());
        assertEquals(HarnessEvent.TOKEN, out.get(1).kind());
        assertEquals("Created", out.get(1).text());
    }

    @Test
    void aDroppedBookkeepingUpdateStillClosesTheBlock() {
        var c = new AcpThoughtCoalescer();
        c.accept(thought("plan"));
        var out = c.accept(new UsageUpdate("s", 1L, 2L));
        assertEquals(1, out.size(), "the block is emitted even though usage itself maps to nothing");
        assertEquals("plan", out.get(0).text());
    }

    @Test
    void flushEmitsThePendingBlockOnceAndSkipsBlankReasoning() {
        var c = new AcpThoughtCoalescer();
        assertEquals(List.of(), c.flush(), "nothing buffered → nothing emitted");
        c.accept(thought("  "));
        assertEquals(List.of(), c.flush(), "whitespace-only reasoning is not a step");
        c.accept(thought("tail thought"));
        var out = c.flush();
        assertEquals(1, out.size());
        assertEquals("tail thought", out.get(0).text());
        assertEquals(List.of(), c.flush(), "a flushed block is not emitted twice");
    }

    @Test
    void nonThoughtUpdatesPassThroughUnchangedWhenNothingIsBuffered() {
        var c = new AcpThoughtCoalescer();
        var out = c.accept(new AgentMessageChunk("s", new TextContent("x")));
        assertEquals(1, out.size());
        assertEquals(HarnessEvent.TOKEN, out.get(0).kind());
    }
}
