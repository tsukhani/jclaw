import channels.WhatsAppCobaltChannel;
import channels.WhatsAppCobaltRunner;
import models.WhatsAppBinding;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A send held for a WhatsApp Web session that is still connecting (JF-278): it runs once the session logs in, or is
 * given up after the wait, and never both. The binding ids are ones no session uses, so nothing here is connected
 * until a test says the session logged in.
 */
class WhatsAppHeldSendTest extends UnitTest {

    private static long unusedBindingId() {
        return 9_000_000_000L + ThreadLocalRandom.current().nextLong(1_000_000_000L);
    }

    @Test
    void aHeldSendWaitsForTheLoginThenRunsOnceAndNeverExpires() throws Exception {
        long id = unusedBindingId();
        var sent = new AtomicInteger();
        var sentLatch = new CountDownLatch(1);
        var expired = new AtomicInteger();

        WhatsAppCobaltRunner.whenConnected(id, () -> {
            sent.incrementAndGet();
            sentLatch.countDown();
        }, expired::incrementAndGet, Duration.ofMillis(400));
        Thread.sleep(150);
        assertEquals(0, sent.get(), "held until the session logs in");

        WhatsAppCobaltRunner.sessionConnected(id);
        assertTrue(sentLatch.await(5, TimeUnit.SECONDS));
        WhatsAppCobaltRunner.sessionConnected(id);
        Thread.sleep(600);

        assertEquals(1, sent.get());
        assertEquals(0, expired.get(), "sent, so the wait running out gives nothing up");
    }

    @Test
    void aSessionThatNeverLogsInGivesTheSendUpAfterTheWait() throws Exception {
        long id = unusedBindingId();
        var sent = new AtomicInteger();
        var expiredLatch = new CountDownLatch(1);
        var expired = new AtomicInteger();

        WhatsAppCobaltRunner.whenConnected(id, sent::incrementAndGet, () -> {
            expired.incrementAndGet();
            expiredLatch.countDown();
        }, Duration.ofMillis(200));

        assertTrue(expiredLatch.await(5, TimeUnit.SECONDS));
        WhatsAppCobaltRunner.sessionConnected(id);
        Thread.sleep(200);
        assertEquals(1, expired.get());
        assertEquals(0, sent.get(), "a login after it was given up sends nothing");
    }

    @Test
    void aLoginForAnotherBindingSendsNothing() throws Exception {
        long id = unusedBindingId();
        var sent = new AtomicInteger();

        WhatsAppCobaltRunner.whenConnected(id, sent::incrementAndGet, () -> { }, Duration.ofSeconds(5));
        WhatsAppCobaltRunner.sessionConnected(id + 1);
        Thread.sleep(200);

        assertEquals(0, sent.get());
        WhatsAppCobaltRunner.sessionConnected(id);
    }

    @Test
    void aNoticeStillUndeliverableWhenTheSessionLogsInIsReportedUndelivered() throws Exception {
        var binding = new WhatsAppBinding();
        binding.id = unusedBindingId();
        var channel = WhatsAppCobaltChannel.forBinding(binding);
        var undelivered = new CountDownLatch(1);

        channel.sendTextWhenConnected("15550001111@s.whatsapp.net", "This response was interrupted", null, undelivered::countDown);
        Thread.sleep(150);
        assertEquals(1, undelivered.getCount(), "held, not reported, while the session connects");

        // The login arrives, but this binding has no live session to send through, so the send fails.
        WhatsAppCobaltRunner.sessionConnected(binding.id);
        assertTrue(undelivered.await(10, TimeUnit.SECONDS));
    }
}
