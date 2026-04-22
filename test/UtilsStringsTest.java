import org.junit.jupiter.api.*;
import play.test.UnitTest;
import utils.Strings;

public class UtilsStringsTest extends UnitTest {

    @Test
    public void truncateReturnsInputWhenShortEnough() {
        assertEquals("hello", Strings.truncate("hello", 10));
        assertEquals("hello", Strings.truncate("hello", 5),
                "exact-length input must not gain a suffix");
    }

    @Test
    public void truncateAppendsEllipsisWhenOverLimit() {
        assertEquals("abcde...", Strings.truncate("abcdefghij", 5));
    }

    @Test
    public void truncatePassesThroughNull() {
        assertNull(Strings.truncate(null, 5));
    }

    @Test
    public void truncateHandlesZeroLimit() {
        assertEquals("...", Strings.truncate("anything", 0));
    }
}
