package channels;

/**
 * Public bridge into {@link TelegramBotIdentity}'s package-private
 * {@code clearForTest} (JCLAW-371). JClaw's tests live in the default package,
 * so they can't reach the package-private cache-eviction helper directly; this
 * class lives in the {@code channels} package and re-exposes it. Mirrors
 * {@link TelegramPollingRunnerTestHooks}.
 *
 * <p>Production code never touches this class — the name makes accidental
 * production use obvious.
 */
public final class TelegramBotIdentityTestHooks {

    private TelegramBotIdentityTestHooks() {}

    /** Drop the cached identity for {@code botToken} so the next resolve re-fetches. */
    public static void clear(String botToken) {
        TelegramBotIdentity.clearForTest(botToken);
    }
}
