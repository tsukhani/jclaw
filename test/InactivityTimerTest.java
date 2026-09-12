import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.InactivityTimer;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

/** JCLAW-1204: the chat stream's inactivity budget counts silence, not elapsed time. */
class InactivityTimerTest extends UnitTest {

    @Test
    void touchesInsideTheBudgetKeepItAliveAndSilenceFiresItOnce() throws Exception {
        var fired = new AtomicInteger();
        var timer = InactivityTimer.start(Duration.ofMillis(400), Duration.ofMillis(10), fired::incrementAndGet);
        // 1.2 s of activity, four times the budget: never fires.
        for (int i = 0; i < 24; i++) {
            Thread.sleep(50);
            timer.touch();
        }
        assertEquals(0, fired.get(), "activity inside the budget must not expire the timer");
        assertFalse(timer.fired());
        // Then silence.
        Thread.sleep(900);
        assertEquals(1, fired.get(), "silence past the budget fires exactly once");
        assertTrue(timer.fired());
        timer.touch();
        Thread.sleep(600);
        assertEquals(1, fired.get(), "a fired timer stays fired");
    }

    @Test
    void cancelStopsItForGood() throws Exception {
        var fired = new AtomicInteger();
        var timer = InactivityTimer.start(Duration.ofMillis(200), Duration.ofMillis(10), fired::incrementAndGet);
        timer.cancel();
        Thread.sleep(500);
        assertEquals(0, fired.get());
        timer.touch();
        Thread.sleep(500);
        assertEquals(0, fired.get(), "a cancelled timer cannot be re-armed");
    }
}
