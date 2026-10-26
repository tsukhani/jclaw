import channels.WhatsAppChannel;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Duration;
import java.time.Instant;

/**
 * JCLAW-1520: an unseen Cloud-API message is accepted within Meta's 7-day redelivery window,
 * where JCLAW-784 refused anything older than 5 minutes.
 */
class WhatsAppReplayWindowTest extends UnitTest {

    private static final Instant NOW = Instant.parse("2026-10-26T12:00:00Z");
    private static final Duration WINDOW = Duration.ofDays(7);

    @Test
    void theWindowIsMetasSevenDayRedeliveryHorizonAndDelayStartsAtFiveMinutes() {
        assertEquals(604_800L, WhatsAppChannel.REPLAY_WINDOW_SECONDS);
        assertEquals(300L, WhatsAppChannel.DELAYED_AFTER_SECONDS);
    }

    @Test
    void aMessageTenMinutesOldIsFresh() {
        assertTrue(WhatsAppChannel.isFreshTimestamp(NOW.minusSeconds(600), NOW));
    }

    @Test
    void aMessageExactlySevenDaysOldIsFreshAndOneSecondOlderIsNot() {
        assertTrue(WhatsAppChannel.isFreshTimestamp(NOW.minus(WINDOW), NOW));
        assertFalse(WhatsAppChannel.isFreshTimestamp(NOW.minus(WINDOW).minusSeconds(1), NOW));
    }

    @Test
    void aTimestampFiveMinutesAheadIsFreshAndOneSecondFurtherIsNot() {
        assertTrue(WhatsAppChannel.isFreshTimestamp(NOW.plusSeconds(300), NOW));
        assertFalse(WhatsAppChannel.isFreshTimestamp(NOW.plusSeconds(301), NOW));
    }

    @Test
    void aTimestampSevenDaysAheadIsRefusedSoItCannotOutliveItsPrunedId() {
        assertFalse(WhatsAppChannel.isFreshTimestamp(NOW.plus(WINDOW), NOW));
    }
}
