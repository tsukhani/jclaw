import channels.SlackApprovalCallback;
import channels.SlackApprovalCallback.Decision;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-350: action_id round-trip and malformed-payload rejection for the Slack
 * approve/deny Block Kit encoding (the Slack analog of
 * {@link channels.TelegramApprovalCallback}).
 */
class SlackApprovalCallbackTest extends UnitTest {

    // ── Round trip ─────────────────────────────────────────────────────

    @Test
    void approveOnceRoundTrip() {
        var encoded = SlackApprovalCallback.encodeApproveOnce("abc12345");
        assertEquals("sa:o:abc12345", encoded);
        var parsed = SlackApprovalCallback.parse(encoded).orElseThrow();
        assertEquals(Decision.APPROVE_ONCE, parsed.decision());
        assertEquals("abc12345", parsed.approvalId());
    }

    @Test
    void approveSessionRoundTrip() {
        var parsed = SlackApprovalCallback.parse(
                SlackApprovalCallback.encodeApproveSession("id")).orElseThrow();
        assertEquals(Decision.APPROVE_SESSION, parsed.decision());
        assertEquals("id", parsed.approvalId());
    }

    @Test
    void approveAlwaysRoundTrip() {
        var parsed = SlackApprovalCallback.parse(
                SlackApprovalCallback.encodeApproveAlways("id")).orElseThrow();
        assertEquals(Decision.APPROVE_ALWAYS, parsed.decision());
    }

    @Test
    void denyRoundTrip() {
        var encoded = SlackApprovalCallback.encodeDeny("abc12345");
        assertEquals("sa:d:abc12345", encoded);
        var parsed = SlackApprovalCallback.parse(encoded).orElseThrow();
        assertEquals(Decision.DENY, parsed.decision());
        assertEquals("abc12345", parsed.approvalId());
    }

    @Test
    void idWithColonRoundTrips() {
        // Everything after the tag is the id, verbatim — a colon in the id survives.
        var parsed = SlackApprovalCallback.parse("sa:o:weird:id").orElseThrow();
        assertEquals(Decision.APPROVE_ONCE, parsed.decision());
        assertEquals("weird:id", parsed.approvalId());
    }

    // ── Malformed input ────────────────────────────────────────────────

    @Test
    void rejectsPayloadWithoutPrefix() {
        // The Telegram prefix (a:) must NOT be mistaken for a Slack approval.
        assertTrue(SlackApprovalCallback.parse("a:o:42").isEmpty());
        assertTrue(SlackApprovalCallback.parse("o:id").isEmpty());
        assertTrue(SlackApprovalCallback.parse("").isEmpty());
        assertTrue(SlackApprovalCallback.parse(null).isEmpty());
    }

    @Test
    void rejectsUnknownDecisionTag() {
        // sa:z:id has the right prefix but an unknown decision tag.
        assertTrue(SlackApprovalCallback.parse("sa:z:id").isEmpty());
    }

    @Test
    void rejectsEmptyId() {
        // sa:o: has a valid tag but no id.
        assertTrue(SlackApprovalCallback.parse("sa:o:").isEmpty());
    }

    @Test
    void rejectsMalformedHeader() {
        // Missing the separating colon after the tag.
        assertTrue(SlackApprovalCallback.parse("sa:oid").isEmpty());
        // Too short to carry a tag + id.
        assertTrue(SlackApprovalCallback.parse("sa:o").isEmpty());
    }
}
