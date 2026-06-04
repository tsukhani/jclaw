import org.junit.jupiter.api.*;
import play.test.*;
import services.DeliverySpec;

/**
 * JCLAW-417: parsing + structural validation of the {@code Task.delivery}
 * grammar — channel / tool / none.
 */
class DeliverySpecTest extends UnitTest {

    // ─── parse: channel ───────────────────────────────────────────────

    @Test
    void parsesChannelWithTarget() {
        var s = DeliverySpec.parse("telegram:878224171");
        assertEquals(DeliverySpec.Kind.CHANNEL, s.kind());
        assertEquals("telegram", s.channel());
        assertEquals("878224171", s.target());
        assertNull(s.tool());
        assertEquals("telegram", s.label());
        assertTrue(s.isDispatchChannel());
    }

    @Test
    void parsesBareChannelAsAutoFillTarget() {
        var s = DeliverySpec.parse("telegram");
        assertEquals(DeliverySpec.Kind.CHANNEL, s.kind());
        assertEquals("telegram", s.channel());
        // Empty target == auto-fill from the calling chat at delivery time.
        assertEquals("", s.target());
    }

    @Test
    void parsingIsCaseAndWhitespaceInsensitive() {
        var s = DeliverySpec.parse("  Telegram:878224171  ");
        assertEquals("telegram", s.channel());
        assertEquals("878224171", s.target());
        var t = DeliverySpec.parse("TOOL:send_gmail_message");
        assertEquals(DeliverySpec.Kind.TOOL, t.kind());
        assertEquals("send_gmail_message", t.tool());
    }

    // ─── parse: tool ──────────────────────────────────────────────────

    @Test
    void parsesToolDelivery() {
        var s = DeliverySpec.parse("tool:send_gmail_message");
        assertEquals(DeliverySpec.Kind.TOOL, s.kind());
        assertEquals("send_gmail_message", s.tool());
        assertNull(s.channel());
        assertEquals("send_gmail_message", s.label());
        assertFalse(s.isDispatchChannel());
    }

    // ─── parse: none ──────────────────────────────────────────────────

    @Test
    void nullBlankAndNoneAllParseToNone() {
        assertEquals(DeliverySpec.Kind.NONE, DeliverySpec.parse(null).kind());
        assertEquals(DeliverySpec.Kind.NONE, DeliverySpec.parse("   ").kind());
        assertEquals(DeliverySpec.Kind.NONE, DeliverySpec.parse("none").kind());
        assertEquals(DeliverySpec.Kind.NONE, DeliverySpec.parse("NONE").kind());
        assertTrue(DeliverySpec.parse(null).isNone());
        assertEquals("none", DeliverySpec.parse(null).label());
    }

    // ─── validate ─────────────────────────────────────────────────────

    @Test
    void validateAcceptsKnownChannelsToolsAndNone() {
        assertNull(DeliverySpec.validate("telegram:123"));
        assertNull(DeliverySpec.validate("slack:C0123"));
        assertNull(DeliverySpec.validate("whatsapp:+15551234567"));
        assertNull(DeliverySpec.validate("web"));
        assertNull(DeliverySpec.validate("tool:send_gmail_message"));
        assertNull(DeliverySpec.validate("none"));
        assertNull(DeliverySpec.validate(null));
    }

    @Test
    void validateRejectsUnknownChannel() {
        // email is NOT a dispatcher channel — the operator means tool:send_gmail_message.
        var err = DeliverySpec.validate("email:foo@bar.com");
        assertNotNull(err);
        assertTrue(err.toLowerCase().contains("unknown delivery channel"), err);
        assertTrue(err.contains("tool:"), "error should point at the tool: alternative: " + err);
    }

    @Test
    void validateRejectsEmptyTool() {
        var err = DeliverySpec.validate("tool:");
        assertNotNull(err);
        assertTrue(err.toLowerCase().contains("tool"), err);
    }
}
