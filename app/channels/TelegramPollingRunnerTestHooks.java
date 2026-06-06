package channels;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

import java.util.function.LongSupplier;

/**
 * Public bridge into {@link TelegramPollingRunner}'s package-private
 * {@code setAppForTest} (JCLAW-316). JClaw's tests live in the default
 * package, so they can't call the package-private setter directly; this
 * class lives in the {@code channels} package and re-exposes the setter
 * as a public method. Mirrors {@code services.search.MessageSearchTestHooks}.
 *
 * <p>Production code never touches this class. The compiler can't forbid
 * that — Java has no "tests-only" visibility — but the name makes
 * accidental production use obvious.
 */
public final class TelegramPollingRunnerTestHooks {

    private TelegramPollingRunnerTestHooks() {}

    /** Install or clear the long-polling app reference. Pass {@code null} to clear. */
    public static void setApp(TelegramBotsLongPollingApplication app) {
        TelegramPollingRunner.setAppForTest(app);
    }

    /**
     * Clear the runner's static state (active map + cooldown map + app
     * reference) without shutting down the background scheduler. Tests
     * call this in {@code @BeforeEach} for isolation; production code
     * uses {@link TelegramPollingRunner#stop} for full drain at app
     * shutdown.
     */
    public static void clear() {
        TelegramPollingRunner.clearForTest();
    }

    // ===== JCLAW-363 liveness-watchdog seams =====

    /**
     * Override the watchdog timeout (ms), bypassing {@code application.conf}.
     * Pass {@code null} to fall back to the configured value/default. {@code 0}
     * disables the watchdog.
     */
    public static void setWatchdogMs(Long ms) {
        TelegramPollingRunner.setWatchdogMsForTest(ms);
    }

    /**
     * Swap the watchdog/progress time source so a test can advance "now" past
     * the timeout without sleeping. Pass {@code null} to restore the wall clock.
     */
    public static void setClock(LongSupplier supplier) {
        TelegramPollingRunner.setClockForTest(supplier);
    }

    /** Stamp {@code token}'s last-progress timestamp to the current clock value. */
    public static void markProgress(String token) {
        TelegramPollingRunner.markProgressForTest(token);
    }

    /** Read {@code token}'s last-progress timestamp, or {@code null} if untracked. */
    public static Long lastProgress(String token) {
        return TelegramPollingRunner.lastProgressForTest(token);
    }

    /** Run one watchdog pass synchronously on the calling thread. */
    public static void runWatchdogTick() {
        TelegramPollingRunner.runWatchdogTickForTest();
    }

    /** Is the periodic watchdog tick currently scheduled? */
    public static boolean isWatchdogRunning() {
        return TelegramPollingRunner.isWatchdogRunningForTest();
    }

    /**
     * Stamp a cooldown on {@code token} for {@code ms} from now without going
     * through an unregister — lets a test put a still-active binding's token
     * into cooldown to verify the watchdog's cooldown guard.
     */
    public static void stampCooldown(String token, long ms) {
        TelegramPollingRunner.stampCooldownForTest(token, ms);
    }

    /**
     * JCLAW-429: force {@code token}'s consecutive-rebuild count so a test can
     * drive the watchdog rebuild cap without sitting through real 30 s cooldowns.
     */
    public static void setRebuildCount(String token, int count) {
        TelegramPollingRunner.setRebuildCountForTest(token, count);
    }
}
