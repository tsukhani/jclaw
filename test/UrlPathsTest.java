import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.UrlPaths;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * JF-374: a request path normalized as a server routes it, so a capture rule naming one path is not slipped past by
 * another spelling of it, and a spelling servers read two ways has no normal form at all. Matched against a rule, a
 * path is compared ignoring case and {@code ;} parameters, and one holding an escaped slash in every way a server may
 * read it.
 */
class UrlPathsTest extends UnitTest {

    private static List<String> differing(String expected, List<String> spellings) {
        var wrong = new ArrayList<String>();
        for (var spelling : spellings) {
            var got = UrlPaths.normalize(spelling);
            if (!expected.equals(got)) wrong.add(spelling + " -> " + got);
        }
        return wrong;
    }

    @Test
    void everySpellingOfOnePathNormalizesToIt() {
        assertEquals(List.of(), differing("/v1/sessions/current", List.of(
                "/v1/sessions/current",
                "/v1/sessions/current/",
                "/v1//sessions/current",
                "//v1///sessions//current//",
                "/v1/./sessions/current",
                "/v1/sessions/./current/.",
                "/v1/x/../sessions/current",
                "/v1/sessions/current/x/..",
                "/v1/%73essions/current",
                "/v1/%2e/sessions/current",
                "/v1/x/%2E%2E/sessions/current",
                "/v1/sessions/current?ref=1",
                "/v1/sessions/current/?a=b/../c")));
    }

    @Test
    void aDotDotAboveTheRootStaysAtTheRoot() {
        assertEquals("/v1/sessions", UrlPaths.normalize("/../../v1/sessions"));
        assertEquals("/", UrlPaths.normalize("/"));
        assertEquals("/", UrlPaths.normalize("//"));
        assertEquals("/", UrlPaths.normalize("/.."));
    }

    @Test
    void anEscapeOfAReservedByteStaysEscapedInCapitals() {
        assertEquals("/a%3Bb/%C3%A9", UrlPaths.normalize("/a%3bb/%c3%a9"));
        assertEquals("/a%20b/%C3%A9", UrlPaths.normalize("/a b/é"));
        assertEquals("/a%F0%9F%98%80", UrlPaths.normalize("/a😀"));
    }

    @Test
    void theCaseOfThePathIsKept() {
        assertEquals("/V1/Sessions", UrlPaths.normalize("/V1/Sessions"));
    }

    @Test
    void anEncodedSlashABackslashOrAMalformedEscapeHasNoNormalForm() {
        var normalized = new ArrayList<String>();
        for (var path : List.of("/v1/sessions%2Fcurrent", "/v1/sessions%2fcurrent", "/v1/sessions%5Ccurrent",
                "/v1/sessions%5ccurrent", "/v1\\sessions\\current", "/v1/%zz", "/v1/%4", "/v1/%", "/v1#frag",
                "v1/sessions", "", "?q=/v1")) {
            var got = UrlPaths.normalize(path);
            if (got != null) normalized.add(path + " -> " + got);
        }
        assertEquals(List.of(), normalized);
    }

    // ==================== matching a request against a rule's path ====================

    private static final String SIGN_IN = "/v1/Sessions/current";

    private static boolean matches(String request, String rule) {
        return UrlPaths.matches(UrlPaths.readings(request), rule);
    }

    private static List<String> missing(List<String> spellings) {
        var wrong = new ArrayList<String>();
        for (var spelling : spellings) {
            if (!matches(spelling, SIGN_IN)) wrong.add(spelling + " -> " + UrlPaths.readings(spelling));
        }
        return wrong;
    }

    @Test
    void aRulesPathIsMatchedIgnoringCaseAndParameters() {
        assertEquals("/v1/sessions/current", UrlPaths.forMatching(SIGN_IN));
        assertEquals(List.of(), missing(List.of(
                "/v1/sessions/current",
                "/V1/SESSIONS/CURRENT",
                "/v1/%53essions/current",
                "/v1/sessions;x=1/current",
                "/v1/sessions/current;jsessionid=a1",
                "/v1;/sessions/current/;",
                "/v1/x/..;y/sessions/current",
                "/v1/sessions/current/.;z",
                "/v1//sessions/./current/?next=/x")));
        assertEquals(new UrlPaths.Readings(Set.of("/v1/sessions/current"), false),
                UrlPaths.readings("/V1/Sessions;a=b/Current"));
    }

    @Test
    void anEscapedSlashIsReadBothAsASlashAndKept() {
        assertEquals(new UrlPaths.Readings(Set.of("/v1/files/a/b", "/v1/files/a%2fb"), true),
                UrlPaths.readings("/v1/files/a%2Fb"));
        assertEquals(List.of(), missing(List.of(
                "/v1/sessions%2Fcurrent",
                "/v1%2fsessions%2Fcurrent",
                "/v1/sessions%5Ccurrent",
                "/v1/sessions/current/a%2Fb/..",
                "/v1/sessions/x%2F../current",
                "/v1%2Fsessions%2Fcurrent/x%2Fy/..")));
        assertFalse(matches("/v1/files/a%2Fb", SIGN_IN));
        assertFalse(UrlPaths.readings("/v1/sessions/current").ambiguous());
    }

