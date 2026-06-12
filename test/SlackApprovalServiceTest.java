import channels.SlackApprovalCallback.Decision;
import channels.SlackApprovalService;
import channels.SlackApprovalService.Outcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-350: pending-approval resolve / unblock and per-owner gating for the Slack
 * approve/deny workflow. Uses the in-memory test seam
 * ({@link SlackApprovalService#registerForTest}, which registers with a null bot
 * token so the message-swap I/O no-ops) — no Slack network calls are exercised.
 */
class SlackApprovalServiceTest extends UnitTest {

    private static final String OWNER = "U111";
    private static final String OTHER_USER = "U222";

    @AfterEach
    void cleanup() {
        SlackApprovalService.clearAll();
    }

    // ── Resolve unblocks the waiting future ─────────────────────────────

    @Test
    void approveResolvesFutureAndDropsEntry() {
        var future = SlackApprovalService.registerForTest("ap1", OWNER);
        assertTrue(SlackApprovalService.isPending("ap1"));

        var resolution = SlackApprovalService.resolve("ap1", Decision.APPROVE_ONCE, OWNER);

        assertTrue(resolution.resolved());
        assertEquals(Outcome.APPROVED_ONCE, resolution.outcome().orElseThrow());
        assertTrue(future.isDone());
        assertEquals(Outcome.APPROVED_ONCE, future.getNow(null));
        // Entry removed so a stale double-tap can't resolve again.
        assertFalse(SlackApprovalService.isPending("ap1"));
    }

    @Test
    void denyResolvesAsDenied() {
        var future = SlackApprovalService.registerForTest("ap2", OWNER);
        var resolution = SlackApprovalService.resolve("ap2", Decision.DENY, OWNER);
        assertTrue(resolution.resolved());
        assertEquals(Outcome.DENIED, future.getNow(null));
    }

    @Test
    void sessionAndAlwaysMapToTheirOutcomes() {
        var sessionFuture = SlackApprovalService.registerForTest("ap-s", OWNER);
        SlackApprovalService.resolve("ap-s", Decision.APPROVE_SESSION, OWNER);
        assertEquals(Outcome.APPROVED_SESSION, sessionFuture.getNow(null));

        var alwaysFuture = SlackApprovalService.registerForTest("ap-a", OWNER);
        SlackApprovalService.resolve("ap-a", Decision.APPROVE_ALWAYS, OWNER);
        assertEquals(Outcome.APPROVED_ALWAYS, alwaysFuture.getNow(null));
    }

    // ── Owner gating ────────────────────────────────────────────────────

    @Test
    void nullAuthorizedUserCannotBeResolvedByAnyone() {
        // A pending approval with no authorized owner must FAIL CLOSED — not be
        // resolvable by an arbitrary tapper (regression guard for the resolve gate).
        var future = SlackApprovalService.registerForTest("ap-null", null);
        var resolution = SlackApprovalService.resolve("ap-null", Decision.APPROVE_ONCE, OWNER);
        assertFalse(resolution.resolved(), "null authorizedUserId must reject the tap");
        assertFalse(future.isDone(), "the future must stay unresolved");
        assertTrue(SlackApprovalService.isPending("ap-null"), "the entry must remain pending");
    }

    @Test
    void rejectsTapFromWrongUser() {
        var future = SlackApprovalService.registerForTest("ap3", OWNER);

        var resolution = SlackApprovalService.resolve("ap3", Decision.APPROVE_ONCE, OTHER_USER);

        assertFalse(resolution.resolved());
        assertTrue(resolution.outcome().isEmpty());
        // The future stays unresolved and the request stays pending — a non-owner
        // can't unblock (or deny) someone else's approval.
        assertFalse(future.isDone());
        assertTrue(SlackApprovalService.isPending("ap3"));
    }

    @Test
    void correctUserCanStillResolveAfterWrongUserAttempt() {
        var future = SlackApprovalService.registerForTest("ap4", OWNER);
        SlackApprovalService.resolve("ap4", Decision.APPROVE_ONCE, OTHER_USER);
        assertFalse(future.isDone());

        var resolution = SlackApprovalService.resolve("ap4", Decision.APPROVE_ONCE, OWNER);
        assertTrue(resolution.resolved());
        assertEquals(Outcome.APPROVED_ONCE, future.getNow(null));
    }

    // ── Unknown / stale id ──────────────────────────────────────────────

    @Test
    void resolveUnknownIdReportsNotPending() {
        var resolution = SlackApprovalService.resolve("nope", Decision.APPROVE_ONCE, OWNER);
        assertFalse(resolution.resolved());
        assertTrue(resolution.outcome().isEmpty());
    }

    @Test
    void doubleTapSecondTimeReportsNotPending() {
        SlackApprovalService.registerForTest("ap5", OWNER);
        var first = SlackApprovalService.resolve("ap5", Decision.APPROVE_ONCE, OWNER);
        var second = SlackApprovalService.resolve("ap5", Decision.APPROVE_ONCE, OWNER);
        assertTrue(first.resolved());
        assertFalse(second.resolved());
    }

    // ── Expire ──────────────────────────────────────────────────────────

    @Test
    void expireCompletesFutureWithTimeout() {
        var future = SlackApprovalService.registerForTest("ap6", OWNER);
        SlackApprovalService.expire("ap6", Outcome.TIMED_OUT);
        assertTrue(future.isDone());
        assertEquals(Outcome.TIMED_OUT, future.getNow(null));
        assertFalse(SlackApprovalService.isPending("ap6"));
    }
}
