import channels.TelegramApprovalCallback;
import channels.TelegramApprovalCallback.Decision;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-373: callback_data round-trip and malformed-payload rejection for
 * the generic approve/deny inline-keyboard encoding.
 */
class TelegramApprovalCallbackTest extends UnitTest {

    // ── Round trip ─────────────────────────────────────────────────────

    @Test
    void approveOnceRoundTrip() {
        var encoded = TelegramApprovalCallback.encodeApproveOnce("abc12345");
        assertEquals("a:o:abc12345", encoded);
        var parsed = TelegramApprovalCallback.parse(encoded).orElseThrow();
        assertEquals(Decision.APPROVE_ONCE, parsed.decision());
        assertEquals("abc12345", parsed.approvalId());
    }

    @Test
    void approveSessionRoundTrip() {
        var parsed = TelegramApprovalCallback.parse(
                TelegramApprovalCallback.encodeApproveSession("id")).orElseThrow();
        assertEquals(Decision.APPROVE_SESSION, parsed.decision());
        assertEquals("id", parsed.approvalId());
    }

    @Test
    void approveAlwaysRoundTrip() {
        var parsed = TelegramApprovalCallback.parse(
                TelegramApprovalCallback.encodeApproveAlways("id")).orElseThrow();
        assertEquals(Decision.APPROVE_ALWAYS, parsed.decision());
    }

    @Test
    void denyRoundTrip() {
        var encoded = TelegramApprovalCallback.encodeDeny("abc12345");
        assertEquals("a:d:abc12345", encoded);
        var parsed = TelegramApprovalCallback.parse(encoded).orElseThrow();
        assertEquals(Decision.DENY, parsed.decision());
        assertEquals("abc12345", parsed.approvalId());
    }

    @Test
    void idWithColonRoundTrips() {
        // Everything after the tag is the id, verbatim — a colon in the id
        // must survive the round trip.
        var parsed = TelegramApprovalCallback.parse("a:o:weird:id").orElseThrow();
        assertEquals(Decision.APPROVE_ONCE, parsed.decision());
        assertEquals("weird:id", parsed.approvalId());
    }

    // ── 64-byte budget check ───────────────────────────────────────────

    @Test
    void realisticPayloadFitsIn64Bytes() {
        // 8-char UUID-prefix id + "a:o:" tag = 12 bytes; generous headroom.
        var payload = TelegramApprovalCallback.encodeApproveOnce("deadbeef");
        assertTrue(payload.getBytes().length <= 64,
                "payload exceeds 64-byte budget: %d bytes (%s)"
                        .formatted(payload.getBytes().length, payload));
    }

    // ── Malformed input ────────────────────────────────────────────────

    @Test
    void rejectsPayloadWithoutPrefix() {
        assertTrue(TelegramApprovalCallback.parse("o:id").isEmpty());
        assertTrue(TelegramApprovalCallback.parse("m:b:42").isEmpty());
        assertTrue(TelegramApprovalCallback.parse("").isEmpty());
        assertTrue(TelegramApprovalCallback.parse(null).isEmpty());
    }

    @Test
    void rejectsUnknownDecisionTag() {
        // a:z:id has the right prefix but an unknown decision tag.
        assertTrue(TelegramApprovalCallback.parse("a:z:id").isEmpty());
    }

    @Test
    void rejectsEmptyId() {
        // a:o: has a valid tag but no id.
        assertTrue(TelegramApprovalCallback.parse("a:o:").isEmpty());
    }

    @Test
    void rejectsMalformedHeader() {
        // Missing the separating colon after the tag.
        assertTrue(TelegramApprovalCallback.parse("a:oid").isEmpty());
        // Too short to carry a tag + id.
        assertTrue(TelegramApprovalCallback.parse("a:o").isEmpty());
    }
}
