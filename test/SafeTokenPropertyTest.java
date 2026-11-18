import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import services.printing.LpdClient;

class SafeTokenPropertyTest extends UnitTest {

    /** LPD control-file records are newline-delimited and capped at 131 chars; see LpdClient.safeToken. */
    private static final int SAFE_TOKEN_MAX = 96;

    // 300 iterations: pure string work, microseconds each.
    @Test
    void safeTokenIsIdempotent() {
        PropertyInputs.check(300, PropertyInputs.HOSTILE_NAMES, raw -> {
            var once = LpdClient.safeToken(raw);
            assertEquals(once, LpdClient.safeToken(once),
                    () -> "safeToken is not a fixed point for " + PropertyInputs.quote(raw) + " -> " + PropertyInputs.quote(once));
        });
    }

    // 300 iterations: the same pure string work.
    @Test
    void safeTokenNeverEmitsASeparatorOrAnOverLongName() {
        PropertyInputs.check(300, PropertyInputs.HOSTILE_NAMES, raw -> {
            var token = LpdClient.safeToken(raw);
            var shown = PropertyInputs.quote(raw) + " -> " + PropertyInputs.quote(token);
            assertFalse(token.isEmpty(), () -> "empty token for " + PropertyInputs.quote(raw));
            assertFalse(token.contains("/") || token.contains("\\"), () -> "path separator survived sanitization of " + shown);
            assertTrue(token.length() <= SAFE_TOKEN_MAX,
                    () -> "token exceeds the RFC 1179 budget for " + PropertyInputs.quote(raw) + " -> length " + token.length());
            assertTrue(token.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '.' || c == '_' || c == '-'),
                    () -> "unsanitized character survived for " + shown);
        });
    }
}
