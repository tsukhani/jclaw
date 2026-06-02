import channels.TelegramApprovalCallback.Decision;
import channels.TelegramApprovalService;
import channels.TelegramApprovalService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-373: pending-approval resolve / unblock and per-user gating for the
 * generic Telegram approve/deny workflow. Uses the in-memory test seam
 * ({@link TelegramApprovalService#registerForTest}) so no Telegram I/O is
 * exercised.
 */
class TelegramApprovalServiceTest extends UnitTest {

    private static final String USER = "111";
    private static final String OTHER_USER = "222";

    @AfterEach
    void cleanup() {
        TelegramApprovalService.clearAll();
    }

    // ── Resolve unblocks the waiting future ─────────────────────────────

    @Test
    void approveResolvesFutureAndDropsEntry() {
        var future = TelegramApprovalService.registerForTest("ap1", USER);
        assertTrue(TelegramApprovalService.isPending("ap1"));

        var resolution = TelegramApprovalService.resolve("ap1", Decision.APPROVE_ONCE, USER);

        assertTrue(resolution.resolved());
        assertEquals(Outcome.APPROVED_ONCE, resolution.outcome().orElseThrow());
        assertTrue(future.isDone());
        assertEquals(Outcome.APPROVED_ONCE, future.getNow(null));
        // Entry removed so a stale double-tap can't resolve again.
        assertFalse(TelegramApprovalService.isPending("ap1"));
    }

    @Test
    void denyResolvesAsDenied() {
        var future = TelegramApprovalService.registerForTest("ap2", USER);
        var resolution = TelegramApprovalService.resolve("ap2", Decision.DENY, USER);
        assertTrue(resolution.resolved());
        assertEquals(Outcome.DENIED, future.getNow(null));
    }

    @Test
    void sessionAndAlwaysMapToTheirOutcomes() {
        var sessionFuture = TelegramApprovalService.registerForTest("ap-s", USER);
        TelegramApprovalService.resolve("ap-s", Decision.APPROVE_SESSION, USER);
        assertEquals(Outcome.APPROVED_SESSION, sessionFuture.getNow(null));

        var alwaysFuture = TelegramApprovalService.registerForTest("ap-a", USER);
        TelegramApprovalService.resolve("ap-a", Decision.APPROVE_ALWAYS, USER);
        assertEquals(Outcome.APPROVED_ALWAYS, alwaysFuture.getNow(null));
    }

    // ── User gating ─────────────────────────────────────────────────────

    @Test
    void nullAuthorizedUserCannotBeResolvedByAnyone() {
        // A pending approval with no authorized user must FAIL CLOSED — not be
        // resolvable by an arbitrary tapper (regression guard for the resolve gate).
        var future = TelegramApprovalService.registerForTest("ap-null", null);
        var resolution = TelegramApprovalService.resolve("ap-null", Decision.APPROVE_ONCE, USER);
        assertFalse(resolution.resolved(), "null authorizedUserId must reject the tap");
        assertFalse(future.isDone(), "the future must stay unresolved");
        assertTrue(TelegramApprovalService.isPending("ap-null"), "the entry must remain pending");
    }

    @Test
    void rejectsTapFromWrongUser() {
        var future = TelegramApprovalService.registerForTest("ap3", USER);

        var resolution = TelegramApprovalService.resolve("ap3", Decision.APPROVE_ONCE, OTHER_USER);

        assertFalse(resolution.resolved());
        assertTrue(resolution.outcome().isEmpty());
        // The future stays unresolved and the request stays pending — a wrong
        // user can't unblock (or deny) someone else's approval.
        assertFalse(future.isDone());
        assertTrue(TelegramApprovalService.isPending("ap3"));
    }

    @Test
    void correctUserCanStillResolveAfterWrongUserAttempt() {
        var future = TelegramApprovalService.registerForTest("ap4", USER);
        TelegramApprovalService.resolve("ap4", Decision.APPROVE_ONCE, OTHER_USER);
        assertFalse(future.isDone());

        var resolution = TelegramApprovalService.resolve("ap4", Decision.APPROVE_ONCE, USER);
        assertTrue(resolution.resolved());
        assertEquals(Outcome.APPROVED_ONCE, future.getNow(null));
    }

    // ── Unknown / stale id ──────────────────────────────────────────────

    @Test
    void resolveUnknownIdReportsNotPending() {
        var resolution = TelegramApprovalService.resolve("nope", Decision.APPROVE_ONCE, USER);
        assertFalse(resolution.resolved());
        assertTrue(resolution.outcome().isEmpty());
    }

    @Test
    void doubleTapSecondTimeReportsNotPending() {
        TelegramApprovalService.registerForTest("ap5", USER);
        var first = TelegramApprovalService.resolve("ap5", Decision.APPROVE_ONCE, USER);
        var second = TelegramApprovalService.resolve("ap5", Decision.APPROVE_ONCE, USER);
        assertTrue(first.resolved());
        assertFalse(second.resolved());
    }

    // ── Expire ──────────────────────────────────────────────────────────

    @Test
    void expireCompletesFutureWithTimeout() {
        var future = TelegramApprovalService.registerForTest("ap6", USER);
        TelegramApprovalService.expire("ap6", Outcome.TIMED_OUT);
        assertTrue(future.isDone());
        assertEquals(Outcome.TIMED_OUT, future.getNow(null));
        assertFalse(TelegramApprovalService.isPending("ap6"));
    }
}
