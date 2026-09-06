package channels;

import org.jspecify.annotations.Nullable;

/**
 * Public bridge into {@link TelegramStreamingSink}'s package-private test accessors
 * (JCLAW-771). JClaw's tests live in the default package, so they cannot call
 * package-private members of {@code channels} directly; this class lives in the
 * package and re-exposes them. Mirrors {@link TelegramPollingRunnerTestHooks}.
 *
 * <p>Production code never touches this class. The compiler can't forbid that —
 * Java has no "tests-only" visibility — but the name makes accidental production
 * use obvious.
 *
 * <p>{@link #flush} and {@link #pendingLength} exist to replace
 * {@code getDeclaredMethod("flush")} / {@code getDeclaredField("pending")} reflection
 * in the integration test. A private-member lookup compiles fine and then throws
 * {@code NoSuchMethodException} at runtime the moment the member is renamed or moved
 * to a collaborator — invisible to {@code compileTestJava}, so it only surfaces under
 * a full test run. Routing through this bridge makes that a compile error instead.
 */
public final class TelegramStreamingSinkTestHooks {

    private TelegramStreamingSinkTestHooks() {}

    public static @Nullable Integer messageId(TelegramStreamingSink sink) { return sink.messageIdForTest(); }

    public static @Nullable Integer replyToMessageId(TelegramStreamingSink sink) { return sink.replyToMessageIdForTest(); }

    public static @Nullable Integer messageThreadId(TelegramStreamingSink sink) { return sink.messageThreadIdForTest(); }

    public static boolean streamCapReached(TelegramStreamingSink sink) { return sink.streamCapReachedForTest(); }

    public static boolean sealed(TelegramStreamingSink sink) { return sink.sealedForTest(); }

    public static String lastSentText(TelegramStreamingSink sink) { return sink.lastSentTextForTest(); }

    public static long lastSentAt(TelegramStreamingSink sink) { return sink.lastSentAtForTest(); }

    public static long currentThrottleMs(TelegramStreamingSink sink) { return sink.currentThrottleMsForTest(); }

    public static boolean typingHeartbeatActive(TelegramStreamingSink sink) { return sink.typingHeartbeatActiveForTest(); }

    public static void setTypingHeartbeatMaxMs(TelegramStreamingSink sink, long ms) {
        sink.setTypingHeartbeatMaxMsForTest(ms);
    }

    /** Drive one flush cycle synchronously, as the scheduler would. */
    public static void flush(TelegramStreamingSink sink) { sink.flushForTest(); }

    /** Buffer text without scheduling a flush, so a hand-driven flush is the only one in flight. */
    public static void appendPending(TelegramStreamingSink sink, String text) {
        sink.appendPendingForTest(text);
    }

    /** Clear the process-global notifier rate limiter between tests. */
    public static void clearNotifierRateLimiter() {
        TelegramStreamingSink.clearNotifierRateLimiterForTest();
    }
}
