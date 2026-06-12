import channels.SlackSocketModeRunner;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.util.ArrayList;

/**
 * JCLAW-351: Socket Mode frame routing. {@link SlackSocketModeRunner#routeMessage} parses
 * a raw socket frame, acks it, and routes the payload — exercised here with an injected
 * ack-sender so no WebSocket is dialed. (The reconcile lifecycle mutates process-global
 * connection state, which would race the concurrent test lanes, so it's verified live —
 * it mirrors the proven {@code TelegramPollingRunner} reconcile.)
 */
class SlackSocketModeRunnerTest extends UnitTest {

    @Test
    void acksEventsApiEnvelope() {
        var acks = new ArrayList<String>();
        SlackSocketModeRunner.routeMessage(999L, acks::add,
                "{\"type\":\"events_api\",\"envelope_id\":\"e1\",\"payload\":{\"type\":\"event_callback\","
                        + "\"event\":{\"type\":\"message\",\"channel\":\"C1\",\"user\":\"U1\",\"text\":\"hi\"}}}");
        assertEquals(1, acks.size(), "an events_api envelope must be acked");
        assertTrue(acks.get(0).contains("e1"), "the ack must echo the envelope_id");
    }

    @Test
    void acksInteractiveEnvelope() {
        var acks = new ArrayList<String>();
        SlackSocketModeRunner.routeMessage(999L, acks::add,
                "{\"type\":\"interactive\",\"envelope_id\":\"e2\",\"payload\":{\"type\":\"block_actions\"}}");
        assertEquals(1, acks.size());
        assertTrue(acks.get(0).contains("e2"));
    }

    @Test
    void ignoresHelloAndDisconnect() {
        var acks = new ArrayList<String>();
        SlackSocketModeRunner.routeMessage(999L, acks::add, "{\"type\":\"hello\"}");
        SlackSocketModeRunner.routeMessage(999L, acks::add, "{\"type\":\"disconnect\",\"reason\":\"refresh\"}");
        assertTrue(acks.isEmpty(), "hello/disconnect frames carry no envelope and are not acked");
    }

    @Test
    void swallowsNonJsonFrame() {
        var acks = new ArrayList<String>();
        SlackSocketModeRunner.routeMessage(999L, acks::add, "not json"); // must not throw
        assertTrue(acks.isEmpty());
    }
}
