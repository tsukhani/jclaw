package channels;

import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;

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
     * Clear the runner's static state (active map + session map + app
     * reference). Tests call this in {@code @BeforeEach} for isolation;
     * production code uses {@link TelegramPollingRunner#stop} for full drain at
     * app shutdown.
     */
    public static void clear() {
        TelegramPollingRunner.clearForTest();
    }

    /**
     * JCLAW-434/436: stub the "has Telegram rejected this token?" probe so a test
     * can drive the health-probe's auto-disable path without dialing
     * {@code api.telegram.org}. Pass {@code null} to restore the real
     * {@code getMe} probe.
     */
    public static void setTokenRejectedCheck(java.util.function.Predicate<String> p) {
        TelegramPollingRunner.setTokenRejectedCheckForTest(p);
    }

    /** JCLAW-436: run the standalone token health-probe synchronously. */
    public static void runTokenHealthProbe() {
        TelegramPollingRunner.probeTokenHealth();
    }
}
