import org.junit.jupiter.api.Test;
import play.Play;
import play.mvc.Scope;
import play.test.UnitTest;

/**
 * JCLAW-1025: the session cookie must be {@code SameSite=Strict}.
 *
 * <p>The framework defaults to {@code Lax}, which deliberately attaches the cookie to a
 * cross-site <em>top-level GET navigation</em>. The fork's router then rewrites the request
 * method from an {@code x-http-method-override} query parameter — after the browser has already
 * made its SameSite decision — so a link on an attacker's page can reach a POST-only endpoint
 * carrying the operator's session. Strict withholds the cookie from that navigation.
 *
 * <p>A configuration test on purpose, and the same argument as {@code LogRetentionConfigTest}:
 * whether a browser honors SameSite is the browser's contract, but whether JClaw still asks for
 * Strict is ours, and it is one commented-out line away from silently reverting to the default.
 * PF-170 fixes the override itself in the fork; this is the layer that does not depend on it.
 */
class SessionCookieSameSiteTest extends UnitTest {

    @Test
    void theSessionCookieIsSameSiteStrict() {
        assertEquals("Strict", Play.configuration.getProperty("application.session.sameSite"),
                "application.session.sameSite must be Strict — the framework default is Lax, "
                        + "which attaches the cookie to a cross-site top-level GET");
    }

    @Test
    void theRunningConfigurationResolvedThatValue() {
        // Reading it back off Scope proves the property name is the one the framework actually
        // consults; a typo in application.conf would leave this at the Lax default while the
        // assertion above still passed.
        assertEquals("Strict", Scope.COOKIE_SAMESITE);
    }

    @Test
    void theCookieStaysHttpOnlyAndSecure() {
        // Strict is a third lock, not a replacement: HttpOnly blocks script theft and Secure
        // keeps the cookie off cleartext. Pinned so a future edit to this block cannot trade
        // one for another.
        assertEquals("true", Play.configuration.getProperty("application.session.httpOnly"));
        assertEquals("true", Play.configuration.getProperty("application.session.secure"));
    }
}
