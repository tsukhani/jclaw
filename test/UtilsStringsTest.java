import org.junit.jupiter.api.*;
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
        assertEquals("abcde...", Strings.truncate("abcdefghij", 5));
    }

    @Test
    void truncatePassesThroughNull() {
        assertNull(Strings.truncate(null, 5));
    }

    @Test
    void truncateHandlesZeroLimit() {
        assertEquals("...", Strings.truncate("anything", 0));
    }
}
