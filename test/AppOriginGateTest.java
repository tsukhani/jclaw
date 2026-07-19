import controllers.AppOriginGate;
import org.junit.jupiter.api.Test;
import play.test.UnitTest;

/**
 * JCLAW-764 / AD-1: unit coverage for the pure provenance predicate
 * {@link AppOriginGate#appOriginSlug}. The HTTP-level enforcement (403 on other
 * endpoints, reset-password/logout guards, fail-closed resolution) is covered by
 * {@code ApiAppInvokeControllerTest}.
 */
class AppOriginGateTest extends UnitTest {

    @Test
    void detectsAppOriginAndExtractsSlug() {
        assertEquals("foo", AppOriginGate.appOriginSlug("same-origin", "http://localhost:3000/apps/foo/"));
        assertEquals("foo", AppOriginGate.appOriginSlug("same-origin", "http://localhost:3000/apps/foo/index.html"));
        assertEquals("foo-bar", AppOriginGate.appOriginSlug("SAME-ORIGIN", "http://host/apps/foo-bar/sub/page.html"));
        assertEquals("a1", AppOriginGate.appOriginSlug("same-origin", "https://host/apps/a1/?q=1#frag"));
    }

    @Test
    void nullWhenSecFetchSiteNotSameOrigin() {
        assertNull(AppOriginGate.appOriginSlug(null, "http://host/apps/foo/"));
        assertNull(AppOriginGate.appOriginSlug("cross-site", "http://host/apps/foo/"));
        assertNull(AppOriginGate.appOriginSlug("same-site", "http://host/apps/foo/"));
        assertNull(AppOriginGate.appOriginSlug("none", "http://host/apps/foo/"));
    }

    @Test
    void nullForSpaOrNonAppReferer() {
        assertNull(AppOriginGate.appOriginSlug("same-origin", "http://localhost:3000/"));
        assertNull(AppOriginGate.appOriginSlug("same-origin", "http://localhost:3000/chat"));
        assertNull(AppOriginGate.appOriginSlug("same-origin", "http://localhost:3000/apps"));   // listing page (SPA)
        assertNull(AppOriginGate.appOriginSlug("same-origin", "http://localhost:3000/apps/"));  // static root, no slug
    }

    @Test
    void nullForMissingOrMalformedReferer() {
        assertNull(AppOriginGate.appOriginSlug("same-origin", null));
        assertNull(AppOriginGate.appOriginSlug("same-origin", ""));
        assertNull(AppOriginGate.appOriginSlug("same-origin", "   "));
        assertNull(AppOriginGate.appOriginSlug("same-origin", "not a url ::: %%%"));
    }
}
