import org.junit.jupiter.api.Test;
import play.test.UnitTest;
import utils.Strings;

class UtilsStringsTest extends UnitTest {

    @Test
    void truncateReturnsInputWhenShortEnough() {
        assertEquals("hello", Strings.truncate("hello", 10));
        assertEquals("hello", Strings.truncate("hello", 5),
                "exact-length input must not gain a suffix");
    }

    @Test
    void truncateAppendsEllipsisWhenOverLimit() {
        assertEquals("abcde…", Strings.truncate("abcdefghij", 5));
    }

    @Test
    void truncateReturnsEmptyForNull() {
        assertEquals("", Strings.truncate(null, 5));
    }

    @Test
    void truncateHandlesZeroLimit() {
        assertEquals("…", Strings.truncate("anything", 0));
    }

    @Test
    void firstNonBlankReturnsFirstQualifyingValue() {
        assertEquals("b", Strings.firstNonBlank(null, "", "  ", "b", "c"));
        assertEquals("a", Strings.firstNonBlank("a", "b"));
    }

    @Test
    void firstNonBlankReturnsNullWhenNoneQualify() {
        assertNull(Strings.firstNonBlank(null, "", "  "));
        assertNull(Strings.firstNonBlank());
    }

    @Test
    void trimTrailingSlashStripsSingleSlash() {
        assertEquals("http://x", Strings.trimTrailingSlash("http://x/"));
        assertEquals("http://x", Strings.trimTrailingSlash("http://x"),
                "no trailing slash must pass through unchanged");
        assertEquals("http://x/", Strings.trimTrailingSlash("http://x//"),
                "only a single trailing slash is stripped");
    }
}
