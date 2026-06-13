import models.WhatsAppConversationWindow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.Fixtures;
import play.test.UnitTest;
import services.Tx;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Unit coverage for {@link WhatsAppConversationWindow} (JCLAW-447) — the 24h
 * customer-service window store. Pins the upsert (insert-then-advance) and the
 * within-window predicate boundaries (no row, just-inside, just-outside).
 */
class WhatsAppConversationWindowTest extends UnitTest {

    private static final Long BINDING = 7L;
    private static final String PEER = "447900000001";

    @BeforeEach
    void setup() {
        Fixtures.deleteDatabase();
    }

    @Test
    void recordInboundInsertsThenAdvances() {
        var t0 = Instant.parse("2026-06-10T10:00:00Z");
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(BINDING, PEER, t0));

        var first = Tx.run(() -> WhatsAppConversationWindow.findRow(BINDING, PEER));
        assertNotNull(first, "first inbound inserts a row");
        assertEquals(t0, first.lastUserMessageAt);

        var t1 = t0.plus(2, ChronoUnit.HOURS);
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(BINDING, PEER, t1));

        // Still exactly one row for the (binding, peer) pair, timestamp advanced.
        long count = Tx.run(() -> WhatsAppConversationWindow.count(
                "bindingId = ?1 and peerId = ?2", BINDING, PEER));
        assertEquals(1, count, "upsert must not create a second row");
        var advanced = Tx.run(() -> WhatsAppConversationWindow.findRow(BINDING, PEER));
        assertEquals(t1, advanced.lastUserMessageAt, "timestamp advanced to the latest inbound");
    }

    @Test
    void noRowMeansOutsideWindow() {
        boolean within = Tx.run(() ->
                WhatsAppConversationWindow.isWithinWindow(BINDING, "never-messaged", Instant.now()));
        assertFalse(within, "a peer that never messaged is outside the window (template required)");
    }

    @Test
    void withinTwentyFourHoursIsInsideTheWindow() {
        var now = Instant.parse("2026-06-10T12:00:00Z");
        var twentyThreeHoursAgo = now.minus(23, ChronoUnit.HOURS);
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(BINDING, PEER, twentyThreeHoursAgo));

        assertTrue(Tx.run(() -> WhatsAppConversationWindow.isWithinWindow(BINDING, PEER, now)),
                "an inbound 23h ago is inside the 24h window");
    }

    @Test
    void pastTwentyFourHoursIsOutsideTheWindow() {
        var now = Instant.parse("2026-06-10T12:00:00Z");
        var twentyFiveHoursAgo = now.minus(25, ChronoUnit.HOURS);
        Tx.run(() -> WhatsAppConversationWindow.recordInbound(BINDING, PEER, twentyFiveHoursAgo));

        assertFalse(Tx.run(() -> WhatsAppConversationWindow.isWithinWindow(BINDING, PEER, now)),
                "an inbound 25h ago has fallen out of the 24h window");
    }

    @Test
    void recordInboundIgnoresBlankPeerAndNulls() {
        Tx.run(() -> {
            WhatsAppConversationWindow.recordInbound(null, PEER, Instant.now());
            WhatsAppConversationWindow.recordInbound(BINDING, "", Instant.now());
            WhatsAppConversationWindow.recordInbound(BINDING, PEER, null);
        });
        long count = Tx.run(() -> WhatsAppConversationWindow.count());
        assertEquals(0, count, "null/blank inputs must not write a row");
    }
}