    /** A server that decodes twice reads {@code %252F} as a slash and {@code %252e} as a dot: such a path is ambiguous too. */
    @Test
    void aDoublyEscapedSlashOrDotIsAmbiguousAndAlsoReadDecodedTwice() {
        assertEquals(new UrlPaths.Readings(Set.of("/v1/sessions%252fcurrent", "/v1/sessions%2fcurrent",
                "/v1/sessions/current"), true), UrlPaths.readings("/v1/sessions%252Fcurrent"));
        assertEquals(List.of(), missing(List.of(
                "/v1/sessions%252Fcurrent",
                "/v1/sessions%252fcurrent",
                "/v1/sessions%255Ccurrent",
                "/v1/x/%252e%252e/sessions/current",
                "/v1/x/%252E%252E/sessions/current")));
        var notAmbiguous = new ArrayList<String>();
        for (var path : List.of("/v1/sessions%252Fcurrent", "/v1/sessions%255Ccurrent", "/v1/a/%252e%252e/b",
                "/v1/files/a%252Fb")) {
            if (!UrlPaths.readings(path).ambiguous()) notAmbiguous.add(path);
        }
        assertEquals(List.of(), notAmbiguous);
        assertFalse(matches("/v1/files/a%252Fb", SIGN_IN), "an ambiguous path elsewhere is no sign-in");
        assertFalse(UrlPaths.readings("/v1/files/100%2541").ambiguous(), "a doubly escaped letter changes no route");
    }

    /** A server that renders formats routes the last segment with a trailing {@code .json} or {@code .xml} as the bare one. */
    @Test
    void aTrailingFormatSuffixIsAlsoReadWithoutIt() {
        assertEquals(new UrlPaths.Readings(Set.of("/v1/sessions/current.json"), false),
                UrlPaths.readings("/v1/sessions/current.json"));
        assertEquals(List.of(), missing(List.of(
                "/v1/sessions/current.json",
                "/v1/sessions/Current.XML",
                "/v1/sessions/current.json;x=1",
                "/v1/sessions/current.json/",
                "/v1/sessions/current.%6Aso%6E",
                "/v1/sessions/current.a",
                "/v1/sessions/current.v2abc",
                "/v1/sessions%2Fcurrent.json")));
        var matched = new ArrayList<String>();
        for (var path : List.of("/v1/sessions.json/current", "/v1/sessions/current.jsonld1", "/v1/sessions/current.",
                "/v1/sessions/current.js-on", "/v1/sessions/current/.json", "/v1/sessions/current.json.txt")) {
            if (matches(path, SIGN_IN)) matched.add(path);
        }
        assertEquals(List.of(), matched, "only one suffix of one to five letters or digits, on the last segment, goes");
    }

    /**
     * A rule saved with a format suffix matches the request without it, and a rule without one the request with one,
     * in either case; but a suffix is dropped from one side at a time, so two names that differ only after a dot are
     * different paths.
     */
    @Test
    void aFormatSuffixIsDroppedFromTheRuleOrTheRequestButNeverBoth() {
        assertTrue(matches("/v1/sessions/current", "/v1/Sessions/current.JSON"), "the rule's suffix dropped");
        assertTrue(matches("/v1/sessions/current.json", "/v1/Sessions/current.JSON"), "both as written");
        assertTrue(matches("/v1/sessions/current.xml", "/v1/sessions/current"), "the request's suffix dropped");
        assertTrue(matches("/v1/sessions/current", "/v1/sessions;x=1/current"));
        assertTrue(matches("/", "/"));
        assertTrue(matches("/v1/users/john", "/v1/users/john.smith"), "the rule's suffix dropped");
        assertTrue(matches("/v1/users/john.doe", "/v1/users/john"), "the request's suffix dropped");
        assertFalse(matches("/v1/users/john.doe", "/v1/users/john.smith"), "both dropped would make them one path");
        assertFalse(matches("/v1/sessions/current.xml", "/v1/sessions/current.json"));
        assertFalse(matches("/v1/sessions/current", "/v1/sessions/current.jsonld1"),
                "a suffix longer than five goes on neither side");
    }

    /** A malformed escape reaches a server as written; read so, it is a literal per cent sign and matches nothing else. */
    @Test
    void aMalformedEscapeIsReadAsALiteralPerCentSign() {
        assertEquals(new UrlPaths.Readings(Set.of("/v1/sessions/current%25zz"), false),
                UrlPaths.readings("/v1/sessions/current%zz"));
        assertEquals(new UrlPaths.Readings(Set.of("/v1/a%254"), false), UrlPaths.readings("/v1/a%4"));
        assertEquals(new UrlPaths.Readings(Set.of(), false), UrlPaths.readings("v1/sessions/current"));
    }
}
