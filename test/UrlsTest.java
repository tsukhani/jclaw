import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import play.test.UnitTest;
import utils.Urls;

import java.net.URI;
import java.util.List;

/**
 * The shared lenient parse (JCLAW-1287). Every case here is a URL {@link URI#create} refuses and a
 * browser loads, reaching the scrape path from a page rather than from an operator: a harvested
 * link, a redirect's {@code Location}, a sitemap {@code <loc>}, the browser's own final URL.
 */
class UrlsTest extends UnitTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://site.test/css?family=Roboto|Open+Sans",
            "https://site.test/img?a=b^c{d}e`f",
            "https://site.test/sale?off=100%",
            "https://site.test/p%2?q=%zz",
            "https://site.test/x#frag|^{}",
            "https://site.test/a[0].js",
            "https://site.test/two words.pdf",
            "https://site.test/q?tag=<b>"})
    void aRawCharacterAfterTheAuthorityParses(String url) {
        assertThrows(IllegalArgumentException.class, () -> URI.create(url),
                "the strict parse this replaces must still refuse it, or the case proves nothing");
        assertEquals("site.test", Urls.parse(url).getHost());
    }

    @Test
    void theAuthorityIsNeverRewritten() {
        // The host a guard reads has to be the host the page wrote, so the encoding starts after the
        // authority — and a URL whose authority is malformed still fails rather than being repaired.
        for (var url : List.of("http://exa|mple.com/", "http://8.8.8.8^/x", "http://{8.8.8.8}/")) {
            assertThrows(IllegalArgumentException.class, () -> Urls.parse(url), url);
        }
        assertEquals("site.test", Urls.parse("https://site.test/p|q").getHost());
    }

    @Test
    void aSchemeRelativeReferenceKeepsItsAuthorityAndEncodesItsQuery() {
        var uri = Urls.parse("//site.test/p?q=|");
        assertEquals("site.test", uri.getHost());
        assertNull(uri.getScheme());
        assertEquals("q=%7C", uri.getRawQuery());
    }

    @Test
    void aRelativeReferenceIsEncodedWhole() {
        // No authority means no host to protect, so encoding starts at the first character.
        assertEquals("/p?q=%7C", Urls.parse("/p?q=|").toString());
        assertEquals("a%7Cb", Urls.parse("a|b").toString());
    }

    @Test
    void aRedirectLocationIsResolvedAgainstItsBase() {
        var base = Urls.parse("https://site.test/dir/page");
        assertEquals("https://site.test/next?a=b%7Cc", Urls.resolve(base, "/next?a=b|c").toString());
        assertEquals("https://site.test/dir/rel?a=%7B1%7D", Urls.resolve(base, "rel?a={1}").toString());
        assertEquals("https://other.test/x?a=%5Eb",
                Urls.resolve(base, "https://other.test/x?a=^b").toString(),
                "an absolute Location replaces the base");
    }

    @Test
    void aStrayPercentIsTheOneRewriteTheOriginSees() {
        // Every other escape stands for a character the origin already had; %25 changes the request
        // it receives. URI refuses the alternative outright, so the link is encoded or lost.
        assertEquals("https://site.test/sale?off=100%25",
                Urls.parse("https://site.test/sale?off=100%").toString());
        assertEquals("https://site.test/p%252?q=%25zz",
                Urls.parse("https://site.test/p%2?q=%zz").toString());
    }

    @Test
    void aSpaceOrAngleBracketAfterTheAuthorityIsEncodedRatherThanDropped() {
        assertEquals("https://site.test/two%20words.pdf",
                Urls.parse("https://site.test/two words.pdf").toString());
        assertEquals("https://site.test/q?tag=%3Cb%3E",
                Urls.parse("https://site.test/q?tag=<b>").toString());
    }

    @Test
    void anAlreadyEncodedEscapeIsLeftAlone() {
        // Re-encoding a valid escape would turn %7C into %257C and fetch a different URL — and a
        // crawl re-parses its own output on every hop.
        var once = Urls.parse("https://site.test/x?family=A|B").toString();
        assertEquals("https://site.test/x?family=A%7CB", once);
        assertEquals(once, Urls.parse(once).toString());
    }

    @Test
    void whatNoBrowserSendsRawStillFails() {
        // The encoding starts after the authority, so a space inside one is still unparseable; a
        // control character is never encoded at all. SsrfGuard refuses both on the raw string
        // before it parses anything, so its own verdict on these is unchanged either way.
        assertThrows(IllegalArgumentException.class, () -> Urls.parse("http://exa mple/"));
        assertThrows(IllegalArgumentException.class, () -> Urls.parse("http://site.test/a\u0007b"));
    }
}
