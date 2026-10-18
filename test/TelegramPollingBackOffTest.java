import channels.TelegramPollingRunnerTestHooks;
import org.junit.jupiter.api.Test;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.interfaces.BackOff;
import play.test.UnitTest;

import java.util.function.Supplier;

class TelegramPollingBackOffTest extends UnitTest {

    private static final long A_MINUTE = 60_000;

    @Test
    void thePollingAppNeverWaitsLongerThanAMinuteBetweenRetries() throws Exception {
        var app = TelegramPollingRunnerTestHooks.newPollingApp();
        var field = TelegramBotsLongPollingApplication.class.getDeclaredField("backOffSupplier");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        BackOff backOff = ((Supplier<BackOff>) field.get(app)).get();

        // From the 13th retry the SDK's interval is 60 s, jittered to as much as 90 s.
        for (int retry = 1; retry <= 100; retry++) {
            long wait = backOff.nextBackOffMillis();
            assertTrue(wait <= A_MINUTE, "retry " + retry + " waits " + wait + " ms");
        }
    }

    @Test
    void theSdksFifteenMinuteWaitBecomesAMinute() {
        var curve = new FixedBackOff(900_000);
        var capped = TelegramPollingRunnerTestHooks.cappedBackOff(curve);

        assertEquals(A_MINUTE, capped.nextBackOffMillis());
        curve.wait = 750;
        assertEquals(750, capped.nextBackOffMillis());
        capped.reset();
        assertEquals(1, curve.resets);
    }

    private static final class FixedBackOff implements BackOff {
        long wait;
        int resets;

        FixedBackOff(long wait) {
            this.wait = wait;
        }

        @Override
        public void reset() {
            resets++;
        }

        @Override
        public long nextBackOffMillis() {
            return wait;
        }
    }
}
