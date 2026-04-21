import channels.TelegramModelCallback;
import channels.TelegramModelCallback.Kind;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-109: callback_data round-trip and malformed-payload rejection
 * for the /model inline-keyboard selector encoding.
 */
public class TelegramModelCallbackTest extends UnitTest {

    // ── Round trip ─────────────────────────────────────────────────────

    @Test
    public void browseRoundTrip() {
        var encoded = TelegramModelCallback.encodeBrowse(42L);
        assertEquals("m:b:42", encoded);
        var parsed = TelegramModelCallback.parse(encoded).orElseThrow();
        assertEquals(Kind.BROWSE, parsed.kind());
        assertEquals(42L, parsed.conversationId());
    }

    @Test
    public void providerPageRoundTrip() {
        var encoded = TelegramModelCallback.encodeProviderPage(42L, 3, 2);
        assertEquals("m:p:42:3:2", encoded);
        var parsed = TelegramModelCallback.parse(encoded).orElseThrow();
        assertEquals(Kind.PROVIDER_PAGE, parsed.kind());
        assertEquals(42L, parsed.conversationId());
        assertEquals(3, parsed.providerIdx());
        assertEquals(2, parsed.page());
    }

    @Test
    public void selectRoundTrip() {
        var encoded = TelegramModelCallback.encodeSelect(42L, 3, 7);
        assertEquals("m:s:42:3:7", encoded);
        var parsed = TelegramModelCallback.parse(encoded).orElseThrow();
        assertEquals(Kind.SELECT, parsed.kind());
        assertEquals(3, parsed.providerIdx());
        assertEquals(7, parsed.modelIdx());
    }

    @Test
    public void backRoundTrip() {
        var parsed = TelegramModelCallback.parse(TelegramModelCallback.encodeBack(42L)).orElseThrow();
        assertEquals(Kind.BACK, parsed.kind());
    }

    @Test
    public void detailsRoundTrip() {
        var parsed = TelegramModelCallback.parse(TelegramModelCallback.encodeDetails(42L)).orElseThrow();
        assertEquals(Kind.DETAILS, parsed.kind());
    }

    // ── 64-byte budget check ───────────────────────────────────────────

    @Test
    public void longestRealisticPayloadFitsIn64Bytes() {
        // Very large conversation id (realistic ceiling), 99th provider, 99th model.
        // 10-digit conv id + ":99:99" in the select form = "m:s:9999999999:99:99" = 19 bytes.
        // Leaves generous headroom under the 64-byte cap.
        var payload = TelegramModelCallback.encodeSelect(9_999_999_999L, 99, 99);
        assertTrue(payload.getBytes().length <= 64,
                "payload exceeds 64-byte budget: %d bytes (%s)"
                        .formatted(payload.getBytes().length, payload));
    }

    // ── Malformed input ────────────────────────────────────────────────

    @Test
    public void rejectsPayloadWithoutPrefix() {
        assertTrue(TelegramModelCallback.parse("b:42").isEmpty());
        assertTrue(TelegramModelCallback.parse("something:else").isEmpty());
        assertTrue(TelegramModelCallback.parse("").isEmpty());
        assertTrue(TelegramModelCallback.parse(null).isEmpty());
    }

    @Test
    public void rejectsUnknownKindTag() {
        // m:z:42 has the right prefix but an unknown kind.
        assertTrue(TelegramModelCallback.parse("m:z:42").isEmpty());
    }

    @Test
    public void rejectsWrongArity() {
        // BROWSE should have exactly 3 parts; extra fields reject.
        assertTrue(TelegramModelCallback.parse("m:b:42:extra").isEmpty());
        // PROVIDER_PAGE needs 5 parts; too few reject.
        assertTrue(TelegramModelCallback.parse("m:p:42:3").isEmpty());
        // SELECT needs 5 parts; too many reject.
        assertTrue(TelegramModelCallback.parse("m:s:42:3:7:extra").isEmpty());
    }

    @Test
    public void rejectsNonNumericIds() {
        assertTrue(TelegramModelCallback.parse("m:b:abc").isEmpty());
        assertTrue(TelegramModelCallback.parse("m:s:42:not-a-number:7").isEmpty());
    }
}
