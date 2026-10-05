import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.EventLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

class EventLoggerCaptureMatchingTest extends UnitTest {

    private static void onOtherThread(Runnable r) {
        try {
            Thread.ofVirtual().start(r).join();
        } catch (InterruptedException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void anEventAnotherThreadLogsIsCapturedWithItsFields() {
        var token = "cap-" + UUID.randomUUID();
        var events = EventLogger.captureMatchingForTest(e -> token.equals(e.agentId()),
                _ -> onOtherThread(() -> EventLogger.record("WARN", "capture-test", token, "web", "hello", "the details")));

        assertEquals(List.of(new EventLogger.Captured("WARN", "capture-test", token, "web", "hello", "the details")),
                events);
    }

    @Test
    void anEventThePredicateRejectsIsExcluded() {
        var token = "cap-" + UUID.randomUUID();
        var events = EventLogger.captureMatchingForTest(e -> token.equals(e.agentId()), _ -> {
            onOtherThread(() -> EventLogger.info("capture-test", "other-" + token, null, "not mine"));
            onOtherThread(() -> EventLogger.info("capture-test", token, null, "mine"));
        });

        assertEquals(List.of("mine"), events.stream().map(EventLogger.Captured::message).toList());
    }

    @Test
    void aClearFromAnotherThreadLeavesTheCaptureIntact() {
        var token = "cap-" + UUID.randomUUID();
        var events = EventLogger.captureMatchingForTest(e -> token.equals(e.agentId()), _ -> {
            onOtherThread(() -> EventLogger.info("capture-test", token, null, "before clear"));
            onOtherThread(EventLogger::clear);
        });

        assertEquals(List.of("before clear"), events.stream().map(EventLogger.Captured::message).toList());
    }

    @Test
    void theListenerIsRemovedWhenTheBodyReturns() {
        var token = "cap-" + UUID.randomUUID();
        var live = new ArrayList<List<EventLogger.Captured>>();
        EventLogger.captureMatchingForTest(e -> token.equals(e.agentId()), live::add);

        onOtherThread(() -> EventLogger.info("capture-test", token, null, "after"));

        assertEquals(List.of(), live.getFirst());
    }

    @Test
    void theListenerIsRemovedWhenTheBodyThrows() {
        var token = "cap-" + UUID.randomUUID();
        var live = new ArrayList<List<EventLogger.Captured>>();
        assertThrows(IllegalStateException.class, () -> EventLogger.captureMatchingForTest(
                e -> token.equals(e.agentId()), l -> {
                    live.add(l);
                    throw new IllegalStateException("body failed");
                }));

        onOtherThread(() -> EventLogger.info("capture-test", token, null, "after"));

        assertEquals(List.of(), live.getFirst());
    }
}
