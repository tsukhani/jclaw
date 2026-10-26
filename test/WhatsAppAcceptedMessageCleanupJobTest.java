import jobs.WhatsAppAcceptedMessageCleanupJob;
import models.WhatsAppAcceptedMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.ConfigService;
import services.EventLogger;
import services.Tx;
import utils.AppClock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * JCLAW-1520: {@link WhatsAppAcceptedMessageCleanupJob} prunes accepted WhatsApp message ids, never
 * inside Meta's 7-day redelivery window. The job runs at the real time, so the rows other classes
 * claim meanwhile are younger than any cutoff here.
 */
class WhatsAppAcceptedMessageCleanupJobTest extends UnitTest {

    private static final String CONFIG_KEY = "whatsapp.acceptedMessages.retentionDays";

    private static <T> T onFreshThread(Supplier<T> block) {
        var result = new CompletableFuture<T>();
        Thread.ofPlatform().start(() -> {
            try {
                result.complete(block.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result.join();
    }

    private static void setRetention(String value) {
        onFreshThread(() -> {
            ConfigService.set(CONFIG_KEY, value);
            return null;
        });
    }

    @BeforeEach
    @AfterEach
    void clearRetention() {
        onFreshThread(() -> {
            ConfigService.delete(CONFIG_KEY);
            return null;
        });
    }

    private static String seedAcceptedAt(Instant at) {
        var id = "wamid.prune-" + System.nanoTime();
        onFreshThread(() -> Tx.run(() -> {
            var row = new WhatsAppAcceptedMessage();
            row.wamid = id;
            row.acceptedAt = at;
            row.save();
            return null;
        }));
        return id;
    }

    private static boolean isRecorded(String id) {
        return onFreshThread(() -> Tx.run(() -> WhatsAppAcceptedMessage.isRecorded(id)));
    }

    private static void runJobAt(Instant now) {
        onFreshThread(() -> {
            AppClock.runWith(Clock.fixed(now, ZoneOffset.UTC), () -> new WhatsAppAcceptedMessageCleanupJob().doJob());
            return null;
        });
    }

    private static Instant daysBefore(Instant now, long days) {
        return now.minus(Duration.ofDays(days));
    }

    @Test
    void theRetentionFloorAndDefaultAreOneDayPastTheSevenDayWindow() {
        assertEquals(8, WhatsAppAcceptedMessageCleanupJob.MIN_RETENTION_DAYS);
        assertEquals(8, WhatsAppAcceptedMessageCleanupJob.DEFAULT_RETENTION_DAYS);
    }

    @Test
    void theDefaultPrunesAnIdJustOlderThanEightDaysAndKeepsOneJustYounger() {
        var now = Instant.now();
        var older = seedAcceptedAt(daysBefore(now, 8).minusSeconds(60));
        var younger = seedAcceptedAt(daysBefore(now, 8).plusSeconds(60));

        runJobAt(now);

        assertFalse(isRecorded(older));
        assertTrue(isRecorded(younger));
    }

    @Test
    void aLongerConfiguredRetentionKeepsWhatTheDefaultWouldPrune() {
        setRetention("30");
        var now = Instant.now();
        var nineDays = seedAcceptedAt(daysBefore(now, 9));
        var thirtyOneDays = seedAcceptedAt(daysBefore(now, 31));

        runJobAt(now);

        assertTrue(isRecorded(nineDays));
        assertFalse(isRecorded(thirtyOneDays));
    }

    @Test
    void aRetentionInsideTheWindowStillKeepsAnIdTheWindowCouldAccept() {
        setRetention("3");
        var now = Instant.now();
        var sixDays = seedAcceptedAt(daysBefore(now, 6));

        runJobAt(now);

        assertTrue(isRecorded(sixDays));
    }

    @Test
    void retentionZeroPrunesNothing() {
        setRetention("0");
        var now = Instant.now();
        var ancient = seedAcceptedAt(daysBefore(now, 400));

        runJobAt(now);

        assertTrue(isRecorded(ancient));
    }

    private static List<String> warnedWhileResolving(int expectedDays) {
        return EventLogger.captureForTest(() ->
                        assertEquals(expectedDays, WhatsAppAcceptedMessageCleanupJob.resolveRetentionDays()))
                .stream().map(EventLogger.Captured::message).toList();
    }

    @Test
    void aRetentionBelowEightDaysIsRaisedToEightWithAWarnAndEightIsKept() {
        setRetention("7");
        assertEquals(List.of(CONFIG_KEY + " is 7, inside the 7-day redelivery window; using 8"),
                warnedWhileResolving(8));
        setRetention("1");
        assertEquals(List.of(CONFIG_KEY + " is 1, inside the 7-day redelivery window; using 8"),
                warnedWhileResolving(8));
        setRetention("8");
        assertEquals(List.of(), warnedWhileResolving(8));
        setRetention("9");
        assertEquals(List.of(), warnedWhileResolving(9));
    }

    @Test
    void theRestOfTheResolverIsRetentionDays() {
        assertEquals(List.of(), warnedWhileResolving(8));
        setRetention("0");
        assertEquals(List.of(), warnedWhileResolving(0));
        setRetention("-1");
        assertEquals(List.of(CONFIG_KEY + " is negative (-1); treating it as 0 (cleanup disabled)"),
                warnedWhileResolving(0));
        setRetention("3650");
        assertEquals(List.of(), warnedWhileResolving(3650));
        setRetention("3651");
        assertEquals(List.of(CONFIG_KEY + " out of range (3651); using default 8. Allowed: 0 (disabled) or 1..3650."),
                warnedWhileResolving(8));
        setRetention("eight");
        assertEquals(List.of(CONFIG_KEY + " is not numeric ('eight'); using default 8"), warnedWhileResolving(8));
    }
}
