import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCall;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallLocation;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallStatus;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolCallUpdateNotification;
import com.agentclientprotocol.sdk.spec.AcpSchema.ToolKind;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import tools.AcpToolCallTracker;
import tools.HarnessEvent;

import java.util.List;

/**
 * The update shapes are the ones claude-agent-acp 0.89.1 sent for a write, a shell command and a
 * failed read: a placeholder announcement, then an update carrying the title and location, then
 * the final status on an update that carries nothing else.
 */
class AcpToolCallTrackerTest extends UnitTest {

    private static ToolCall announced(String id, String title, ToolKind kind) {
        return new ToolCall(null, id, title, null, kind, ToolCallStatus.PENDING, null, List.of(), null, null, null);
    }

    private static ToolCallUpdateNotification update(String id, @Nullable String title, @Nullable ToolCallStatus status,
                                                     @Nullable List<ToolCallLocation> locations) {
        return new ToolCallUpdateNotification(null, id, title, null, null, status, null, locations, null, null, null);
    }

    private static List<ToolCallLocation> at(String path) {
        return List.of(new ToolCallLocation(path, null));
    }

    @Test
    void aWriteIsReportedWithItsFileOnceItCompletes() {
        var tracker = new AcpToolCallTracker();
        tracker.started(announced("w1", "Preparing file…", ToolKind.EDIT));
        assertNull(tracker.updated(update("w1", "Write probe.txt", null, at("/work/probe.txt"))));
        assertNull(tracker.updated(update("w1", null, null, null)));

        var done = tracker.updated(update("w1", null, ToolCallStatus.COMPLETED, null));

        assertNotNull(done);
        assertEquals(HarnessEvent.TOOL_CALL, done.kind());
        assertEquals("Write probe.txt: /work/probe.txt (completed)", done.text());
    }

    @Test
    void aCommandIsReportedByItsTitleAlone() {
        var tracker = new AcpToolCallTracker();
        tracker.started(announced("c1", "Terminal", ToolKind.EXECUTE));
        assertNull(tracker.updated(update("c1", "cat probe.txt", null, null)));

        var done = tracker.updated(update("c1", null, ToolCallStatus.COMPLETED, null));

        assertNotNull(done);
        assertEquals("cat probe.txt (completed)", done.text());
    }

    @Test
    void aFailedCallSaysItFailed() {
        var tracker = new AcpToolCallTracker();
        tracker.started(announced("r1", "Read File", ToolKind.READ));
        assertNull(tracker.updated(update("r1", "Read missing.txt", null, at("/work/missing.txt"))));

        var done = tracker.updated(update("r1", null, ToolCallStatus.FAILED, null));

        assertNotNull(done);
        assertEquals("Read missing.txt: /work/missing.txt (failed)", done.text());
    }

    @Test
    void twoCallsInFlightKeepTheirOwnTitles() {
        var tracker = new AcpToolCallTracker();
        tracker.started(announced("a", "Preparing file…", ToolKind.EDIT));
        tracker.started(announced("b", "Terminal", ToolKind.EXECUTE));
        tracker.updated(update("a", "Write a.txt", null, at("/work/a.txt")));
        tracker.updated(update("b", "ls", null, null));

        var b = tracker.updated(update("b", null, ToolCallStatus.COMPLETED, null));
        var a = tracker.updated(update("a", null, ToolCallStatus.COMPLETED, null));

        assertNotNull(a);
        assertNotNull(b);
        assertEquals("ls (completed)", b.text());
        assertEquals("Write a.txt: /work/a.txt (completed)", a.text());
    }

    @Test
    void aPathAlreadyInTheTitleIsNotRepeatedAndAnUnannouncedCallStillReports() {
        var tracker = new AcpToolCallTracker();

        var done = tracker.updated(update("x", "Write /work/a.txt", ToolCallStatus.COMPLETED, at("/work/a.txt")));

        assertNotNull(done);
        assertEquals("Write /work/a.txt (completed)", done.text());
    }

    @Test
    void aCallIsReportedOnce() {
        var tracker = new AcpToolCallTracker();
        tracker.started(announced("o", "Terminal", ToolKind.EXECUTE));
        tracker.updated(update("o", "pwd", null, null));
        assertNotNull(tracker.updated(update("o", null, ToolCallStatus.COMPLETED, null)));

        var again = tracker.updated(update("o", null, null, null));

        assertNull(again, "an update after the finishing one must not report the call a second time");
    }
}
