import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.UnitTest;
import utils.SsrfGuard;

class SsrfGuardIpLiteralTest extends UnitTest {

    @ParameterizedTest
    @ValueSource(strings = {"93.184.215.14", "[::1]", "[2001:db8::1]"})
    void dottedIpv4AndBracketedIpv6AreLiterals(String host) {
        assertTrue(SsrfGuard.isLikelyIpLiteral(host), host);
    }

    @Test
    void aBareDecimalIntegerIsALiteralSoItNeverReachesDns() {
        assertTrue(SsrfGuard.isLikelyIpLiteral("2130706433")); // 127.0.0.1 packed
    }

    @ParameterizedTest
    @ValueSource(strings = {"example.com", "localhost", ""})
    void hostnamesAndTheEmptyStringAreNotLiterals(String host) {
        assertFalse(SsrfGuard.isLikelyIpLiteral(host), host);
    }

    // Callers pass URI.getHost(), where an IPv6 host is always bracketed.
    @Test
    void anUnbracketedIpv6IsNotALiteral() {
        assertFalse(SsrfGuard.isLikelyIpLiteral("::1"));
    }
}
