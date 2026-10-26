import models.WhatsAppAcceptedMessage;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.Tx;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * JCLAW-1520: {@link WhatsAppAcceptedMessage#claim} is the WhatsApp inbound dedup. Each claim runs
 * on its own thread in its own transaction, as two webhook deliveries do.
 */
class WhatsAppAcceptedMessageTest extends UnitTest {

    private static String uniqueId() {
        return "wamid.claim-" + System.nanoTime();
    }

    private static <T> CompletableFuture<T> fork(Supplier<T> block) {
        var result = new CompletableFuture<T>();
        Thread.ofPlatform().start(() -> {
            try {
                result.complete(block.get());
            } catch (Throwable t) {
                result.completeExceptionally(t);
            }
        });
        return result;
    }

    private static <T> T inFreshTx(Supplier<T> block) {
        return fork(() -> Tx.run(block::get)).join();
    }

    private static boolean claim(String id) {
        return inFreshTx(() -> WhatsAppAcceptedMessage.claim(id, Instant.now()));
    }

    private static boolean isRecorded(String id) {
        return inFreshTx(() -> WhatsAppAcceptedMessage.isRecorded(id));
    }

    @Test
    void theFirstClaimOfAnIdWinsAndEveryLaterOneLoses() {
        var id = uniqueId();
        assertFalse(isRecorded(id));

        assertTrue(claim(id));
        assertTrue(isRecorded(id));
        assertFalse(claim(id));
        assertFalse(claim(id));
    }

    @Test
    void distinctIdsAreClaimedIndependently() {
        var a = uniqueId();
        var b = uniqueId();

        assertTrue(claim(a));
        assertTrue(claim(b));
        assertFalse(isRecorded(uniqueId()));
    }

    @Test
    void aClaimRacingAnUncommittedClaimOfTheSameIdLoses() throws Exception {
        var id = uniqueId();
        var holderClaimed = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var holder = fork(() -> Tx.run(() -> {
            var won = WhatsAppAcceptedMessage.claim(id, Instant.now());
            holderClaimed.countDown();
            try {
                assertTrue(release.await(10, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return won;
        }));
        assertTrue(holderClaimed.await(10, TimeUnit.SECONDS));

        var racer = fork(() -> Tx.run(() -> WhatsAppAcceptedMessage.claim(id, Instant.now())));
        try {
            // H2 holds the racer on the uncommitted row until the holder ends, or reports it at once.
            racer.get(500, TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            // still waiting on the holder's transaction
        }
        release.countDown();

        assertTrue(holder.get(10, TimeUnit.SECONDS));
        assertFalse(racer.get(10, TimeUnit.SECONDS), "two concurrent deliveries of one id dispatch once");
    }
}
